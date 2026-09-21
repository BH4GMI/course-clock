package courseclock.timetable

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.utils.AndroidLiveNotification
import courseclock.timetable.utils.CourseNotificationSettings
import courseclock.timetable.utils.CourseReminderScheduler.OngoingState
import courseclock.timetable.utils.CourseReminderScheduler.Occurrence
import courseclock.timetable.utils.CourseReminderScheduler.CourseDetail
import courseclock.timetable.utils.getPrefer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [LiveNotificationManagerShadow::class])
class AndroidLiveNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun state(inClass: Boolean) = OngoingState(listOf(Occurrence("course",
            CourseDetail("高等数学", "B210"), if (inClass) now - 60_000 else now + 60_000, now + 600_000)), inClass)
    private fun notification() = NotificationCompat.Builder(context, "course-status")
            .setSmallIcon(R.drawable.wakeup).setContentTitle("高等数学").setContentText("正在上课")
            .setStyle(NotificationCompat.BigTextStyle().bigText("课程详情"))
            .setOngoing(true).setSilent(true).build()

    @Before fun setup() {
        context.getPrefer().edit().putBoolean("course_notification_settings_v2", true)
                .putString(CourseNotificationSettings.KEY_STATUS_MODE, "AUTO").commit()
        Shadow.extract<LiveNotificationManagerShadow>(manager).allowed = true
    }

    @Test fun activeCourseRequestsNativePromotionWithoutVendorPayload() {
        val result = AndroidLiveNotification.promote(context, notification(), state(true), now)
        assertTrue(result.extras.getBoolean("android.requestPromotedOngoing"))
        assertTrue(result.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("高等数学", result.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(result.extras.keySet().any { it.startsWith("miui.") })
        assertNull(result.contentView)
    }

    @Test fun futureCalendarEventIsNotPromoted() {
        val original = notification()
        assertSame(original, AndroidLiveNotification.promote(context, original, state(false), now))
        assertFalse(original.extras.getBoolean("android.requestPromotedOngoing"))
    }

    @Test fun deniedPermissionAndShadeModeKeepTheStandardNotification() {
        Shadow.extract<LiveNotificationManagerShadow>(manager).allowed = false
        assertEquals(AndroidLiveNotification.Availability.BLOCKED, AndroidLiveNotification.availability(context))
        val original = notification()
        assertSame(original, AndroidLiveNotification.promote(context, original, state(true), now))
        Shadow.extract<LiveNotificationManagerShadow>(manager).allowed = true
        context.getPrefer().edit().putString(CourseNotificationSettings.KEY_STATUS_MODE, "SHADE").commit()
        assertSame(original, AndroidLiveNotification.promote(context, original, state(true), now))
    }
}

@Implements(NotificationManager::class)
class LiveNotificationManagerShadow : ShadowNotificationManager() {
    var allowed = false
    @Implementation(minSdk = 36)
    protected fun canPostPromotedNotifications(): Boolean = allowed
}
