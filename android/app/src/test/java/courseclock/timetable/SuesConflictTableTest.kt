package courseclock.timetable

import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.CourseTimes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「345 节冲突」测试课表。
 *
 * 数据不是在这里手写的，而是一份真实的 `.wakeup_schedule` 文件
 * （`src/test/resources/sues_345_conflict_test.wakeup_schedule`）—— 它就是 App「文件导入」认的
 * 那个格式，由教务排课文本经同一套解析与排版逻辑产出。所以这份测试同时钉住三件事：
 *
 * 1. 数据里的作息与校方公布的三套方案一致（第 3~5 节按楼宇错峰）；
 * 2. 同一份数据里，**同一个「第 4 节」有三种不同的下课时间**，谁也不能代表谁；
 * 3. App 的 [CourseTimes] 按 (节次, 分组) 取值，而不是按 `timeList[startNode - 1]` 取第 N 行
 *    —— 后者会把上午所有课都显示成 08:15（真实故障，见 ScheduleFragment 里的注释）。
 *
 * 课表长这样。**每门课都是两节连上** —— 本校不存在单节课，所以第 3 节只可能是 B/C 方案课的
 * 开头、第 5 节只可能是 A 方案课的结尾，三套方案各占几天：
 *
 *     周一  1~2 节 A（共享时段，对照）
 *     周二  第 3~4 节 B 09:55~11:15      （B 楼 B210）
 *     周三  第 3~4 节 C 10:15~11:35      （教学楼E302）
 *     周四  第 4~5 节 A 10:40~12:00      （教学楼A201）
 *     周五  第 3~4 节 B                  （J302）
 *     周六  第 3~4 节 C                  （J303）
 *     周日  第 4~5 节 A                  （J301）
 *
 * 因此另有三条不变量被测试钉住：每门课 ≥ 2 节且 ≥ 50 分钟、三套方案都要有真实的课承载、
 * 同一天不重叠（重叠的课块会一格压两门，那是另一个测试对象）。
 */
class SuesConflictTableTest {

    /**
     * 夹具是随仓库提交的测试资源（`src/test/resources/`），不是运行时生成物：全新 clone 上
     * 直接跑 `./gradlew test` 就必须能过，否则这条链在最需要它的地方是断的。
     */
    private val parts: List<String> by lazy {
        val stream = javaClass.getResourceAsStream("/sues_345_conflict_test.wakeup_schedule")
                ?: throw AssertionError("测试夹具缺失：android/app/src/test/resources/" +
                        "sues_345_conflict_test.wakeup_schedule")
        // .wakeup_schedule 是「每行一个 JSON 文档」：时间表 / 作息 / 课表 / 课程基表 / 安排。
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .lines().filter { it.isNotBlank() }
    }

    private val timeDetailListType: Type = object : TypeToken<List<TimeDetailBean>>() {}.type
    private val courseDetailListType: Type = object : TypeToken<List<CourseDetailBean>>() {}.type

    private val timeDetails: List<TimeDetailBean> by lazy { Gson().fromJson(parts[1], timeDetailListType) }
    private val details: List<CourseDetailBean> by lazy { Gson().fromJson(parts[4], courseDetailListType) }
    private val times: CourseTimes by lazy { CourseTimes.of(timeDetails) }

    private fun spanOf(node: Int, group: String): String =
            "${times.startOfNode(node, group)}~${times.endOfNode(node, group)}"

    /** 一条安排实际显示的起止：首节起、末节止 —— 与 App 渲染课块取的是同样两个值。 */
    private fun courseSpan(course: CourseDetailBean): String =
            times.startOfNode(course.startNode, course.timeGroup) + "~" +
                    times.endOfNode(course.startNode + course.step - 1, course.timeGroup)

    private fun minutesOf(hhmm: String): Int =
            hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(3, 5).toInt()

    /** 某个「HH:mm」下课时刻之前 10 分钟对应的毫秒（日期取今天，只有时分参与倒计时计算）。 */
    private fun tenMinutesBefore(hhmm: String): Long =
            LocalDateTime.of(LocalDate.now(), LocalTime.of(minutesOf(hhmm) / 60, minutesOf(hhmm) % 60))
                    .minusMinutes(10)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

    @Test
    fun fixtureIsAWellFormedWakeupSchedule() {
        assertEquals("生成的课表应有 5 行 JSON", 5, parts.size)
        assertEquals("时间表应有 4 个分组：默认 + A + B + C",
                listOf("", "A", "B", "C"), timeDetails.map { it.timeGroup }.distinct().sorted())
    }

    @Test
    fun sameFourthPeriodHasThreeDifferentEndTimes() {
        // 「不能按下标取时间」的直接证据：同一个第 4 节，三套方案三种起止，互不为子集。
        assertEquals("10:40~12:00", spanOf(4, "A"))
        assertEquals("09:55~11:15", spanOf(4, "B"))
        assertEquals("10:15~11:35", spanOf(4, "C"))
    }

    @Test
    fun periodsThreeToFiveFollowTheOfficialTable() {
        assertEquals("09:55~10:35", spanOf(3, "A"))
        assertEquals("10:40~12:00", spanOf(5, "A"))
        assertEquals("09:55~11:15", spanOf(3, "B"))
        assertEquals("11:20~12:00", spanOf(5, "B"))
        assertEquals("10:15~11:35", spanOf(3, "C"))
        assertEquals("11:40~12:20", spanOf(5, "C"))
    }

    @Test
    fun everyCourseRunsAtLeastTwoPeriodsAndFiftyMinutes() {
        // 本校不存在单节课：第 3 节单上只有 40 分钟、第 5 节单上也只有 40 分钟，所以
        // 第 3 节只可能是 B/C 方案课的开头，第 5 节只可能是 A 方案课的结尾。课表上要是冒出
        // 一条 40 分钟的课，看的人第一反应是"数据错了"，而不是"学校就这样"。
        assertTrue("这份课表里应该有安排", details.isNotEmpty())
        details.forEach { course ->
            assertTrue("周${course.day} 第${course.startNode}节只有 ${course.step} 节",
                    course.step >= 2)
            val span = courseSpan(course)
            val start = span.substringBefore("~")
            val end = span.substringAfter("~")
            assertTrue("周${course.day} $span 不足 50 分钟", minutesOf(end) - minutesOf(start) >= 50)
        }
    }

    @Test
    fun everySchemeIsCarriedByARealMorningCourse() {
        // 三套方案的时间差必须落在**真的课**上，否则"冲突"只存在于时间表里，界面上看不到。
        // 上午第 3~5 节一共三条不同的时间，分别由 A/B/C 三种楼宇的课承载。
        val morning = details.filter { it.startNode in 3..5 }
        assertEquals("上午第 3~5 节应覆盖三套方案",
                setOf("A", "B", "C"), morning.map { it.timeGroup }.toSet())
        assertEquals("同一节次在三套方案下必须是三种不同的时间",
                mapOf("A" to "10:40~12:00", "B" to "09:55~11:15", "C" to "10:15~11:35"),
                morning.associate { it.timeGroup to courseSpan(it) })
    }

    @Test
    fun everyDayHasACourseThatProducesACountdownLine() {
        // 「这份课表能不能用来测下课倒计时」不靠"晚上有课"这种形容来保证 —— 直接用 App 那两个
        // 函数算一遍：每一天都必须存在一条安排，在它下课前 10 分钟时 countdownMinutesLeft
        // 给出 10，也就是那一行会写「还有 10 分钟下课」。
        (1..7).forEach { day ->
            val courses = details.filter { it.day == day }
            assertTrue("周$day 一条安排都没有，那天没法测倒计时", courses.isNotEmpty())
            val hit = courses.filter { course ->
                val end = times.endOfNode(course.startNode + course.step - 1, course.timeGroup)
                CourseReminderScheduler.countdownMinutesLeft(end, tenMinutesBefore(end)) == 10L
            }
            assertTrue("周$day 没有任何一节课能在下课前显示出倒计时", hit.isNotEmpty())
        }
    }

    @Test
    fun defaultSchemeBlocksStayExactlyWhereTheOldFormulaPutThem() {
        // 旧公式：height = step*itemHeight + gap*(step-1)，top = (startNode-1)*(itemHeight+gap) + gap。
        // 默认分组的课必须继续落在这里，一个像素都不动 —— 换摆放规则不能动默认课表的观感。
        (1..15).forEach { node ->
            val box = times.blockBox(node, 1, "", 56, 2)
            assertEquals("第 $node 节的上边", (node - 1) * 58 + 2, box.top)
            assertEquals("第 $node 节的块高", 56, box.height)
        }
        val twoRows = times.blockBox(3, 2, "", 56, 2)
        assertEquals(118, twoRows.top)
        assertEquals(114, twoRows.height)
    }

    @Test
    fun otherSchemeBlocksShiftToTheirRealTime() {
        // D 楼（C 方案）第 3~4 节是 10:15~11:35，默认分组（B）第 3~4 节是 09:55~11:15：
        // 整段晚 20 分钟，而 B 第 3 行区间长 80 分钟 → 偏离 20/80 = 25% 行高 = 14px（行高 56）。
        val b = times.blockBox(3, 2, "B", 56, 2)
        val c = times.blockBox(3, 2, "C", 56, 2)
        assertEquals("时长一样，块高不该变", b.height, c.height)
        assertEquals("整块下移 25% 行高", b.top + 14, c.top)

        // A 楼（A 方案）第 4~5 节是 10:40~12:00：10:40 落在默认分组第 4 行（09:55~11:15）的
        // 45/80 = 56.25% → 下移 32px；但它的真实时长只有 80 分钟，而它占的第 4、5 行合起来
        // 标着 125 分钟 —— 严格按时间算块高只有 82px，比两行（114px）还矮，看起来就是"被压扁"。
        // 所以块高有下限：**永不小于它占的节次高度**，时间差异只体现在整块下移。
        val a = times.blockBox(4, 2, "A", 56, 2)
        val natural = times.blockBox(4, 2, "", 56, 2)
        assertEquals("上边下移 round(0.5625 × 56)", natural.top + 32, a.top)
        assertEquals("块高不许被压扁：4~5 节永远是两行高", natural.height, a.height)
    }

    @Test
    fun everyBlockIsAtLeastAsTallAsThePeriodsItOccupies() {
        // 无论哪个分组、哪一段节次，块高都不小于它占的节次高度。
        // 这条是从真机反馈里长出来的：4~5 节的课被压成了一行，看着像"第 5 节"。
        (1..13).forEach { node ->
            listOf("", "A", "B", "C").forEach { group ->
                listOf(1, 2, 3).forEach { step ->
                    val box = times.blockBox(node, step, group, 56, 2)
                    val floor = step * 56 + (step - 1) * 2
                    assertTrue("第${node}节起共${step}节（分组 ${group.ifEmpty { "默认" }}）块高 ${box.height} < $floor",
                            box.height >= floor)
                }
            }
        }
    }

    @Test
    fun noTwoCoursesShareTheSameSlotOnOneDay() {
        // 同一天不重叠：重叠的课块在界面上会一格压两门，那是另一个测试对象，
        // 不该混进这份用来判作息的课表里。
        details.groupBy { it.day }.forEach { (day, courses) ->
            val sorted = courses.sortedBy { it.startNode }
            sorted.zipWithNext().forEach { (earlier, later) ->
                assertTrue("周$day 第${earlier.startNode}~${earlier.startNode + earlier.step - 1}节" +
                        "与第${later.startNode}~${later.startNode + later.step - 1}节重叠",
                        earlier.startNode + earlier.step - 1 < later.startNode)
            }
        }
    }

    @Test
    fun leftTimeColumnUsesTheDefaultGroupOnly() {
        // 左侧时间栏只显示默认分组（空串），生成器把它固定成 B 方案。同一节次在时间栏与
        // 课块上可能不同，那是对的行为而不是错位：时间栏是参照刻度，课块自己带分组。
        val defaultFourth = times.defaultTimeForNode(4)
        assertTrue("默认分组必须存在于时间表里", defaultFourth != null)
        assertEquals("09:55~11:15", "${defaultFourth!!.startTime}~${defaultFourth.endTime}")
    }
}
