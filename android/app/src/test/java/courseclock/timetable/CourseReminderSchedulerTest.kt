package courseclock.timetable

import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.CourseTimes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 提醒排程的纯计算自检。
 *
 * 这里断言的每一个数字都是**手算出来的常数**（时间戳与周次另用独立的 .NET 计算核对过），
 * 不是用生产公式反推的 —— 拿被测公式算期望值再和被测公式比对，是自证循环，永远抓不到
 * 公式本身的错。
 *
 * 测试固定用东八区：换算全走 `Calendar.getInstance()`，结果依赖默认时区，不固定的话在别的
 * 机器上会偶发失败。东八区没有夏令时，所以测试内部的 ±24 小时算术是安全的。
 */
class CourseReminderSchedulerTest {

    private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private var defaultTimeZone: TimeZone? = null

    /** 某天某个时刻的绝对毫秒数；[month] 用 0 基是为了少一次换算出错的机会。 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long {
        val calendar = GregorianCalendar(shanghai)
        calendar.clear()
        calendar.set(year, month, day, hour, minute, second)
        return calendar.timeInMillis
    }

    private fun dayStart(year: Int, month: Int, day: Int): Long = at(year, month, day, 0, 0)

    private val oneDay = 24L * 60 * 60 * 1000

    @Before
    fun pinTimeZone() {
        defaultTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(shanghai)
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(defaultTimeZone)
    }

    // ---- 下一个 00:05：00:04 / 00:06 / 23:59 三个边界 ----

    @Test
    fun nextTriggerBeforeFivePastMidnightStaysToday() {
        // 1 月 1 日 00:04:00 的下一个 00:05 就是 60 秒后，即当天 00:05:00。
        val now = at(2024, 0, 1, 0, 4)
        val expected = dayStart(2024, 0, 1) + 5 * 60_000L
        assertEquals(60_000L, expected - now)
        assertEquals(expected, CourseReminderScheduler.nextTriggerMillisAt(now))
    }

    @Test
    fun nextTriggerAfterFivePastMidnightMovesToTomorrow() {
        // 00:06 已过 00:05，下一个触发点是 1 月 2 日 00:05，距此刻 23 小时 59 分。
        val now = at(2024, 0, 1, 0, 6)
        val expected = dayStart(2024, 0, 2) + 5 * 60_000L
        assertEquals(23 * 60 + 59, (expected - now) / 60_000L)
        assertEquals(expected, CourseReminderScheduler.nextTriggerMillisAt(now))
    }

    @Test
    fun nextTriggerLateAtNightMovesToTomorrow() {
        // 1 月 31 日 23:59 → 2 月 1 日 00:05，跨月也必须对。
        val now = at(2024, 0, 31, 23, 59)
        val expected = dayStart(2024, 1, 1) + 5 * 60_000L
        assertEquals(6, (expected - now) / 60_000L)
        assertEquals(expected, CourseReminderScheduler.nextTriggerMillisAt(now))
    }

    @Test
    fun nextTriggerExactlyAtTargetMovesToTomorrow() {
        // 正好等于 00:05:00 时不能再返回"现在"：排一个已经到点的闹钟会立刻触发，
        // 而这时它只该等第二天。
        val now = dayStart(2024, 0, 1) + 5 * 60_000L
        assertEquals(now + oneDay, CourseReminderScheduler.nextTriggerMillisAt(now))
    }

    @Test
    fun nextTriggerCrossesLeapDay() {
        // 2024 是闰年：2 月 28 日 23:59 → 2 月 29 日 00:05。
        val now = at(2024, 1, 28, 23, 59)
        val expected = dayStart(2024, 1, 29) + 5 * 60_000L
        assertEquals(6, (expected - now) / 60_000L)
        assertEquals(expected, CourseReminderScheduler.nextTriggerMillisAt(now))
    }

    @Test
    fun nextTriggerIsAlwaysStrictlyInTheFuture() {
        // 任意时刻调用都必须严格晚于此刻，否则闹钟会立刻触发、形成死循环。
        var now = at(2024, 0, 1, 0, 0)
        repeat(48) {
            val next = CourseReminderScheduler.nextTriggerMillisAt(now)
            assertTrue("now=$now next=$next", next > now)
            assertTrue("跨度必须在一分钟内到一天之间：${next - now}", next - now <= oneDay)
            now += 30 * 60_000L
        }
    }

    // ---- 该不该排：边界 ----

    @Test
    fun courseIsScheduledWhileItsReminderIsStillAhead() {
        // 08:00 上课、提前 20 分钟 → 触发时刻 07:40；此刻 07:39，还差一分钟，要排。
        val now = at(2024, 0, 1, 7, 39)
        assertEquals("07:40", CourseReminderScheduler.clockOf(8 * 60 - 20))
        assertEquals(dayStart(2024, 0, 1) + (8 * 60 - 20) * 60_000L,
                CourseReminderScheduler.reminderFreeze("08:00", dayStart(2024, 0, 1), 20))
        assertTrue(CourseReminderScheduler.shouldSchedule("08:00", now, 20))
    }

    @Test
    fun courseExactlyAtItsReminderInstantIsNotScheduled() {
        // 此刻正好是触发时刻 07:40：算已经到点，不排 —— 排一个"现在触发"的精确闹钟没有意义。
        val now = dayStart(2024, 0, 1) + (8 * 60 - 20) * 60_000L
        assertEquals(now, CourseReminderScheduler.reminderFreeze("08:00", dayStart(2024, 0, 1), 20))
        assertFalse(CourseReminderScheduler.shouldSchedule("08:00", now, 20))
    }

    @Test
    fun courseOneMinutePastItsReminderInstantIsNotScheduled() {
        // 07:41，触发时刻已过一分钟。
        val now = dayStart(2024, 0, 1) + (8 * 60 - 19) * 60_000L
        assertFalse(CourseReminderScheduler.shouldSchedule("08:00", now, 20))
    }

    @Test
    fun courseExactlyAtItsClassTimeButPastReminderIsNotScheduled() {
        // 正好 08:00 上课：触发时刻 07:40 早已过去，不能再排（否则 08:00 才收到"该上课了"）。
        val now = dayStart(2024, 0, 1) + 8 * 60 * 60_000L
        assertFalse(CourseReminderScheduler.shouldSchedule("08:00", now, 20))
    }

    @Test
    fun zeroLeadTimeSchedulesOnlyBeforeTheClassStarts() {
        // 提前 0 分钟：07:59 要排，08:00 整不排。
        assertTrue(CourseReminderScheduler.shouldSchedule("08:00", at(2024, 0, 1, 7, 59), 0))
        assertFalse(CourseReminderScheduler.shouldSchedule("08:00", at(2024, 0, 1, 8, 0), 0))
    }

    @Test
    fun missingOrMalformedStartTimeIsNeverScheduled() {
        val now = at(2024, 0, 1, 7, 0)
        for (bad in listOf("", " ", "08", "aa:bb", "24:00", "08:60", "-1:00", "8:xx", ":30", "08:")) {
            assertNull("「$bad」不该解析出时刻", CourseReminderScheduler.minutesOfDay(bad))
            assertNull("「$bad」不该换算出提醒时刻",
                    CourseReminderScheduler.reminderFreeze(bad, dayStart(2024, 0, 1), 20))
            assertFalse("「$bad」不该被排闹钟", CourseReminderScheduler.shouldSchedule(bad, now, 20))
        }
    }

    @Test
    fun clockStringWithTrailingSecondsIsAcceptedLeniently() {
        // 有意的宽松：只认「时:分」前缀，后面多出来的段直接忽略，绝不抛异常。
        // 时间串来自数据库（恒为 HH:mm），对纯计算做字符串校验只会把"数据脏一点"升级成崩溃。
        assertEquals(480, CourseReminderScheduler.minutesOfDay("08:00:00"))
        assertEquals(495, CourseReminderScheduler.minutesOfDay("08:15:00"))
    }

    @Test
    fun minutesOfDayParsesStandardClockStrings() {
        assertEquals(0, CourseReminderScheduler.minutesOfDay("00:00"))
        assertEquals(495, CourseReminderScheduler.minutesOfDay("08:15"))
        assertEquals(875, CourseReminderScheduler.minutesOfDay("14:35"))
        assertEquals(1439, CourseReminderScheduler.minutesOfDay("23:59"))
        assertEquals("08:15", CourseReminderScheduler.clockOf(495))
        assertEquals("00:05", CourseReminderScheduler.clockOf(5))
    }

    // ---- 没有剩余课程时不产生提醒闹钟 ----

    @Test
    fun noCoursesMeansNoReminderAlarms() {
        val now = at(2024, 0, 1, 7, 0)
        assertTrue(CourseReminderScheduler.reminderMillisFor(emptyList(), now, 20).isEmpty())
    }

    @Test
    fun allCoursesAlreadyPastMeansNoReminderAlarms() {
        // 此刻 18:00，剩下的课最早也是 08:15，触发时刻全在过去 → 一枚都不排。
        // 这一天剩下的唤醒代价就只有那枚跨天闹钟。
        val now = at(2024, 0, 1, 18, 0)
        val startTimes = listOf("08:15", "09:55", "13:20", "15:00", "16:35")
        assertTrue(CourseReminderScheduler.reminderMillisFor(startTimes, now, 20).isEmpty())
    }

    @Test
    fun coursesWithoutTimesDoNotProduceAlarms() {
        // 作息表缺行时 startOfNode/endOfNode 返回空串，不能因此排出一个"零点上课"的闹钟。
        val now = at(2024, 0, 1, 7, 0)
        assertTrue(CourseReminderScheduler.reminderMillisFor(listOf("", "", ""), now, 20).isEmpty())
    }

    @Test
    fun remainingCoursesAreScheduledInClassOrder() {
        // 上午的课都过了，只剩下午两节：13:20 与 15:00 各排一枚（提前 20 分钟）。
        val now = at(2024, 0, 1, 12, 0)
        val startTimes = listOf("08:15", "09:55", "15:00", "13:20")
        val expected = listOf(
                dayStart(2024, 0, 1) + (13 * 60 + 20 - 20) * 60_000L,
                dayStart(2024, 0, 1) + (15 * 60 - 20) * 60_000L
        )
        assertEquals(expected, CourseReminderScheduler.reminderMillisFor(startTimes, now, 20))
        assertEquals(2, CourseReminderScheduler.reminderMillisFor(startTimes, now, 20).size)
    }

    // ---- 上下课各一次 + 分组感知 ----
    //
    // 固定课表：开学日 2024-03-04（周一），基准时刻 2024-03-06 12:00（第 1 周周三）。
    //   W = 周三（day = 3）：第 1~16 周每周上，08:15 / 09:55 / 15:00
    //   F = 周五（day = 5）：第 1~16 周每周上，08:15 / 13:20
    // 窗口 7 天覆盖 03-06…03-12：周三 x2（第 1、2 周）+ 周五 x1（第 1 周），共 5 节。
    // 一节课两枚提醒（上课 + 下课），且 12:00 时当天 08:15 与 09:55 两节的下课提醒都已过点。

    private val startDate = "2024-03-04"

    /**
     * 作息行：[node]、开始、结束、分组。默认分组是 A/F 楼方案，
     * "C" 分组模拟 D/E 楼的第 3 节错峰（同一 node 的上课时刻相同、下课时刻不同）。
     */
    private val timeRows = listOf(
            TimeDetailBean(node = 2, startTime = "08:15", endTime = "09:35"),
            TimeDetailBean(node = 3, startTime = "09:55", endTime = "10:35"),
            TimeDetailBean(node = 6, startTime = "13:20", endTime = "14:40"),
            TimeDetailBean(node = 8, startTime = "15:00", endTime = "16:20"),
            TimeDetailBean(node = 3, startTime = "10:15", endTime = "11:35", timeGroup = "C")
    )

    private val courseTimes = CourseTimes.of(timeRows)

    private var allCourses: List<CourseBean> = emptyList()

    private fun weekly(id: Int, name: String, day: Int, node: Int) = CourseBean(
            id = id, courseName = name, day = day, room = "x", teacher = "x",
            startNode = node, step = 1, startWeek = 1, endWeek = 16, type = 0,
            color = "#000000", tableId = 1)

    private fun coursesOn(day: CourseReminderScheduler.CourseDayInWeek): List<CourseBean> =
            allCourses
                    .filter { it.day == day.weekday }
                    .filter { it.inWeek(day.week) }
                    .sortedBy { it.startNode }

    /**
     * 把「7 天滚动窗口 x 每节课两枚提醒」跑一遍，返回 (课程名, 类型, 触发时刻) 的升序列表。
     *
     * 逐天调生产的 [CourseReminderScheduler.alarmsForDay]，与生产代码走同一条路径 ——
     * 抑制谁、抑制的前提是否成立，都由那一个函数决定，测试不再自己复算抑制集合。
     * 窗口的**条数**断言全部是手算常数，不用生产公式反推。
     */
    private fun windowAlarms(
            now: Long,
            beforeStart: Int,
            beforeEnd: Int,
            merge: Boolean = false,
            startEnabled: Boolean = true,
            endEnabled: Boolean = true
    ): List<Triple<String, CourseReminderScheduler.ReminderKind, Long>> {
        val todayStart = CourseReminderScheduler.startOfDayMillis(now)
        val result = ArrayList<Triple<String, CourseReminderScheduler.ReminderKind, Long>>()
        for (dayOffset in 0 until CourseReminderScheduler.WINDOW_DAYS) {
            val dayStart = CourseReminderScheduler.startOfDayAfter(todayStart, dayOffset)
            val day = CourseReminderScheduler.dayOf(startDate, false, dayStart)
            val todays = coursesOn(day)
            for (alarm in CourseReminderScheduler.alarmsForDay(
                    todays, day, courseTimes, dayStart, now, beforeStart, beforeEnd,
                    startEnabled, endEnabled, merge)) {
                result.add(Triple(alarm.course.courseName, alarm.kind, alarm.triggerAt))
            }
        }
        return result.sortedBy { it.third }
    }

    @Test
    fun windowCoversSevenDaysAndSchedulesBothKindsPerCourse() {
        allCourses = listOf(
                weekly(1, "W-0815", 3, 2),
                weekly(2, "W-0955", 3, 3),
                weekly(3, "W-1500", 3, 8),
                weekly(4, "F-0815", 5, 2),
                weekly(5, "F-1320", 5, 6)
        )
        val now = at(2024, 2, 6, 12, 0)
        val beforeStart = 20
        val beforeEnd = 0
        val alarms = windowAlarms(now, beforeStart, beforeEnd)

        // 手算。窗口 = 03-06(周三) 起 7 天 = 03-06…03-12，含 **1 个周三 + 1 个周五**
        // （第二个周三 03-13 已经在窗口之外 —— 这一点我自己先算错过一次，以实测为准）。
        //   03-06：08:15(起07:55 排 / 止09:35 已过)、09:55(起09:35 已过 / 止10:35 已过)、
        //          15:00(起14:40 排 / 止16:20 排)                          -> 2 枚
        //   03-08：08:15(起07:55 / 止09:35) + 13:20(起13:00 / 止14:40)     -> 4 枚
        //   03-09…03-12：周六/周日/周一/周二没有课                      -> 0 枚
        assertEquals(6, alarms.size)
        assertEquals(7, CourseReminderScheduler.WINDOW_DAYS)

        // 逐枚钉死（升序）：当天 12:00 时只剩 15:00 那节的两枚，且顺序是先上课后下课。
        assertEquals(listOf(
                Triple("W-1500", CourseReminderScheduler.ReminderKind.START, 1709707200000L), // 03-06 14:40
                Triple("W-1500", CourseReminderScheduler.ReminderKind.END, 1709713200000L),   // 03-06 16:20
                Triple("F-0815", CourseReminderScheduler.ReminderKind.START, 1709855700000L), // 03-08 07:55
                Triple("F-0815", CourseReminderScheduler.ReminderKind.END, 1709861700000L),   // 03-08 09:35
                Triple("F-1320", CourseReminderScheduler.ReminderKind.START, 1709874000000L), // 03-08 13:00
                Triple("F-1320", CourseReminderScheduler.ReminderKind.END, 1709880000000L)    // 03-08 14:40
        ), alarms)

        // 两类数量相等（3 + 3），都能排出来，不是"只排了上课"。
        assertEquals(3, alarms.count { it.second == CourseReminderScheduler.ReminderKind.START })
        assertEquals(3, alarms.count { it.second == CourseReminderScheduler.ReminderKind.END })
        // 每节课的两枚都在，且同一时刻的上课/下课提醒互不覆盖（靠 requestCode 分段）。
        assertEquals(3, alarms.map { it.first }.distinct().size)
    }

    @Test
    fun windowTriggerTimesMatchHandComputedConstants() {
        // 只留一节课，把两类提醒的时刻逐个钉死，避免整体列表对得上但归属错位。
        // 窗口 03-06…03-12 里只有一个周三 03-06，而 12:00 时它两枚都已过点 -> 只剩下一个周三
        // 03-13 不在窗口内，所以这里改用"当天 00:00"作基准，让 03-06 那天的两枚都排出来：
        //   上课 08:15 - 20 分 = 07:55；下课 09:35 - 0 分 = 09:35。
        allCourses = listOf(weekly(1, "W-0815", 3, 2))
        val alarms = windowAlarms(at(2024, 2, 6, 0, 0), 20, 0)

        assertEquals(listOf(
                Triple("W-0815", CourseReminderScheduler.ReminderKind.START, 1709682900000L), // 03-06 07:55
                Triple("W-0815", CourseReminderScheduler.ReminderKind.END, 1709688900000L)    // 03-06 09:35
        ), alarms)
    }

    @Test
    fun endReminderIsGroupAware() {
        // 同一个 node 3，默认分组（A/F 楼 09:55-10:35）与 "C" 分组（D/E 楼 10:15-11:35）：
        // 上课时刻相同 -> 上课提醒都是 09:35；下课时刻不同 -> 下课提醒 10:35 vs 11:35。
        // 这正是本校上午 3~5 节按楼宇错峰的那个坑，绝不能按 node 自己推时长。
        val day = CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 13, 0, 0))
        assertEquals(2, day.week)

        val defaultGroupCourse = weekly(1, "A-0955", 3, 3)
        val groupCCourse = weekly(2, "C-1015", 3, 3).copy(timeGroup = "C")

        val a = CourseReminderScheduler.alarmsForDay(listOf(defaultGroupCourse), day, courseTimes,
                dayStart(2024, 2, 13), at(2024, 2, 13, 0, 0), 20, 0)
        val c = CourseReminderScheduler.alarmsForDay(listOf(groupCCourse), day, courseTimes,
                dayStart(2024, 2, 13), at(2024, 2, 13, 0, 0), 20, 0)

        // 按 kind 取而不是按下标取：alarmsForDay 是两遍扫描（先排全部下课、再排全部上课），
        // 返回顺序与"先上课后下课"无关，生产代码反正会按触发时刻整体排序。
        val aStart = a.first { it.kind == CourseReminderScheduler.ReminderKind.START }
        val aEnd = a.first { it.kind == CourseReminderScheduler.ReminderKind.END }
        val cStart = c.first { it.kind == CourseReminderScheduler.ReminderKind.START }
        val cEnd = c.first { it.kind == CourseReminderScheduler.ReminderKind.END }

        assertEquals("09:55", aStart.time)
        assertEquals(1710293700000L, aStart.triggerAt) // 03-13 09:35
        assertEquals("10:35", aEnd.time)
        assertEquals(1710297300000L, aEnd.triggerAt) // 03-13 10:35

        assertEquals("10:15", cStart.time)
        assertEquals(1710294900000L, cStart.triggerAt) // 03-13 09:55
        assertEquals("11:35", cEnd.time)
        assertEquals(1710300900000L, cEnd.triggerAt) // 03-13 11:35

        // 上课时刻的错峰差 20 分钟，下课时刻的错峰差 60 分钟 —— 完全由分组决定。
        assertEquals((20 * 60_000L), cStart.triggerAt - aStart.triggerAt)
        assertEquals((60 * 60_000L), cEnd.triggerAt - aEnd.triggerAt)
    }

    @Test
    fun zeroLeadTimeForEndReminderFiresExactlyAtClassEnd() {
        // 下课提前 0 分钟：正好等于下课时刻算已到点、不排；早一分钟才排。
        // 08:15-09:35 的课，下课 09:35：now = 09:34 排，now = 09:35 不排。
        assertEquals(true, CourseReminderScheduler.shouldSchedule("09:35", at(2024, 2, 13, 9, 34), 0))
        assertEquals(false, CourseReminderScheduler.shouldSchedule("09:35", at(2024, 2, 13, 9, 35), 0))
    }

    @Test
    fun degenerateCaseWhereStartAndEndRemindersCoincideKeepsBoth() {
        // 退化：触发时刻恰好相同。先把 if 拆开算一遍期望值（纯手算，不用被测函数）：
        //   开始 08:15(-60 分) = 07:15；结束 09:35(-140 分) = 07:15。两者相差 140-60 = 80 分钟，
        //   正好等于这节课的时长，所以两枚提醒同刻。
        // 行为要求明确：**两枚都保留**，因为请求码按类型分段，不会互相覆盖；
        // 静默丢掉一枚才是错的（用户会少一条"下课了"或"该上课了"）。
        val day = CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 13, 0, 0))
        val course = weekly(1, "W-0815", 3, 2)
        val beforeStart = 60
        val beforeEnd = 140

        val startFreeze = dayStart(2024, 2, 13) + (8 * 60 + 15 - beforeStart) * 60_000L
        val endFreeze = dayStart(2024, 2, 13) + (9 * 60 + 35 - beforeEnd) * 60_000L
        assertEquals(startFreeze, endFreeze)
        assertEquals(at(2024, 2, 13, 7, 15), startFreeze)

        val alarms = CourseReminderScheduler.alarmsForDay(listOf(course), day, courseTimes,
                dayStart(2024, 2, 13), at(2024, 2, 13, 0, 0), beforeStart, beforeEnd)
        assertEquals("同刻时必须两枚都保留", 2, alarms.size)
        assertEquals(1, alarms.count { it.kind == CourseReminderScheduler.ReminderKind.START })
        assertEquals(1, alarms.count { it.kind == CourseReminderScheduler.ReminderKind.END })
        assertTrue(alarms.all { it.triggerAt == at(2024, 2, 13, 7, 15) })
        // 两枚的时间串不同（08:15 是上课、09:35 是下课），通知文案才不会撞成一样。
        assertEquals(setOf("08:15", "09:35"), alarms.map { it.time }.toSet())

        // 换到窗口里：这一天这节课贡献 2 枚，且都在 00:00 之后。
        allCourses = listOf(course)
        val window = windowAlarms(at(2024, 2, 13, 0, 0), beforeStart, beforeEnd)
        assertEquals(2, window.size)
        assertEquals(setOf(CourseReminderScheduler.ReminderKind.START,
                CourseReminderScheduler.ReminderKind.END), window.map { it.second }.toSet())
    }

    @Test
    fun windowCountStaysUnderTheSystemAlarmLimit() {
        // 最坏情况实算：每天 8 节课，满 7 天。
        //   一节课 2 枚 x 8 节 = 每天 16 枚 x 7 天 = 112 枚。
        // AOSP 的 MAX_ALARMS_PER_UID = 500，所以 112 远在限内（上限 500 对应"窗口内 250 节课"）。
        val eightNodes = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        val perDay = eightNodes.mapIndexed { index, node ->
            CourseBean(id = index, courseName = "C$node", day = 3, room = "x", teacher = "x",
                    startNode = node, step = 1, startWeek = 1, endWeek = 16, type = 0,
                    color = "#000000", tableId = 1)
        }
        val times = CourseTimes.of((1..8).map {
            TimeDetailBean(node = it, startTime = String.format("%02d:00", 7 + it), endTime = String.format("%02d:50", 7 + it))
        })
        val day = CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 13, 0, 0))
        // mergeEnabled = false：本用例量的是**没有合并时**的原始条数（每节课两枚）。
        // 这 8 节彼此只隔 10 分钟，开着合并会压掉 7 枚，量到的就不是"最坏情况"了。
        val perDayAlarms = CourseReminderScheduler.alarmsForDay(perDay, day, times,
                dayStart(2024, 2, 13), at(2024, 2, 13, 0, 0), 20, 0, mergeEnabled = false)
        assertEquals("8 节课 x 2 枚", 16, perDayAlarms.size)
        assertEquals("7 天窗口", 112, perDayAlarms.size * CourseReminderScheduler.WINDOW_DAYS)
        assertTrue("必须远低于 MAX_ALARMS_PER_UID = 500",
                perDayAlarms.size * CourseReminderScheduler.WINDOW_DAYS < 500)
    }

    @Test
    fun windowDoesNotScheduleCoursesWhoseReminderAlreadyPassed() {
        allCourses = listOf(
                weekly(1, "W-0815", 3, 2),
                weekly(2, "W-0955", 3, 3),
                weekly(3, "W-1500", 3, 8),
                weekly(4, "F-0815", 5, 2),
                weekly(5, "F-1320", 5, 6)
        )
        // 基准推到周三 10:00。逐节算（beforeStart=20、beforeEnd=0，触发时刻 = 开始−20 / 结束−0）：
        //   W-0815 08:15-09:35 -> 起 07:55(已过)、止 09:35(已过)  -> 0 枚
        //   W-0955 09:55-10:35 -> 起 09:35(已过)、止 10:35(未到)  -> 1 枚
        //   W-1500 15:00-16:20 -> 起 14:40、止 16:20              -> 2 枚
        //   03-08 周五 两节 x 2 枚                                   -> 4 枚
        // 合计 7 枚；03-13 那个周三在窗口（03-06…03-12）之外。
        val now = at(2024, 2, 6, 10, 0)
        val alarms = windowAlarms(now, 20, 0)

        assertEquals(7, alarms.size)
        assertTrue("已过点的课不许出现在窗口里", alarms.none { it.first == "W-0815" })
        // 逐枚钉死（升序）。注意 W-0955 的**下课**提醒 10:35 仍未到点，必须留下 ——
        // 只按"开始时刻已过"就整节跳过，会丢掉这一枚，这正是上下课分开判断的意义。
        assertEquals(listOf(
                Triple("W-0955", CourseReminderScheduler.ReminderKind.END, 1709692500000L),   // 03-06 10:35
                Triple("W-1500", CourseReminderScheduler.ReminderKind.START, 1709707200000L), // 03-06 14:40
                Triple("W-1500", CourseReminderScheduler.ReminderKind.END, 1709713200000L),   // 03-06 16:20
                Triple("F-0815", CourseReminderScheduler.ReminderKind.START, 1709855700000L), // 03-08 07:55
                Triple("F-0815", CourseReminderScheduler.ReminderKind.END, 1709861700000L),   // 03-08 09:35
                Triple("F-1320", CourseReminderScheduler.ReminderKind.START, 1709874000000L), // 03-08 13:00
                Triple("F-1320", CourseReminderScheduler.ReminderKind.END, 1709880000000L)    // 03-08 14:40
        ), alarms)
    }

    @Test
    fun windowAlternatesOddAndEvenWeekCourses() {
        // 单周课与双周课各一门，都排在周三且时间不同：
        //   单周 08:15（第 1~3 周的单周，type = 1）
        //   双周 09:55（第 2~4 周的双周，type = 2）
        // 窗口 03-06…03-12 里只有一个周三 03-06，且它是第 1 周（单周）：
        // 于是只有单周课出现；双周课在 03-13（第 2 周，窗口外），把它挪进窗口要另起一个基准。
        // 这里用两段基准分别验证"单周确实上了"和"双周确实没在单周串场"。
        allCourses = listOf(
                CourseBean(id = 1, courseName = "ODD-0815", day = 3, room = "x", teacher = "x",
                        startNode = 2, step = 1, startWeek = 1, endWeek = 3, type = 1,
                        color = "#000000", tableId = 1),
                CourseBean(id = 2, courseName = "EVEN-0955", day = 3, room = "x", teacher = "x",
                        startNode = 3, step = 1, startWeek = 2, endWeek = 4, type = 2,
                        color = "#000000", tableId = 1)
        )

        // 基准 03-06 00:00（第 1 周周三）：只有单周课，两枚。
        assertEquals(listOf(
                Triple("ODD-0815", CourseReminderScheduler.ReminderKind.START, 1709682900000L), // 03-06 07:55
                Triple("ODD-0815", CourseReminderScheduler.ReminderKind.END, 1709688900000L)    // 03-06 09:35
        ), windowAlarms(at(2024, 2, 6, 0, 0), 20, 0))

        // 基准 03-11 00:00（第 2 周周一）：窗口 03-11…03-17 含 03-13（第 2 周周三），
        // 此时轮到双周课，单周课必须消失 —— 这就是"单双周在窗口内正确交替"。
        assertEquals(listOf(
                Triple("EVEN-0955", CourseReminderScheduler.ReminderKind.START, 1710293700000L), // 03-13 09:35
                Triple("EVEN-0955", CourseReminderScheduler.ReminderKind.END, 1710297300000L)    // 03-13 10:35
        ), windowAlarms(at(2024, 2, 11, 0, 0), 20, 0))
    }

    @Test
    fun windowResolvesTheRealSemesterWeekAndWeekday() {
        // 周次必须与 CourseUtils.countWeek 一致：开学 2024-03-04（周一）。
        // 03-06 是第 1 周、03-11 是第 2 周、03-13 是第 2 周、03-18 是第 3 周。
        assertEquals(1, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 6, 12, 0)).week)
        assertEquals(2, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 11, 12, 0)).week)
        assertEquals(2, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 13, 12, 0)).week)
        assertEquals(3, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 18, 12, 0)).week)
        // 单双周 type 跟着周次走：单周 1、双周 2。
        assertEquals(1, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 6, 12, 0)).type)
        assertEquals(2, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 13, 12, 0)).type)
        // 星期编号：03-06 周三 = 3、03-10 周日 = 7、03-11 周一 = 1。
        assertEquals(3, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 6, 12, 0)).weekday)
        assertEquals(7, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 10, 12, 0)).weekday)
        assertEquals(1, CourseReminderScheduler.dayOf(startDate, false, at(2024, 2, 11, 12, 0)).weekday)
    }

    @Test
    fun startOfDayAfterUsesCalendarArithmetic() {
        // 按天推进必须走 Calendar：固定 86400000 毫秒在夏令时切换日会算到前一天 23:00。
        // 这里验证它每步都落在整点零点上，且 7 步恰好跨到下一周的同一星期几。
        val base = CourseReminderScheduler.startOfDayMillis(at(2024, 2, 6, 12, 0))
        assertEquals(dayStart(2024, 2, 6), base)
        assertEquals(dayStart(2024, 2, 7), CourseReminderScheduler.startOfDayAfter(base, 1))
        assertEquals(dayStart(2024, 2, 12), CourseReminderScheduler.startOfDayAfter(base, 6))
        assertEquals(dayStart(2024, 2, 13), CourseReminderScheduler.startOfDayAfter(base, 7))
        // 7 天后是同一个星期几。
        assertEquals(CourseReminderScheduler.weekdayOf(base), CourseReminderScheduler.weekdayOf(
                CourseReminderScheduler.startOfDayAfter(base, 7)))
    }

    @Test
    fun weekdayMappingMatchesTheAppConvention() {
        // 周一 = 1 … 周日 = 7，与 CourseUtils.getWeekdayInt 的口径一致。
        assertEquals(3, CourseReminderScheduler.weekdayOf(at(2024, 2, 6, 12, 0)))  // 周三
        assertEquals(7, CourseReminderScheduler.weekdayOf(at(2024, 2, 10, 12, 0))) // 周日
        assertEquals(1, CourseReminderScheduler.weekdayOf(at(2024, 2, 11, 12, 0))) // 周一
    }

    // ---- 通知文案：纯函数，断言完整字符串 ----

    private val math = CourseReminderScheduler.CourseDetail("高等数学", "B210")
    private val noRoom = CourseReminderScheduler.CourseDetail("高等数学", "")
    private val noName = CourseReminderScheduler.CourseDetail("", "B210")
    private val blank = CourseReminderScheduler.CourseDetail("", "")

    @Test
    fun startNotificationTextMatchesTheCopyTable() {
        // 上课：剩余 ≥ 1 是"还有 N 分钟上课"，已到点/被推迟到过点是"已到上课时间"。
        assertEquals(CourseReminderScheduler.NotificationText("还有 5 分钟上课", "高等数学 · B210"),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.START, 5, math, null))
        assertEquals(CourseReminderScheduler.NotificationText("还有 1 分钟上课", "高等数学 · B210"),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.START, 1, math, null))
        assertEquals(CourseReminderScheduler.NotificationText("已到上课时间", "高等数学 · B210"),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.START, 0, math, null))
        assertEquals(CourseReminderScheduler.NotificationText("已到上课时间", "高等数学 · B210"),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.START, -3, math, null))
    }

    @Test
    fun endNotificationTextIsBodylessUnlessMerged() {
        // 下课不合并时正文留空 —— 用户只要"还剩几分钟下课"这一个信息。
        assertEquals(CourseReminderScheduler.NotificationText("还有 2 分钟下课", ""),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.END, 2, math, null))
        assertEquals(CourseReminderScheduler.NotificationText("下课时间到", ""),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.END, 0, math, null))

        // 合并时正文是"下一节 <课名> · <教室>"。
        assertEquals(CourseReminderScheduler.NotificationText("还有 2 分钟下课", "下一节 线性代数 · C305", "下一节 线性代数 · C305"),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.END, 2, math,
                        CourseReminderScheduler.CourseDetail("线性代数", "C305")))
        assertEquals(CourseReminderScheduler.NotificationText("下课时间到", "下一节 线性代数 · C305", "下一节 线性代数 · C305"),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.END, 0, math,
                        CourseReminderScheduler.CourseDetail("线性代数", "C305")))
    }

    @Test
    fun widgetCountdownLineIsTheSameSentenceAsTheEndNotificationTitle() {
        // 小部件那一行（TodayColorfulService 直接用 countdownText）与下课通知的标题
        // 必须是逐字一致的同一句话；这条断言就是它们之间唯一的契约。
        assertEquals("还有 20 分钟下课", CourseReminderScheduler.countdownText(20))
        assertEquals("还有 1 分钟下课", CourseReminderScheduler.countdownText(1))
        for (minutes in 1L..20L) {
            assertEquals("第 $minutes 分钟时两处文案不一致",
                    CourseReminderScheduler.countdownText(minutes),
                    CourseReminderScheduler.notificationText(
                            CourseReminderScheduler.ReminderKind.END, minutes, math, null).title)
        }
    }

    @Test
    fun countdownLineSpellsOutTheMinuteFromTheSameRule() {
        // 窗口内：先由 countdownMinutesLeft 现算分钟数（向上取整），再套 countdownText。
        // 20 分钟的窗口、20:15 下课 —— 从 19:55 的"还有 20 分钟"一路走到 20:14 的"还有 1 分钟"。
        val end = "20:15"
        assertEquals("还有 20 分钟下课",
                CourseReminderScheduler.countdownText(CourseReminderScheduler.countdownMinutesLeft(end, at(2026, 9, 15, 19, 55))!!))
        assertEquals("还有 1 分钟下课",
                CourseReminderScheduler.countdownText(CourseReminderScheduler.countdownMinutesLeft(end, at(2026, 9, 15, 20, 14))!!))
        // 正好下课与窗口之外都不写：没有分钟数可写，那一行整段消失。
        assertNull(CourseReminderScheduler.countdownMinutesLeft(end, at(2026, 9, 15, 20, 15)))
        assertNull(CourseReminderScheduler.countdownMinutesLeft(end, at(2026, 9, 15, 19, 54)))
    }

    @Test
    fun courseNameAndRoomDegradeWithoutStraySeparator() {
        // 教室为空只显示课名，课名为空只显示教室，两者都空则正文为空 —— 绝不留下孤零零的 "·"。
        assertEquals(CourseReminderScheduler.NotificationText("还有 5 分钟上课", "高等数学"),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.START, 5, noRoom, null))
        assertEquals(CourseReminderScheduler.NotificationText("还有 5 分钟上课", "B210"),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.START, 5, noName, null))
        assertEquals(CourseReminderScheduler.NotificationText("还有 5 分钟上课", ""),
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.START, 5, blank, null))
        // "下一节"这一段同样退化：教室空 → "下一节 线性代数"；两个都空 → 整段消失。
        assertEquals("下一节 线性代数",
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.END, 5, math,
                        CourseReminderScheduler.CourseDetail("线性代数", "")).body)
        assertEquals("",
                CourseReminderScheduler.notificationText(
                        CourseReminderScheduler.ReminderKind.END, 5, math,
                        CourseReminderScheduler.CourseDetail("", "")).body)
    }

    @Test
    fun joinNeverLeavesAStraySeparator() {
        assertEquals("高等数学 · B210", CourseReminderScheduler.join(math))
        assertEquals("高等数学", CourseReminderScheduler.join(noRoom))
        assertEquals("B210", CourseReminderScheduler.join(noName))
        assertEquals("", CourseReminderScheduler.join(blank))
    }

    // ---- 剩余分钟取整：向上取整 ----

    @Test
    fun remainingMinutesRoundsUp() {
        val base = at(2024, 2, 6, 7, 55)
        // 期望值由最终公式 `Math.floorDiv(delta + 59999, 60000)` 单独跑一遍生成（不是手算：
        // 我先手算错过三格，第一版实现 `-((-delta)/60000)` 也过点方向偏一格，就是被这组抓出来的）。
        //   delta > 0（还没到点）：0→0、1→1、59999→1、60000→1、60001→2、119999→2、
        //                          120000→2、239999→4、240001→5、299999→5、300000→5、
        //                          300001→6、360000→6
        //   delta < 0（已过点）：  -1→0、-59999→0、-60000→-1、-60001→-1、-119999→-1、-120000→-2
        val notPassed = listOf(
                0L to 0L, 1L to 1L, 59_999L to 1L, 60_000L to 1L, 60_001L to 2L,
                119_999L to 2L, 120_000L to 2L, 239_999L to 4L, 240_001L to 5L,
                299_999L to 5L, 300_000L to 5L, 300_001L to 6L, 360_000L to 6L
        )
        for ((delta, expected) in notPassed) {
            assertEquals("delta=$delta 应还剩 $expected 分钟",
                    expected, CourseReminderScheduler.remainingMinutes(base + delta, base))
        }
        val passed = listOf(
                -1L to 0L, -59_999L to 0L, -60_000L to -1L,
                -60_001L to -1L, -119_999L to -1L, -120_000L to -2L
        )
        for ((delta, expected) in passed) {
            assertEquals("已过 ${-delta} 毫秒应得 $expected",
                    expected, CourseReminderScheduler.remainingMinutes(base + delta, base))
        }
        // 规格点名的两个取整关键点：4 分 01 秒 → 5、4 分 59 秒 → 5（不足 5 分钟按 5 分钟报，
        // 用户按这个数字出门不会迟到）；降到 4 要等只剩不足 4 分钟。
        assertEquals(5L, CourseReminderScheduler.remainingMinutes(base, base - 4 * 60_000L - 1_000L)) // 4 分 01 秒
        assertEquals(5L, CourseReminderScheduler.remainingMinutes(base, base - 4 * 60_000L - 59_000L)) // 4 分 59 秒
        assertEquals(5L, CourseReminderScheduler.remainingMinutes(base, base - 5 * 60_000L)) // 正好 5 分
        assertEquals(4L, CourseReminderScheduler.remainingMinutes(
                base, base - 4 * 60_000L + 1L)) // 3 分 59.999 秒
    }

    // ---- 连堂判定与合并 ----
    //
    // 用一套自足的时刻表把"空档"这个变量单独隔离出来：T1 是基准课（08:00-08:50），
    // 其余每个 slot 的**开始时刻**与 T1 结束之间正好构成一个要测的空档。
    //   slot 2: 08:55 -> 空档  5 分钟
    //   slot 3: 09:05 -> 空档 15 分钟
    //   slot 4: 09:10 -> 空档 20 分钟  ← 阈值上界，应合并
    //   slot 5: 09:15 -> 空档 25 分钟  ← 应不合并
    //   slot 6: 09:20 -> 空档 30 分钟  ← 应不合并
    //   slot 7: 12:00 -> 与 slot 8(13:20) 构成 80 分钟午休

    private fun row(node: Int, start: String, end: String) =
            TimeDetailBean(node = node, startTime = start, endTime = end)

    private val gapTimes = CourseTimes.of(listOf(
            row(1, "08:00", "08:50"),
            row(2, "08:55", "09:45"),
            row(3, "09:05", "09:55"),
            row(4, "09:10", "10:00"),
            row(5, "09:15", "10:05"),
            row(6, "09:20", "10:10"),
            row(7, "11:20", "12:00"),
            row(8, "13:20", "14:10")
    ))

    private fun oneCourse(id: Int, name: String, node: Int) = CourseBean(
            id = id, courseName = name, day = 3, room = "x", teacher = "x",
            startNode = node, step = 1, startWeek = 1, endWeek = 16, type = 0,
            color = "#000000", tableId = 1)

    private fun adjacency(a: CourseBean, vararg others: CourseBean): CourseBean? =
            CourseReminderScheduler.adjacentAfter(
                    a, listOf(a) + others, gapTimes, CourseReminderScheduler.ADJACENT_BREAK_MAX_MINUTES)

    private fun courseNamed(node: Int, name: String) = oneCourse(node, name, node)

    @Test
    fun mergeThresholdIsExactlyTwentyMinutes() {
        assertEquals(20, CourseReminderScheduler.ADJACENT_BREAK_MAX_MINUTES)
        val a = courseNamed(1, "A")
        // 5 / 15 / 20 分钟空档：合并（课名不同）。
        assertEquals("B", adjacency(a, courseNamed(2, "B"))?.courseName)
        assertEquals("B", adjacency(a, courseNamed(3, "B"))?.courseName)
        assertEquals("B", adjacency(a, courseNamed(4, "B"))?.courseName)
        // 25 / 30 分钟空档：不合并。
        assertNull(adjacency(a, courseNamed(5, "B")))
        assertNull(adjacency(a, courseNamed(6, "B")))
    }

    @Test
    fun sameCourseNameIsNeverMerged() {
        // 课名相同即同一门课：即便空档只有 5 分钟也不合并。
        assertNull(adjacency(courseNamed(1, "高等数学"), courseNamed(2, "高等数学")))
        // 空档 20 分钟也一样不合并。
        assertNull(adjacency(courseNamed(1, "高等数学"), courseNamed(4, "高等数学")))
    }

    @Test
    fun lunchBreakIsNeverMerged() {
        // 12:00 → 13:20 = 80 分钟，远超阈值：上午最后一节与下午第一节不合并。
        assertNull(adjacency(courseNamed(7, "上午最后一节"), courseNamed(8, "下午第一节")))
    }

    @Test
    fun overlappingOrBackwardsCoursesAreNeverMerged() {
        // 同一格（同时刻）与更早开始的课都不算"下一节"。
        val a = courseNamed(4, "A") // 09:10-10:00
        assertNull(adjacency(a, courseNamed(2, "更早的课")))  // 08:55 开始
        assertNull(adjacency(a, courseNamed(4, "同格的课")))  // 同时刻
    }

    @Test
    fun earliestCandidateWinsAfterFilteringOutSameName() {
        // 候选里既有同名（空档 20）也有别的课（空档 25）：同名先被剔除，剩下的超阈值 → 不合并。
        val a = courseNamed(1, "A")
        assertNull(adjacency(a, courseNamed(4, "A"), courseNamed(5, "B")))
        // 换成空档 5 分钟的别的课，就应当选它。
        assertEquals("B", adjacency(a, courseNamed(4, "A"), courseNamed(2, "B"))?.courseName)
    }

    @Test
    fun mergeSuppressesExactlyOneAlarmAndKeepsBothWhenNotMerged() {
        val a = courseNamed(1, "A")
        val b = courseNamed(4, "B") // 空档 20 分钟 → 合并
        val now = at(2024, 2, 13, 0, 0)
        val day = CourseReminderScheduler.dayOf(startDate, false, now)
        val courses = listOf(a, b)

        val adjacency = CourseReminderScheduler.adjacencyOf(
                courses, gapTimes, CourseReminderScheduler.ADJACENT_BREAK_MAX_MINUTES)
        assertEquals("B", adjacency[a]?.courseName)
        val merged = CourseReminderScheduler.alarmsForDay(
                courses, day, gapTimes, dayStart(2024, 2, 13), now, 20, 0, true, true, true)
        // 合并：A 上课 + A 下课 + B 下课 = 3 枚；B 的上课被抑制。不合并时是 4 枚。
        assertEquals(3, merged.size)
        assertEquals(1, merged.count { it.kind == CourseReminderScheduler.ReminderKind.START })
        assertEquals(2, merged.count { it.kind == CourseReminderScheduler.ReminderKind.END })
        assertTrue("被抑制的必须是 B 的上课提醒",
                merged.none {
                    it.kind == CourseReminderScheduler.ReminderKind.START && it.course.courseName == "B"
                })
        val aEnd = merged.first {
            it.kind == CourseReminderScheduler.ReminderKind.END && it.course.courseName == "A"
        }
        assertEquals("B", aEnd.nextCourse?.courseName)

        // 不合并（开关关掉）：4 枚，两条都照常发。
        val unmerged = CourseReminderScheduler.alarmsForDay(
                courses, day, gapTimes, dayStart(2024, 2, 13), now, 20, 0, true, true, false)
        assertEquals(4, unmerged.size)
        assertEquals(2, unmerged.count { it.kind == CourseReminderScheduler.ReminderKind.START })
        assertEquals(2, unmerged.count { it.kind == CourseReminderScheduler.ReminderKind.END })
    }

    @Test
    fun lunchBreakKeepsBothNotifications() {
        // 跨午休那两节：四条都在，不许为了"少发一条"抑制任何一条。
        val morning = courseNamed(7, "上午最后一节")
        val afternoon = courseNamed(8, "下午第一节")
        val now = at(2024, 2, 13, 0, 0)
        val day = CourseReminderScheduler.dayOf(startDate, false, now)
        val courses = listOf(morning, afternoon)
        val adjacency = CourseReminderScheduler.adjacencyOf(
                courses, gapTimes, CourseReminderScheduler.ADJACENT_BREAK_MAX_MINUTES)
        assertNull(adjacency[morning])

        val alarms = CourseReminderScheduler.alarmsForDay(
                courses, day, gapTimes, dayStart(2024, 2, 13), now, 20, 0, true, true, true)
        assertEquals(4, alarms.size)
        assertEquals(2, alarms.count { it.kind == CourseReminderScheduler.ReminderKind.START })
        assertEquals(2, alarms.count { it.kind == CourseReminderScheduler.ReminderKind.END })
    }

    @Test
    fun sameNameAdjacentCoursesStillGetBothNotifications() {
        // 同名课连堂：不合并，于是 A 下课与 B 上课两条都在。
        val a = courseNamed(1, "高等数学")
        val b = courseNamed(2, "高等数学") // 空档 5 分钟，但同门课
        val now = at(2024, 2, 13, 0, 0)
        val day = CourseReminderScheduler.dayOf(startDate, false, now)
        val courses = listOf(a, b)
        val adjacency = CourseReminderScheduler.adjacencyOf(
                courses, gapTimes, CourseReminderScheduler.ADJACENT_BREAK_MAX_MINUTES)
        assertNull(adjacency[a])

        val alarms = CourseReminderScheduler.alarmsForDay(
                courses, day, gapTimes, dayStart(2024, 2, 13), now, 20, 0, true, true, true)
        assertEquals(4, alarms.size)
        assertEquals(2, alarms.count { it.kind == CourseReminderScheduler.ReminderKind.START })
        assertEquals(2, alarms.count { it.kind == CourseReminderScheduler.ReminderKind.END })
    }

    // ---- 三个开关各自的效果 ----

    @Test
    fun switchesDecideWhichKindsAreRegistered() {
        // 用两节**不相邻**的课（周三 / 周五），避免合并把 B 的上课提醒抑制掉干扰本用例。
        allCourses = listOf(
                weekly(1, "W-0815", 3, 2),
                weekly(2, "F-0815", 5, 2)
        )
        val now = at(2024, 2, 6, 12, 0)
        // 两节都在 03-06（周三）与 03-08（周五）；12:00 时 03-06 那节两枚都已过点，
        // 只剩 03-08 那节的两枚。
        val both = windowAlarms(now, 20, 0, merge = false, startEnabled = true, endEnabled = true)
        assertEquals(2, both.size)
        // 关上课：只剩 03-08 的下课一枚。
        val endOnly = windowAlarms(now, 20, 0, merge = false, startEnabled = false, endEnabled = true)
        assertEquals(1, endOnly.size)
        assertTrue(endOnly.all { it.second == CourseReminderScheduler.ReminderKind.END })
        // 关下课：只剩 03-08 的上课一枚。
        val startOnly = windowAlarms(now, 20, 0, merge = false, startEnabled = true, endEnabled = false)
        assertEquals(1, startOnly.size)
        assertTrue(startOnly.all { it.second == CourseReminderScheduler.ReminderKind.START })
        // 两个都关：一枚提醒都没有 —— 生产代码在这个分支里直接 return 0，只留跨天闹钟。
        assertTrue(windowAlarms(now, 20, 0, merge = false, startEnabled = false, endEnabled = false)
                .isEmpty())
    }

    // ---- 最坏情况条数：每天 8 节连堂课 x 7 天 ----

    @Test
    fun worstCaseCountWithAndWithoutMerge() {
        // 8 节课首尾相接、每处空档 10 分钟（≤ 20，构成连堂），课名互不相同。
        //   合并关：8 节 x 2 枚 = 16 枚/天 x 7 天 = 112 枚。
        //   合并开：每处连堂抑制下一节的上课提醒 = 抑制 7 枚，于是 16 - 7 = 9 枚/天 x 7 天 = 63 枚。
        // 两者都远在 AOSP 的 MAX_ALARMS_PER_UID = 500 之内。
        val times = CourseTimes.of((1..8).map {
            val start = 8 * 60 + (it - 1) * 60
            row(it, String.format("%02d:%02d", start / 60, start % 60),
                    String.format("%02d:%02d", (start + 50) / 60, (start + 50) % 60))
        })
        val courses = (1..8).map { oneCourse(it, "第${it}节", it) }
        val now = at(2024, 2, 13, 0, 0)
        val day = CourseReminderScheduler.dayOf(startDate, false, now)

        val perDayUnmerged = CourseReminderScheduler.alarmsForDay(
                courses, day, times, dayStart(2024, 2, 13), now, 20, 0, true, true, false)
        assertEquals(16, perDayUnmerged.size)
        assertEquals(112, perDayUnmerged.size * CourseReminderScheduler.WINDOW_DAYS)

        val adjacency = CourseReminderScheduler.adjacencyOf(
                courses, times, CourseReminderScheduler.ADJACENT_BREAK_MAX_MINUTES)
        val preceded = adjacency.values.filterNotNull().toSet()
        assertEquals(7, preceded.size)
        val perDayMerged = CourseReminderScheduler.alarmsForDay(
                courses, day, times, dayStart(2024, 2, 13), now, 20, 0, true, true, true)
        assertEquals(9, perDayMerged.size)
        assertEquals(63, perDayMerged.size * CourseReminderScheduler.WINDOW_DAYS)
        assertEquals(16 - 7, perDayUnmerged.size - preceded.size)
        assertTrue(112 < 500 && 63 < 500)
        // 结构性上界：本校一天最多 15 节 x 7 天 x 每节两枚 = 210 枚，够不着每类 250 的上限，
        // 所以 requestCode "注册量 ≤ 可取消量" 这条约束永远不会被课表撑破。
        assertEquals(210, 15 * CourseReminderScheduler.WINDOW_DAYS * 2)
    }

    // ---- 合并的前提：承载"下一节 …"的那枚下课提醒必须真的排出来 ----
    //
    // 抑制某节课的上课提醒，唯一依据是上一节的下课提醒会替它说话。下课提醒开关关掉、或那枚
    // 下课提醒已经过点时，它就不存在了；此时再抑制下一节的上课提醒，那节课就彻底没人提醒。
    // 这两条用例在改成两遍扫描之前都是**真的失败**的。

    @Test
    fun turningOffEndRemindersKeepsTheNextClassStartReminder() {
        // 08:00-08:50 之后 08:55 接一节，空档 5 分钟 —— 本来构成连堂。
        val a = courseNamed(1, "A")
        val b = courseNamed(2, "B")
        val now = at(2024, 2, 13, 0, 0)
        val day = CourseReminderScheduler.dayOf(startDate, false, now)

        // 下课提醒关掉、合并开着：两枚下课提醒都不存在，于是没有任何东西能承载"下一节"，
        // B 的上课提醒**必须**照常排出来。
        val alarms = CourseReminderScheduler.alarmsForDay(
                listOf(a, b), day, gapTimes, dayStart(2024, 2, 13), now, 20, 0,
                startEnabled = true, endEnabled = false, mergeEnabled = true)
        assertEquals(2, alarms.size)
        assertTrue(alarms.all { it.kind == CourseReminderScheduler.ReminderKind.START })
        assertEquals(at(2024, 2, 13, 7, 40),
                alarms.first { it.course.courseName == "A" }.triggerAt)
        assertEquals(at(2024, 2, 13, 8, 35),
                alarms.first { it.course.courseName == "B" }.triggerAt)
    }

    @Test
    fun lateEndReminderDoesNotSwallowTheNextClassStartReminder() {
        // 下课提前量 30 分钟 > 上课提前量 0 分钟。08:25 时：
        //   A 上课 08:00 已过、A 下课 08:20 已过（承载者不存在）
        //   B 上课 08:55 未到（必须排）、B 下课 09:15 未到
        val a = courseNamed(1, "A")
        val b = courseNamed(2, "B")
        val now = at(2024, 2, 13, 8, 25)
        val day = CourseReminderScheduler.dayOf(startDate, false, now)

        val alarms = CourseReminderScheduler.alarmsForDay(
                listOf(a, b), day, gapTimes, dayStart(2024, 2, 13), now, 0, 30)
        assertEquals(2, alarms.size)
        assertEquals(at(2024, 2, 13, 8, 55),
                alarms.first { it.kind == CourseReminderScheduler.ReminderKind.START
                        && it.course.courseName == "B" }.triggerAt)
    }

    @Test
    fun carrierPresentIsWhatTriggersSuppression() {
        // 对照组：同一对课、同一个提前量，但 A 的下课提醒还在未来 —— 承载者存在，
        // 抑制照旧成立，B 的上课提醒被压掉。证明上面两条不是"把合并整体关掉"换来的。
        val a = courseNamed(1, "A")
        val b = courseNamed(2, "B")
        val now = at(2024, 2, 13, 7, 0)
        val day = CourseReminderScheduler.dayOf(startDate, false, now)

        val alarms = CourseReminderScheduler.alarmsForDay(
                listOf(a, b), day, gapTimes, dayStart(2024, 2, 13), now, 0, 0)
        assertEquals(3, alarms.size)
        assertTrue("B 的上课提醒应被抑制",
                alarms.none {
                    it.kind == CourseReminderScheduler.ReminderKind.START && it.course.courseName == "B"
                })
        // 承载者身上确实带着"下一节"。
        val aEnd = alarms.first {
            it.kind == CourseReminderScheduler.ReminderKind.END && it.course.courseName == "A"
        }
        assertEquals("B", aEnd.nextCourse?.courseName)
    }

    // ---- 下课倒计时：窗口内的分钟边界 ----

    /** 一节课的时间取自 [timeRows]：第 6 节 13:20-14:40。 */
    private val countdownCourse = weekly(1, "倒计时", 3, 6)

    @Test
    fun countdownInstantsCoverEveryMinuteOfTheWindowIncludingTheEnd() {
        // 14:00 起算，第 6 节 14:40 下课：窗口是 14:20~14:40，共 21 个分钟边界
        // （k = 20..0，含下课那一刻 —— 那一下负责把数字抹掉）。
        val now = at(2024, 2, 13, 14, 0)
        val instants = CourseReminderScheduler.countdownInstants(
                listOf(countdownCourse), courseTimes, dayStart(2024, 2, 13), now)

        assertEquals(21, instants.size)
        assertEquals(at(2024, 2, 13, 14, 20), instants.first())
        assertEquals(at(2024, 2, 13, 14, 40), instants.last())
        // 相邻两枚恰好差一分钟，中间没有跳格。
        instants.zipWithNext { a, b -> assertEquals(60_000L, b - a) }
    }

    @Test
    fun countdownInstantsDropTheOnesAlreadyPast() {
        // 14:30 整起算：14:30 那一刻本身算"已到"，不排；剩下 14:31~14:40 共 10 枚。
        val now = at(2024, 2, 13, 14, 30)
        val instants = CourseReminderScheduler.countdownInstants(
                listOf(countdownCourse), courseTimes, dayStart(2024, 2, 13), now)

        assertEquals(10, instants.size)
        assertEquals(at(2024, 2, 13, 14, 31), instants.first())
    }

    @Test
    fun countdownInstantsAreDeduplicatedAcrossCoursesEndingAtTheSameMinute() {
        // 两节课同一时刻下课（第 5、6 节连上到 14:40 与第 6 节单独一节）：刷一次就够，
        // 否则同一分钟会有两枚闹钟同时唤醒，白烧一倍电。
        val sameEnd = weekly(2, "另一节", 3, 6)
        val instants = CourseReminderScheduler.countdownInstants(
                listOf(countdownCourse, sameEnd), courseTimes, dayStart(2024, 2, 13),
                at(2024, 2, 13, 14, 0))

        assertEquals(21, instants.size)
    }

    @Test
    fun countdownInstantsAreEmptyWhenTheCourseIsAlreadyOver() {
        // 15:00 时第 6 节早已下课，今天不再有该变的时刻。
        val instants = CourseReminderScheduler.countdownInstants(
                listOf(countdownCourse), courseTimes, dayStart(2024, 2, 13),
                at(2024, 2, 13, 15, 0))

        assertTrue(instants.isEmpty())
    }

    @Test
    fun countdownMinutesLeftIsOneAtTheEdgeAndCoversTheWholeWindow() {
        // 14:40 下课。窗口上沿：14:20:00 恰好是 20 分钟，14:19:59 就只能向上取整成 21 ——
        // 超出窗口，不显示。这条边界决定"数字第一次出现"到底在哪一秒。
        assertEquals(20L, CourseReminderScheduler.countdownMinutesLeft("14:40", at(2024, 2, 13, 14, 20)))
        assertNull(CourseReminderScheduler.countdownMinutesLeft("14:40", at(2024, 2, 13, 14, 19, 59)))
        // 中间每分钟降一格。
        assertEquals(19L, CourseReminderScheduler.countdownMinutesLeft("14:40", at(2024, 2, 13, 14, 21)))
        // 下沿：还剩 30 秒也要说"还有 1 分钟"（向上取整，与通知文案同一套规则）。
        assertEquals(1L, CourseReminderScheduler.countdownMinutesLeft("14:40", at(2024, 2, 13, 14, 39, 30)))
        // 正好下课与下课之后都不显示 —— "还有 0 分钟下课"是句废话。
        assertNull(CourseReminderScheduler.countdownMinutesLeft("14:40", at(2024, 2, 13, 14, 40)))
        assertNull(CourseReminderScheduler.countdownMinutesLeft("14:40", at(2024, 2, 13, 14, 41)))
    }

    @Test
    fun countdownMinutesLeftIgnoresMalformedTimeStrings() {
        // 时间表里出现畸形字符串时只是不显示，绝不能抛异常 —— 它跑在小部件出图路径上。
        assertNull(CourseReminderScheduler.countdownMinutesLeft("", at(2024, 2, 13, 14, 20)))
        assertNull(CourseReminderScheduler.countdownMinutesLeft("25:99", at(2024, 2, 13, 14, 20)))
        assertNull(CourseReminderScheduler.countdownMinutesLeft("14", at(2024, 2, 13, 14, 20)))
    }
}
