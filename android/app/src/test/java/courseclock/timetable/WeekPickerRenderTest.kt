package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.schedule.WeekPickerFragment
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 周数选择面板（设计稿 2）的几何、配色与出图。
 *
 * 这一屏的每一条要求都是可量的：面板铺满宽度、一行 7 个格子、格子 40×36dp、
 * **当前周是实心强调色**（这条只有画出来才验得到 —— `GradientDrawable` 没有公开的取色 API，
 * 所以这里读像素），以及右上角那句「共 N 周」要和格子的数量对得上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeekPickerRenderTest {

    private val screenWidthDp = 360
    private val screenHeightDp = 780
    private val maxWeek = 16
    private val currentWeek = 3

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density

    private fun dip(value: Int): Int = (value * density).toInt()

    /** 走 [WeekPickerFragment] 真实的 onCreateView，量成一台手机的宽度、贴到屏幕底边。 */
    private fun render(): View {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        val fragment = WeekPickerFragment.newInstance(maxWeek, currentWeek)
        activity.supportFragmentManager.beginTransaction()
                .add(container.id, fragment)
                .commitNow()

        val root = fragment.view!!
        root.measure(View.MeasureSpec.makeMeasureSpec(dip(screenWidthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dip(screenHeightDp), View.MeasureSpec.AT_MOST))
        assertTrue("面板放不进屏幕高度，出图会被裁", root.measuredHeight <= dip(screenHeightDp))
        root.layout(0, dip(screenHeightDp) - root.measuredHeight, dip(screenWidthDp), dip(screenHeightDp))
        return root
    }

    private fun panelOf(root: View): ViewGroup =
            root.findViewById<ViewGroup>(R.id.base_card_view).getChildAt(0) as ViewGroup

    private fun gridOf(root: View): ViewGroup = root.findViewById(R.id.grid_week)

    private fun chips(root: View): List<TextView> =
            (0 until gridOf(root).childCount).map { gridOf(root).getChildAt(it) as TextView }

    /** 视图相对祖先 [ancestor] 的左上角（逐层累加，直到那一层）。 */
    private fun offsetWithin(view: View, ancestor: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            x += current.left
            y += current.top
            current = current.parent as? View
        }
        return x to y
    }

    private fun toBitmap(root: View): Bitmap {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        root.draw(canvas)
        return bitmap
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 面板铺满宽度且标题与总数都在() {
        val root = render()
        assertEquals("面板没有铺满屏幕宽度", dip(screenWidthDp), root.width)

        val panel = panelOf(root)
        assertEquals("内容没有铺满面板宽度", root.width, panel.width)
        assertEquals("周数标题不对", "周数",
                root.findViewById<TextView>(R.id.tv_week_title).text.toString())
        assertEquals("右上角的周数总数不对", "共 $maxWeek 周",
                root.findViewById<TextView>(R.id.tv_week_total).text.toString())
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 格子一共_maxWeek_个且一行七个() {
        val root = render()
        val grid = gridOf(root)
        assertEquals("格子数量应等于课表的周数", maxWeek, grid.childCount)
        for (week in 1..maxWeek) {
            assertEquals("第 $week 个格子的数字不对", week.toString(),
                    (grid.getChildAt(week - 1) as TextView).text.toString())
        }

        // 一行 7 个：前 7 个顶边相同、第 8 个换行（设计稿 16 周 = 7+7+2 三行）。
        val firstRowTop = grid.getChildAt(0).top
        for (index in 0 until 7) {
            assertEquals("第 ${index + 1} 个格子没和第一个同一行",
                    firstRowTop, grid.getChildAt(index).top)
        }
        assertTrue("第 8 个格子没有换行", grid.getChildAt(7).top > firstRowTop)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 格子是_40x36dp_且留出_6dp_间距() {
        val root = render()
        val grid = gridOf(root)
        val chip = grid.getChildAt(0)
        assertEquals("格子宽度不是 40dp", dip(40), chip.width)
        assertEquals("格子高度不是 36dp", dip(36), chip.height)
        // 设计稿：格子 40dp + 相邻两个各留 3dp = 中心到中心 46dp，一行 7 个正好塞进左右各 20dp。
        assertEquals("相邻两个格子的间距不是 6dp",
                dip(40) + dip(6), grid.getChildAt(1).left - grid.getChildAt(0).left)
        val panelPadding = panelOf(root).paddingLeft
        assertEquals("周次网格没有从内容左边距开始排", dip(20), panelPadding)
        assertEquals("网格没有落在内容左边距上", dip(20), offsetWithin(grid, root).first)
        // 格子自己还有半个间距的外边距（相邻两个各 3dp，合起来正好 6dp）。
        assertEquals("第一个格子和网格左边差了半个间距", dip(3), chip.left)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 当前周是实心强调色而其他周是卡片色() {
        val root = render()
        val bitmap = toBitmap(root)
        val grid = gridOf(root)

        val accent = ContextCompat.getColor(context, R.color.colorPrimary)
        val card = ContextCompat.getColor(context, R.color.card_background)
        for (week in 1..maxWeek) {
            val chip = grid.getChildAt(week - 1)
            val offset = offsetWithin(chip, root)
            // 读格子正中偏下一点：中间可能有数字字形，格子底在下方一定是纯底色。
            val pixel = bitmap.getPixel(offset.first + chip.width / 2, offset.second + chip.height - dip(4))
            val expected = if (week == currentWeek) accent else card
            assertEquals("第 $week 个格子的底色不对（当前周 = 第 $currentWeek 周）",
                    Integer.toHexString(expected), Integer.toHexString(pixel))
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 点一个格子回传的是那一周() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        var picked = 0
        // 回传约定：宿主（ScheduleActivity）就是靠这个 key 拿到用户选的周，再切 ViewPager。
        activity.supportFragmentManager.setFragmentResultListener(
                WeekPickerFragment.REQUEST_KEY, activity) { _, bundle ->
            picked = bundle.getInt(WeekPickerFragment.RESULT_WEEK)
        }

        val fragment = WeekPickerFragment.newInstance(maxWeek, currentWeek)
        activity.supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment)
                .commitNow()
        val root = fragment.view!!
        root.measure(View.MeasureSpec.makeMeasureSpec(dip(screenWidthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dip(screenHeightDp), View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        val fifthWeek = gridOf(root).getChildAt(4) as TextView
        fifthWeek.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("点第 5 周没有回传 5", 5, picked)
        assertTrue("选完应该自己收起面板", fragment.isHidden || fragment.dialog == null ||
                fragment.dialog?.isShowing != true)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 出图_周数面板浅色() = renderToPng("week_picker_light.png")

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 出图_周数面板深色() = renderToPng("week_picker_dark.png")

    private fun renderToPng(name: String) {
        val root = render()
        val dir = File("../../_crop").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { toBitmap(root).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
