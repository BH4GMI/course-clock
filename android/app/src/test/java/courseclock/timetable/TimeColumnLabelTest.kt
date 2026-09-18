package courseclock.timetable

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.schedule.ScheduleUI
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseTimes
import courseclock.timetable.utils.getPrefer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 左侧时间栏：**一格只标一次上/下课时间**，竖着读不能出现时间倒流。
 *
 * 这条测试是为真机截图里那个现象写的：第 3、4 节同属 09:55~11:15 一格，两行各自都显示
 * 「开始/结束」，而连堂内部又不画分隔线，于是时间栏竖着读成
 * `09:55 → 11:15 → 09:55 → 11:15`，看起来时间往回跳。
 *
 * 修法：连堂首行给开始时间、末行给结束时间，中间行只留节次号（与学校印的课表一致），
 * 判据与网格线共用同一份 [CourseTimes.hasGridLineAfter]，两条线不会分叉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TimeColumnLabelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun table() = TableBean(
            id = 1, tableName = "时间轴用例", nodes = 15, timeTable = 1,
            startDate = "2026-09-14", itemHeight = 56, widgetItemHeight = 56)

    private val common = mapOf(
            1 to ("08:15" to "09:35"), 2 to ("08:15" to "09:35"),
            6 to ("13:20" to "14:40"), 7 to ("13:20" to "14:40"),
            8 to ("15:00" to "16:20"), 9 to ("15:00" to "16:20"),
            10 to ("16:35" to "17:55"), 11 to ("16:35" to "17:55"),
            12 to ("18:10" to "19:30"), 13 to ("18:10" to "19:30"),
            14 to ("19:35" to "20:15"), 15 to ("20:20" to "21:00"))

    private val morningB = mapOf(3 to ("09:55" to "11:15"), 4 to ("09:55" to "11:15"), 5 to ("11:20" to "12:00"))
    private val morningA = mapOf(3 to ("09:55" to "10:35"), 4 to ("10:40" to "12:00"), 5 to ("10:40" to "12:00"))

    private fun courseTimes(morning: Map<Int, Pair<String, String>>): CourseTimes =
            CourseTimes.of((common + morning).toSortedMap().map { (node, time) ->
                TimeDetailBean(node = node, startTime = time.first, endTime = time.second,
                        timeTable = 1, timeGroup = CourseTimes.DEFAULT_GROUP)
            })

    /** 造一个和 [courseclock.timetable.schedule.ScheduleFragment.fillTimeColumn] 同样接线的课表视图。 */
    private fun uiWith(morning: Map<Int, Pair<String, String>>): ScheduleUI {
        val times = courseTimes(morning)
        val ui = ScheduleUI(context, table(), 1, times = times)
        for (i in 0 until table().nodes) {
            val timeRow = times.defaultTimeForNode(i + 1) ?: continue
            val frame = ui.content.findViewById<FrameLayout>(R.id.anko_tv_node1 + i)
            frame.findViewById<TextView>(R.id.tv_start).text = timeRow.startTime
            frame.findViewById<TextView>(R.id.tv_end).text = timeRow.endTime
        }
        return ui
    }

    private fun cell(ui: ScheduleUI, index: Int, id: Int): TextView =
            ui.content.findViewById<FrameLayout>(R.id.anko_tv_node1 + index).findViewById(id)

    /** 竖着读下来的全部可见时间标签。 */
    private fun visibleLabels(ui: ScheduleUI): List<String> = (0 until table().nodes).flatMap { i ->
        listOf(R.id.tv_start, R.id.tv_end)
                .map { cell(ui, i, it) }
                .filter { it.visibility == View.VISIBLE }
                .map { it.text.toString() }
    }

    @Test
    fun `labels never run backwards down the column`() {
        // 「HH:mm」补零后按字符串比较就是按时间比较；倒流会在这里被抓到
        val labels = visibleLabels(uiWith(morningB))
        assertEquals("可见时间标签应按时间递增：$labels", labels.sorted(), labels)
    }

    @Test
    fun `a two-period session is labelled once at the top and once at the bottom`() {
        val ui = uiWith(morningB)
        // 第 1、2 节是一格：开始在第 1 行、结束在第 2 行，中间不再重复
        assertEquals(View.VISIBLE, cell(ui, 0, R.id.tv_start).visibility)
        assertEquals(View.GONE, cell(ui, 0, R.id.tv_end).visibility)
        assertEquals(View.GONE, cell(ui, 1, R.id.tv_start).visibility)
        assertEquals(View.VISIBLE, cell(ui, 1, R.id.tv_end).visibility)
        assertEquals("08:15", cell(ui, 0, R.id.tv_start).text.toString())
        assertEquals("09:35", cell(ui, 1, R.id.tv_end).text.toString())
        // 第 3、4 节同样是一格（B 方案）
        assertEquals("09:55", cell(ui, 2, R.id.tv_start).text.toString())
        assertEquals(View.GONE, cell(ui, 3, R.id.tv_start).visibility)
        assertEquals("11:15", cell(ui, 3, R.id.tv_end).text.toString())
    }

    @Test
    fun `single-period cells keep both labels`() {
        val ui = uiWith(morningB)
        // 第 5 节单独一格：自己的开始与结束都要标出来
        assertEquals(View.VISIBLE, cell(ui, 4, R.id.tv_start).visibility)
        assertEquals(View.VISIBLE, cell(ui, 4, R.id.tv_end).visibility)
        assertEquals("11:20", cell(ui, 4, R.id.tv_start).text.toString())
        assertEquals("12:00", cell(ui, 4, R.id.tv_end).text.toString())
        // 最后一节之后没有第 16 节，结束时间照旧要显示
        assertEquals(View.VISIBLE, cell(ui, 14, R.id.tv_end).visibility)
        assertEquals("21:00", cell(ui, 14, R.id.tv_end).text.toString())
    }

    @Test
    fun `A scheme morning labels its own shape without running backwards`() {
        val ui = uiWith(morningA)
        val labels = visibleLabels(ui)
        assertEquals("可见时间标签应按时间递增：$labels", labels.sorted(), labels)
        // A 楼第 3 节单独一格（09:55~10:35），第 4、5 节同格（10:40~12:00）
        assertEquals("09:55", cell(ui, 2, R.id.tv_start).text.toString())
        assertEquals(View.VISIBLE, cell(ui, 2, R.id.tv_end).visibility)
        assertEquals(View.VISIBLE, cell(ui, 3, R.id.tv_start).visibility)
        assertEquals(View.GONE, cell(ui, 4, R.id.tv_start).visibility)
        assertEquals("12:00", cell(ui, 4, R.id.tv_end).text.toString())
    }

    @Test
    fun `turning the setting off hides every time label`() {
        val ui = uiWith(morningB)
        context.getPrefer().edit().putBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, false).commit()
        ui.applyPreferences()
        assertTrue("关掉「节数栏显示具体时间」后不该还有时间标签", visibleLabels(ui).isEmpty())
    }
}
