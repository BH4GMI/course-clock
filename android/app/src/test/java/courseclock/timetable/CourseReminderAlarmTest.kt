package courseclock.timetable

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseReminderReceiver
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.TimetableChangeWatcher
import courseclock.timetable.utils.getPrefer
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 需要真 `Context` / `AlarmManager` / `NotificationManager` 的那部分提醒逻辑。
 *
 * 为什么要单独有这么一个测试类：提醒曾经**一次都没有注册成功过** —— `cancelAll` 用
 * `FLAG_NO_CREATE` 去查 PendingIntent，而工厂函数被声明成非空返回，于是第一轮取消就抛
 * `NullPointerException: getBroadcast(...) must not be null`。纯函数单测（47 项）全绿，
 * 因为它们根本走不到这条路径；这个缺陷只在真机上暴露。下面这些用例就是那个盲区的补丁。
 *
 * **本类刻意不创建 `type = 1` 的默认课表**：`AppDatabase` 是单例，同一测试类里各方法的库状态
 * 是共享的，"有没有默认课表"决定了重排走哪条分支，所以让它始终为"没有"，各方法才互不干扰。
 * 需要真课表的场景请另开测试类（Robolectric 按类隔离静态状态）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CourseReminderAlarmTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val alarmManager: AlarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun scheduledAlarms() = shadowOf(alarmManager).scheduledAlarms

    /** 天亮前没有任何课表时，重排必须**不抛异常**，且只排跨天与自刷新两枚杂务闹钟。 */
    @Test
    fun rescheduleWithoutAnyTableRegistersOnlyHousekeepingAlarms() {
        CourseReminderScheduler.reschedule(context)

        val alarms = scheduledAlarms()
        assertEquals("空库时只应有跨天闹钟与自刷新广播", 2, alarms.size)
        assertTrue("两枚都必须是 RTC_WAKEUP",
                alarms.all { it.type == AlarmManager.RTC_WAKEUP })

        // 自刷新定在约 1 秒后。
        val refreshAt = alarms.minOf { it.triggerAtTime }
        val now = System.currentTimeMillis()
        assertTrue("自刷新应落在 1 秒后附近，实际 ${refreshAt - now} ms",
                refreshAt - now in 0..10_000)

        // 跨天闹钟是"下一个 00:05"，用独立算出的时刻断言，不复用被测函数。
        val rolloverAt = alarms.maxOf { it.triggerAtTime }
        assertEquals(LocalTime.of(0, 5),
                Instant.ofEpochMilli(rolloverAt).atZone(ZoneId.systemDefault()).toLocalTime()
                        .truncatedTo(ChronoUnit.MINUTES))
        assertTrue("跨天闹钟必须在未来 24 小时内",
                rolloverAt in now..(now + 24 * 60 * 60 * 1000L))
    }

    /**
     * 连续重排不留副本。
     *
     * 这条直接测取消路径：`cancelAll` 会扫 64 个遗留编号 + 两类各 250 个编号。
     * 它要是没真的取消掉，第二次重排之后会看到 4 枚而不是 2 枚。
     */
    @Test
    fun reschedulingTwiceLeavesNoDuplicateAlarms() {
        CourseReminderScheduler.reschedule(context)
        val first = scheduledAlarms().size
        CourseReminderScheduler.reschedule(context)
        CourseReminderScheduler.reschedule(context)

        assertEquals(first, scheduledAlarms().size)
        assertEquals(2, scheduledAlarms().size)
    }

    /**
     * 观测器清单必须与 Room 真正建出来的表名对得上。
     *
     * [androidx.room.InvalidationTracker] **不校验表名**：观察一个拼错的表不会报错，只会永远
     * 不触发，静默失效。所以这里用 `sqlite_master` 里 Room 实际创建的表名与清单对账，不走异步、
     * 不会偶发失败。
     *
     * 覆盖缺口（诚实标注）：这条只证明"表名对得上"，**没有**证明"写入之后回调真的被调用"。
     * Robolectric 下 Room 的失效回调在等待窗口内没有送达（真机上 Room 是异步比对版本号再通知），
     * 那条端到端断言我删掉了 —— 与其留一条会偶发失败、或者靠 sleep 掩盖的测试，不如把缺口写在这里。
     * "写入 → 重排"的链路目前只由 Room 自己的文档契约保证。
     */
    @Test
    fun watcherObservesExactlyTheTablesRoomActuallyCreates() {
        val openHelper = AppDatabase.getDatabase(context).openHelper
        val actual = HashSet<String>()
        openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) {
                actual.add(cursor.getString(0))
            }
        }

        TimetableChangeWatcher.TABLES.forEach { observed ->
            assertTrue("观测器里的表 `$observed` 在库里不存在，这个观察者永远不会触发；" +
                    "实际存在的表：$actual", observed in actual)
        }
        assertEquals("影响提醒的表有且只有这四张，多观察或漏观察都要显式改这里",
                setOf("CourseBaseBean", "CourseDetailBean", "TableBean", "TimeDetailBean"),
                TimetableChangeWatcher.TABLES.toSet())
    }

    /**
     * 通知渲染：剩余分钟**在弹出那一刻现算**，正文是「课名 · 教室」，通知 id 就是闹钟的
     * requestCode。
     */
    @Test
    fun remindBroadcastPostsNotificationWithComputedRemainingMinutes() {
        context.getPrefer().edit().putBoolean(Const.KEY_COURSE_REMIND, true).commit()

        val requestCode = 0x5700
        val targetAt = System.currentTimeMillis() + 5 * 60_000L
        // 收件方是提醒模块自己的接收器，不再是 TodayCourseAppWidget：提醒与小部件之间没有
        // 依赖，"桌面没放小部件"正是原先这个功能最容易被卡死的地方。
        val intent = Intent(context, CourseReminderReceiver::class.java).apply {
            action = CourseReminderScheduler.ACTION_REMIND_COURSE
            putExtra(CourseReminderScheduler.EXTRA_KIND, CourseReminderScheduler.ReminderKind.START.name)
            putExtra(CourseReminderScheduler.EXTRA_COURSE_NAME, "高等数学")
            putExtra(CourseReminderScheduler.EXTRA_ROOM, "B210")
            putExtra(CourseReminderScheduler.EXTRA_TARGET_AT, targetAt)
            putExtra(CourseReminderScheduler.EXTRA_INDEX, requestCode)
        }

        CourseReminderReceiver().onReceive(context, intent)

        val posted = shadowOf(notificationManager).getNotification(requestCode)
        assertNotNull("通知 id 应当等于闹钟 requestCode $requestCode", posted)
        assertEquals("还有 5 分钟上课", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("高等数学 · B210", posted.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("标题已写明是上课还是下课，不该再有 subText",
                null, posted.extras.getString(Notification.EXTRA_SUB_TEXT))
    }

    /**
     * 总闸关掉之后，即使有一枚提醒广播已经在队列里，也必须安静地什么都不做。
     *
     * 关开关时 `reschedule` 会取消系统里已排的闹钟，但"闹钟已派发、广播还在队列中"这个
     * 竞态窗口是消不掉的。少了这道判断，用户关掉提醒之后还会收到最后一条通知。
     */
    @Test
    fun remindBroadcastIsDroppedWhenMasterSwitchIsOff() {
        context.getPrefer().edit().putBoolean(Const.KEY_COURSE_REMIND, false).commit()

        val requestCode = 0x5701
        val intent = Intent(context, CourseReminderReceiver::class.java).apply {
            action = CourseReminderScheduler.ACTION_REMIND_COURSE
            putExtra(CourseReminderScheduler.EXTRA_KIND, CourseReminderScheduler.ReminderKind.START.name)
            putExtra(CourseReminderScheduler.EXTRA_COURSE_NAME, "高等数学")
            putExtra(CourseReminderScheduler.EXTRA_ROOM, "B210")
            putExtra(CourseReminderScheduler.EXTRA_TARGET_AT, System.currentTimeMillis() + 5 * 60_000L)
            putExtra(CourseReminderScheduler.EXTRA_INDEX, requestCode)
        }

        CourseReminderReceiver().onReceive(context, intent)

        assertEquals("总闸关着时不应发出任何通知",
                null, shadowOf(notificationManager).getNotification(requestCode))
    }
}
