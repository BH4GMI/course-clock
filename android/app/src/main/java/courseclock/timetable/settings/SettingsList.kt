package courseclock.timetable.settings

import android.content.Context
import courseclock.timetable.R
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.CategoryItem
import courseclock.timetable.settings.items.HorizontalItem
import courseclock.timetable.settings.items.SeekBarItem
import courseclock.timetable.settings.items.SettingRowState
import courseclock.timetable.settings.items.SwitchItem
import courseclock.timetable.settings.items.VerticalItem
import courseclock.timetable.utils.BatteryOptimization
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.getPrefer
import splitties.resources.color

/**
 * 设置页整份列表的**唯一**构造处。
 *
 * 为什么从 Activity 里搬出来：列表内容（分组、文案、依赖关系）是这一页的"数据"，
 * 而它正确与否恰恰最难靠真机肉眼看出来（哪一行该灰、哪一行是两行高）。放在一个只依赖
 * Context 的普通类里，Robolectric 测试就能直接把**这一份**渲染出来验，
 * 既不用启动 Activity、也不用在测试里复制一套构造逻辑。
 *
 * 依赖关系的判断也集中在这里（见 [refreshAvailability]）：设计稿要求"关掉的开关，
 * 下面依赖它的行变灰且不可点"。若交给各个 provider 各自判断，同一条规则会出现 5 遍，
 * 而且 provider 只看得到"自己这一行"，看不到"上游开关现在是什么状态"。
 */
class SettingsList(private val context: Context) {

    private val prefer = context.getPrefer()

    /** 「后台运行不受限制」那一行。从系统设置回来后要刷新它的状态文本，所以留个引用。 */
    var batteryItem: HorizontalItem? = null
        private set

    /** 「开启上课提醒」总闸下面的行。总闸一动，这些行整体重算可用性。 */
    private val gatedRows = mutableListOf<BaseSettingItem>()

    /**
     * 构造整份列表。
     *
     * 分组按设计稿的三层意思排：课表显示 / 外观 / 上课提醒。
     * 功能行**一行都没少**，只是换了归属与说法。
     */
    fun build(): MutableList<BaseSettingItem> {
        val items = mutableListOf<BaseSettingItem>()
        gatedRows.clear()

        items += CategoryItem("课表显示")
        items += HorizontalItem(SettingRowId.CURRENT_TABLE, "设置当前课表",
                "上课时间、周数、格子样式", arrow = NAVIGATE)
        items += SwitchItem(SettingRowId.SCHEDULE_DETAIL_TIME, "显示上课时间", detailTime(),
                "关闭后只显示节次")
        items += SwitchItem(SettingRowId.SCHEDULE_GRID, "显示虚线网格", scheduleGrid(),
                "关闭后课表只剩留白")
        items += HorizontalItem(SettingRowId.TIME_AXIS_SCHEME, "作息时间表",
                schemeLabel(prefer.getString(Const.KEY_TIME_AXIS_SCHEME, "").orEmpty()),
                desc = "左侧时间按它显示", arrow = SELECT)
        items += SwitchItem(SettingRowId.SCHEDULE_PRE_LOAD, "页面预加载", preLoad(),
                "关掉更省内存，滑动课表要等一下；重启 App 后生效")
        items += SwitchItem(SettingRowId.SCHEDULE_BLANK_AREA, "课表底部留白", blankArea(),
                "把最后一节课滑到屏幕中间看")
        items += SwitchItem(SettingRowId.SHOW_EMPTY_VIEW, "空课表插图", showEmptyView(),
                "没课的日子显示一张图，切换页面后生效")

        items += CategoryItem("外观")
        items += HorizontalItem(SettingRowId.DAY_NIGHT_THEME, "显示主题", dayNightLabel(),
                arrow = SELECT)
        items += VerticalItem(SettingRowId.THEME_COLOR, "主题颜色",
                "更换课表配色，重启后生效")
        items += SwitchItem(SettingRowId.HIDE_NAV_BAR, "课表全屏显示", hideNavBar(),
                "隐藏状态栏，课表多一行；重启 App 后生效")
        items += SwitchItem(SettingRowId.DAY_WIDGET_COLOR, "日视图用课程颜色", dayWidgetColor(),
                "桌面小部件按课程色显示，小部件右上角可切换")

        items += CategoryItem("上课提醒")
        // 总闸排在最前：用户先看到"开不开"，再决定"怎么开"。
        items += SwitchItem(SettingRowId.COURSE_REMIND, "开启上课提醒", courseRemind(),
                "上课前用通知和振动提醒")
        // 这一条不是提醒的开关，而是"提醒能不能真的响"的**前提**：系统省电限制会把已经排好的
        // 提醒推到很久以后。它因此不跟着总闸变灰，理由见 [refreshAvailability] 的说明。
        //
        // 行名**固定不变**：原来它会随状态在「后台运行不受限制 / 加入 AOSP 白名单 /
        // 后台运行已设置」之间改名，同一行三个名字，用户下次根本找不到自己在看的是哪一条。
        // 状态改由右侧的值承担（「未设置 / 还需一步 / 已设置」）。
        batteryItem = HorizontalItem(SettingRowId.BATTERY_UNRESTRICTED, "后台运行不受限制",
                batteryStateText(), desc = "减少待机时提醒延迟", arrow = NAVIGATE)
        items += batteryItem!!
        items += gated(SwitchItem(SettingRowId.REMINDER_START, "上课提醒", reminderStart(),
                "上课前提醒一次"))
        items += gated(SwitchItem(SettingRowId.REMINDER_END, "下课提醒", reminderEnd(),
                "下课前提醒一次"))
        items += gated(SwitchItem(SettingRowId.REMINDER_MERGE, "连堂课只提醒一次", reminderMerge(),
                "两节连上时，下课时顺带说下一节"))
        items += gated(SwitchItem(SettingRowId.REMINDER_ON_GOING, "提醒通知不划走", onGoing(),
                "通知常驻在状态栏，直到下课；对下一次提醒生效"))
        // 提前量是"课前设好就行"的偏好，不跟着总闸灰：先定时间再开提醒是正常顺序，
        // 而且它没有副作用，也用不着重排。
        items += SeekBarItem(SettingRowId.REMINDER_BEFORE_START, "上课前提醒",
                prefer.getInt(CourseReminderScheduler.KEY_REMINDER_BEFORE_START,
                        CourseReminderScheduler.DEFAULT_BEFORE_START),
                0, 90, "分钟")
        items += SeekBarItem(SettingRowId.REMINDER_BEFORE_END, "下课前提醒",
                prefer.getInt(CourseReminderScheduler.KEY_REMINDER_BEFORE_END,
                        CourseReminderScheduler.DEFAULT_BEFORE_END),
                0, 90, "分钟")

        refreshAvailability()
        RowCardDecoration.apply(context, items)
        return items
    }

    /**
     * 总闸一变就整份重算依赖行的可用性。
     *
     * 这是"总闸关闭时依赖行变灰不可点"的**唯一**实现处：规则只写一遍，
     * provider 只照着 [BaseSettingItem.rowState] 画。
     * 返回 true 表示确实有行的状态变了（调用方据此决定要不要整表刷新）。
     *
     * 刻意**不**跟着灰的行，理由都是"灰掉反而挡住用户"：
     * - 「后台运行不受限制」是提醒能不能响的前提，用户完全可能想提前授权；
     *   灰掉它等于把"先去系统里放行"这条路堵上，而这恰恰是最容易漏、也最要命的一步。
     * - 两个提前量是课前偏好，没有副作用。
     * - 「显示主题 / 主题颜色 / 全屏显示 / 日视图用课程颜色」与提醒无关。
     */
    fun refreshAvailability(): Boolean {
        val on = prefer.getBoolean(Const.KEY_COURSE_REMIND, false)
        var changed = false
        for (row in gatedRows) {
            val wanted = if (on) SettingRowState.ENABLED else SettingRowState.DISABLED_BY_DEPENDENCY
            if (row.rowState != wanted) {
                row.rowState = wanted
                changed = true
            }
        }
        return changed
    }

    private fun gated(item: BaseSettingItem): BaseSettingItem {
        gatedRows += item
        return item
    }

    private fun detailTime() = prefer.getBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, true)
    private fun scheduleGrid() = prefer.getBoolean(Const.KEY_SCHEDULE_GRID, true)
    private fun preLoad() = prefer.getBoolean(Const.KEY_SCHEDULE_PRE_LOAD, true)
    private fun blankArea() = prefer.getBoolean(Const.KEY_SCHEDULE_BLANK_AREA, true)
    private fun showEmptyView() = prefer.getBoolean(Const.KEY_SHOW_EMPTY_VIEW, true)
    private fun dayWidgetColor() = prefer.getBoolean(Const.KEY_DAY_WIDGET_COLOR, true)
    private fun hideNavBar() = prefer.getBoolean(Const.KEY_HIDE_NAV_BAR, false)
    private fun courseRemind() = prefer.getBoolean(Const.KEY_COURSE_REMIND, false)
    private fun onGoing() = prefer.getBoolean(Const.KEY_REMINDER_ON_GOING, false)

    private fun reminderStart() = prefer.getBoolean(CourseReminderScheduler.KEY_REMINDER_START_ENABLED,
            CourseReminderScheduler.DEFAULT_REMINDER_KIND_ENABLED)

    private fun reminderEnd() = prefer.getBoolean(CourseReminderScheduler.KEY_REMINDER_END_ENABLED,
            CourseReminderScheduler.DEFAULT_REMINDER_KIND_ENABLED)

    private fun reminderMerge() = prefer.getBoolean(CourseReminderScheduler.KEY_REMINDER_MERGE_ENABLED,
            CourseReminderScheduler.DEFAULT_MERGE_ENABLED)

    private fun dayNightLabel(): String {
        val themes = context.resources.getStringArray(R.array.day_night_setting)
        val index = prefer.getInt(Const.KEY_DAY_NIGHT_THEME, 2).coerceIn(0, themes.size - 1)
        return themes[index]
    }

    /**
     * 电池优化那一行的**状态**。每次回前台都重算，用户可能刚从系统那个确认框回来。
     *
     * 只报「还要不要动手」，不写系统名：原来三种状态写的是「设置 HyperOS 无限制」/
     * 「已加入 AOSP 白名单」/「未加入，点这里设置」，用户既不知道 AOSP 是什么，也不知道
     * 自己这台机器到底处在哪一步；而具体该点哪里由点开之后的对话框一步步说。
     */
    fun batteryStateText(): String = when {
        batteryGranted() -> "已设置"
        // 厂商的省电策略与系统的电池优化是两处开关：系统那边放行了也不算完。
        BatteryOptimization.isExempt(context) -> "还需一步"
        else -> "未设置"
    }

    /**
     * 把这一行的状态值刷成最新的。
     *
     * **原地改 `value`，绝不用 `copy()`** —— 这是踩过的真机 bug：`rowPosition` / `topGap` 是
     * [RowCardDecoration] 标注在 item 上的**派生**状态，不在 data class 的构造参数里，`copy()`
     * 只会把它们重置成默认值（`rowPosition` 默认 `SINGLE` = 四角全圆角）。于是这一行会从
     * "「上课提醒」组里的中间行"变成一张凭空冒出来的独立卡片 —— `onResume` 每次都调这个方法，
     * 所以用户在真机上几乎总是看到错的那一版，而任何"刚 build 完就出图"的渲染都复现不出来。
     *
     * 行名现在固定不变，所以这里要改的只有 `value` 一项，原地改既够用也不会丢派生状态。
     */
    fun refreshBatteryItem(): HorizontalItem? {
        val item = batteryItem ?: return null
        item.value = batteryStateText()
        return item
    }

    /**
     * 两个步骤分别判断；历史确认结果绝不代替系统真实的电池优化白名单。
     */
    fun batteryGranted(): Boolean =
            !BatteryOptimization.needsMiuiStep(context) && BatteryOptimization.isExempt(context)

    /**
     * 「作息时间表」的显示名（原来是「时间栏作息方案」—— 术语太多，本科生看不懂）。
     *
     * 空串 = **自动**：用导入时按本次安排占比选出来的那一套（占比相同时是 B）。
     * 另外三套是学校公布的上午错峰作息：A = 教学楼 A、F 楼、J301 室；B = 教学楼 B、C 楼、
     * J302 室及其他楼宇；C = 教学楼 D、E 楼、J303 室。
     */
    private fun schemeLabel(value: String): String = when (value) {
        "A" -> "A 方案（A、F 楼）"
        "B" -> "B 方案（B、C 楼及其他）"
        "C" -> "C 方案（D、E 楼）"
        else -> "自动"
    }

    /** 供设置页的选择弹窗复用同一套文案，避免列表和弹窗对同一种方案叫两个名字。 */
    fun schemeLabelOf(value: String): String = schemeLabel(value)

}
