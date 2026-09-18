package courseclock.timetable.settings

import courseclock.timetable.settings.items.SettingRowArrow

/**
 * 每一行的稳定身份。
 *
 * 为什么不让点击分发继续用"标题字符串"：标题是**文案**，文案会为了说人话反复改，
 * 而 `when (item.title)` 把它同时当成了标识符 —— 改一次措辞就得回去改一处 `when`，
 * 漏一处就是"点了没反应"。把"文案"和"身份"分开之后，改句子不再有第二种后果。
 *
 * 顺带解决了一个真实的撞名：「上课提醒」既是分组标题、又是总闸、还是子开关，
 * 三者都叫这个名字，靠字符串区分本来就已经很勉强。
 */
object SettingRowId {
    // 课表显示
    const val SCHEDULE_DETAIL_TIME = "schedule_detail_time"
    const val SCHEDULE_GRID = "schedule_grid"
    const val TIME_AXIS_SCHEME = "time_axis_scheme"
    const val SCHEDULE_PRE_LOAD = "schedule_pre_load"
    const val SCHEDULE_BLANK_AREA = "schedule_blank_area"
    const val SHOW_EMPTY_VIEW = "show_empty_view"

    // 外观
    const val DAY_NIGHT_THEME = "day_night_theme"
    const val THEME_COLOR = "theme_color"
    const val HIDE_NAV_BAR = "hide_nav_bar"
    const val DAY_WIDGET_COLOR = "day_widget_color"

    // 上课提醒
    const val COURSE_REMIND = "course_remind"
    const val REMINDER_START = "reminder_start"
    const val REMINDER_END = "reminder_end"
    const val REMINDER_MERGE = "reminder_merge"
    const val REMINDER_ON_GOING = "reminder_on_going"
    const val REMINDER_BEFORE_START = "reminder_before_start"
    const val REMINDER_BEFORE_END = "reminder_before_end"

    // 课表数据 / 系统
    const val CURRENT_TABLE = "current_table"
    const val BATTERY_UNRESTRICTED = "battery_unrestricted"
}

/** 「>」：进新页面。 */
internal val NAVIGATE = SettingRowArrow.NAVIGATE

/** 「⌃⌄」：就地选择。 */
internal val SELECT = SettingRowArrow.SELECT
