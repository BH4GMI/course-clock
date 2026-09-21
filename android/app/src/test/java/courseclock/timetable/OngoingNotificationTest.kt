package courseclock.timetable

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseReminderNotifier
import courseclock.timetable.utils.CourseNotificationSettings
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.getPrefer
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 常驻状态通知的端到端自检：真的写库、真的调刷新、真的去通知栏里看那条在不在。
 *
 * ## 为什么非要 Robolectric
 *
 * `ongoingStateOf` 的取值已经由纯函数测试逐格钉住了，但"读库 → 判状态 → 投到正确渠道 /
 * 该撤就撤"这一段只有真 `Context` 和真 `NotificationManager` 才走得到。通知代码最典型的
 * 缺陷恰恰是**逻辑对、投递错**：渠道 id 写错在 API 26+ 会被系统静默丢弃（只打一条 log），
 * 纯函数测试永远碰不到。这里把渠道、通知 id、常驻标志、撤销路径一并钉住。
 *
 * ## 时刻不靠操纵系统时钟
 *
 * 播种一门**正好跨过当前时刻**的课（10 分钟前开始、15 分钟后下课），于是取值是确定的：
 * 距下课恒为 15 分钟（向上取整对 14 分 01 秒~15 分整都给出 15）。这样就不必去晃系统时钟，
 * 也就不受 Robolectric 时钟语义变化的影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 29], application = android.app.Application::class)
class OngoingNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 通知栏里那条常驻通知；没有则为 null。 */
    private val posted: Notification?
        get() = shadowOf(notificationManager)
                .getNotification(CourseReminderNotifier.ONGOING_NOTIFICATION_ID)

    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    private fun minuteOffset(from: Long, minutes: Int): String =
            clock.format(Date(from + minutes * 60_000L))

    /** 播一门"现在正在上"的课，并把两个开关都打开。 */
    @Before
    fun seedCurrentCourse() {
        if (android.os.Build.VERSION.SDK_INT >= 26) notificationManager.createNotificationChannel(android.app.NotificationChannel(
                CourseReminderNotifier.ONGOING_CHANNEL_ID, "课程状态", NotificationManager.IMPORTANCE_LOW))
        runBlocking {
            seed()
        }
    }

    private suspend fun seed() {
        // `AppDatabase` 是静态单例，而 Robolectric 的沙箱按 SDK 复用、数据目录每个用例都是新的：
        // 先关掉单例再删库，让 `getDatabase` 重新走一遍建库（与 `WidgetPreviewImageTest` 同做法）。
        val existing = AppDatabase.getDatabase(context)
        if (existing.isOpen) existing.close()
        context.getDatabasePath("wakeup").delete()
        val database = AppDatabase.getDatabase(context)

        val now = System.currentTimeMillis()
        database.tableDao().clearAllTables()
        database.timeTableDao().clearAllTimeTables()
        database.timeTableDao().insertTimeTable(TimeTableBean(id = 1, name = "示例作息"))
        database.timeDetailDao().insertTimeList(listOf(
                TimeDetailBean(node = 1, startTime = minuteOffset(now, -10),
                        endTime = minuteOffset(now, 15), timeTable = 1)))
        database.tableDao().insertTable(TableBean(
                id = 1, tableName = "我的课表", nodes = 10, timeTable = 1,
                startDate = java.time.Instant.ofEpochMilli(now - 10 * 60_000L).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                maxWeek = 20, type = 1, widgetItemTextSize = 12))
        // 课的星期必须取自 `getWeekdayIntAt` —— `dayOf` 用的就是它，自己另算一套编码必然对不上。
        database.courseDao().insertCourses(
                listOf(CourseBaseBean(id = 1, courseName = "高等数学", color = "#2979ff", tableId = 1)),
                listOf(CourseDetailBean(id = 1, day = CourseUtils.getWeekdayIntAt(now - 10 * 60_000L), room = "B210",
                        teacher = "王老师", startNode = 1, step = 1, startWeek = 1, endWeek = 20,
                        type = 0, tableId = 1, timeGroup = "")))

        context.getPrefer().edit()
                .putBoolean(Const.KEY_COURSE_REMIND, true)
                .putString(CourseNotificationSettings.KEY_STATUS_MODE, "SHADE")
                .commit()
        assertNotNull("课程应能从数据库映射为当前状态，now=$now; " +
                courseclock.timetable.utils.CourseReminderScheduler.nearbyOccurrences(context, now),
                courseclock.timetable.utils.CourseReminderScheduler.currentOngoingState(context))
    }

    @Test
    fun systemTimeoutDoesNotHideTheCourseButUserDismissalDoes() {
        val scheduler = courseclock.timetable.utils.CourseReminderScheduler
        val keys = scheduler.currentOngoingState(context)!!.entries.map { it.key }.toSet()
        val now = System.currentTimeMillis()
        scheduler.dismissStatus(context, keys, now, now)
        assertNotNull("系统超时不能被解释为用户隐藏", posted)
        scheduler.dismissStatus(context, keys, now + 60_000L, now)
        CourseReminderNotifier.refreshOngoing(context)
        val next = scheduler.currentOngoingState(context)!!
        assertTrue("用户隐藏后，同一次课程不能复活", next.entries.none { it.key in keys })
        assertTrue("周课的下一次实例仍然常驻", next.transitionAt > now + 24 * 60 * 60_000L)
        assertTrue(posted!!.extras.getString(Notification.EXTRA_TEXT)!!.contains("距离上课"))
    }

    @Test
    fun showsTheClassInProgressOnTheSilentChannel() {
        CourseReminderNotifier.refreshOngoing(context)

        val notification = posted
        assertNotNull("正在上课时应当有一条常驻通知", notification)
        assertEquals("高等数学", notification!!.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("B210"))
        if (android.os.Build.VERSION.SDK_INT >= 24) assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        if (android.os.Build.VERSION.SDK_INT >= 26) assertEquals("状态更新必须走静默渠道",
                CourseReminderNotifier.ONGOING_CHANNEL_ID, notification.channelId)
        assertTrue("状态通知携带 ongoing 标志；Android 14 起仍允许用户主动划走",
                notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun doesNotAlertAgainOnEveryMinuteUpdate() {
        CourseReminderNotifier.refreshOngoing(context)

        val notification = posted
        assertNotNull(notification)
        assertTrue("第二道防线：即便用户把渠道重要级调高，分钟级重发也不能再响一次",
                notification!!.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test fun futureDatabaseCoursePersistsAndUsesNonWakeupDisplayButWakeupBoundary() {
        val now = System.currentTimeMillis()
        val course = CourseReminderScheduler.currentOngoingState(context, now)!!.entries.single()
        val before = course.startAt - 119 * 60_000L
        CourseReminderNotifier.refreshOngoing(context, before)
        assertTrue(posted!!.extras.getString(Notification.EXTRA_TEXT)!!.contains("距离上课 1 小时 59 分钟"))
        val nextWeek = CourseReminderScheduler.currentOngoingState(context, course.endAt)!!
        assertTrue(nextWeek.transitionAt > course.endAt + 24 * 60 * 60_000L)

        CourseReminderScheduler.hideStatus(context, setOf(course.key))
        val manager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val alarms = shadowOf(manager).scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.action == CourseReminderScheduler.ACTION_REFRESH_STATUS
        }
        assertEquals(setOf(android.app.AlarmManager.RTC, android.app.AlarmManager.RTC_WAKEUP), alarms.map { it.type }.toSet())
        assertEquals("隐藏本次后仍保留当前课程结束边界，用于清理本次隐藏记录", course.endAt,
                alarms.single { it.type == android.app.AlarmManager.RTC_WAKEUP }.triggerAtTime)
    }

    @Test fun smallWidgetAloneStillRegistersTheCountdownChain() {
        val widgetManager = android.appwidget.AppWidgetManager.getInstance(context)
        shadowOf(widgetManager).bindAppWidgetId(42,
                android.content.ComponentName(context, courseclock.timetable.today_appwidget.SmallTodayCourseAppWidget::class.java))
        CourseReminderScheduler.armNextCountdownRefresh(context)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val alarms = shadowOf(manager).scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.action == CourseReminderScheduler.ACTION_REFRESH_COUNTDOWN
        }
        assertEquals(1, alarms.size)
        assertEquals(android.app.AlarmManager.RTC, alarms.single().type)
    }

    @Test
    fun disappearsWhenTheOptionIsSwitchedOff() {
        CourseReminderNotifier.refreshOngoing(context)
        assertNotNull("前提：开着的时候确实有", posted)

        context.getPrefer().edit().putString(CourseNotificationSettings.KEY_STATUS_MODE, "OFF").commit()
        CourseReminderNotifier.refreshOngoing(context)

        assertNull("关掉选项后必须撤掉，否则用户会以为没关干净", posted)
    }

    @Test
    fun disappearsWhenTheMasterSwitchIsOffEvenIfTheOptionStaysOn() {
        context.getPrefer().edit().putBoolean(Const.KEY_COURSE_REMIND, false).commit()
        CourseReminderNotifier.refreshOngoing(context)

        assertNull("总闸关着就不该有这条通知", posted)
    }

    @Test
    fun disappearsWhenThereIsNoScheduleAtAll() {
        // 把默认课表删掉：读不出任何课程时通知栏必须是干净的，而不是留一条常驻残留。
        runBlocking {
            AppDatabase.getDatabase(context).tableDao().clearAllTables()
        }

        CourseReminderNotifier.refreshOngoing(context)

        assertNull("没有课表时不该留一条常驻残留", posted)
    }
}
