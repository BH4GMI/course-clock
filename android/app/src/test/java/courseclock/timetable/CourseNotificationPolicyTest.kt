package courseclock.timetable

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.settings.SettingsList
import courseclock.timetable.settings.SettingRowId
import courseclock.timetable.settings.items.HorizontalItem
import courseclock.timetable.settings.items.SeekBarItem
import courseclock.timetable.utils.*
import courseclock.timetable.utils.CourseNotificationSettings.StatusMode
import courseclock.timetable.utils.CourseReminderScheduler.Occurrence
import courseclock.timetable.utils.CourseReminderScheduler.CourseDetail
import courseclock.timetable.utils.CourseReminderScheduler.ReminderKind
import courseclock.timetable.utils.CourseReminderScheduler.ReminderEntry
import courseclock.timetable.utils.CourseReminderScheduler.ReminderPayload
import courseclock.timetable.utils.CourseReminderScheduler.OngoingState
import courseclock.timetable.utils.CourseReminderScheduler.KEY_REMINDER_START_ENABLED
import courseclock.timetable.utils.CourseReminderScheduler.KEY_REMINDER_END_ENABLED
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class CourseNotificationPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val preferences get() = context.getPrefer()
    private val now = 1_800_000_000_000L
    private fun occurrence(key: String = "A", start: Long = now - 600_000L, end: Long = now + 600_000L,
                           room: String = "教学楼 B210") = Occurrence(key,
            CourseDetail("高等数学与工程应用（实验）", room), start, end)

    @Before
    fun reset() {
        preferences.edit().clear().commit()
        context.getSharedPreferences("active_course_notifications", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
        manager.createNotificationChannel(NotificationChannel(CourseReminderNotifier.CHANNEL_ID,
                "课程提醒", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CourseReminderNotifier.ONGOING_CHANNEL_ID,
                "课程状态", NotificationManager.IMPORTANCE_LOW))
    }

    @Test fun newInstallIsOptInAndEndIsOff() {
        CourseNotificationSettings.initialize(context)
        assertFalse(CourseNotificationSettings.enabled(context))
        assertEquals(StatusMode.OFF, CourseNotificationSettings.mode(context))
        preferences.edit().putBoolean(Const.KEY_COURSE_REMIND, true).commit()
        assertTrue(CourseNotificationSettings.kindEnabled(context, ReminderKind.START))
        assertFalse(CourseNotificationSettings.kindEnabled(context, ReminderKind.END))
    }

    @Test fun migrationPreservesLegacyStatusAndImplicitEndPreference() {
        preferences.edit().putBoolean(Const.KEY_COURSE_REMIND, true)
                .putBoolean(Const.KEY_REMINDER_ON_GOING, true).commit()
        CourseNotificationSettings.initialize(context)
        assertEquals(StatusMode.SHADE, CourseNotificationSettings.mode(context))
        assertTrue(CourseNotificationSettings.kindEnabled(context, ReminderKind.END))
    }

    @Test fun migrationDoesNotOverrideExplicitEndOffOrExplicitMode() {
        preferences.edit().putBoolean(Const.KEY_COURSE_REMIND, true)
                .putBoolean(KEY_REMINDER_END_ENABLED, false)
                .putString(CourseNotificationSettings.KEY_STATUS_MODE, "AUTO").commit()
        CourseNotificationSettings.initialize(context)
        CourseNotificationSettings.initialize(context)
        assertFalse(CourseNotificationSettings.kindEnabled(context, ReminderKind.END))
        assertEquals(StatusMode.AUTO, CourseNotificationSettings.mode(context))
    }

    @Test fun stateDoesNotDependOnEventSwitches() {
        CourseNotificationSettings.initialize(context)
        preferences.edit().putBoolean(Const.KEY_COURSE_REMIND, true)
                .putBoolean(KEY_REMINDER_START_ENABLED, false).putBoolean(KEY_REMINDER_END_ENABLED, false)
                .putString(CourseNotificationSettings.KEY_STATUS_MODE, "AUTO").commit()
        assertTrue(CourseReminderScheduler.ongoingEnabled(context))
    }

    @Test fun disabledStateDoesNotDisableReminders() {
        CourseNotificationSettings.initialize(context)
        preferences.edit().putBoolean(Const.KEY_COURSE_REMIND, true).commit()
        assertFalse(CourseReminderScheduler.ongoingEnabled(context))
        assertTrue(CourseNotificationSettings.kindEnabled(context, ReminderKind.START))
    }

    @Test fun timeControlsFollowTheirOwnSwitchAndMaster() {
        CourseNotificationSettings.initialize(context)
        preferences.edit().putBoolean(Const.KEY_COURSE_REMIND, true).commit()
        val settings = SettingsList(context)
        val rows = settings.build()
        assertTrue(rows.filterIsInstance<SeekBarItem>().single { it.id == SettingRowId.REMINDER_BEFORE_START }.isRowEnabled)
        assertFalse(rows.filterIsInstance<SeekBarItem>().single { it.id == SettingRowId.REMINDER_BEFORE_END }.isRowEnabled)
        preferences.edit().putBoolean(Const.KEY_COURSE_REMIND, false).commit()
        settings.refreshAvailability()
        assertTrue(rows.filterIsInstance<SeekBarItem>().all { !it.isRowEnabled })
        assertTrue(rows.filterIsInstance<HorizontalItem>().single { it.id == SettingRowId.NOTIFICATION_HEALTH }.isRowEnabled)
        assertEquals(3, StatusMode.values().size)
    }

    @Test fun statePersistsAcrossLongGapsUntilTheNextClass() {
        for (minutes in listOf(60, 61, 99, 100, 2_880, 10_080)) {
            val course = occurrence(start = now + minutes * 60_000L, end = now + (minutes + 80) * 60_000L)
            assertFalse(CourseReminderScheduler.stateOf(listOf(course), now)!!.inClass)
            assertEquals(course.startAt, CourseReminderScheduler.stateOf(listOf(course), now)!!.transitionAt)
        }
    }

    @Test fun startBoundaryBecomesInClassAndEndBoundaryDisappears() {
        val course = occurrence()
        assertTrue(CourseReminderScheduler.stateOf(listOf(course), course.startAt)!!.inClass)
        assertNull(CourseReminderScheduler.stateOf(listOf(course), course.endAt))
    }

    @Test fun sameNameDifferentRoomsRemainDistinctConflicts() {
        val courses = listOf(occurrence("A", room = "B210"), occurrence("B", room = "C305"))
        val state = CourseReminderScheduler.stateOf(courses, now)!!
        assertEquals(2, state.entries.size)
        assertEquals("2 门课程同时进行", state.title)
        assertEquals(setOf("B210", "C305"), state.entries.map { it.course.room }.toSet())
    }

    @Test fun hiddenCourseDoesNotComeBackAndNextOccurrenceIsVisible() {
        val a = occurrence("A")
        val b = occurrence("B", start = a.endAt, end = a.endAt + 600_000L)
        assertNull(CourseReminderScheduler.stateOf(listOf(a), now, setOf("A")))
        assertEquals("B", CourseReminderScheduler.stateOf(listOf(a, b), b.startAt, setOf("A"))!!.entries.single().key)
    }

    @Test fun hiddenConflictDoesNotHideTheOtherCourse() {
        assertEquals("B", CourseReminderScheduler.stateOf(listOf(occurrence("A"), occurrence("B")),
                now, setOf("A"))!!.entries.single().key)
    }

    @Test fun courseBoundariesAndDisplayMinutesAreScheduledSeparately() {
        val a = occurrence(start = now + 1_800_000L, end = now + 5_400_000L)
        assertEquals(listOf(a.startAt, a.endAt),
                CourseReminderScheduler.statusBoundaries(listOf(a, a), now))
        assertEquals(now + 60_000L, CourseReminderScheduler.statusDisplayAt(OngoingState(listOf(a), false), now))
        assertEquals(now + 60_000L, CourseReminderScheduler.statusDisplayAt(OngoingState(listOf(a), false), now + 1))
        assertNull(CourseReminderScheduler.statusDisplayAt(OngoingState(listOf(a), false), a.startAt - 1))
        assertNull(CourseReminderScheduler.statusDisplayAt(OngoingState(listOf(a), true), a.startAt))
    }

    @Test fun notificationAlwaysUsesHoursAndMinutes() {
        for ((minutes, text) in listOf(1L to "距离上课 0 小时 1 分钟", 59L to "距离上课 0 小时 59 分钟",
                60L to "距离上课 1 小时 0 分钟", 119L to "距离上课 1 小时 59 分钟",
                2_880L to "距离上课 48 小时 0 分钟")) {
            assertEquals(text, CourseReminderScheduler.notificationCountdownText(minutes))
        }
    }

    @Test fun mixedStateUsesEarliestEndBoundary() {
        val a = occurrence("A", end = now + 200_000L)
        val b = occurrence("B", end = now + 600_000L)
        assertEquals(a.endAt, CourseReminderScheduler.stateOf(listOf(a, b), now)!!.transitionAt)
    }

    @Test fun parcelContractPreservesEveryMergedEntry() {
        val entries = listOf(ReminderEntry(ReminderKind.START, now, occurrence("A")),
                ReminderEntry(ReminderKind.END, now, occurrence("B")))
        val payload = ReminderPayload(0x5700, entries)
        assertEquals(payload, CourseReminderScheduler.payloadOf(payload.toIntent(context)))
    }

    @Test fun emptyOldBroadcastCannotInventCourseData() {
        assertTrue(CourseReminderScheduler.payloadOf(Intent()).entries.isEmpty())
    }

    @Test fun olderAndroidKeepsTheStandardNotificationWithoutVendorExtras() {
        assertEquals(AndroidLiveNotification.Availability.UNSUPPORTED, AndroidLiveNotification.availability(context))
        preferences.edit().putString(CourseNotificationSettings.KEY_STATUS_MODE, "AUTO").commit()
        val original = androidx.core.app.NotificationCompat.Builder(context, "test")
                .setSmallIcon(R.drawable.wakeup).setContentTitle("课程").setOngoing(true).build()
        val result = AndroidLiveNotification.promote(context, original, OngoingState(listOf(occurrence()), true), now)
        assertSame(original, result)
        assertFalse(result.extras.keySet().any { it.startsWith("miui.") })
        assertFalse(result.extras.getBoolean("android.requestPromotedOngoing"))
    }

    @Test fun eventKeepsAbsoluteTimeRoomAndFullExpandedName() {
        val entry = ReminderEntry(ReminderKind.START, now, occurrence(start = now + 1_200_000L, end = now + 4_000_000L))
        CourseReminderNotifier.remind(context, listOf(entry), now)
        val posted = manager.activeNotifications.single().notification
        assertEquals(entry.occurrence.course.name, posted.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(posted.extras.getString(Notification.EXTRA_TEXT)!!.contains("B210"))
        assertTrue(posted.extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains(entry.occurrence.timeRange()))
        assertFalse(posted.extras.getString(Notification.EXTRA_TEXT)!!.contains("还有"))
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertFalse(posted.publicVersion.extras.getString(Notification.EXTRA_TEXT)!!.contains("B210"))
    }

    @Test fun repeatedDeliveryCannotAlertAgainAfterDismissal() {
        val entry = ReminderEntry(ReminderKind.START, now, occurrence(start = now + 1_200_000L, end = now + 4_000_000L))
        CourseReminderNotifier.remind(context, listOf(entry), now)
        val tag = manager.activeNotifications.single().tag
        CourseReminderNotifier.cancel(context, tag)
        CourseReminderNotifier.remind(context, listOf(entry), now + 1)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun midnightCannotReplayAnAlreadyDismissedEvent() {
        val midnight = CourseReminderScheduler.startOfDayAfter(now, 1)
        val entry = ReminderEntry(ReminderKind.START, midnight - 60_000L,
                occurrence(start = midnight + 1_200_000L, end = midnight + 4_000_000L))
        CourseReminderNotifier.remind(context, listOf(entry), midnight - 30_000L)
        CourseReminderNotifier.cancel(context, manager.activeNotifications.single().tag)
        CourseReminderNotifier.remind(context, listOf(entry), midnight + 30_000L)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun overnightCourseUsesNextDaysEndForBothStateAndAlarm() {
        val startOfDay = CourseReminderScheduler.startOfDayMillis(now)
        val course = courseclock.timetable.bean.CourseBean(id = 1, tableId = 1,
                courseName = "夜间实验", startNode = 1, step = 1, day = 1, room = "B210",
                teacher = "测试", startWeek = 1, endWeek = 20, type = 0, color = "#2979ff")
        val times = CourseTimes(listOf(courseclock.timetable.bean.TimeDetailBean(1, "23:50", "00:30", 1)))
        val occurrence = CourseReminderScheduler.occurrenceOf(course, times, startOfDay)!!
        assertEquals(40 * 60_000L, occurrence.endAt - occurrence.startAt)
        val afterMidnight = CourseReminderScheduler.startOfDayAfter(startOfDay, 1) + 10 * 60_000L
        assertTrue(CourseReminderScheduler.stateOf(listOf(occurrence), afterMidnight)!!.inClass)
        val alarms = CourseReminderScheduler.alarmsForDay(listOf(course),
                CourseReminderScheduler.dayOf("2026-01-01", false, startOfDay), times, startOfDay,
                afterMidnight, 20, 0)
        assertEquals(listOf(ReminderKind.END), alarms.map { it.kind })
        assertEquals(occurrence.endAt, alarms.single().triggerAt)
        assertEquals(occurrence, alarms.single().occurrence)
    }

    @Test fun latePreClassReminderIsNotReplayed() {
        val course = occurrence(start = now, end = now + 600_000L)
        CourseReminderNotifier.remind(context, listOf(ReminderEntry(ReminderKind.START, now - 1_200_000L, course)), now)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun onTimeStartAndEndHaveFiniteLifetimes() {
        val course = occurrence(start = now, end = now + 600_000L)
        assertEquals(now + 300_000L, ReminderEntry(ReminderKind.START, now, course).expiresAt)
        assertEquals(course.endAt + 300_000L, ReminderEntry(ReminderKind.END, course.endAt, course).expiresAt)
    }

    @Test fun mergedNotificationKeepsAllEntries() {
        val a = occurrence("A", start = now + 1_200_000L, end = now + 4_000_000L)
        val b = occurrence("B", start = now + 1_200_000L, end = now + 4_000_000L, room = "C305")
        CourseReminderNotifier.remind(context, listOf(ReminderEntry(ReminderKind.START, now, a),
                ReminderEntry(ReminderKind.START, now, b)), now)
        val notification = manager.activeNotifications.single().notification
        assertEquals("2 项课程提醒", notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains("C305"))
        assertTrue(notification.extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains("B210"))
    }

    @Test fun masterOffClearsOwnedEventsAndExpiryAlarms() {
        val entry = ReminderEntry(ReminderKind.START, now, occurrence(start = now + 1_200_000L, end = now + 4_000_000L))
        CourseReminderNotifier.remind(context, listOf(entry), now)
        assertEquals(1, manager.activeNotifications.size)
        CourseReminderNotifier.reconcileReminders(context)
        assertTrue(manager.activeNotifications.isEmpty())
        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
        assertTrue(alarms.none { shadowOf(it.operation).savedIntent.action == CourseReminderNotifier.ACTION_EXPIRE })
    }
}
