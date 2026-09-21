package courseclock.timetable.utils

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/** Android 16 原生实时通知；低版本保留同一条标准状态通知，不使用厂商协议。 */
object AndroidLiveNotification {
    enum class Availability(val label: String) {
        AVAILABLE("允许请求实时通知，实际展示由系统决定"),
        BLOCKED("实时通知权限关闭，使用通知栏"),
        UNSUPPORTED("需要 Android 16 或更高版本，使用通知栏"),
        UNKNOWN("实时通知权限查询失败，使用通知栏")
    }

    fun availability(context: Context): Availability {
        if (Build.VERSION.SDK_INT < 36) return Availability.UNSUPPORTED
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.canPostPromotedNotifications()) Availability.AVAILABLE else Availability.BLOCKED
        } catch (e: RuntimeException) {
            Log.w("CourseLiveNotification", "原生实时通知权限查询失败，保留标准通知", e)
            Availability.UNKNOWN
        }
    }

    fun promote(context: Context, notification: Notification,
                state: CourseReminderScheduler.OngoingState, now: Long): Notification {
        // 未来课程属于日程提醒；只有正在进行且用户开启自动模式的课程请求提升。
        if (Build.VERSION.SDK_INT < 36 || !state.inClass ||
                CourseNotificationSettings.mode(context) != CourseNotificationSettings.StatusMode.AUTO ||
                availability(context) != Availability.AVAILABLE) return notification
        notification.extras.putBoolean(REQUEST_PROMOTED_ONGOING, true)
        val builder = Notification.Builder.recoverBuilder(context, notification)
        if (CourseClock.simulated) {
            val minutes = CourseReminderScheduler.remainingMinutes(state.transitionAt, now)
            builder.setShortCriticalText(if (minutes in 1..99) "${minutes}分钟" else "上课中")
        }
        return builder.build()
    }

    // Android 官方 EXTRA_REQUEST_PROMOTED_ONGOING 的公开协议键；Java 常量在 SDK 36.1 才导出。
    // compileSdk 36 使用官方文档支持的 extras 入口，不反射更高版本方法或引入厂商字段。
    private const val REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
}
