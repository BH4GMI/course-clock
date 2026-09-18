package courseclock.timetable

import courseclock.timetable.utils.CourseUtils.calAfterTime
import courseclock.timetable.utils.CourseUtils.countWeek
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 两处时间数学的回归：
 *
 * 1. [calAfterTime]——旧实现用 `substring(0, 2)/(3, 5)` 取时分：无前导零（"8:00"）直接
 *    NumberFormatException，跨天还被钳成 00:00。现在走分钟数算术，跨天按 24 小时回绕。
 * 2. [countWeek]——[courseclock.timetable.utils.CourseUtils.daysBetween] 现在把开学日期
 *    也归一到所在周的周首日。开学日期不在周首日（如周三开学）时，旧实现因为两侧不都是
 *    周首日，`/7` 截断会让下一周的周次少算 1；这里把边界逐一钉住。
 */
class CourseUtilsTimeMathTest {

    private fun millis(date: String): Long =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date)!!.time

    // ---- calAfterTime ------------------------------------------------------

    @Test
    fun `adds minutes within the same day`() {
        assertEquals("08:45", calAfterTime("08:00", 45))
        assertEquals("22:50", calAfterTime("21:20", 90))
    }

    @Test
    fun `tolerates times without leading zero`() {
        // 旧实现在这里抛 NumberFormatException
        assertEquals("08:45", calAfterTime("8:00", 45))
    }

    @Test
    fun `wraps across midnight instead of clamping`() {
        // 旧实现把跨天钳成 00:00；真实的下课时刻是次日的 00:20
        assertEquals("00:20", calAfterTime("23:30", 50))
    }

    @Test
    fun `unparsable input is returned as-is`() {
        assertEquals("garbage", calAfterTime("garbage", 45))
        assertEquals("", calAfterTime("", 45))
    }

    // ---- countWeek（开学日期不在周首日的场景） -----------------------------

    @Test
    fun `monday start keeps the classic boundaries`() {
        val start = "2026-09-14" // 周一
        assertEquals(0, countWeek(start, false, basisMillis = millis("2026-09-13")))
        assertEquals(1, countWeek(start, false, basisMillis = millis("2026-09-14")))
        assertEquals(1, countWeek(start, false, basisMillis = millis("2026-09-20")))
        assertEquals(2, countWeek(start, false, basisMillis = millis("2026-09-21")))
    }

    @Test
    fun `midweek start counts the whole containing week as week one`() {
        val start = "2026-09-16" // 周三
        assertEquals(0, countWeek(start, false, basisMillis = millis("2026-09-13")))
        assertEquals(1, countWeek(start, false, basisMillis = millis("2026-09-14")))
        assertEquals(1, countWeek(start, false, basisMillis = millis("2026-09-16")))
        assertEquals(1, countWeek(start, false, basisMillis = millis("2026-09-20")))
        // 旧实现在这里给出 1（两侧不都是周首日，/7 截断少算一周）
        assertEquals(2, countWeek(start, false, basisMillis = millis("2026-09-21")))
        assertEquals(3, countWeek(start, false, basisMillis = millis("2026-09-28")))
    }

    @Test
    fun `sunday-first tables align to sunday`() {
        val start = "2026-09-13" // 周日
        assertEquals(0, countWeek(start, true, basisMillis = millis("2026-09-12")))
        assertEquals(1, countWeek(start, true, basisMillis = millis("2026-09-13")))
        assertEquals(1, countWeek(start, true, basisMillis = millis("2026-09-19")))
        assertEquals(2, countWeek(start, true, basisMillis = millis("2026-09-20")))
    }

    @Test
    fun `blank start date means week zero`() {
        assertEquals(0, countWeek("", false))
        assertEquals(0, countWeek("   ", false))
    }

    @Test
    fun `sanity on calendar construction`() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis("2026-09-16")
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        assertEquals("2026-09-16", sdf.format(cal.time))
    }
}
