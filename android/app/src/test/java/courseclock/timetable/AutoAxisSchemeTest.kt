package courseclock.timetable

import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.utils.CourseTimes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「自动」时间栏作息的选择规则：**取让整张课表偏移量总和最小的那一套**。
 *
 * 课块的上边按「这门课**自己楼宇**的首节时刻」落在「轴的第 startNode 行」区间里的比例摆放，
 * 下边同理（`CourseTimes.blockBox`）。轴与这门课同属一套作息时，两个比例正好是 0 与 1；
 * 轴是另一套时，偏离 0/1 多少，块就错位多少。把每套候选作息下所有课块的偏离加起来取最小，
 * 就是"看起来最整齐的那套"。
 *
 * 这样就不必再回答"哪些课有资格投票"：**对轴无所谓的课自然贡献 0**（第 1~10 节长实验的
 * 上边锚在第 1 行、下边锚在第 10 行，这两行三套作息逐格相同），**有所谓的课自然贡献它真实的
 * 错位量**。用户连续追问的正是"第 6~7 节的课算不算""长实验课算不算""3~9、1~4 算不算"。
 */
class AutoAxisSchemeTest {

    /** 学校公布的三套作息（第 1~15 节）；上午 3~5 节各自错峰，其余逐格相同。 */
    private val common = mapOf(
            1 to ("08:15" to "09:35"), 2 to ("08:15" to "09:35"),
            6 to ("13:20" to "14:40"), 7 to ("13:20" to "14:40"),
            8 to ("15:00" to "16:20"), 9 to ("15:00" to "16:20"),
            10 to ("16:35" to "17:55"), 11 to ("16:35" to "17:55"),
            12 to ("18:10" to "19:30"), 13 to ("18:10" to "19:30"),
            14 to ("19:35" to "20:15"), 15 to ("20:20" to "21:00"))

    private val morningA = mapOf(3 to ("09:55" to "10:35"), 4 to ("10:40" to "12:00"), 5 to ("10:40" to "12:00"))
    private val morningB = mapOf(3 to ("09:55" to "11:15"), 4 to ("09:55" to "11:15"), 5 to ("11:20" to "12:00"))
    private val morningC = mapOf(3 to ("10:15" to "11:35"), 4 to ("10:15" to "11:35"), 5 to ("11:40" to "12:20"))

    private val schemes: Map<String, Map<Int, Pair<String, String>>> = mapOf(
            "A" to (common + morningA), "B" to (common + morningB), "C" to (common + morningC))

    private fun rowOf(group: String, node: Int): Pair<String, String>? = schemes[group]?.get(node)

    private fun placement(day: Int, startNode: Int, step: Int, group: String) = CourseDetailBean(
            id = 0, day = day, room = "", teacher = "", startNode = startNode, step = step,
            startWeek = 1, endWeek = 8, type = 0, tableId = 1, timeGroup = group)

    private fun choose(vararg placements: CourseDetailBean): String =
            CourseTimes.autoAxisGroup(listOf("A", "B", "C"), ::rowOf, placements.toList())

    @Test
    fun `a long lab course does not drag the axis away from the morning courses`() {
        // 三门 A 楼 1~10 节的长实验（对轴无所谓，偏移恒为 0）+ 一门 E 楼 3~4 节
        val axis = choose(
                placement(1, 1, 10, "A"), placement(2, 1, 10, "A"), placement(4, 1, 10, "A"),
                placement(3, 3, 2, "C"))
        assertEquals("长实验课不该把轴拉到 A", "C", axis)
    }

    @Test
    fun `courses outside the morning periods do not affect the choice`() {
        // 三门 D、E 楼的下午课（三套作息下位置完全相同）+ 一门 B 楼 3~4 节
        val axis = choose(
                placement(1, 6, 2, "C"), placement(2, 6, 2, "C"), placement(3, 8, 2, "C"),
                placement(5, 3, 2, "B"))
        assertEquals("下午的课不该替上午做主", "B", axis)
    }

    @Test
    fun `a placement ending inside the morning periods does count`() {
        // 第 1~4 节：上边锚在第 1 行（三套相同），下边锚在第 4 行（三套不同）—— 它有偏好
        val axis = choose(placement(1, 1, 4, "C"))
        assertEquals("1~4 节的课应当选它自己的那套", "C", axis)
    }

    @Test
    fun `a placement starting inside the morning periods does count`() {
        // 第 3~9 节：上边锚在第 3 行，三套各不相同 —— 它有偏好
        assertEquals("3~9 节的课应当选它自己的那套", "C", choose(placement(1, 3, 7, "C")))
        // 第 4~5 节在 A 方案里同属 10:40~12:00 一格，锚在第 4、5 行上
        assertEquals("A 楼第 4~5 节应当选 A", "A", choose(placement(1, 4, 2, "A")))
    }

    /**
     * A 楼第 3~9 节是个边界情形：它的上边锚在第 3 行，而**第 3 节在 A 与 B 两套里都是 09:55 开始**，
     * 于是 A、B 两套下偏移都是 0，并列 → 按确定性规则回退 B。
     * 这不是"选错了"，而是这两套在这门课上**确实一样整齐**。
     */
    @Test
    fun `A and B can be equally tidy for a course starting at the shared row start`() {
        assertEquals("两套同样整齐时回退 B", "B", choose(placement(1, 3, 7, "A")))
    }

    @Test
    fun `the axis with the least total misalignment wins`() {
        // 三门 E 楼 3~4 节（C 方案）对一门 B 楼 3~4 节：C 的总偏移更小
        val axis = choose(
                placement(3, 3, 2, "C"), placement(6, 3, 2, "C"), placement(2, 3, 2, "C"),
                placement(4, 3, 2, "B"))
        assertEquals("多数课所在的那套偏移最小", "C", axis)
    }

    @Test
    fun `a tie prefers B and is deterministic`() {
        // 一门 D 楼 3~4 节（C）对一门 C 楼 3~4 节（B）：两套各让对方的课偏 0.5 行
        val axis = choose(placement(1, 3, 2, "C"), placement(2, 3, 2, "B"))
        assertEquals("并列时回退 B", "B", axis)
        // 反过来放也一样，不依赖顺序
        assertEquals("B", choose(placement(2, 3, 2, "B"), placement(1, 3, 2, "C")))
    }

    @Test
    fun `courses without their own time rows have no preference`() {
        // 手工建的时间表：课程分组是空串、作息也只有空串那一套 —— 没有候选可挑
        val handMade = placement(1, 3, 2, CourseTimes.DEFAULT_GROUP)
        assertEquals("没有候选时回退 B",
                "B", CourseTimes.autoAxisGroup(emptyList(), ::rowOf, listOf(handMade)))
        assertEquals("课程自己的分组查不到时间 ⇒ 不计入偏移",
                "B", CourseTimes.autoAxisGroup(listOf("A", "B", "C"), ::rowOf, listOf(handMade)))
    }

    @Test
    fun `no placement at all falls back to B`() {
        assertEquals("B", CourseTimes.autoAxisGroup(listOf("A", "B", "C"), ::rowOf, emptyList()))
    }
}
