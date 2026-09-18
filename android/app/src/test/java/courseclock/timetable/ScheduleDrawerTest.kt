package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.schedule.ScheduleActivityUI
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
 * 侧栏抽屉的排版契约（2026-09-18 用户逐条反馈后定下）。
 *
 * 三条不许倒退的规则：
 * 1. 抽屉里**没有**「新建课表」这一项 —— 它与底部面板那个按钮是同一件事，留两份入口只会让用户
 *    在两个地方看到同一种行为。新建课表仍然由底部面板提供（`createNewTable()` 有且只有一个实现）。
 * 2. 「关于」在抽屉的**最下方**：它不是导航项，是页脚。靠一个占满剩余高度的弹簧把它压到底，
 *    上面三行怎么增减都不会把它挤上来。
 * 3. 「关于」**不再用导航行的样式**：去掉了 32dp 圆底图标、16dp 圆角胶囊、56dp 行高，只剩
 *    一枚 16dp 线性图标 + 13sp 次要文字色的一行字，居中、行高 48dp。
 *
 * 抽屉是"当前在哪、还能去哪"的地图，页脚是署名 —— 两种语言混在一行里，用户会把「关于」读成
 * 与「设置」平级的第四项。这条区分就是这个文件要钉住的东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleDrawerTest {

    private val context: Context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
            R.style.AppTheme)
    private val density = context.resources.displayMetrics.density

    private fun dip(value: Int): Int = (value * density).toInt()

    private val drawerHeight = 780

    /** 把抽屉按 272dp × 780dp 量一遍（真机上它铺满屏幕高）。 */
    private fun buildDrawer(): ScheduleActivityUI {
        val ui = ScheduleActivityUI(context)
        val drawer = ui.navViewStart
        drawer.measure(View.MeasureSpec.makeMeasureSpec(dip(272), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dip(drawerHeight), View.MeasureSpec.EXACTLY))
        drawer.layout(0, 0, drawer.measuredWidth, drawer.measuredHeight)
        return ui
    }

    private fun childText(view: View): String? =
            (view as? TextView)?.text?.toString()
                    ?: (view as? ViewGroup)?.let { group ->
                        (0 until group.childCount).firstNotNullOfOrNull { childText(group.getChildAt(it)) }
                    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 抽屉里没有新建课表这一项() {
        val ui = buildDrawer()
        assertNull("侧栏不该再有「新建课表」入口",
                ui.navViewStart.findViewById<View>(R.id.nav_row_new_table))
        val labels = (0 until ui.navViewStart.childCount).mapNotNull { childText(ui.navViewStart.getChildAt(it)) }
        assertTrue("抽屉里出现了「新建课表」：$labels", labels.none { it == "新建课表" })
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 关于固定在抽屉最下方() {
        val ui = buildDrawer()
        val drawer = ui.navViewStart
        assertEquals("「关于」必须是抽屉的最后一个子视图",
                ui.navRowAbout, drawer.getChildAt(drawer.childCount - 1))

        val about = ui.navRowAbout
        assertEquals("「关于」没有贴在抽屉底部：${about.bottom} / ${drawer.height}",
                drawer.height - dip(12), about.bottom)
        assertTrue("「关于」跑到上半屏去了：${about.top}",
                about.top > drawer.height / 2)

        // 页脚上面那根细线要挨着它，且只把三行导航项与页脚分开。
        val divider = drawer.getChildAt(drawer.childCount - 2)
        assertEquals("页脚细线没有落在「关于」正上方", about.top - dip(8), divider.bottom)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 关于是页脚而不是第四个导航行() {
        val ui = buildDrawer()
        val about = ui.navRowAbout as LinearLayoutCompat

        assertEquals("页脚行高应该比导航行矮一截", dip(48), about.layoutParams.height)
        assertEquals("页脚应该居中", Gravity.CENTER, about.gravity)
        assertTrue("页脚也要有按下回执", about.background is RippleDrawable)

        assertEquals("页脚只有图标 + 文字两个子视图", 2, about.childCount)
        val icon = about.getChildAt(0) as ImageView
        assertEquals("页脚图标不再是 32dp 的圆底图标", dip(16), icon.layoutParams.width)
        assertEquals("页脚图标不该有圆形底", null, icon.background)

        val label = about.getChildAt(1) as TextView
        assertEquals("关于", label.text.toString())
        assertEquals("页脚文字应比导航行的 15sp 小", 13f, label.textSize / density)
        assertEquals("页脚文字应该是次要文字色",
                context.getResources().getColor(R.color.text_secondary), label.currentTextColor)

        // 对照：导航行仍是 56dp / 32dp 圆底图标 / 15sp —— 页脚"简化"是因为和它比出来的差别。
        val navRow = ui.navRowSetting as LinearLayoutCompat
        assertEquals(dip(56), navRow.layoutParams.height)
        assertEquals(dip(32), (navRow.getChildAt(0) as View).layoutParams.width)
        assertEquals(15f, (navRow.getChildAt(1) as TextView).textSize / density)
    }

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 深色模式下关于仍用次要文字色() {
        val ui = buildDrawer()
        val label = (ui.navRowAbout as LinearLayoutCompat).getChildAt(1) as TextView
        assertEquals(context.getResources().getColor(R.color.text_secondary), label.currentTextColor)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 出图_侧栏抽屉() {
        val ui = buildDrawer()
        val drawer = ui.navViewStart
        val bitmap = Bitmap.createBitmap(drawer.width, drawer.height, Bitmap.Config.ARGB_8888)
        drawer.draw(Canvas(bitmap))
        val file = File("../../_crop/drawer_light.png")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
