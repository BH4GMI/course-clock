package courseclock.timetable.testing

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import courseclock.timetable.R
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.CourseClock
import courseclock.timetable.utils.CourseReminderNotifier
import courseclock.timetable.utils.appScope
import courseclock.timetable.utils.pendingIntentFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 仅测试包持有此服务；暂停释放 CPU 锁，恢复真实时间停止服务。 */
class TimeLabService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock
    private var refreshing = false
    private var lastMinute: Long? = null
    private val ticker = object : Runnable {
        override fun run() {
            updateWakeLock()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification())
            CourseClock.checkpoint()
            val minute = CourseClock.nowMillis() / 60_000L
            if (CourseClock.simulated && !CourseClock.applying && !refreshing && minute != lastMinute) {
                refreshing = true
                lastMinute = minute
                appScope.launch {
                    try {
                        CourseReminderNotifier.refreshOngoing(applicationContext)
                        AppWidgetUtils.refreshAllWidgets(applicationContext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        CourseClock.failure("测试视图刷新失败，播放已暂停", e)
                    } finally {
                        handler.post { refreshing = false }
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "$packageName:time-lab")
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                    NotificationChannel(CHANNEL, "测试时间控制", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        setSound(null, null)
                    })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification())
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> CourseClock.playPause()
            ACTION_REAL -> CourseClock.restoreRealTime()
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    // 测试播放的显式生命周期持有；暂停、真实时间和 onDestroy 均释放，不依赖超时续租。
    @SuppressLint("WakelockTimeout")
    private fun updateWakeLock() {
        if (CourseClock.playing && !wakeLock.isHeld) wakeLock.acquire()
        if (!CourseClock.playing && wakeLock.isHeld) wakeLock.release()
    }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, TimeLabActivity::class.java), pendingIntentFlags())
        fun action(name: String) = PendingIntent.getService(this, ID,
                Intent(this, TimeLabService::class.java).setAction(name), pendingIntentFlags())
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(CourseClock.nowMillis()))
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.wakeup)
                .setContentTitle("测试时间 · $time")
                .setContentText(if (CourseClock.playing) "${CourseClock.speed} 倍速" else CourseClock.message)
                .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(android.R.drawable.ic_media_pause, if (CourseClock.playing) "暂停" else "继续", action(ACTION_PLAY_PAUSE))
                .addAction(android.R.drawable.ic_menu_revert, "恢复真实时间", action(ACTION_REAL)).build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        CourseClock.pauseOnServiceStop()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "test_time_control"
        private const val ID = 0x6600
        private const val ACTION_PLAY_PAUSE = "time_lab_play_pause"
        private const val ACTION_REAL = "time_lab_real"
    }
}
