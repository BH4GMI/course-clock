package courseclock.timetable.utils

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences

fun Context.getPrefer(name: String = "config"): SharedPreferences = getSharedPreferences(name, MODE_PRIVATE)

object Const {

    const val REQUEST_CODE_EXPORT = 100
    const val REQUEST_CODE_IMPORT = 101
    const val REQUEST_CODE_SCHEDULE_SETTING = 102
    const val REQUEST_CODE_EXPORT_ICS = 103
    const val REQUEST_CODE_IMPORT_FILE = 104
    const val REQUEST_CODE_ADD_COURSE = 108

    const val KEY_HAS_ADJUST = "has_adjust"

    const val KEY_DAY_NIGHT_THEME = "day_night_theme"
    const val KEY_HIDE_NAV_BAR = "hide_main_nav_bar"
    const val KEY_COURSE_REMIND = "course_reminder"
    const val KEY_REMINDER_ON_GOING = "reminder_on_going"
    const val KEY_DAY_WIDGET_COLOR = "s_colorful_day_widget"
    const val KEY_SHOW_EMPTY_VIEW = "show_empty_view"
    const val KEY_THEME_COLOR = "nav_bar_color"
    const val KEY_HAS_INTRO = "has_intro"
    const val KEY_SCHEDULE_PRE_LOAD = "schedule_pre_load"
    const val KEY_SCHEDULE_BLANK_AREA = "schedule_blank_area"
    const val KEY_SCHEDULE_DETAIL_TIME = "schedule_detail_time"
    const val KEY_SCHEDULE_GRID = "schedule_grid"

    /**
     * 左侧时间栏用哪一套作息（见 [courseclock.timetable.utils.CourseTimes.defaultGroup]）。
     *
     * 取值：空串 = 「自动」，即按本次导入里安排条数最多的那套；否则是 "A"/"B"/"C"。
     */
    const val KEY_TIME_AXIS_SCHEME = "time_axis_scheme"

    /** 旧版结果码记录，仅用于兼容性回归验证，不再参与权限判断。 */
    const val KEY_BATTERY_WHITELIST_CONFIRMED = "battery_whitelist_confirmed"
    const val KEY_HYPEROS_BATTERY_CONFIRMED = "hyperos_battery_confirmed"
    const val KEY_HYPEROS_BATTERY_PENDING = "hyperos_battery_pending"

    /** 导入结果里"教务系统还没排课、因此没进课表"的课程名（StringArrayList）。 */
    const val EXTRA_UNSCHEDULED_COURSES = "extra_unscheduled_courses"

}
