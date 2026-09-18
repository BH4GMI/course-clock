package courseclock.timetable

import biweekly.ICalendar
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.utils.CourseTimes
import courseclock.timetable.utils.ICalUtils
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ICS 导出的三个契约：
 *
 * 1. **星期定位与 locale 无关**：周日（day=7）的课必须落在开学周的周日。旧实现依赖
 *    Calendar 默认 locale 的 firstDayOfWeek，美式 locale（周日开头）下会整周偏移；
 *    现在 ICalUtils 显式钉在周一开头的周，本测试在任意默认 locale 的 JVM 上都成立。
 * 2. **连续周合并成一个循环事件**：第 1~2 周的课是一个 DTSTART 在第 1 周、按周循环到
 *    第 2 周的 event，而不是两个 event；间隔的单周（1、3、5）才拆成多个 event。
 * 3. **[ICalUtils.getClassEvents] 返回实际新增事件数**：时刻数据解析不出时返回 0，
 *    调用方据此把"没能写入日历的课程"可见化，而不是静默少课。
 */
class ICalExportTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val hm = SimpleDateFormat("HH:mm", Locale.US)

    /** 2026-09-14 是周一：本项目约定学期第 1 周从周一算起。 */
    private val termStart = sdf.parse("2026-09-14")!!

    private fun course(day: Int, startNode: Int, type: Int = 0,
                       startWeek: Int = 1, endWeek: Int = 2) = CourseBean(
            id = 0, courseName = "测试课", day = day, room = "A101", teacher = "老师",
            startNode = startNode, step = 1, startWeek = startWeek, endWeek = endWeek,
            type = type, color = "#FF1744", tableId = 1)

    private val times = CourseTimes.of(listOf(TimeDetailBean(1, "08:00", "08:50", 1)))

    @Test
    fun `consecutive weeks merge into one weekly event on the right sunday`() {
        val ical = ICalendar()
        val added = ICalUtils.getClassEvents(ical, times, 5, course(day = 7, startNode = 1), termStart, sundayFirst = false)

        assertEquals("第 1~2 周合并成一个事件", 1, added)
        val event = ical.events.single()
        assertEquals("第 1 周的周日", "2026-09-20", sdf.format(event.dateStart.value))
        assertEquals("上课时刻 08:00", "08:00", hm.format(event.dateStart.value))
    }

    @Test
    fun `gapped odd weeks become separate events`() {
        val ical = ICalendar()
        val course = course(day = 7, startNode = 1, type = 1, startWeek = 1, endWeek = 5)
        val added = ICalUtils.getClassEvents(ical, times, 5, course, termStart, sundayFirst = false)

        assertEquals("单周 1、3、5 拆成三个事件", 3, added)
        val dates = ical.events.map { sdf.format(it.dateStart.value) }.sorted()
        assertEquals(listOf("2026-09-20", "2026-10-04", "2026-10-18"), dates)
    }

    @Test
    fun `course on a node without time data is not added`() {
        val ical = ICalendar()
        // 第 9 节在作息表里没有行：startOfNode 返回空串，事件应被跳过而不是写出 0 点事件
        val added = ICalUtils.getClassEvents(ical, times, 5, course(day = 1, startNode = 9), termStart, sundayFirst = false)
        assertEquals(0, added)
        assertEquals(0, ical.events.size)
    }

    /**
     * 七天各排一节，逐天校验导出日期落在正确的星期。
     *
     * 为什么需要这一条：原来只测了 day=7（周日），以及一条 day=1 的用例（而那条只验
     * "没有作息数据不导出"）。把 `ICalUtils.weekDayConvert` 里的
     * `3 -> WEDNESDAY` 改成 `FRIDAY`（**周三的课导出到周五**）之后实测 **240 项全绿** ——
     * 中间五天的映射没有任何保护，而它直接决定写进用户日历的日期。
     */
    @Test
    fun `every weekday lands on its own date`() {
        // 第 1 周的周一 ~ 周日（2026-09-14 是周一，见 termStart 的注释）
        val expected = listOf(
                1 to "2026-09-14", 2 to "2026-09-15", 3 to "2026-09-16", 4 to "2026-09-17",
                5 to "2026-09-18", 6 to "2026-09-19", 7 to "2026-09-20")

        for ((day, date) in expected) {
            val ical = ICalendar()
            val added = ICalUtils.getClassEvents(ical, times, 5,
                    course(day = day, startNode = 1, startWeek = 1, endWeek = 1),
                    termStart, sundayFirst = false)

            assertEquals("第 $day 天应当导出 1 个事件", 1, added)
            assertEquals("第 $day 天必须落在 $date",
                    date, sdf.format(ical.events.single().dateStart.value))
        }
    }

    // ------------------------------------------------------------------
    // UID 契约：旧实现是 `Uid.random()`，同一份课表导出两次得到两套 UID，
    // 日历里出现两份完全重复的课程，用户只能手动删。以下三条把它钉住。
    // ------------------------------------------------------------------

    /** 两节都有作息的作息表：UID 的差异用例需要跨节次的课也能导出成功。 */
    private val twoNodes = CourseTimes.of(listOf(
            TimeDetailBean(1, "08:00", "08:50", 1),
            TimeDetailBean(2, "09:00", "09:50", 1)))

    /**
     * 一门课导出的全部 UID，按事件顺序。
     *
     * 用列表而不是单个：单周的课（type=1）会按"间隔周拆成多个事件"的既有规则产出多个事件
     * （第 1、3 周 → 2 个），这时 `.single()` 会抛 IllegalArgumentException。
     */
    private fun uidsOf(course: CourseBean): List<String> {
        val ical = ICalendar()
        ICalUtils.getClassEvents(ical, twoNodes, 5, course, termStart, sundayFirst = false)
        return ical.events.map { it.uid.value }
    }

    @Test
    fun `uid is stable across repeated exports of the same course`() {
        val base = course(day = 3, startNode = 1, startWeek = 1, endWeek = 4)
        val first = ICalendar()
        val second = ICalendar()

        ICalUtils.getClassEvents(first, twoNodes, 5, base, termStart, sundayFirst = false)
        ICalUtils.getClassEvents(second, twoNodes, 5, base, termStart, sundayFirst = false)

        val a = first.events.map { it.uid.value }
        val b = second.events.map { it.uid.value }
        assertTrue("这个用例至少要产出一个事件，否则下面的比较没有意义", a.isNotEmpty())
        assertEquals("同一门课两次导出必须是同一组 UID，否则重复导出会在日历里堆重复日程", a, b)
    }

    @Test
    fun `uid differs whenever an identifying field differs`() {
        val base = course(day = 3, startNode = 1, startWeek = 1, endWeek = 4)
        val baseUids = uidsOf(base)

        val others = listOf(
                "星期" to base.copy(day = 4),
                "起始节次" to base.copy(startNode = 2),
                "跨节数" to base.copy(step = 2),
                "结束周" to base.copy(endWeek = 6),
                "单双周" to base.copy(type = 1),
                "所属课表" to base.copy(tableId = 2),
                "课程 id" to base.copy(id = 7))

        for ((what, other) in others) {
            assertNotEquals("$what 不同必须换 UID —— 否则两条不同的课会被日历合并成一条",
                    baseUids, uidsOf(other))
        }
    }

    @Test
    fun `uid does not carry the upstream product name`() {
        val uids = uidsOf(course(day = 1, startNode = 1, startWeek = 1, endWeek = 1))
        assertTrue(uids.isNotEmpty())
        for (uid in uids) {
            assertFalse("NOTICE 声明不沿用上游产品名，UID 前缀也不该带它：$uid",
                    uid.contains("WakeUpSchedule"))
        }
    }
}
