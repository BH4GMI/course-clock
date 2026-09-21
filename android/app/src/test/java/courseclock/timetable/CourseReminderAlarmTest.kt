package courseclock.timetable

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseReminderNotifier
import courseclock.timetable.utils.CourseReminderReceiver
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.TimetableChangeWatcher
import courseclock.timetable.utils.getPrefer
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun statusModeNeedsOnlyTheMasterNotTheEventSwitches() {
        val prefer = context.getPrefer()
        prefer.edit().putBoolean(Const.KEY_COURSE_REMIND, true)
                .putBoolean(CourseReminderScheduler.KEY_REMINDER_START_ENABLED, false)
                .putBoolean(CourseReminderScheduler.KEY_REMINDER_END_ENABLED, false)
                .putString(courseclock.timetable.utils.CourseNotificationSettings.KEY_STATUS_MODE, "SHADE").commit()
        assertTrue(CourseReminderScheduler.ongoingEnabled(context))
        prefer.edit().putBoolean(Const.KEY_COURSE_REMIND, false).commit()
        assertFalse(CourseReminderScheduler.ongoingEnabled(context))
    }

    /**
     * 常驻通知的 id 必须整体落在提醒编号区段**之外**。
     *
     * 提醒的通知 id 直接就是闹钟的 requestCode，从 `0x5700` 起（上课、下课各占 250 枚）。
     * 撞号会让两条通知互相覆盖，而且是静默的：不抛异常、不打日志，用户只会看到"通知没了"。
     * 这条断言把"取更小的值"这个约定钉住，免得日后有人顺手把它改成 `0x5701` 之类。
     */
    @Test
    fun ongoingNotificationIdStaysOutsideTheReminderIdRange() {
        val ongoing = CourseReminderNotifier.ONGOING_NOTIFICATION_ID
        assertTrue("常驻通知 id 必须严格小于提醒编号基址 0x5700，当前是 0x${ongoing.toString(16)}",
                ongoing < 0x5700)
    }
}
