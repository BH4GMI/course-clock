package courseclock.timetable.schedule

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ShareCompat
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.viewpager.widget.ViewPager
import courseclock.timetable.utils.BackgroundImageLoader
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseActivity
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TableSelectBean
import courseclock.timetable.course_add.AddCourseActivity
import courseclock.timetable.intro.AboutActivity
import courseclock.timetable.schedule_import.Common
import courseclock.timetable.schedule_import.LoginWebActivity
import courseclock.timetable.schedule_manage.ScheduleManageActivity
import courseclock.timetable.schedule_settings.ScheduleSettingsActivity
import courseclock.timetable.settings.SettingsActivity
import courseclock.timetable.utils.*
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.delay
import splitties.activities.start
import splitties.dimensions.dip
import splitties.resources.styledDimenPxSize
import splitties.snackbar.longSnack
import java.io.IOException
import kotlin.math.roundToInt

class ScheduleActivity : BaseActivity() {

    private val viewModel by viewModels<ScheduleViewModel>()
    private var mAdapter: SchedulePagerAdapter? = null

    private lateinit var ui: ScheduleActivityUI
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private val preLoad by lazy(LazyThreadSafetyMode.NONE) {
        getPrefer().getBoolean(Const.KEY_SCHEDULE_PRE_LOAD, true)
    }

    /**
     * 七天课程数据的观察者登记。initView 会随每次从设置页返回而重跑，重挂前先摘掉旧的，
     * 否则观察者按调用次数线性堆积：课程表每变一次，旧的查询全部重跑、课表被重复刷新 N 遍。
     */
    private var courseObservers:
            List<Pair<LiveData<List<CourseBean>>, Observer<List<CourseBean>>>> = emptyList()

    /**
     * 首次启动的「许可与免费声明」还没讲完。
     *
     * 全新安装会同时具备两个"想弹窗"的条件：没有课表（[showImportPrompt]）和没讲过声明
     * （[initIntro]）。这两件事原先互不知情 —— 声明走 `postDelayed(500)`、导入引导走 initView
     * 里的协程，谁先到谁先弹，于是用户先看到「还没有课表」、半秒后才是「关于本软件」，
     * 而后者才是"使用之前就该知道"的那一条。
     *
     * 这里把优先级写成显式状态：声明没讲完，导入引导只登记（[importPromptDeferred]），
     * 由 [finishIntro] 在收尾时补上。顺序由状态决定，不再由两个计时器赛跑决定。
     */
    private var introPending = false

    /** 被首次声明挡下的导入引导（见 [introPending]）：声明一讲完就补弹。 */
    private var importPromptDeferred = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getPrefer().getBoolean(Const.KEY_HIDE_NAV_BAR, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
        ui = ScheduleActivityUI(this)
        setContentView(ui.root)

        bottomSheetBehavior = BottomSheetBehavior.from(ui.bottomSheet)

        introPending = !getPrefer().getBoolean(Const.KEY_HAS_INTRO, false)
        if (introPending) {
            // 等首帧画完再弹，不写死等待时长："够不够"是个猜出来的数（原来是 postDelayed(500)），
            // 而这里要的只是"别和 setContentView 挤在同一帧"。
            ui.content.post { initIntro() }
        }

        initView()
        // 事件监听器只挂一次：initView 会随设置页返回反复执行，按钮/翻页的注册留在这里，
        // 各点击行为都在事件发生时读 viewModel，与具体是哪张表无关。反复注册的后果是同一
        // 次点击触发 N 遍、翻页监听器堆叠 N 层。
        initEvent()
        initNavView()

        viewModel.initTableSelectList().observe(this, Observer {
            if (it == null) return@Observer
            viewModel.tableSelectList.clear()
            viewModel.tableSelectList.addAll(it)
            if (ui.rvTableName.adapter == null) {
                initTableMenu(viewModel.tableSelectList)
            } else {
                ui.rvTableName.adapter?.notifyDataSetChanged()
            }
        })

        initBottomSheetAction()

        // 周数面板的回传：选一周就切过去。用 fragment result 而不是回调字段 —— 面板在
        // 配置变更后会被重建，回调字段会丢，而结果会重新投递（见 WeekPickerFragment 的说明）。
        supportFragmentManager.setFragmentResultListener(
                WeekPickerFragment.REQUEST_KEY, this) { _, bundle ->
            val week = bundle.getInt(WeekPickerFragment.RESULT_WEEK)
            if (week >= 1 && week <= viewModel.table.maxWeek) {
                viewModel.selectedWeek = week
                ui.viewPager.currentItem = week - 1
            }
        }
    }

    /** 从底部滑出周数面板（设计稿 2）。 */
    private fun showWeekPicker() {
        WeekPickerFragment.newInstance(viewModel.table.maxWeek, viewModel.selectedWeek)
                .show(supportFragmentManager, "weekPicker")
    }

    private fun initTheme() {
        // 课表这一屏的文字颜色见 ViewUtils.scheduleTextColor：没设自定义背景图时跟随主题，
        // 因为课表底（ui.bg）在浅色下是纯白、深色下是纯黑，分别需要黑字与白字。
        // 头部三行原先写死 Color.BLACK，五个图标按钮原先继承主题色，都是同一处错的两种表现：
        // 结果就是在深色模式下整排文字与图标全部消失。
        val chromeTextColor = scheduleTextColor(this, viewModel.table)
        // addBtn 故意不在这份名单里：它是右下角品牌色圆底上的"+"，取色是 colorOnPrimary，
        // 跟着课表文字色走会在浅色课表上变成黑底黑字。
        for (view in listOf(ui.titleView, ui.dateView, ui.weekView, ui.weekDayView,
                ui.importBtn, ui.shareBtn, ui.moreBtn)) {
            view.setTextColor(chromeTextColor)
        }
        // 三条杠是矢量图，不是字体字形，只能染色不能 setTextColor。
        ui.navBtn.setColorFilter(chromeTextColor)

        if (viewModel.table.background != "") {
            val x = (ViewUtils.getRealSize(this).x * 0.5).toInt()
            val y = (ViewUtils.getRealSize(this).y * 0.5).toInt()
            // 背景图只解码一次，主背景与侧栏头共用同一份 Bitmap —— 各建各的 Drawable，
            // 因为交叉淡入会改写 Drawable 的 alpha（见 BackgroundImageLoader.attach）。
            //
            // 这里原先是两次独立的 Glide 调用（bg 用 x×y、侧栏头用 0.8 倍），同一张照片被
            // 解码两遍：1080×2400 的机器上约 540×1200 + 432×960 ≈ 4.2 MB 常驻，而它们显示的是
            // 同一张图。侧栏头本来只有屏幕宽度的三分之一，把同一份 Bitmap 交给它去缩放，
            // 观感没有差别，内存直接省掉一半。
            BackgroundImageLoader.loadAsync(this, viewModel.table.background, x, y) { bitmap ->
                // 解码是异步的（12MP 的图要走磁盘），回来时这一屏可能已经结束或销毁：
                // 换了课表、back 掉、旋转都会走到这里。原先由 Glide 的请求生命周期兜住，
                // 现在自己判断 —— 否则会往一个死的 Activity 上贴图和弹 Toast。
                if (isFinishing || isDestroyed) return@loadAsync
                if (bitmap == null) {
                    // 解码不出来时保留上一次的图（初次则是空，露出 content 的纯色背景），
                    // 而不是清成透明把已经显示着的背景也抹掉。
                    Toasty.error(this@ScheduleActivity, "无法检索背景图片，可能是它为某个应用私有所致，可以尝试在文件管理器中将它移动到其他位置，或是选择其它图片", Toasty.LENGTH_LONG).show()
                    return@loadAsync
                }
                BackgroundImageLoader.attach(ui.bg, bitmap, crossfade = true)
                BackgroundImageLoader.attach(ui.navHeaderImage, bitmap, crossfade = true)
            }
        } else {
            // 没有自定义背景图**不再加载任何图片**：主界面背景是纯色（浅色纯白 / 深色纯黑），
            // 由 ScheduleActivityUI 给 content 设的背景色承担。
            // 上游那张 main_background_2020_1.jpg 已随之删除 —— 它来源不明，是本项目里风险最高的
            // 一件美术资源，而"默认背景是纯色"本来就不需要图片，留着它只是因为没人问过
            // "默认背景为什么是一张照片"。
            ui.bg.setImageDrawable(null)
            // 侧边栏头部同理留空，露出抽屉自己的卡片色与那两行字。
            ui.navHeaderImage.setImageDrawable(null)
            // 上一次可能是有背景的、解码还在路上：撤掉它，否则回调回来会把"已经取消掉的自定义
            // 背景"重新贴回来（attach 不看数据源，只看它自己记的那张图）。
            BackgroundImageLoader.cancel(ui.bg)
            BackgroundImageLoader.cancel(ui.navHeaderImage)
        }

        // 这里原本还有第二个设色循环：
        //     for (i in 0 until ui.content.childCount) { ... setTextColor(table.textColor) ... }
        // 它把 ui.content 的直接子 View（日期、周次、星期行、四个图标按钮）全部刷成
        // TableBean.textColor —— 默认是纯黑。initTheme() 明明已经按主题设好了颜色，却被这一遍
        // 覆盖掉，于是深色模式下顶部栏与图标依旧是黑的（实测日期/图标区域像素与 #2A2F3A 背景
        // 同值，只剩三条杠是白的，因为它是 ImageView 两个分支都不匹配）。
        // 同一个规则在两个地方各写一遍就是根因，所以这里直接删除，取色只留 initTheme() 一处。

        // 状态栏图标同样不能用 table.textColor 判断：底色是主题色（浅色纯白 / 深色 #2A2F3A），
        // 不是用户配的背景图配色。用与顶部栏同一个取色函数，深浅就与它们一致了。
        if (ViewUtils.judgeColorIsLight(scheduleTextColor(this, viewModel.table))) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            }
        }

    }

    private fun initTableMenu(data: MutableList<TableSelectBean>) {
        val adapter = TableNameAdapter(R.layout.item_table_select_main, data)
        adapter.addChildClickViewIds(R.id.menu_setting)
        adapter.setOnItemChildClickListener { _, view, _ ->
            when (view.id) {
                R.id.menu_setting -> {
                    startActivityForResult(Intent(this,
                            ScheduleSettingsActivity::class.java).apply {
                        putExtra("tableData", viewModel.table)
                    }, Const.REQUEST_CODE_SCHEDULE_SETTING)
                }
            }
        }
        adapter.setOnItemClickListener { _, _, position ->
            if (position < data.size) {
                if (data[position].id != viewModel.table.id) {
                    launch {
                        viewModel.changeDefaultTable(data[position].id)
                        refreshAfterDefaultTableChanged()
                    }
                }
            }
        }
        ui.rvTableName.adapter = adapter
    }

    /**
     * 默认表换了之后，重载首页并刷新桌面小部件。
     *
     * 切表和导入都会换掉默认表——导入的新表直接接管默认表（见 ImportViewModel.insertTableAsDefault）。
     * 这两条路以前只靠「用户自己切一次表」顺带刷新，导入改成直接接管默认表之后就没有人刷新了，
     * 所以两处必须共用这一件事，而不是各写一遍。
     */
    private suspend fun refreshAfterDefaultTableChanged() {
        initView()
        // 没有默认课表就没有可刷新的内容（initView 那边已经在引导导入了）。
        if (viewModel.getDefaultTable() == null) return
        // 刷新目标一律以平台登记的实例为准：自建的 AppWidgetBean 表可能一行都没有（配置页只在
        // 有多张课表时才写入），拿它当实例清单会漏刷新（日视图早就改成平台口径了）。
        AppWidgetUtils.refreshAllWidgets(applicationContext)
    }

    private fun initBottomSheetAction() {        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {

            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    ui.weekScrollView.smoothScrollTo(if (viewModel.selectedWeek > 4) (viewModel.selectedWeek - 4) * dip(56) else 0, 0)
                    if (ui.weekToggleGroup.checkedButtonId != viewModel.selectedWeek) {
                        checkWeekSilently(viewModel.selectedWeek)
                    }
                }
            }
        })
        ui.createScheduleBtn.setOnClickListener { createNewTable() }
        ui.manageScheduleBtn.setOnClickListener {
            startActivityForResult(
                    Intent(this, ScheduleManageActivity::class.java), Const.REQUEST_CODE_SCHEDULE_SETTING)
        }
        ui.changeWeekBtn.setOnClickListener {
            startActivityForResult(Intent(this,
                    ScheduleSettingsActivity::class.java).apply {
                putExtra("tableData", viewModel.table)
                putExtra("settingItem", "当前周")
            }, Const.REQUEST_CODE_SCHEDULE_SETTING)
        }
        ui.timeBtn.setOnClickListener {
            startActivityForResult(Intent(this,
                    ScheduleSettingsActivity::class.java).apply {
                putExtra("tableData", viewModel.table)
                putExtra("settingItem", "上课时间")
            }, Const.REQUEST_CODE_SCHEDULE_SETTING)
        }
        ui.changeBgBtn.setOnClickListener {
            startActivityForResult(Intent(this,
                    ScheduleSettingsActivity::class.java).apply {
                putExtra("tableData", viewModel.table)
                putExtra("settingItem", "课程表背景")
            }, Const.REQUEST_CODE_SCHEDULE_SETTING)
        }
        // 「捷径 → 上课提醒」：不经过桌面小部件的入口，直接把用户送到设置页的提醒分组。
        // 用普通 startActivity 而不是 startActivityForResult：提醒设置不改变课表数据，
        // 返回时没有什么需要回传给本页的（对比 timeBtn / changeBgBtn 那几个会改课表）。
        ui.remindBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_REMINDER)
            })
        }
        // 多课表里现在能直接切换默认表，所以这两个入口都要带结果启动：
        // 返回时经 REQUEST_CODE_SCHEDULE_SETTING 分支 initView() 重载，否则主页还是旧表。
        ui.courseBtn.setOnClickListener {
            startActivityForResult(Intent(this, ScheduleManageActivity::class.java).apply {
                putExtra("selectedTable", TableSelectBean(
                        id = viewModel.table.id,
                        background = viewModel.table.background,
                        tableName = viewModel.table.tableName,
                        maxWeek = viewModel.table.maxWeek,
                        nodes = viewModel.table.nodes,
                        type = viewModel.table.type
                ))
            }, Const.REQUEST_CODE_SCHEDULE_SETTING)
        }
    }

    /**
     * 首次启动的「许可与免费声明」，只讲一次（用 [Const.KEY_HAS_INTRO] 标记）。
     *
     * 三件事必须在这里说清楚：上游来源与具体版本、Apache-2.0 授权、完全免费。
     * 之所以单独给一个弹窗而不是塞进「关于」页：这三条是使用之前就该知道的信息，而「关于」页
     * 要用户主动点进去才看得到。
     *
     * 「完全免费」这类内容只在这里完整说一次；关于页只保留署名并顺带提一句，不做宣传。
     *
     * 注意「查看完整许可证」这条路：AlertDialog 的按钮点下去会关掉当前弹窗，所以声明弹窗会先
     * 消失、许可证弹窗接着弹出来，收尾动作必须由 [showLicenseDialog] 自己完成。
     */
    private fun initIntro() {
        MaterialAlertDialogBuilder(this)
                .setTitle(R.string.first_run_title)
                .setMessage(R.string.first_run_body)
                .setCancelable(false)
                .setPositiveButton(R.string.first_run_ok) { _, _ -> finishIntro() }
                .setNeutralButton(R.string.first_run_view_license) { _, _ -> showLicenseDialog() }
                .show()
    }

    /**
     * 声明讲完之后的收尾：记下已经讲过，再决定给用户哪个"下一步"。
     *
     * 判断依据是**此刻有没有课表**：有课表就展开底部面板（导入、添加课程的入口都在那里）；
     * 没有课表就把被声明挡下的导入引导补弹出来 —— 那才是用户此刻唯一能做的事，而"展开面板再压一个
     * 对话框"会让屏幕上同时出现两个下一步。
     */
    private fun finishIntro() {
        getPrefer().edit { putBoolean(Const.KEY_HAS_INTRO, true) }
        introPending = false
        if (importPromptDeferred) {
            importPromptDeferred = false
            showImportPrompt()
        } else {
            showBottomSheetDialog()
        }
    }

    /**
     * Apache-2.0 全文，从 assets/LICENSE 读，可滚动。
     *
     * 把全文随 APK 一起打包是 Apache-2.0 第 4 条的要求（再分发时要附带许可证副本），不是装饰。
     * 读不到时明确告知并指向 NOTICE/LICENSE，不静默给一个空框。
     */
    private fun showLicenseDialog() {
        val text = try {
            assets.open("LICENSE").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.w("ScheduleActivity", "读取 assets/LICENSE 失败，许可证全文无法显示", e)
            getString(R.string.license_missing)
        }
        val body = TextView(this).apply {
            setText(text)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dip(20), dip(12), dip(20), dip(12))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        MaterialAlertDialogBuilder(this)
                .setTitle(R.string.license_title)
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton(R.string.first_run_ok) { _, _ -> finishIntro() }
                .show()
    }

    override fun onStart() {
        super.onStart()
        ui.dateView.text = CourseUtils.getTodayDate()
    }

    /**
     * 侧栏抽屉四个去处的接线。
     *
     * 抽屉的行是 [ScheduleActivityUI] 自己搭的（NavigationView 的行样式被 Material 定死，
     * 做不出设计稿的胶囊 + 圆角图标底），所以这里给每一行挂点击，不再用
     * setNavigationItemSelectedListener。
     *
     * 每一行都先给一下 [Haptics.tap]：这一屏的点击全都立刻改变界面，按下去应该有回执，
     * 和其它入口保持同一套触感（见 [Haptics]）。
     */
    private fun initNavView() {
        // 「我的课表」就是本页：它已经是当前项（高亮），点它只把抽屉收起来。
        ui.navRowSchedule.setOnClickListener {
            Haptics.tap(ui.navRowSchedule)
            ui.drawerLayout.closeDrawer(GravityCompat.START)
        }

        ui.navRowCourse.setOnClickListener {
            Haptics.tap(ui.navRowCourse)
            // 抽屉随手就能展开，而课表要等 initView 的协程读完库才有（`table` 是 lateinit）：
            // 没就绪就跳，下一行读 `viewModel.table` 会直接抛未初始化异常。
            // 原来那句 `postDelayed(360)` 只是把这个窗口掩盖小了，并没有消除它 ——
            // 现在改成立刻跳转，就必须显式挡住。
            if (!viewModel.isTableReady()) {
                ui.drawerLayout.closeDrawer(GravityCompat.START)
                ui.content.longSnack("课表还在加载，稍等一下再试~")
                return@setOnClickListener
            }
            goFromDrawer {
                startActivityForResult(Intent(this, ScheduleManageActivity::class.java).apply {
                    putExtra("selectedTable", TableSelectBean(
                            id = viewModel.table.id,
                            background = viewModel.table.background,
                            tableName = viewModel.table.tableName,
                            maxWeek = viewModel.table.maxWeek,
                            nodes = viewModel.table.nodes,
                            type = viewModel.table.type
                    ))
                }, Const.REQUEST_CODE_SCHEDULE_SETTING)
            }
        }

        ui.navRowSetting.setOnClickListener {
            Haptics.tap(ui.navRowSetting)
            goFromDrawer {
                startActivityForResult(Intent(this, SettingsActivity::class.java),
                        Const.REQUEST_CODE_SCHEDULE_SETTING)
            }
        }

        ui.navRowAbout.setOnClickListener {
            Haptics.tap(ui.navRowAbout)
            goFromDrawer { start<AboutActivity>() }
        }
    }

    /**
     * 从抽屉跳去另一个页面：抽屉**不做收起动画**，目标页面**立刻启动**。
     *
     * 这里原来是 `closeDrawer()` + `drawerLayout.postDelayed({ startActivity... }, 360)`：
     * 写死等 360ms 让抽屉先收完，结果用户先看一遍抽屉回弹、再看一遍 Activity 转场 ——
     * 两次动画串行，像卡了一拍（而且 360 是个估出来的常数，抽屉动画时长一变就不准了）。
     *
     * 另一条路是"两个动画同时跑"，那会让新页面在半路被还没收完的抽屉推一下。
     * 所以这里选第三条：**抽屉干脆不动画**，屏幕上只剩 Activity 这一次转场 ——
     * 没有先后等待，也没有互相打架，还顺手去掉了一个写死的时长。
     */
    private fun goFromDrawer(block: () -> Unit) {
        ui.drawerLayout.closeDrawer(GravityCompat.START, false)
        block()
    }

    /**
     * 新建一张空课表。
     *
     * 底部面板的「新建课表」和侧栏抽屉的「新建课表」是同一个动作，所以抽成一处：
     * 原先这段只写在按钮监听里，抽屉要加同一件事就只能整段复制，两处一旦分叉
     * （改了一处的提示、忘了另一处），用户就会在两个入口看到两种行为。
     */
    private fun createNewTable() {
        val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_schedule_name)
                .setView(R.layout.dialog_edit_text)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sure, null)
                .create()
        dialog.show()
        val inputLayout = dialog.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialog.findViewById<TextInputEditText>(R.id.edit_text)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = editText?.text
            if (value.isNullOrBlank()) {
                inputLayout?.error = "名称不能为空哦>_<"
            } else {
                launch {
                    try {
                        // 全新安装是一张表都没有的状态，此时新建的这张会接管默认表 —— 首页必须
                        // 立刻重载，否则用户建完表仍停在「去导入」的引导上，看着像没建成。
                        val hadNoTable = viewModel.getDefaultTable() == null
                        viewModel.addBlankTable(editText.text.toString())
                        Toasty.success(this@ScheduleActivity, "新建成功~").show()
                        if (hadNoTable) initView()
                    } catch (e: Exception) {
                        Toasty.error(this@ScheduleActivity, "操作失败>_<").show()
                    }
                    dialog.dismiss()
                }
            }
        }
        // 建完表这件事从底部面板发起时就先让开，避免对话框压在面板上（原来是同样的顺序）。
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun initViewPage(maxWeek: Int, table: TableBean) {
        if (mAdapter == null) {
            mAdapter = SchedulePagerAdapter(maxWeek, supportFragmentManager)
            ui.viewPager.adapter = mAdapter
            // 「页面预加载」开关现在管的是缓存几页：开（默认）缓存左右各两周——快速连滑
            // 时落点页早已装配完；关只缓存左右各一页（ViewPager 的下限就是 1）。
            // 数据装配本身不因开关延迟：页面一创建就装（见 ScheduleFragment.onViewCreated）。
            ui.viewPager.offscreenPageLimit = if (preLoad) 2 else 1
        }
        mAdapter!!.maxWeek = maxWeek
        mAdapter!!.notifyDataSetChanged()
        if (CourseUtils.countWeek(table.startDate, table.sundayFirst) > 0) {
            ui.viewPager.currentItem = CourseUtils.countWeek(table.startDate, table.sundayFirst) - 1
        } else {
            ui.viewPager.currentItem = 0
        }
    }

    private fun initEvent() {
        ui.addBtn.setOnClickListener {
            Haptics.tap(ui.addBtn)
            start<AddCourseActivity> {
                putExtra("tableId", viewModel.table.id)
                putExtra("maxWeek", viewModel.table.maxWeek)
                putExtra("nodes", viewModel.table.nodes)
                putExtra("id", -1)
            }
        }

        ui.moreBtn.setOnClickListener {
            Haptics.tap(ui.moreBtn)
            showBottomSheetDialog()
            //ui.drawerLayout.openDrawer(Gravity.END)
        }

        ui.bottomSheet.setOnClickListener {
            // 点面板空白处 = 收起面板：这是"视图切换"，用轻一档的 tick。
            Haptics.tick(ui.bottomSheet)
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        ui.weekToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                // check() 也会走到这里，而初始化定位、展开面板时对齐当前周都是程序化 check()。
                // 那些不是用户操作，不该震 —— 否则冷启动 1 秒后会凭空震一下。见 suppressWeekTick。
                if (!suppressWeekTick) Haptics.tick(ui.weekToggleGroup)
                ui.viewPager.currentItem = checkedId - 1
            }
        }

        ui.navBtn.setOnClickListener {
            Haptics.tap(ui.navBtn)
            ui.drawerLayout.openDrawer(GravityCompat.START)
        }

        ui.shareBtn.setOnClickListener {
            Haptics.tap(ui.shareBtn)
            ExportSettingsFragment().show(supportFragmentManager, null)
        }

        ui.importBtn.setOnClickListener {
            Haptics.tap(ui.importBtn)
            ImportChooseFragment().show(supportFragmentManager, "importDialog")
        }

        // 顶栏那一行「9月16日 周三 第1周」就是"看第几周 / 换到第几周"的入口（设计稿 2）：
        // 点它从底部滑出周数面板。原来点周几是"跳回当前周"——那件事在面板里点当前周同样一步就成，
        // 而"换到任意一周"在没有面板时无处可去（只能绕到课表设置页里改）。
        for (entry in listOf(ui.dateView, ui.weekView, ui.weekDayView)) {
            entry.setOnClickListener {
                Haptics.tap(entry)
                showWeekPicker()
            }
        }

        ui.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {

            override fun onPageSelected(position: Int) {
                viewModel.selectedWeek = position + 1
                if (viewModel.currentWeek > 0) {
                    ui.weekView.text = "第${viewModel.selectedWeek}周"
                    ui.weekDayView.text =
                            if (viewModel.selectedWeek == viewModel.currentWeek) CourseUtils.getWeekday()
                            else "非本周"
                } else {
                    ui.weekView.text = "第${viewModel.selectedWeek}周"
                    ui.weekDayView.text = "还没有开学哦"
                }
                // 手指抬起、落点已定的这一帧就给回执（理由见下面 onPageScrollStateChanged 的说明）。
                if (weekPagerDragged) {
                    weekPagerDragged = false
                    Haptics.tick(ui.viewPager)
                }
            }

            override fun onPageScrolled(a: Int, b: Float, c: Int) {

            }

            /**
             * 翻周手势的闸门：只有真被手指拖过才置位。
             *
             * [onPageSelected] 对**程序化**换页同样会响（冷启动定位到当前周、点周数按钮都会），
             * 所以不能无条件在它里面震。点周数按钮那一路由按钮自己给回执，这里只认手势。
             *
             * ## 为什么不在 [ViewPager.SCROLL_STATE_IDLE] 里震
             *
             * 这是一处真实存在的时序缺陷：回执跟着**滚动器的账本**走，而用户看的是**画面**，
             * 两者差了 0.2～0.5 秒。两个原因叠加（均在真机 1220px 宽屏实测）：
             *
             * 1. ViewPager 的落位动画是五次方缓出插值（`t^5`）。一次 620ms 的翻页里，手指抬起后
             *    约 300ms 画面就已经走完 98%，剩下的十几像素肉眼分辨不出 —— 用户这时认为"动画播完了"，
             *    而滚动器还要再跑 300ms 才 `isFinished()`。
             * 2. 滚动器走完到 [ViewPager.SCROLL_STATE_IDLE] 回调之间，还夹着"邻页 Fragment resume +
             *    七天课程装配"的主线程工作，实测又晚约 160ms 才发出 IDLE。
             *
             * 所以回执必须挂在**用户动作落定的那一帧**（[onPageSelected] 正是"落点已定"，与手指抬起
             * 同帧），而不是挂在动画结束的记账回调上。
             *
             * IDLE 在这里只负责落下闸门：拖了但没翻过去（回弹）时 [onPageSelected] 不会响，
             * 闸门必须在这里清掉，否则下一次程序化换页会被误判成手势而多震一下。
             */
            override fun onPageScrollStateChanged(state: Int) {
                when (state) {
                    ViewPager.SCROLL_STATE_DRAGGING -> weekPagerDragged = true
                    ViewPager.SCROLL_STATE_IDLE -> weekPagerDragged = false
                }
            }
        })

    }

    private fun showBottomSheetDialog() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED

    }

    /**
     * 拉起教学服务中心的引导式导入。
     *
     * 只负责打开 WebVPN 入口：登录、人机验证、进入课表页面都由用户自己在里面完成，
     * 到了课表页面点右下角按钮就取数入库。首次使用与学期过期都走这一条路。
     */
    private fun startSuesImport() {
        startActivityForResult(Intent(this, LoginWebActivity::class.java).apply {
            putExtra("import_type", Common.TYPE_SUES)
            putExtra("school_name", "上海工程技术大学")
            putExtra("url", Common.SUES_WEBVPN_URL)
        }, Const.REQUEST_CODE_IMPORT)
    }

    /**
     * 没有可用课表时唯一的出口：说清原因并直接去导入。
     *
     * 两种状态共用它 ——「课表一行都没有（用户删光了）」和「课表在、但开学日期还是空的」，
     * 对用户来说是同一件事：现在没有能看的课表。以前只有后者有出口，前者会在拿到 null 之后
     * 于后面的一连串渲染里崩掉。
     *
     * 首次启动时它会让位给 [initIntro]：后者是"使用之前就该知道"的声明，且 `setCancelable(false)`，
     * 两条一起弹就变成用户先读哪条由计时决定（见 [introPending]）。让位时只登记，收尾时补上。
     */
    private fun showImportPrompt() {
        if (introPending) {
            importPromptDeferred = true
            return
        }
        ui.weekView.text = "还没设置开学日期"
        MaterialAlertDialogBuilder(this)
                .setTitle("提示")
                .setMessage("还没有课表。去教学服务中心导入吧：登录后进到课表页面，" +
                        "点一下右下角的按钮就行，学期起止日期会一并从教务系统取回。")
                .setPositiveButton("去导入") { _, _ -> startSuesImport() }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    /**
     * 「时间栏作息方案」在设置页可改，而 [ScheduleViewModel.courseTimes] 是 [initView] 时按
     * 当时的设置构建的。设置页还有一条**不返回结果**的入口（捷径 →「上课提醒」），
     * 所以这里比一次"已生效的取值 vs 当前偏好"，真的变了才重建 ——
     * 从那条入口改完回来才不会什么都没发生。null 表示还没建过，交给 onCreate 的 initView。
     */
    private var appliedAxisScheme: String? = null

    /** 用户是否正在用手指左右滑动翻周（见 onPageScrollStateChanged 的说明）。 */
    private var weekPagerDragged = false

    /** 正在程序化 check() 周数按钮：这一下不该发触感回执。 */
    private var suppressWeekTick = false

    /**
     * 程序化选中周数按钮。
     *
     * 与用户点击走同一条 `check()`，靠 [suppressWeekTick] 把"这不是用户操作"标出来：
     * 初始化定位与展开面板时对齐当前周都不该震，否则冷启动 1 秒后会凭空震一下。
     */
    private fun checkWeekSilently(week: Int) {
        suppressWeekTick = true
        ui.weekToggleGroup.check(week)
        suppressWeekTick = false
    }

    override fun onResume() {
        super.onResume()
        if (appliedAxisScheme != null && appliedAxisScheme != currentAxisScheme()) {
            initView()
        }
    }

    /** 设置里选的时间栏作息方案：空串 = 自动（导入时按占比写进默认分组的那一套）。 */
    private fun currentAxisScheme(): String =
            getPrefer().getString(Const.KEY_TIME_AXIS_SCHEME, "").orEmpty()

    private fun initView() {
        launch {
            // 一张课表都没有（用户把课表删光了）时，后面所有初始化都没有依据 ——
            // 直接引导导入并结束，不要拿 null 去渲染界面和列表。
            val table = viewModel.getDefaultTable() ?: run {
                showImportPrompt()
                return@launch
            }
            viewModel.table = table
            viewModel.currentWeek = CourseUtils.countWeek(viewModel.table.startDate, viewModel.table.sundayFirst)
            viewModel.selectedWeek = viewModel.currentWeek
            when {
                // 「尚未设置开学日期」和「当前周超出设定周数」是两种状态，提示与出口都必须不同
                viewModel.table.startDate.isBlank() -> {
                    showImportPrompt()
                }
                viewModel.currentWeek > viewModel.table.maxWeek -> {
                    ui.weekView.text = "当前周已超出设定范围"
                    MaterialAlertDialogBuilder(this@ScheduleActivity)
                            .setTitle("提示")
                            .setMessage("当前周已超出这张课表设定的周数范围，学期可能已经结束。" +
                                    "重新导入新课表，或者去设置里修改「当前周」和「开学日期」。")
                            .setPositiveButton("重新导入") { _, _ -> startSuesImport() }
                            .setNeutralButton("打开设置") { _, _ ->
                                startActivityForResult(Intent(this@ScheduleActivity,
                                        ScheduleSettingsActivity::class.java).apply {
                                    putExtra("tableData", viewModel.table)
                                }, Const.REQUEST_CODE_SCHEDULE_SETTING)
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                }
                viewModel.currentWeek > 0 -> {
                    ui.weekView.text = "第${viewModel.currentWeek}周"
                }
                else -> {
                    ui.weekView.text = "还没有开学哦"
                }
            }

            ui.weekToggleGroup.removeAllViews()
            ui.weekToggleGroup.clearChecked()
            for (i in 1..viewModel.table.maxWeek) {
                ui.weekToggleGroup.addView(ui.createOutlineButton().apply {
                    id = i
                    text = i.toString()
                    textSize = 12f
                }, dip(48), dip(48))
            }

            launch {
                delay(1000)
                // selectedWeek 为 0 表示尚未设置开学日期，此时没有对应周次的按钮，不能去 check(0)
                if (viewModel.selectedWeek > 0 && ui.weekToggleGroup.checkedButtonId != viewModel.selectedWeek) {
                    checkWeekSilently(viewModel.selectedWeek)
                }
                ui.weekScrollView.smoothScrollTo(if (viewModel.selectedWeek > 4) (viewModel.selectedWeek - 4) * dip(56) else 0, 0)
            }

            ui.weekDayView.text = CourseUtils.getWeekday()

            initTheme()

            viewModel.timeList = viewModel.getTimeList(viewModel.table.timeTable)
            // 时间栏用哪一套作息由设置决定；「自动」按这张课表现在实际的安排重算
            viewModel.courseTimes = CourseTimes.ofPreferred(this@ScheduleActivity, viewModel.timeList,
                    viewModel.getDetailOfTable(viewModel.table.id))
            appliedAxisScheme = currentAxisScheme()

            viewModel.alphaInt = (255 * (viewModel.table.itemAlpha.toFloat() / 100)).roundToInt()

            initViewPage(viewModel.table.maxWeek, viewModel.table)

            // 先摘掉上一轮的观察者再重挂：initView 每次都会新建 LiveData（查询里带了新的
            // tableId），旧 LiveData 上的观察者不摘就会一直活着，按调用次数线性堆积。
            courseObservers.forEach { (liveData, observer) -> liveData.removeObserver(observer) }
            courseObservers = (1..7).map { day ->
                val liveData = viewModel.getRawCourseByDay(day, viewModel.table.id)
                val observer = Observer<List<CourseBean>> { list ->
                    if (list == null) return@Observer
                    if (list.isNotEmpty() && list[0].tableId != viewModel.table.id) return@Observer
                    viewModel.allCourseList[day - 1].value = list
                }
                liveData.observe(this@ScheduleActivity, observer)
                liveData to observer
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) {
            when (requestCode) {
                Const.REQUEST_CODE_EXPORT -> {
                    ui.content.longSnack("导出失败了，请检查存储权限，或换一个目录后重试")
                }
            }
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        when (requestCode) {
            Const.REQUEST_CODE_SCHEDULE_SETTING -> initView()
            Const.REQUEST_CODE_IMPORT -> {
                // 导入的新表已经接管默认表，界面必须跟着重载，否则用户看不到刚导入的课表
                launch { refreshAfterDefaultTableChanged() }
                showBottomSheetDialog()
                //ui.drawerLayout.openDrawer(Gravity.END)
                MaterialAlertDialogBuilder(this)
                        .setTitle("温馨提示")
                        .setView(AppCompatTextView(this).apply {
                            text = ViewUtils.getHtmlSpannedString(unscheduledNotice(data) +
                                    "记得<b><font color='#fa6278'>仔细检查</font></b>有没有少课、课程信息对不对哦，不要到时候<b><font color='#fa6278'>一不小心就翘课</font></b>啦<br>解析算法不是100%可靠的哦<br>但会朝这个方向努力")
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            val space = styledDimenPxSize(R.attr.dialogPreferredPadding)
                            setPadding(space, dip(8), space, 0)
                        })
                        .setCancelable(false)
                        .setPositiveButton("我知道啦", null)
                        .show()
            }
            Const.REQUEST_CODE_EXPORT -> {
                val uri = data?.data
                launch {
                    try {
                        viewModel.exportData(uri)
                        showShareDialog("分享课程文件", uri!!)
                    } catch (e: Exception) {
                        Toasty.error(this@ScheduleActivity, "导出失败>_<${e.message}")
                    }
                }
            }
            Const.REQUEST_CODE_EXPORT_ICS -> {
                val uri = data?.data
                launch {
                    try {
                        val skipped = viewModel.exportICS(uri)
                        if (skipped.isEmpty()) {
                            showShareDialog("分享日历文件", uri!!)
                        } else {
                            // 部分成功必须可见：日历少了课用户却不知道，等于给了一份错的日历。
                            MaterialAlertDialogBuilder(this@ScheduleActivity)
                                    .setTitle("有课程没有写入日历")
                                    .setMessage("这些课程的时间数据有问题（或不在学期周范围内），没能写入：\n" +
                                            skipped.joinToString("、") +
                                            "\n\n其余课程已正常导出。")
                                    .setPositiveButton("知道了") { _, _ ->
                                        showShareDialog("分享日历文件", uri!!)
                                    }
                                    .setCancelable(false)
                                    .show()
                        }
                    } catch (e: Exception) {
                        Toasty.error(this@ScheduleActivity, "导出失败>_<${e.message}")
                    }
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * 导入结果里「教务系统还没排课、因此没进课表」的课程提示；没有这种课时返回空串。
     *
     * 真实数据里确实有这种课（军训、军事理论、大学物理实验A 上下）：接口 `scheduleText`
     * 的四个字段全是 null，学校还没给它们排时间。**这不是解析失败，但用户会以为少了课**，
     * 所以导入完立刻点名说清楚，而不是让人自己对着教务系统数。
     */
    private fun unscheduledNotice(data: Intent?): String {
        val names = data?.getStringArrayListExtra(Const.EXTRA_UNSCHEDULED_COURSES).orEmpty()
        if (names.isEmpty()) return ""
        return "以下 ${names.size} 门课在教务系统里<b>还没有排上课时间</b>，不会出现在课表里：<br>" +
                "<b><font color='#fa6278'>${names.joinToString("、")}</font></b><br><br>"
    }

    private fun showShareDialog(title: String, uri: Uri) {        MaterialAlertDialogBuilder(this)
                .setTitle("分享")
                .setMessage("成功导出至你指定的路径啦，是否还要分享出去呢？")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("分享") { _, _ ->
                    val shareIntent = ShareCompat.IntentBuilder.from(this)
                            .setChooserTitle(title)
                            .setStream(uri)
                            .setType("*/*")
                            .createChooserIntent()
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(shareIntent)
                }
                // 不再 setCancelable(false)：文件**已经导出成功**了，这个框只是在问"还要不要顺手
                // 分享"。用户点遮罩或按返回手势就是"不用了"，把它锁住只会让人以为卡死。
                .show()
    }

    override fun onBackPressed() {
        when {
            ui.drawerLayout.isDrawerOpen(GravityCompat.START) -> ui.drawerLayout.closeDrawer(GravityCompat.START)
            ui.drawerLayout.isDrawerOpen(GravityCompat.END) -> ui.drawerLayout.closeDrawer(GravityCompat.END)
            bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED -> bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        ui.viewPager.clearOnPageChangeListeners()
        ui.weekToggleGroup.clearOnButtonCheckedListeners()
        // 这里原先调 AppWidgetUtils.updateWidget() 想"离开课表页时顺手刷一遍小部件"。
        // 那个入口发的是受保护广播，发不出去（见 AppWidgetUtils.refreshAllWidgets 的说明），
        // 一直是空转；而现在真正改动课表的每条路（加课/删课/切表/导入/改课表设置）自己都会刷新，
        // 所以直接删掉，不再留一个触发不了的动作。
        super.onDestroy()
    }

}