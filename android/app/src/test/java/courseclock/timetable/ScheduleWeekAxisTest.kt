package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.schedule.ScheduleUI
import courseclock.timetable.utils.CourseTimes
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 星期轴（课表最上面那一行"周几 + 日期"）的几何与出图。
 *
 * 这一行是真实用户报过两次问题的地方：一次是日期压在课程块上、一次是头部吃掉太多纵向空间。
 * 两件事都不是"看起来差不多"能验的 —— 前者要看这一行与网格的边距，后者要看两行文字有没有被裁。
 * 所以这里既真的量一遍（[星期轴不压到课表且两行都放得下]），也把这一行画成 PNG
 * （[出图_星期轴]）：改完视觉先看图，不用装到手机上。
 *
 * 填字这一步是 `ScheduleFragment.onViewCreated` 的复制：那边要 ViewModel 和 Room，这里只要
 * 一个 [ScheduleUI]。复制的只有"哪一格写哪一天"，字号层级和"今天"的判据仍然从 ScheduleUI 取，
 * 所以这一行长什么样由生产代码决定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleWeekAxisTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density

    private fun dip(value: Int): Int = (value * density).toInt()

    private fun timeDetails(): List<TimeDetailBean> {
        fun row(node: Int, start: String, end: String) =
                TimeDetailBean(node = node, startTime = start, endTime = end, timeTable = 1, timeGroup = "")
        return listOf(row(1, "08:15", "09:35"), row(2, "08:15", "09:35"), row(3, "09:55", "11:15"),
                row(4, "09:55", "11:15"), row(5, "11:20", "12:00"), row(6, "13:20", "14:40"),
                row(7, "15:00", "16:20"), row(8, "16:35", "17:55"))
    }

    /** weekDate[0] 是月份，1..7 是这一周七天的日期，与 CourseUtils.getDateStringFromWeek 同形。 */
    private val weekDate = listOf("9", "15", "16", "17", "18", "19", "20", "21")
    private val dayLabels = arrayOf("日", "一", "二", "三", "四", "五", "六", "日")

    /** 与 ScheduleFragment 同一条路径：`day` 是今天所在的那一天，看别的周时传 -1（没有高亮）。 */
    private fun buildUi(day: Int, textColor: Int = 0xff000000.toInt()): ScheduleUI {
        val table = TableBean(id = 1, tableName = "星期轴", nodes = 8, timeTable = 1,
                startDate = "2026-09-14", itemHeight = 56, textColor = textColor)
        val ui = ScheduleUI(context, table, day, times = CourseTimes.of(timeDetails()))
        ui.setWeekAxisCell(0, "月", weekDate[0])
        for (i in 1..7) {
            val column = ui.dayMap[i]
            if (column == -1) continue
            ui.setWeekAxisCell(column, "周" + dayLabels[i], weekDate[column])
        }
        return ui
    }

    /** 这一张表真的显示出来的列号（时间栏是第 0 列）。 */
    private fun usedColumns(ui: ScheduleUI): List<Int> =
            (1..7).map { ui.dayMap[it] }.filter { it != -1 }.distinct()

    /** 按 360×780dp 摆一遍（一台普通手机）。 */
    private fun layoutRoot(ui: ScheduleUI): ConstraintLayout {
        val root = ui.root
        root.measure(View.MeasureSpec.makeMeasureSpec(dip(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dip(780), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    /** 星期轴一格的容器（上下两行文字装在它里面）。 */
    private fun axisCell(root: View, column: Int): View =
            root.findViewById(R.id.anko_tv_title0 + column)

    private fun axisLabel(root: View, column: Int): TextView =
            axisCell(root, column).findViewById(R.id.anko_tv_title_label)

    private fun axisValue(root: View, column: Int): TextView =
            axisCell(root, column).findViewById(R.id.anko_tv_title_value)

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 星期轴不压到课表且两行都放得下() {
        val ui = buildUi(day = 1)
        val root = layoutRoot(ui)
        val column = usedColumns(ui).first()
        val cell = axisCell(root, column)
        val label = axisLabel(root, column)
        val value = axisValue(root, column)
        val scroll = root.findViewById<View>(R.id.anko_sv_schedule)

        // 两行都在视图里，而且顺序是"星期在上、日期在下"：日期被裁掉、或者两行叠在一起，
        // 用户看到的就是"日期没了 / 糊成一团"。
        assertTrue("星期轴上「周几」压到了日期上：${label.bottom} > ${value.top}", label.bottom <= value.top)
        assertTrue("日期这一行被裁掉了：${value.bottom} > 可用高度 ${cell.height - cell.paddingBottom}",
                value.bottom <= cell.height - cell.paddingBottom)
        // 主次分明才是这一行改造的目的：小字星期、大字日期。
        assertTrue("「周几」应该比日期小：${label.textSize} vs ${value.textSize}", label.textSize < value.textSize)

        // 网格紧贴在星期轴下面：既不许重叠（日期压到课块上），也不许留空当（头部白占地方）。
        assertEquals("星期轴与课表之间出现了重叠或空当", cell.bottom, scroll.top)
        // 第 0 列（月份格）和星期格结构相同，必须一样高：谁矮一截，网格钉在某一行上就会压到别人。
        for (c in 0..usedColumns(ui).max()) {
            assertEquals("第 $c 列的底边没落在星期轴的下沿上", scroll.top, axisCell(root, c).bottom)
        }

        // 头部预算：这一行的总高约 40dp（改前是 46dp）。谁把留白加回去就会挂在这里。
        println("星期轴实测高度 = ${cell.height}px = ${cell.height / density}dp" +
                "（其中内边距 ${(cell.paddingTop + cell.paddingBottom) / density}dp，" +
                "星期 ${label.height / density}dp，日期 ${value.height / density}dp）")
        assertTrue("星期轴占了 ${cell.height / density}dp，超过了允许的 44dp", cell.height <= dip(44))
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 看别的周时没有今天滑块() {
        assertNull("不是本周就不该有滑块", layoutRoot(buildUi(day = -1)).findViewById<View>(R.id.anko_tv_today_slider))
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 今天那一列的滑块落在该列上() {
        val ui = buildUi(day = 3)
        val root = layoutRoot(ui)
        val slider = root.findViewById<View>(R.id.anko_tv_today_slider)
        val cell = axisCell(root, ui.dayMap[3])
        assertEquals("滑块宽度应该是 24dp", dip(24), slider.width)
        val center = (slider.left + slider.right) / 2
        assertTrue("滑块没有落在今天那一列里：滑块中心 $center，列区间 ${cell.left}..${cell.right}",
                center > cell.left && center < cell.right)
    }

    /** 今天那一列用品牌色：这是"一眼看出今天在哪一列"的全部依据。 */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 今天那一列用主题品牌色() {
        val ui = buildUi(day = 3)
        val root = layoutRoot(ui)
        val today = ui.dayMap[3]
        val other = usedColumns(ui).first { it != today }
        assertEquals("今天的日期应该是品牌色", context.getColor(R.color.colorPrimary),
                axisValue(root, today).currentTextColor)
        assertTrue("不是今天的那一列不该用品牌色",
                axisValue(root, other).currentTextColor != context.getColor(R.color.colorPrimary))
    }

    /** 列号和日期是两套下标（隐藏周六/周日时列会左移），最容易写错的就是这里。 */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 每列写的是它自己的日期() {
        val ui = buildUi(day = 1)
        val root = layoutRoot(ui)
        assertEquals("9", axisValue(root, 0).text)
        assertEquals("月", axisLabel(root, 0).text)
        assertEquals("周一", axisLabel(root, 1).text)
        assertEquals("15", axisValue(root, 1).text)
        assertEquals("周五", axisLabel(root, 5).text)
        assertEquals("19", axisValue(root, 5).text)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 出图_星期轴() = renderHeader("axis_header_light.png", 0xff000000.toInt(), 0xffffffff.toInt())

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 出图_星期轴_深色() = renderHeader("axis_header_dark.png", 0xffffffff.toInt(), 0xff2A2F3A.toInt())

    private fun renderHeader(outName: String, textColor: Int, backdrop: Int) {
        val root = layoutRoot(buildUi(day = 1, textColor = textColor))
        val height = dip(200)
        val bitmap = Bitmap.createBitmap(root.width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backdrop)
        canvas.save()
        canvas.clipRect(0, 0, root.width, height)
        root.draw(canvas)
        canvas.restore()
        val file = File("../../_crop/$outName")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
