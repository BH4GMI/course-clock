package courseclock.timetable.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** 课程领域时间与闹钟使用同一时间源，进程保活、性能计时仍使用系统单调时钟。 */
abstract class CourseTime {
    abstract fun nowMillis(): Long
    open val simulated: Boolean = false
    open val controlsAvailable: Boolean = false
    open val revision: Long = 0L
    open fun initialize(context: Context) = Unit
    open fun openControls(context: Context) = Unit

    open fun timeoutUntil(target: Long): Long = (target - nowMillis()).coerceAtLeast(1)

    open fun schedule(manager: AlarmManager, target: Long, pending: PendingIntent, idle: Boolean = true) {
        if (idle && Build.VERSION.SDK_INT >= 23) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
        } else manager.setExact(if (idle) AlarmManager.RTC_WAKEUP else AlarmManager.RTC, target, pending)
    }

    open fun cancel(manager: AlarmManager, pending: PendingIntent) = manager.cancel(pending)

    fun stamp(intent: Intent): Intent = intent.putExtra(EXTRA_REVISION, revision)

    fun accepts(intent: Intent): Boolean = !intent.hasExtra(EXTRA_REVISION) ||
            intent.getLongExtra(EXTRA_REVISION, 0) == revision

    companion object {
        private const val EXTRA_REVISION = "course_clock_revision"
    }
}
