package courseclock.timetable.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import courseclock.timetable.testing.Timeline
import courseclock.timetable.testing.TimeLabActivity
import courseclock.timetable.testing.TimeLabService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 此实现只进入 simulation APK。所有时间操作串行完成，重启恢复为暂停。 */
object CourseClock : CourseTime() {
    private val handler = Handler(Looper.getMainLooper())
    private val commands = Mutex()
    private val alarmLock = Any()
    private val deadlines = mutableMapOf<PendingIntent, Long>()
    private lateinit var context: Context
    @Volatile private var timeline = Timeline(0, 0, 0.0, false)
    @Volatile override var revision: Long = 0
        private set
    @Volatile var selectedSpeed = 10.0
        private set
    @Volatile var applying = false
        private set
    @Volatile var message = "真实时间"
        private set
    override val controlsAvailable = true
    override val simulated get() = timeline.enabled
    val playing get() = simulated && timeline.speed > 0
    val speed get() = timeline.speed

    override fun nowMillis() = timeline.now(SystemClock.elapsedRealtime(), System.currentTimeMillis())

    override fun initialize(context: Context) {
        this.context = context.applicationContext
        val prefs = context.getSharedPreferences("time_lab", Context.MODE_PRIVATE)
        selectedSpeed = prefs.getFloat("speed", 10f).toDouble().coerceIn(0.25, 120.0)
        revision = prefs.getLong("revision", 0) + 1
        timeline = Timeline(prefs.getLong("instant", System.currentTimeMillis()), SystemClock.elapsedRealtime(),
                0.0, prefs.getBoolean("enabled", false))
        message = if (simulated) "已恢复测试时间，播放已暂停" else "真实时间"
        checkpoint()
    }

    override fun openControls(context: Context) {
        context.startActivity(Intent(context, TimeLabActivity::class.java))
    }

    override fun timeoutUntil(target: Long): Long = if (!simulated) super.timeoutUntil(target)
        else (timeline.delayUntil(target, SystemClock.elapsedRealtime(), System.currentTimeMillis()) ?: 0)
                .let { if (it == 0L && playing) 1 else it }

    override fun schedule(manager: AlarmManager, target: Long, pending: PendingIntent, idle: Boolean) {
        if (!simulated) {
            super.schedule(manager, target, pending, idle)
            return
        }
        synchronized(alarmLock) {
            deadlines[pending] = target
            arm(pending, target)
        }
    }

    override fun cancel(manager: AlarmManager, pending: PendingIntent) {
        synchronized(alarmLock) {
            deadlines.remove(pending)
            handler.removeCallbacksAndMessages(pending)
        }
        super.cancel(manager, pending)
    }

    private fun arm(pending: PendingIntent, target: Long) {
        handler.removeCallbacksAndMessages(pending)
        if (!playing) return
        val delay = timeline.delayUntil(target, SystemClock.elapsedRealtime(), System.currentTimeMillis()) ?: return
        val session = revision
        handler.postAtTime({
            val deliver = synchronized(alarmLock) {
                if (session != revision || !playing || deadlines[pending] != target) false
                else if (nowMillis() < target) { arm(pending, target); false }
                else { deadlines.remove(pending); true }
            }
            if (deliver) try {
                pending.send()
            } catch (e: PendingIntent.CanceledException) {
                failure("测试提醒已被取消，请重新定位时间", e)
            }
        }, pending, SystemClock.uptimeMillis() + delay)
    }

    fun seek(at: Long) = change(at = at, play = false)
    fun setSpeed(value: Double): Job? {
        require(value.isFinite() && value in 0.25..120.0)
        return change(newSpeed = value, play = playing)
    }
    fun playPause() = change(play = !playing)
    fun restoreRealTime() = change(real = true, play = false)

    private fun change(at: Long? = null, newSpeed: Double? = null, real: Boolean = false, play: Boolean): Job? {
        if (applying) return null
        applying = true
        message = "正在应用时间"
        if (!real) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TimeLabService::class.java))
            } catch (e: RuntimeException) {
                applying = false
                failure("无法启动测试计时，请回到时间实验室重试", e)
                return null
            }
        }
        return appScope.launch {
            commands.withLock {
                try {
                    val entering = !simulated && !real
                    val instant = at ?: nowMillis()
                    if (newSpeed != null) selectedSpeed = newSpeed
                    CourseReminderScheduler.changeTimeSource(context, at != null || real || entering) {
                        synchronized(alarmLock) {
                            deadlines.keys.forEach { handler.removeCallbacksAndMessages(it) }
                            deadlines.clear()
                            if (at != null || real || entering) revision++
                            timeline = Timeline(instant, SystemClock.elapsedRealtime(), 0.0, !real)
                        }
                    }
                    synchronized(alarmLock) {
                        timeline = Timeline(instant, SystemClock.elapsedRealtime(), if (play && !real) selectedSpeed else 0.0, !real)
                        deadlines.forEach { (pending, target) -> arm(pending, target) }
                    }
                    CourseReminderNotifier.reconcileReminders(context, forceRefresh = true)
                    CourseReminderNotifier.refreshOngoing(context)
                    AppWidgetUtils.refreshAllWidgets(context)
                    checkpoint()
                    message = if (real) "真实时间" else if (play) "播放中" else "已暂停"
                    if (real) context.stopService(Intent(context, TimeLabService::class.java))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure("时间应用失败，播放已暂停；请重新定位或恢复真实时间", e)
                } finally {
                    applying = false
                }
            }
        }
    }

    fun pauseOnServiceStop() {
        if (playing) {
            synchronized(alarmLock) {
                timeline = Timeline(nowMillis(), SystemClock.elapsedRealtime(), 0.0, true)
                deadlines.keys.forEach { handler.removeCallbacksAndMessages(it) }
            }
            message = "测试计时已停止，时间已暂停"
            checkpoint()
        }
    }

    fun checkpoint() {
        if (!::context.isInitialized) return
        context.getSharedPreferences("time_lab", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", simulated).putLong("instant", nowMillis())
                .putFloat("speed", selectedSpeed.toFloat()).putLong("revision", revision).apply()
    }

    fun failure(text: String, cause: Exception) {
        Log.e("TimeLab", text, cause)
        pauseOnServiceStop()
        message = text
        handler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
