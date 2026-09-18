package courseclock.timetable

import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.schedule.verticalGridLineXs
import courseclock.timetable.utils.CourseTimes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网格横线的密度：**连堂内部不画线**。
 *
 * 学校公布的作息表里，有些节次共用同一格 —— 第 1、2 节都是 8:15~9:35，第 6、7 节
 * 都是 13:20~14:40……它们本来就是连着上的一节课，中间画一条虚线会把一次连堂读成
 * 两节课（用户报的正是「1、2 节之间不应该出现虚线」）。上午第 3~5 节按楼错峰，
 * A 楼是「3」与「4-5」、B/C 楼是「3-4」与「5」，连格形状还不一样。
 *
 * 所以判据不是写死的节次表，而是「相邻两节起止时刻是否相同」（[CourseTimes.hasGridLineAfter]）：
 * 时刻相同就是同一格，用户自定义的作息表也照样成立。
 *
 * 三套方案按学校公布的作息逐节钉住（见 docs/COURSETIME.md 与 `coursetime` 模块自测）。
 */
class SessionGridLineTest {

    /** 三套方案共有的时段；上午 3~5 节由各方案自己叠。 */
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

    /** 默认分组（左侧时间栏显示的那一套）就是这些行。 */
    private fun defaultTimes(morning: Map<Int, Pair<String, String>>): CourseTimes {
        val rows = (common + morning).toSortedMap().map { (node, time) ->
            TimeDetailBean(node = node, startTime = time.first, endTime = time.second,
                    timeTable = 1, timeGroup = CourseTimes.DEFAULT_GROUP)
        }
        return CourseTimes.of(rows)
    }

    /** 第 1~15 节各自「下面要不要画线」。 */
    private fun breaks(times: CourseTimes): BooleanArray =
            BooleanArray(15) { times.hasGridLineAfter(it + 1) }

    @Test
    fun `B scheme pairs 1-2 and 3-4, lines only at session boundaries`() {
        assertArrayEquals(
                booleanArrayOf(
                        false, true,  // 1-2 同格；2 与 3 之间是连堂分界
                        false, true,  // 3-4 同格；4 与 5 之间
                        true,         // 5 单独一格
                        false, true,  // 6-7
                        false, true,  // 8-9
                        false, true,  // 10-11
                        false, true,  // 12-13
                        true,         // 14 单独一格
                        true),        // 15 单独一格（其后没有第 16 节，照旧收边）
                breaks(defaultTimes(morningB)))
    }

    @Test
    fun `A scheme pairs 4-5 instead of 3-4`() {
        assertArrayEquals(
                booleanArrayOf(
                        false, true,
                        true, false,  // A 楼第 3 节单独一格，第 4、5 节同格
                        true,
                        false, true, false, true, false, true, false, true, true, true),
                breaks(defaultTimes(morningA)))
    }

    @Test
    fun `C scheme pairs 3-4 like B`() {
        assertArrayEquals(
                booleanArrayOf(
                        false, true, false, true, true,
                        false, true, false, true, false, true, false, true, true, true),
                breaks(defaultTimes(morningC)))
    }

    @Test
    fun `the user reported case - no line between period 1 and 2`() {
        val times = defaultTimes(morningB)
        assertFalse("第 1、2 节共用同一格，中间不应有线", times.hasGridLineAfter(1))
        assertTrue("第 2、3 节是两段连堂的分界，应当有线", times.hasGridLineAfter(2))
    }

    @Test
    fun `a timetable with distinct times per period keeps every line`() {
        // 用户自定义作息：每节时间都不同 ⇒ 没有连堂，线一根不少（不能因为改了判据就把线吞掉）
        val rows = (1..15).map {
            TimeDetailBean(node = it, startTime = "%02d:00".format(it), endTime = "%02d:45".format(it),
                    timeTable = 1, timeGroup = CourseTimes.DEFAULT_GROUP)
        }
        val expected = BooleanArray(15) { true }
        assertArrayEquals(expected, breaks(CourseTimes.of(rows)))
    }

    @Test
    fun `missing timetable data keeps every line`() {
        // 作息还没载入（或课表没有时间表）时不能凭空把线画没了
        assertArrayEquals(BooleanArray(15) { true }, breaks(CourseTimes.of(emptyList())))
    }

    /**
     * 竖线：**每一天都要被两条线框住**，N 天就是 N+1 条。
     *
     * 真机上报的是"周六与周日之间缺少 Y 向虚线"：原来的代码把"最后一列取右边界"当成了
     * 补一条外框线，于是最后一列自己的**左边界**（正是周六｜周日那条）被跳过，
     * 画出来的是 时间列|一、一|二、…、五|六、以及周日右侧那条外框。
     */
    @Test
    fun `seven days need eight vertical lines including the last boundary`() {
        // 7 天各占 100px：左边界 0,100,…,600，最后一天的右边界 700
        val columns = (0 until 7).map { (it * 100).toFloat() to ((it + 1) * 100).toFloat() }
        val xs = verticalGridLineXs(columns)
        assertArrayEquals(
                "N 天要有 N+1 条竖线：每列收左边 + 末尾收右边",
                floatArrayOf(0f, 100f, 200f, 300f, 400f, 500f, 600f, 700f), xs.toFloatArray(), 0f)
        assertTrue("周六与周日之间那条线（x=600）不能少", xs.contains(600f))
    }

    @Test
    fun `no columns means no vertical lines`() {
        assertTrue(verticalGridLineXs(emptyList()).isEmpty())
    }
}
