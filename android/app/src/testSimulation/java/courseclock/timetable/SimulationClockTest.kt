package courseclock.timetable

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.utils.CourseClock
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.pendingIntentFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.text.SimpleDateFormat
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SimulationClockTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val instant = 1_800_000_000_000L
    @Before fun reset() {
        app.getSharedPreferences("time_lab", Context.MODE_PRIVATE).edit().clear().commit()
        CourseClock.initialize(app)
    }

    @Test fun seekChangesDomainDateWithoutChangingSystemTime() = runBlocking {
        CourseClock.seek(instant)!!.join()
        assertEquals(instant, CourseClock.nowMillis())
        assertEquals(SimpleDateFormat("M月d日").format(Date(instant)), CourseUtils.getTodayDate())
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - instant) > 60_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(instant, CourseClock.nowMillis())
    }

    @Test fun restoresPausedRatherThanCatchingUpAcrossProcessDeath() {
        app.getSharedPreferences("time_lab", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", true).putLong("instant", instant).putFloat("speed", 60f).commit()
        CourseClock.initialize(app)
        assertEquals(instant, CourseClock.nowMillis())
        assertFalse(CourseClock.playing)
        assertEquals(0L, CourseClock.timeoutUntil(instant + 60000))
    }

    @Test fun accelerationAndPauseDriveTheSamePendingIntentClock() = runBlocking {
        CourseClock.seek(instant)!!.join()
        CourseClock.setSpeed(60.0)!!.join()
        CourseClock.playPause()!!.join()
        val start = CourseClock.nowMillis()
        val intent = Intent("test.clock.boundary").setPackage(app.packageName)
        val pending = PendingIntent.getBroadcast(app, 991, intent, pendingIntentFlags())
        CourseClock.schedule(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager, start + 120_000, pending)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(shadowOf(app).broadcastIntents.any { it.action == intent.action })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(shadowOf(app).broadcastIntents.any { it.action == intent.action })
        CourseClock.playPause()!!.join()
        val paused = CourseClock.nowMillis()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(paused, CourseClock.nowMillis())
    }

    @Test fun seekingInvalidatesOldBroadcastSessionAndRealModeRestoresClock() = runBlocking {
        CourseClock.seek(instant)!!.join()
        val old = CourseClock.stamp(Intent("old"))
        CourseClock.seek(instant - 60_000)!!.join()
        assertFalse(CourseClock.accepts(old))
        CourseClock.restoreRealTime()!!.join()
        assertFalse(CourseClock.simulated)
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - CourseClock.nowMillis()) < 1000)
    }
}
