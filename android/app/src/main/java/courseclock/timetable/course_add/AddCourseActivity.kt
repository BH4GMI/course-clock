package courseclock.timetable.course_add

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseListActivity
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseEditBean
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.ViewUtils
import courseclock.timetable.widget.colorpicker.ColorPickerFragment
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.delay
import splitties.dimensions.dip
import splitties.snackbar.action
import splitties.snackbar.longSnack

/**
 * 添加课程（设计稿 6）：一页只留一个「保存课程」。
 *
 * - 头部「基本信息」卡：课程名称 + 课程颜色圆点（调色板 9 枚，选中的画 ✓；
 *   长按圆点仍可进取色器选自定义色——老功能保留）。
 * - 每个时间段一张卡：周数 / 节次是选择器行（带箭头），教师 / 地点直接打字
 *   （[AddCourseAdapter] 里 TextWatcher 实时写回）。
 * - 底部固定两条按钮：「+ 添加时间段」浅强调（次要动作），「保存课程」实心（唯一保存入口）。
 *   原来右上角还有一个「保存」，和底部按钮做同一件事，用户会犹豫点哪个——删掉。
 */
class AddCourseActivity : BaseListActivity(), ColorPickerFragment.ColorPickerDialogListener, AddCourseAdapter.OnItemEditTextChangedListener {

    private lateinit var llColor: LinearLayoutCompat
    private lateinit var tvColorCaption: AppCompatTextView

    /** 颜色圆点的视图，与 R.array.customizedColors 一一对应（选中的画 ✓）。 */
    private val colorCircles = mutableListOf<AppCompatTextView>()

    private val viewModel by viewModels<AddCourseViewModel>()
    private lateinit var etName: AppCompatEditText
    private var isExit: Boolean = false
    private lateinit var adapter: AddCourseAdapter

    override fun onSetupSubButton(tvButton: AppCompatTextView): AppCompatTextView? {
        // 设计稿 6：保存只在底部一条，不再放右上角的第二个「保存」。
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras!!
        viewModel.tableId = extras.getInt("tableId")
        viewModel.maxWeek = extras.getInt("maxWeek")
        viewModel.nodes = extras.getInt("nodes")

        // 适配器**同步**建好：底部「添加时间段」在异步加载完成前也可点，不能踩在
        // lateinit 上。数据统一装进 viewModel.editList（适配器从一开始就包着它），
        // 装完 notifyDataSetChanged 一次即可。
        // ViewModel 在 Activity 重建（深色切换、字号调整等）时保留：editList 已有数据就
        // 不能再 append——否则每个时间段卡片都会翻倍，一保存就落库两份。
        adapter = AddCourseAdapter(R.layout.item_add_course_detail, viewModel.editList)
        initAdapter(viewModel.baseBean)

        val courseId = extras.getInt("id")
        if (courseId == -1) {
            if (viewModel.editList.isEmpty()) {
                viewModel.initData(viewModel.maxWeek)
                adapter.notifyDataSetChanged()
            }
        } else if (viewModel.editList.isEmpty() || viewModel.baseBean.id != courseId) {
            launch {
                val detailList = viewModel.initData(courseId, viewModel.tableId)
                detailList.forEach {
                    viewModel.editList.add(CourseUtils.detailBean2EditBean(it))
                }
                val courseBaseBean = viewModel.initBaseData(courseId)
                viewModel.baseBean.id = courseBaseBean.id
                viewModel.baseBean.color = courseBaseBean.color
                viewModel.baseBean.courseName = courseBaseBean.courseName
                viewModel.baseBean.tableId = courseBaseBean.tableId
                // 头部卡是加载前建的：名称与颜色选中态要等数据到位后补填。
                etName.setText(viewModel.baseBean.courseName)
                etName.setSelection(viewModel.baseBean.courseName.length)
                refreshColorUi()
                adapter.notifyDataSetChanged()
            }
        }
        initBottomActions()
    }

    /** 底部固定的两条按钮：次要（添加时间段）在主操作（保存课程）之上，都随内容全宽。 */
    private fun initBottomActions() {
        val bar = LinearLayoutCompat(this).apply {
            id = R.id.ll_add_course_actions
            orientation = LinearLayoutCompat.VERTICAL
            setPadding(dip(16), dip(8), dip(16), dip(24))
            addView(AppCompatTextView(context).apply {
                text = getString(R.string.add_course_add_period)
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_soft_button)
                layoutParams = LinearLayoutCompat.LayoutParams(
                        LinearLayoutCompat.LayoutParams.MATCH_PARENT, dip(48))
                setOnClickListener { addTimePeriod() }
            })
            addView(AppCompatTextView(context).apply {
                text = getString(R.string.add_course_save)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_primary_button)
                layoutParams = LinearLayoutCompat.LayoutParams(
                        LinearLayoutCompat.LayoutParams.MATCH_PARENT, dip(52)).apply {
                    topMargin = dip(10)
                }
                setOnClickListener { saveCourse() }
            })
        }
        rootView.addView(bar, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomToBottom = ConstraintSet.PARENT_ID
            startToStart = ConstraintSet.PARENT_ID
            endToEnd = ConstraintSet.PARENT_ID
        })
        // 列表底边让开按钮条，别让最后一行被压在按钮下面。
        (mRecyclerView.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
            bottomToBottom = R.id.ll_add_course_actions
            bottomMargin = dip(4)
        }
    }

    /** 追加一个时间段：沿用上一段的教师/教室，周数默认全选（与旧版加号一致）。 */
    private fun addTimePeriod() {
        if (viewModel.editList.isEmpty()) {
            adapter.addData(CourseEditBean(
                    teacher = "",
                    room = "",
                    tableId = viewModel.tableId,
                    weekList = MutableLiveData<ArrayList<Int>>().apply {
                        this.value = ArrayList<Int>().apply {
                            for (i in 1..viewModel.maxWeek) {
                                this.add(i)
                            }
                        }
                    }))
        } else {
            adapter.addData(CourseEditBean(
                    teacher = viewModel.editList[0].teacher,
                    room = viewModel.editList[0].room,
                    tableId = viewModel.tableId,
                    weekList = MutableLiveData<ArrayList<Int>>().apply {
                        this.value = ArrayList<Int>().apply {
                            for (i in 1..viewModel.maxWeek) {
                                this.add(i)
                            }
                        }
                    }))
        }
        mRecyclerView.scrollToPosition(adapter.data.size)
    }

    override fun onEditTextAfterTextChanged(editable: Editable, position: Int, what: String) {
        when (what) {
            "room" -> viewModel.editList[position].room = editable.toString()
            "teacher" -> viewModel.editList[position].teacher = editable.toString()
        }
    }

    private fun initAdapter(baseBean: CourseBaseBean) {
        adapter.setListener(this)
        adapter.addHeaderView(initHeaderView(baseBean))
        adapter.addChildClickViewIds(R.id.ll_time, R.id.ib_delete, R.id.ll_weeks)
        adapter.setOnItemChildClickListener { _, view, position ->
            when (view.id) {
                R.id.ll_time -> {
                    // 用回调而不是 observe：每次点开都往同一个 LiveData 上再挂一个观察者，
                    // 挂 N 次就触发 N 遍。对话框保存后整项重绑一次即可。
                    val selectTimeDialog = SelectTimeFragment.newInstance(position)
                    selectTimeDialog.onSaved = { adapter.notifyItemChanged(position + 1) }
                    selectTimeDialog.isCancelable = false
                    selectTimeDialog.show(supportFragmentManager, "selectTime")
                }
                R.id.ib_delete -> {
                    if (adapter.data.size == 1) {
                        Toasty.error(this.applicationContext, "至少要保留一个时间段").show()
                    } else {
                        adapter.remove(position)
                    }
                }
                R.id.ll_weeks -> {
                    // 同 ll_time：回调 + 整项重绑，替代每次点击都新挂一个观察者。
                    val selectWeekDialog = SelectWeekFragment.newInstance(position)
                    selectWeekDialog.onSaved = { adapter.notifyItemChanged(position + 1) }
                    selectWeekDialog.isCancelable = false
                    selectWeekDialog.show(supportFragmentManager, "selectWeek")
                }
            }
        }
        mRecyclerView.adapter = adapter
        mRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    /** 头部「基本信息」卡：课程名输入 + 调色板圆点选色（长按进取色器选自定义）。 */
    private fun initHeaderView(baseBean: CourseBaseBean): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_add_course_base, null)
        etName = view.findViewById(R.id.et_name)
        llColor = view.findViewById(R.id.ll_color)
        tvColorCaption = view.findViewById(R.id.tv_color_caption)
        etName.setText(baseBean.courseName)
        etName.setSelection(baseBean.courseName.length)
        etName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                baseBean.courseName = s.toString()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        })
        val palette = resources.getIntArray(R.array.customizedColors)
        palette.forEachIndexed { index, colorInt ->
            val circle = AppCompatTextView(this).apply {
                text = "✓"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_color_circle)
                layoutParams = LinearLayoutCompat.LayoutParams(dip(28), dip(28)).apply {
                    marginEnd = dip(10)
                }
                setOnClickListener {
                    baseBean.color = "#${Integer.toHexString(colorInt)}"
                    refreshColorUi()
                }
                // 长按 = 不被调色板限制的取色器（老入口保留）。
                setOnLongClickListener {
                    ColorPickerFragment.newBuilder()
                            .setColor(colorInt)
                            .setShowAlphaSlider(false)
                            .show(this@AddCourseActivity)
                            true
                }
            }
            colorCircles.add(circle)
            llColor.addView(circle)
        }
        refreshColorUi(baseBean)
        return view
    }

    /** 按当前 [AddCourseViewModel.baseBean.color] 刷新圆点选中态和「已选：X」说明。 */
    private fun refreshColorUi() {
        refreshColorUi(viewModel.baseBean)
    }

    private fun refreshColorUi(baseBean: CourseBaseBean) {
        if (!this::llColor.isInitialized) return
        val palette = resources.getIntArray(R.array.customizedColors)
        val names = resources.getStringArray(R.array.customizedColorNames)
        // 选中匹配按「解析后的颜色值」：库里存的是任意历史串（大小写、长短 hex 都有），
        // 字符串直接比会漏。0 作「没设置」哨兵——调色板全是带 alpha 的不透明色，不会撞上。
        val parsed = ViewUtils.parseCourseColor(baseBean.color, 0)
        colorCircles.forEachIndexed { index, circle ->
            val selected = parsed != 0 && parsed == palette[index]
            circle.background.mutate().setTint(palette[index])
            circle.setTextColor(Color.WHITE)
            circle.text = if (selected) "✓" else ""
        }
        tvColorCaption.text = when {
            parsed == 0 -> getString(R.string.add_course_pick_color)
            palette.indexOfFirst { it == parsed } >= 0 ->
                    getString(R.string.add_course_picked, names[palette.indexOfFirst { it == parsed }])
            else -> getString(R.string.add_course_picked_custom)
        }
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        viewModel.baseBean.color = "#${Integer.toHexString(color)}"
        refreshColorUi()
    }

    /** 底部「保存课程」的唯一入口（原右上角「保存」的校验与落库路径原样保留）。 */
    private fun saveCourse() {
        if (viewModel.baseBean.courseName == "") {
            Toasty.error(this.applicationContext, "请填写课程名称").show()
            return
        }
        if (viewModel.baseBean.id == -1 || !viewModel.updateFlag) {
            launch {
                val task = viewModel.checkSameName()
                if (task != null) {
                    viewModel.baseBean.id = task.id
                }
                saveData(task != null)
            }
        } else {
            saveData()
        }
    }

    private fun saveData(isSame: Boolean = false) {
        launch {
            val maxId = viewModel.getLastId()
            if (viewModel.newId == -1) {
                if (maxId == null) {
                    viewModel.newId = 0
                } else {
                    viewModel.newId = maxId + 1
                }
            }

            try {
                viewModel.preSaveData(isSame)
                // 小部件刷新一律以平台登记的实例为准（见 AppWidgetUtils.refreshAllWidgets）：
                // 自建的 AppWidgetBean 表可能一行都没有（配置页只在有多张课表时才写入），
                // 拿它当实例清单会漏刷新。
                AppWidgetUtils.refreshAllWidgets(applicationContext)
                Toasty.success(applicationContext, "保存成功").show()
                // 新增和编辑都回 RESULT_OK：课程管理页靠这个结果重拉列表（编辑以前没人接收结果，
                // 列表停在旧数据）。course 参数只对「新增」有用，多带不伤。
                setResult(Activity.RESULT_OK, Intent().putExtra("course", viewModel.baseBean))
                finish()
            } catch (e: Exception) {
                Toasty.error(applicationContext, e.message ?: "发生异常", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exitBy2Click() {
        if (!isExit) {
            isExit = true // 准备退出
            mRecyclerView.longSnack("真的不保存吗？那再按一次退出编辑哦，就不保存啦。") {
                action("退出编辑") {
                    finish()
                }
            }
            launch {
                delay(2000)
                isExit = false
            }
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        exitBy2Click()  //退出应用的操作
    }
}
