package courseclock.timetable.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import courseclock.timetable.AppDatabase
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseListActivity
import courseclock.timetable.dao.TableDao
import courseclock.timetable.schedule_settings.ScheduleSettingsActivity
import courseclock.timetable.settings.items.*
import courseclock.timetable.utils.BatteryOptimization
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.getPrefer
import courseclock.timetable.widget.colorpicker.ColorPickerFragment
import splitties.dimensions.dip
import splitties.resources.color
import splitties.snackbar.longSnack

/**
 * 设置页。
 *
 * 这个类现在只管三件事：**列表怎么建**（委托给 [SettingsList]）、
 * **点一行做什么**、**从别的页面回来后刷新哪几行**。
 * 行的样子在 `provider/` 里，列表的内容与依赖关系在 [SettingsList] 里 —— 分开是为了
 * 让"总闸关了哪些行要灰"这种规则只存在一处（见 [SettingsList.refreshAvailability]）。
 */
class SettingsActivity : BaseListActivity(), ColorPickerFragment.ColorPickerDialogListener {

    override fun onColorSelected(dialogId: Int, color: Int) {
        getPrefer().edit {
            putInt(Const.KEY_THEME_COLOR, color)
        }
        // 不弹"重启后生效"：那一行的副标题本来就写着，弹窗是重复通知。
    }

    private lateinit var dataBase: AppDatabase
    private lateinit var tableDao: TableDao
    private val dayNightTheme by lazy(LazyThreadSafetyMode.NONE) {
        resources.getStringArray(R.array.day_night_setting)
    }
    private var dayNightIndex = 2

    /**
     * 「上课提醒」分组在设置列表里的下标。
     *
     * 由 [onCreate] 在添加该分组时记下，供主界面「捷径 → 上课提醒」跳进来时定位。
     * 不写成常量：分组顺序由 [SettingsList.build] 里 `items +=` 的先后决定，
     * 写死一个数字迟早在插队时悄悄错位。
     */
    private var reminderSectionIndex = 0

    /** 列表内容与依赖关系的唯一出处。见类文档。 */
    private lateinit var settingsList: SettingsList
    private val items = mutableListOf<BaseSettingItem>()
    private val mAdapter = SettingItemAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dataBase = AppDatabase.getDatabase(application)
        tableDao = dataBase.tableDao()
        dayNightIndex = getPrefer().getInt(Const.KEY_DAY_NIGHT_THEME, 2)

        settingsList = SettingsList(this)
        items += settingsList.build()
        mAdapter.data = items
        mRecyclerView.layoutManager = LinearLayoutManager(this)
        mRecyclerView.itemAnimator?.changeDuration = 250
        mRecyclerView.adapter = mAdapter
        // 列表第一组的组标题贴顶会显得挤，留出一点呼吸；卡片左右 16dp 的契约不受影响。
        mRecyclerView.setPadding(0, dip(4), 0, 0)
        // 从主界面「捷径 → 上课提醒」进来时直接定位到那一节，不把用户丢在列表顶部。
        reminderSectionIndex = items.indexOfFirst { it is CategoryItem && it.title == "上课提醒" }
                .coerceAtLeast(0)
        if (intent.getStringExtra(EXTRA_SECTION) == SECTION_REMINDER) {
            mRecyclerView.scrollToPosition(reminderSectionIndex)
        }

        // 开关注释见 SwitchItemProvider：它自己 isClickable=false，点击由整行接收，
        // 所以这里**不要**再注册子视图点击 —— 否则点开关会触发两次、一开一关。
        mAdapter.setOnItemClickListener { _, view, position ->
            val item = items[position]
            // "关掉的开关，下面依赖它的行变灰且不可点"：分发处统一挡一次，
            // 各 provider 就不用各写一遍"我是不是该响应点击"。
            if (!item.isRowEnabled) return@setOnItemClickListener
            // 线性马达的一下轻震：按开关、按"值 + 箭头"的行（就地选择）都该有触感回执。
            // 不可用的行在上面已经返回了，所以"灰着的行点了也震"不会发生。
            view.settingRowTapFeedback()
            when (item) {
                is HorizontalItem -> onHorizontalItemClick(item, position, view)
                is VerticalItem -> onVerticalItemClick(item)
                is SwitchItem -> onSwitchItemCheckChange(!item.checked, item, view)
                is SeekBarItem -> onSeekBarItemClick(item, position)
            }
        }
    }

    private fun onSwitchItemCheckChange(isChecked: Boolean, item: SwitchItem, rowView: View? = null) {
        // 用户点的那一行：让活着的那枚开关自己滑，不重绑（理由见 setSwitch）。
        rowView?.findViewById<SwitchView>(R.id.anko_switch)?.animateTo(isChecked)
        // 这一族开关**一律不弹"xx后生效"**：开关本身的状态变化就是回执，改完回到课表自然生效。
        // 时机上容易让人以为"没保存上"的（要重启、要切页面、要下一次提醒），把时机写进
        // SettingsList 的行副标题里常驻显示，而不是每次都弹一条全宽的 Snackbar 挡住列表。
        when (item.id) {
            SettingRowId.SCHEDULE_PRE_LOAD -> {
                getPrefer().edit { putBoolean(Const.KEY_SCHEDULE_PRE_LOAD, isChecked) }
                setSwitch(item, isChecked, rowView)
            }
            SettingRowId.SCHEDULE_BLANK_AREA -> {
                getPrefer().edit { putBoolean(Const.KEY_SCHEDULE_BLANK_AREA, isChecked) }
                setSwitch(item, isChecked, rowView)
            }
            SettingRowId.SCHEDULE_DETAIL_TIME -> {
                getPrefer().edit { putBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, isChecked) }
                setSwitch(item, isChecked, rowView)
            }
            SettingRowId.SCHEDULE_GRID -> {
                getPrefer().edit { putBoolean(Const.KEY_SCHEDULE_GRID, isChecked) }
                setSwitch(item, isChecked, rowView)
            }
            SettingRowId.SHOW_EMPTY_VIEW -> {
                getPrefer().edit { putBoolean(Const.KEY_SHOW_EMPTY_VIEW, isChecked) }
                setSwitch(item, isChecked, rowView)
            }
            SettingRowId.DAY_WIDGET_COLOR -> {
                getPrefer().edit { putBoolean(Const.KEY_DAY_WIDGET_COLOR, isChecked) }
                setSwitch(item, isChecked, rowView)
            }
            SettingRowId.HIDE_NAV_BAR -> {
                getPrefer().edit { putBoolean(Const.KEY_HIDE_NAV_BAR, isChecked) }
                setSwitch(item, isChecked, rowView)
            }
            SettingRowId.COURSE_REMIND -> {
                // 这里原先有一段"没有日视图小部件就拒绝打开、并把开关弹回关"的判定。它把
                // 提醒当成了小部件的附属功能：提醒只用 AlarmManager + NotificationManager，
                // 桌面放不放小部件与它毫无关系（见 CourseReminderNotifier 的类文档）。
                // 该判定已删除，用户单独想要提醒、不要小部件，现在走得通。
                getPrefer().edit { putBoolean(Const.KEY_COURSE_REMIND, isChecked) }
                // 改了总闸就必须立刻重排，否则要等下一次跨天闹钟才生效。重排要同步读三张表
                // 再循环几百枚 PendingIntent，丢到协程里，不在主线程上等它
                // （BaseActivity.launch 挂在生命周期上，页面销毁自动取消）。
                launch { CourseReminderScheduler.reschedule(applicationContext) }
                item.checked = isChecked
                // 总闸一动，它下面依赖它的行的可用性要整体重算 —— 规则只在 SettingsList 里，
                // 这里只负责"算完刷新"。刷新用整表通知：受影响的是跨多个位置的若干行，
                // 逐个算下标再 notifyItemChanged 反而更容易漏。
                settingsList.refreshAvailability()
                mAdapter.notifyDataSetChanged()
                if (isChecked) {
                    warnIfNotificationsDisabled()
                    warnIfBatteryRestricted()
                }
            }
            SettingRowId.REMINDER_ON_GOING -> {
                getPrefer().edit { putBoolean(Const.KEY_REMINDER_ON_GOING, isChecked) }
                setSwitch(item, isChecked, rowView)
                // 常驻通知对**下一次**提醒才变样，这个时机写在那行的副标题里，不弹窗。
            }
            // 下面三个都在总闸之下，改完必须重排：关掉的类别要撤掉已注册的闹钟，
            // 开回来的类别要立刻补排，否则得等到下一次跨天。
            SettingRowId.REMINDER_START -> {
                getPrefer().edit { putBoolean(CourseReminderScheduler.KEY_REMINDER_START_ENABLED, isChecked) }
                setSwitch(item, isChecked, rowView)
                launch { CourseReminderScheduler.reschedule(applicationContext) }
            }
            SettingRowId.REMINDER_END -> {
                getPrefer().edit { putBoolean(CourseReminderScheduler.KEY_REMINDER_END_ENABLED, isChecked) }
                setSwitch(item, isChecked, rowView)
                launch { CourseReminderScheduler.reschedule(applicationContext) }
            }
            SettingRowId.REMINDER_MERGE -> {
                getPrefer().edit { putBoolean(CourseReminderScheduler.KEY_REMINDER_MERGE_ENABLED, isChecked) }
                setSwitch(item, isChecked, rowView)
                launch { CourseReminderScheduler.reschedule(applicationContext) }
            }
        }
    }

    /**
     * 把新状态写回 item，并就地更新那一行 —— **刻意不 notifyItemChanged**。
     *
     * 那一行刚才已经由 [SwitchView.animateTo] 直接改好了，再刷一次会让 RecyclerView 走
     * 「变更」那条路：它为这个位置**另造一个 ViewHolder**，把正在滑动的那枚开关连同它的
     * itemView 一起换掉。真机连按三次的日志（`changeDuration = 250`）能看到两条后果：
     *
     * 1. 动画白播 —— 新造的开关按 `convert()` 里的吸附值画，旧的那枚在约 150ms 后
     *    `onDetachedFromWindow`（实测 `detach … anim=true`），用户看到的就是"没有动画"。
     * 2. 点击会丢 —— itemView 换了身份，落在替换窗口里的那一下没有接收者，
     *    表现是"连按第二下没反应"。
     *
     * 这一行的可见内容里只有开关的开关态依赖 `checked`，而它已经由 [SwitchView.animateTo]
     * 画好了；唯一要跟着变的是读屏用的整行描述（开关自己不可聚焦，见 [SwitchView]），
     * 所以这里显式补上，不需要整行重绑。
     */
    private fun setSwitch(item: SwitchItem, isChecked: Boolean, rowView: View?) {
        item.checked = isChecked
        rowView?.contentDescription = item.rowContentDescription
    }

    /**
     * 通知权限被关掉时明确告诉用户「提醒不会出现」。
     *
     * targetSdk 还是 29，在 Android 13+ 上系统会**代替 App** 弹一次通知权限对话框，而用户
     * 一旦点了"不允许"，除非卸载重装或把 targetSdk 提到 33，系统再也不会弹。App 这边唯一
     * 能做的就是把用户送到系统设置页。
     *
     * 判据用 `areNotificationsEnabled()` 而不是 `checkSelfPermission(POST_NOTIFICATIONS)`：
     * 后者在 targetSdk < 33 上恒为"已授予"，与用户在设置里关没关通知无关，两者不等价。
     */
    private fun warnIfNotificationsDisabled() {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return
        }
        MaterialAlertDialogBuilder(this)
                .setTitle("通知权限被关闭了")
                .setMessage("上课提醒是靠通知发出来的。现在通知权限是关闭状态，" +
                        "到了上课时间也不会有任何提醒。\n\n点下面的按钮到系统设置里重新打开。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
                .setNegativeButton("稍后再说", null)
                .show()
    }

    /**
     * 没有拿到电池优化豁免时，明确告诉用户「整夜待机之后提醒可能不响」。
     *
     * 这不是"提醒某处有个开关"，而是一条**实测过的不变式**：深度 Doze 下未加白名单的
     * 第三方 App 的精确闹钟会被 MIUI 的 `ssru` 策略推到约一年后。开关打开、闹钟排好、
     * 通知权限也给了，它照样可以一声不响 —— 用户看不到任何症状，只会觉得"这 App 不灵"。
     * 所以这条提示与"通知权限被关"同等重要，出现时机也一样（用户刚打开总闸时）。
     */
    private fun warnIfBatteryRestricted() {
        if (settingsList.batteryGranted()) {
            return
        }
        MaterialAlertDialogBuilder(this)
                .setTitle("还差最后一步")
                .setMessage("系统对后台 App 有省电限制。在这种限制下，手机待机一段时间后，" +
                        "排好的提醒会被系统推迟到很久以后 —— 早上第一节课的提醒可能整夜都没响。\n\n" +
                        "点下面的「去设置」，按系统给的提示让课钟不被限制后台运行。" +
                        "有的手机要设两处（厂商的省电管理 + 系统的电池优化），跟着走一遍就好。")
                .setPositiveButton("去设置") { _, _ -> requestBatteryWhitelist() }
                .setNegativeButton("稍后再说", null)
                .show()
    }

    /** 厂商设置与系统白名单分步处理，返回后分别确认或查询。 */
    private fun requestBatteryWhitelist() {
        if (BatteryOptimization.needsMiuiStep(this)) {
            getPrefer().edit { putBoolean(Const.KEY_HYPEROS_BATTERY_PENDING, true) }
            if (!BatteryOptimization.openMiuiForResult(this, REQUEST_HYPEROS_BATTERY)) {
                getPrefer().edit { putBoolean(Const.KEY_HYPEROS_BATTERY_PENDING, false) }
                MaterialAlertDialogBuilder(this)
                        .setTitle("没能打开系统的省电管理")
                        .setMessage("请自己在系统设置里找到「应用省电管理 / 应用启动管理」这类入口，" +
                                "把课钟的省电策略设为「无限制」。\n\n" +
                                "找不到也没关系：点下面的按钮，直接让系统允许课钟在后台运行。")
                        .setPositiveButton("允许后台运行") { _, _ -> requestAospWhitelist() }
                        .setNegativeButton(R.string.cancel, null).show()
            }
        } else requestAospWhitelist()
    }

    private fun requestAospWhitelist() {
        if (!BatteryOptimization.requestForResult(this, REQUEST_BATTERY)) {
            mRecyclerView.longSnack("无法打开系统的电池优化设置，请在系统设置中搜索「电池优化」")
        }
    }

    private fun refreshBatteryRow() {
        val index = items.indexOfFirst { it is HorizontalItem && it.id == SettingRowId.BATTERY_UNRESTRICTED }
        val item = settingsList.refreshBatteryItem() ?: return
        if (index < 0) return
        items[index] = item
        mAdapter.notifyItemChanged(index)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统那个确认框（或厂商的省电策略页）回来，状态文本要跟着变；
        // 列表本身不会重建。
        refreshBatteryRow()
        if (getPrefer().getBoolean(Const.KEY_HYPEROS_BATTERY_PENDING, false)) {
            getPrefer().edit { putBoolean(Const.KEY_HYPEROS_BATTERY_PENDING, false) }
            MaterialAlertDialogBuilder(this)
                    .setTitle("后台运行设置")
                    .setMessage("刚才在系统设置里，课钟的省电策略是不是已经设成「无限制」了？")
                    .setPositiveButton("已设为无限制") { _, _ ->
                        getPrefer().edit { putBoolean(Const.KEY_HYPEROS_BATTERY_CONFIRMED, true) }
                        refreshBatteryRow()
                    }
                    .setNegativeButton("尚未设置", null).show()
        }
    }

    private fun onHorizontalItemClick(item: HorizontalItem, position: Int, rowView: View) {
        when (item.id) {
            SettingRowId.BATTERY_UNRESTRICTED -> {
                requestBatteryWhitelist()
            }
            SettingRowId.CURRENT_TABLE -> {
                launch {
                    // 一张课表都没有时没有可以设置的课表，说清楚而不是打开一个空页面。
                    val table = tableDao.getDefaultTable() ?: run {
                        mRecyclerView.longSnack("还没有课表，先导入一张再来设置吧~")
                        return@launch
                    }
                    startActivityForResult(
                            Intent(this@SettingsActivity, ScheduleSettingsActivity::class.java).apply {
                                putExtra("tableData", table)
                            }, 180)
                }
            }
            SettingRowId.TIME_AXIS_SCHEME -> {
                val values = arrayOf("", "A", "B", "C")
                val labels = values.map { settingsList.schemeLabelOf(it) }
                val current = values.indexOf(getPrefer().getString(Const.KEY_TIME_AXIS_SCHEME, "").orEmpty())
                        .coerceAtLeast(0)
                // 就地换值：选完立刻生效并刷新那一行（见 ChoicePopup 的说明）。
                // 回到课表由 onResume 的比对重建接手，不弹"回到课表就生效"。
                ChoicePopup.show(rowView, labels, current) { picked ->
                    getPrefer().edit {
                        putString(Const.KEY_TIME_AXIS_SCHEME, values[picked])
                    }
                    item.value = labels[picked]
                    mAdapter.notifyItemChanged(position)
                }
            }
            SettingRowId.DAY_NIGHT_THEME -> {
                val current = getPrefer().getInt(Const.KEY_DAY_NIGHT_THEME, 2)
                        .coerceIn(0, dayNightTheme.size - 1)
                ChoicePopup.show(rowView, dayNightTheme.toList(), current) { picked ->
                    dayNightIndex = picked
                    getPrefer().edit {
                        putInt(Const.KEY_DAY_NIGHT_THEME, dayNightIndex)
                    }
                    item.value = dayNightTheme[dayNightIndex]
                    mAdapter.notifyItemChanged(position)
                    when (dayNightIndex) {
                        0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        2 -> {
                            when {
                                Build.VERSION.SDK_INT >= 29 -> {
                                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                                }
                                Build.VERSION.SDK_INT >= 23 -> {
                                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
                                }
                                else -> {
                                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onVerticalItemClick(item: VerticalItem) {
        when (item.id) {
            SettingRowId.THEME_COLOR -> {
                ColorPickerFragment.newBuilder()
                        .setShowAlphaSlider(true)
                        .setColor(getPrefer().getInt(Const.KEY_THEME_COLOR, color(R.color.colorAccent)))
                        .show(this)
            }
        }
    }

    private fun onSeekBarItemClick(item: SeekBarItem, position: Int) {
        val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(item.title)
                .setView(R.layout.dialog_edit_text)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sure, null)
                .create()
        dialog.show()
        val inputLayout = dialog.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialog.findViewById<TextInputEditText>(R.id.edit_text)
        inputLayout?.helperText = "范围 ${item.min} ~ ${item.max}"
        inputLayout?.suffixText = item.unit
        editText?.inputType = InputType.TYPE_CLASS_NUMBER
        val valueStr = item.valueInt.toString()
        editText?.setText(valueStr)
        editText?.setSelection(valueStr.length)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = editText?.text
            if (value.isNullOrBlank()) {
                inputLayout?.error = "数值不能为空哦>_<"
                return@setOnClickListener
            }
            val valueInt = try {
                value.toString().toInt()
            } catch (e: Exception) {
                inputLayout?.error = "输入异常>_<"
                return@setOnClickListener
            }
            if (valueInt < item.min || valueInt > item.max) {
                inputLayout?.error = "注意范围 ${item.min} ~ ${item.max}"
                return@setOnClickListener
            }
            when (item.id) {
                SettingRowId.REMINDER_BEFORE_START -> {
                    getPrefer().edit {
                        putInt(CourseReminderScheduler.KEY_REMINDER_BEFORE_START, valueInt)
                    }
                    // 提前量变了，已排的提醒时刻全部作废，必须立刻重排（丢协程，不占主线程）。
                    launch { CourseReminderScheduler.reschedule(applicationContext) }
                }
                SettingRowId.REMINDER_BEFORE_END -> {
                    getPrefer().edit {
                        putInt(CourseReminderScheduler.KEY_REMINDER_BEFORE_END, valueInt)
                    }
                    launch { CourseReminderScheduler.reschedule(applicationContext) }
                }
            }
            item.valueInt = valueInt
            mAdapter.notifyItemChanged(position)
            dialog.dismiss()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_BATTERY) {
            refreshBatteryRow()
        }
        if (requestCode == 180) {
            setResult(RESULT_OK)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        /**
         * 从主界面「捷径 → 上课提醒」进来时的定位标记（值为 [SECTION_REMINDER]）。
         *
         * 为什么需要它：设置页很长，而"提醒"是用户专门来找的功能。只把用户丢在列表顶部、
         * 让他自己往下翻，等于入口没做完整。这里只做"滚到对应分组"，不做高亮 ——
         * 高亮要动 item 的背景与动画，收益远小于多一份状态。
         */
        const val EXTRA_SECTION = "setting_section"

        /** [EXTRA_SECTION] 的取值：滚到「上课提醒」分组。 */
        const val SECTION_REMINDER = "reminder"

        /** 电池优化确认框的请求码。 */
        private const val REQUEST_BATTERY = 181
        private const val REQUEST_HYPEROS_BATTERY = 182
    }
}
