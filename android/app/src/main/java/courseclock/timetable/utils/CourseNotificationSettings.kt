package courseclock.timetable.utils

import android.content.Context

/** 用户意愿与系统权限分开保存；状态显示不依赖上课/下课提醒开关。 */
object CourseNotificationSettings {
    const val KEY_STATUS_MODE = "course_status_mode"
    private const val KEY_INITIALIZED = "course_notification_settings_v2"

    enum class StatusMode(val label: String) {
        OFF("关闭"), AUTO("自动（Android 实时通知）"), SHADE("仅通知栏")
    }

    fun initialize(context: Context) {
        val preferences = context.getPrefer()
        if (preferences.getBoolean(KEY_INITIALIZED, false)) return
        val existing = preferences.contains(Const.KEY_COURSE_REMIND) ||
                preferences.contains(CourseReminderScheduler.KEY_REMINDER_END_ENABLED)
        val editor = preferences.edit()
        // 旧版通知没有持久投递记录，升级时只清理旧版本专属编号，不影响其它通知。
        if (existing) {
            val manager = androidx.core.app.NotificationManagerCompat.from(context)
            for (id in 0x5700..0x58ff) manager.cancel(id)
        }
        if (!preferences.contains(KEY_STATUS_MODE)) {
            editor.putString(KEY_STATUS_MODE, if (preferences.getBoolean(Const.KEY_REMINDER_ON_GOING, false))
                StatusMode.SHADE.name else StatusMode.OFF.name)
        }
        if (!preferences.contains(CourseReminderScheduler.KEY_REMINDER_END_ENABLED)) {
            editor.putBoolean(CourseReminderScheduler.KEY_REMINDER_END_ENABLED, existing)
        }
        editor.putBoolean(KEY_INITIALIZED, true).apply()
    }

    fun mode(context: Context): StatusMode {
        initialize(context)
        return StatusMode.valueOf(context.getPrefer().getString(KEY_STATUS_MODE, StatusMode.OFF.name)!!)
    }

    fun enabled(context: Context) = context.getPrefer().getBoolean(Const.KEY_COURSE_REMIND, false)

    fun kindEnabled(context: Context, kind: CourseReminderScheduler.ReminderKind): Boolean {
        initialize(context)
        val key = when (kind) {
            CourseReminderScheduler.ReminderKind.START -> CourseReminderScheduler.KEY_REMINDER_START_ENABLED
            CourseReminderScheduler.ReminderKind.END -> CourseReminderScheduler.KEY_REMINDER_END_ENABLED
        }
        return enabled(context) && context.getPrefer().getBoolean(key,
                kind == CourseReminderScheduler.ReminderKind.START)
    }
}
