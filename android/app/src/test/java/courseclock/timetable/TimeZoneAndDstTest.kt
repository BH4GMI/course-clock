package courseclock.timetable

import courseclock.timetable.utils.CourseReminderScheduler
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 时区与夏令时。
 *
 * 提醒是这个 App 的核心承诺（"该响的时候响"），而它的时刻全部由设备时区推导：
 * 跨天闹钟是"下一个当地 00:05"，课程的星期/周次也按当地日期算。日历规则里有两处
 * 容易出错且**平时看不出来**的地方，这里各钉一条：
 *
 * 1. **夏令时切换日不是 24 小时**：用 `now + 86400000` 推"下一天的 00:05"会在前跳日落到
 *    01:05、在后拨日落到 23:05。源码注释声明用的是 `Calendar.add(DAY_OF_YEAR, 1)`，
 *    这两条用例就是那句声明的证据（也能反过来证伪毫秒相加的写法）。
 * 2. **换时区要跟着走**：同一绝对时刻，在两个时区里应当得到两个不同的闹钟时刻，
 *    并且各自落在当地的 00:05。
 *
 * 用例自带"独立算出的期望值"（`java.time` 那一侧），不复用被测函数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TimeZoneAndDstTest {

    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun millisAt(zone: String, text: String): Long =
            LocalDateTime.parse(text).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun wallClock(millis: Long, zone: String): LocalTime =
            Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)).toLocalTime()

    /** 前跳：2025-03-09 02:00 美东直接跳到 03:00，这一天只有 23 小时。 */
    @Test
    fun 夏令时前跳日的跨天闹钟仍是当地零点零五分() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_NEW_YORK))
        val now = millisAt(ZONE_NEW_YORK, "2025-03-09T01:00")

        val next = CourseReminderScheduler.nextTriggerMillisAt(now)

        assertEquals("跨天闹钟必须是当地 00:05；毫秒相加的写法会落到 01:05",
                LocalTime.of(0, 5), wallClock(next, ZONE_NEW_YORK))
        assertEquals("01:00 之后的那一发要跨过前跳，只隔 22 小时 05 分",
                Duration.ofHours(22).plusMinutes(5), Duration.ofMillis(next - now))
    }

    /** 后拨：2025-11-02 02:00 美东退回 01:00，这一天有 25 小时。 */
    @Test
    fun 夏令时后拨日的跨天闹钟仍是当地零点零五分() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_NEW_YORK))
        val now = millisAt(ZONE_NEW_YORK, "2025-11-02T01:30")

        val next = CourseReminderScheduler.nextTriggerMillisAt(now)

        assertEquals("跨天闹钟必须是当地 00:05；毫秒相加的写法会落到 23:05",
                LocalTime.of(0, 5), wallClock(next, ZONE_NEW_YORK))
        assertEquals("01:30 之后的那一发要跨过后拨，隔 23 小时 35 分",
                Duration.ofHours(23).plusMinutes(35), Duration.ofMillis(next - now))
    }

    /** 换时区（坐飞机、或手动改时区）后，闹钟按**新时区**的当地零点零五分重排。 */
    @Test
    fun 换时区后跨天闹钟跟着新时区走() {
        val now = millisAt(ZONE_SHANGHAI, "2025-09-17T22:00")

        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_SHANGHAI))
        val inShanghai = CourseReminderScheduler.nextTriggerMillisAt(now)

        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_NEW_YORK))
        val inNewYork = CourseReminderScheduler.nextTriggerMillisAt(now)

        assertEquals(LocalTime.of(0, 5), wallClock(inShanghai, ZONE_SHANGHAI))
        assertEquals(LocalTime.of(0, 5), wallClock(inNewYork, ZONE_NEW_YORK))
        assertNotEquals("同一个绝对时刻在两个时区里本就该得到两个不同的闹钟时刻",
                inShanghai, inNewYork)
    }

    /** "今天是星期几、第几周"按设备时区算：跨过当地零点就该翻页。 */
    @Test
    fun 星期几与周次跟着设备时区走() {
        // 2025-09-17T16:30Z：上海已是 09-18 00:30（周四），纽约还是 09-17 12:30（周三）。
        val now = Instant.parse("2025-09-17T16:30:00Z").toEpochMilli()

        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_SHANGHAI))
        val inShanghai = CourseReminderScheduler.dayOf("2025-09-15", false, now)

        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_NEW_YORK))
        val inNewYork = CourseReminderScheduler.dayOf("2025-09-15", false, now)

        assertEquals("上海已跨过当地零点，应算周四", "周四", inShanghai.weekdayName)
        assertEquals(4, inShanghai.weekday)
        assertEquals("纽约还是前一天，应算周三", "周三", inNewYork.weekdayName)
        assertEquals(3, inNewYork.weekday)
        assertEquals("两天都还在开学当周（2025-09-15 是周一）", 1, inShanghai.week)
        assertEquals(1, inNewYork.week)
    }

    private companion object {
        const val ZONE_SHANGHAI = "Asia/Shanghai"
        const val ZONE_NEW_YORK = "America/New_York"
    }
}
