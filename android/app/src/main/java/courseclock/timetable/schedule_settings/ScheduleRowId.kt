package courseclock.timetable.schedule_settings

/**
 * 「课表设置」页每一行的稳定身份。
 *
 * 这一页是搜索驱动的（`keys` 用来做关键词匹配），**不能**再用标题当分发的依据：
 * 用户在搜索框里过滤出来的行照样要能点，而标题随时可能为了说人话而改。
 * 取值只在这一页内使用，所以放在本包而不是 `settings/SettingRowId.kt`。
 */
object ScheduleRowId {
    // 课程数据
    const val TABLE_NAME = "table_name"
    const val CLASS_TIME = "class_time"
    const val TERM_START = "term_start"
    const val CURRENT_WEEK = "current_week"
    const val MANAGE_COURSE = "manage_course"
    const val NODES = "nodes"
    const val MAX_WEEK = "max_week"
    const val SUNDAY_FIRST = "sunday_first"
    const val SHOW_SAT = "show_sat"
    const val SHOW_SUN = "show_sun"

    // 课表外观
    const val SHOW_TIME_IN_CELL = "show_time_in_cell"
    const val TABLE_BACKGROUND = "table_background"
    const val UI_TEXT_COLOR = "ui_text_color"
    const val COURSE_TEXT_COLOR = "course_text_color"
    const val STROKE_COLOR = "stroke_color"
    const val ITEM_HEIGHT = "item_height"
    const val ITEM_ALPHA = "item_alpha"
    const val ITEM_TEXT_SIZE = "item_text_size"
    const val SHOW_OTHER_WEEK = "show_other_week"

    // 桌面小部件外观
    const val WIDGET_ITEM_HEIGHT = "widget_item_height"
    const val WIDGET_ITEM_ALPHA = "widget_item_alpha"
    const val WIDGET_ITEM_TEXT_SIZE = "widget_item_text_size"
    const val WIDGET_TITLE_COLOR = "widget_title_color"
    const val WIDGET_COURSE_COLOR = "widget_course_color"
    const val WIDGET_STROKE_COLOR = "widget_stroke_color"

    /** 列表末尾的留白占位行。 */
    const val BOTTOM_SPACER = "bottom_spacer"
}
