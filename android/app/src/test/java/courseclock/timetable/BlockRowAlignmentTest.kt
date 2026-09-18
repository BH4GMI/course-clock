package courseclock.timetable

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.schedule.ScheduleUI
import courseclock.timetable.utils.CourseTimes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 每一节课的色块都必须落在它自己那一节格子里（块顶 == 行顶、块底 == 行底）。
 *
 * 手机是 520dpi → density 3.25，2dp 行距 = 6.5px，正好卡在"截断"和"四舍五入"中间：
 * 行视图用 `dip(2)`（→6px），`CourseTimes.blockBox` 用 `getDimensionPixelSize`（→7px），
 * 于是课块的行距比格子大 1px，**每向下一节多偏 1px** —— 下午的课整体掉出格子，
 * 上午只偏 1~5px 所以看不出来。这就是用户报的"下午的每一节课偏下、上边没挨着上边框线、
 * 下边超出下边框"。
 *
 * 现在行距只有 [ScheduleUI.rowGap] 一个来源，两边必然相等；这个测试钉住这个不变量：
 * 谁再把行高/行距拆成第二份来源，或者把取整方式换回截断，它就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BlockRowAlignmentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 与手机同一套作息（15 节，默认分组）。 */
    private fun rows(): List<TimeDetailBean> = (1..15).map {
        TimeDetailBean(node = it, startTime = String.format("%02d:00", 7 + it),
                endTime = String.format("%02d:40", 8 + it), timeTable = 1, timeGroup = "")
    }

    @Test
    @Config(qualifiers = "zh-rCN-520dpi")
    fun 课块与格子逐节对齐() {
        val table = TableBean(id = 1, tableName = "对齐", nodes = 15, timeTable = 1, startDate = "2026-09-14")
        val ui = ScheduleUI(context, table, 1)
        val times = CourseTimes.of(rows())
        val content = ui.content
        val column = content.findViewById<FrameLayout>(R.id.anko_ll_week_panel_0)

        // 每一节放一个"单节"课块：单节块的上下边应该正好是这一行的上下边。
        for (node in 1..table.nodes) {
            val box = ui.blockBoxOf(times, node, 1, "")
            column.addView(View(context), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, box.height).apply {
                gravity = Gravity.TOP
                topMargin = box.top
            })
        }

        // 先量再摆：约束布局要跑完 measure/layout 才有真实坐标。
        val width = 1220
        ui.scrollView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2656, View.MeasureSpec.EXACTLY))
        ui.scrollView.layout(0, 0, width, ui.scrollView.measuredHeight)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(content.measuredHeight, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)

        for (node in 1..table.nodes) {
            val row = content.findViewById<FrameLayout>(R.id.anko_tv_node1 + node - 1)
            val block = column.getChildAt(node - 1)
            // 两块都在 content 坐标系里比：column.top 是列自己的偏移，不该算进"行距漂移"。
            assertEquals("第 $node 节课块顶 vs 行顶", row.top, column.top + block.top)
            assertEquals("第 $node 节课块底 vs 行底", row.bottom, column.top + block.bottom)
        }
    }
}
