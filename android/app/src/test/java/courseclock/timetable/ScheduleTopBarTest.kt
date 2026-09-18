package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.schedule.ScheduleActivityUI
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 课表主界面顶栏（大标题 + 副标题 + 三个圆钮 + 右下角加号）的几何与出图。
 *
 * 顶栏是这一屏唯一"永远看得见"的东西，所以它的三条契约值得钉住：副标题三段必须排在**一行**
 * （原来散在两行，白白吃掉纵向空间）、圆钮必须同高（差一像素就像没对齐）、加号必须落在右下角
 * 且不许压住顶栏。改完视觉先看图（[出图_顶栏]），别拿真机当画板。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleTopBarTest {

    private val context: Context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
            R.style.AppTheme)
    private val density = context.resources.displayMetrics.density

    private fun dip(value: Int): Int = (value * density).toInt()

    /** 顶栏的文字在真机上由 ScheduleActivity 填（日期、周几、第几周），这里照同一套填。 */
    private fun buildTopBar(): ScheduleActivityUI {
        val ui = ScheduleActivityUI(context)
        ui.dateView.text = "9月15日"
        ui.weekDayView.text = "周一"
        ui.weekView.text = "第3周"
        val content = ui.content
        content.measure(View.MeasureSpec.makeMeasureSpec(dip(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dip(780), View.MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        return ui
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 标题在副标题上方且两行左对齐() {
        val ui = buildTopBar()
        val title = ui.content.findViewById<View>(R.id.anko_tv_title)
        val date = ui.content.findViewById<View>(R.id.anko_tv_date)
        assertEquals("副标题的左沿必须和大标题对齐", title.left, date.left)
        assertTrue("副标题跑到标题上面去了：${date.top} vs ${title.top}", date.top >= title.top)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 副标题三段排成一行() {
        val ui = buildTopBar()
        val date = ui.content.findViewById<TextView>(R.id.anko_tv_date)
        val weekday = ui.content.findViewById<TextView>(R.id.anko_tv_weekday)
        val week = ui.content.findViewById<TextView>(R.id.anko_tv_week)
        assertEquals("日期和星期要在同一条基线上", date.baseline, weekday.baseline)
        assertEquals("星期和第几周要在同一条基线上", weekday.baseline, week.baseline)
        assertTrue("副标题的顺序应该是 日期 → 周几 → 第几周",
                date.left < weekday.left && weekday.left < week.left)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 三个圆钮同高同大且不与标题重叠() {
        val ui = buildTopBar()
        val nav = ui.content.findViewById<View>(R.id.anko_ib_nav)
        val importBtn = ui.content.findViewById<View>(R.id.anko_ib_import)
        val share = ui.content.findViewById<View>(R.id.anko_ib_share)
        val more = ui.content.findViewById<View>(R.id.anko_ib_more)
        for (button in listOf(importBtn, share, more)) {
            assertEquals("圆钮应该是 36dp 见方", dip(36), button.width)
            assertEquals("圆钮没有对齐", nav.top, button.top)
            assertEquals("圆钮没有对齐", nav.bottom, button.bottom)
        }
        assertTrue("三个圆钮压住了标题", ui.content.findViewById<View>(R.id.anko_tv_title).right <= importBtn.left)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 加号在右下角且不压顶栏() {
        val ui = buildTopBar()
        val add = ui.content.findViewById<TextView>(R.id.anko_ib_add)
        assertEquals("加号应该是 56dp 见方", dip(56), add.width)
        assertTrue("加号没在右半边：${add.left}", add.left > ui.content.width / 2)
        assertTrue("加号没在下半边：${add.top}", add.top > ui.content.height / 2)
        assertTrue("加号压住了顶栏", add.top > ui.content.findViewById<View>(R.id.anko_tv_title).bottom)
        // 品牌色圆底上的"+"必须是浅色（设计稿就是品牌色底 + 白字）。这里钉"浅色那一侧"而不是
        // 一个对比度数字：品牌色 #fa6278 对白字只有 2.96，本来就够不到 3.0 的非文字目标 ——
        // 那是品牌色本身的上限（设计稿的 #3482FF 同样是 2.9）。真正要防的是顶栏那套
        // 课表文字色漏到加号上（浅色模式下就是黑底黑字）。
        assertTrue("加号应该是浅色（品牌色底 + 白字）：实际 ${Integer.toHexString(add.currentTextColor)}",
                ColorUtils.calculateLuminance(add.currentTextColor) > 0.5)
    }

    /** 课表从副标题下面开始：中间既不许空出一条，也不许压住副标题。 */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 课表紧接副标题() {
        val ui = buildTopBar()
        val pager = ui.content.findViewById<View>(R.id.anko_vp_schedule)
        val subtitle = ui.content.findViewById<View>(R.id.anko_tv_week)
        assertEquals("课表与副标题之间出现了空当或重叠", subtitle.bottom, pager.top)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 出图_顶栏() = renderTopBar("topbar_light.png", 0xffffffff.toInt())

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 出图_顶栏_深色() = renderTopBar("topbar_dark.png", 0xff2A2F3A.toInt())

    private fun renderTopBar(outName: String, backdrop: Int) {
        val ui = buildTopBar()
        val height = dip(150)
        val bitmap = Bitmap.createBitmap(ui.content.width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backdrop)
        canvas.save()
        canvas.clipRect(0, 0, ui.content.width, height)
        // 顶栏的文字颜色在真机上由 ScheduleActivity.initTheme 按课表底色定，这里手动对齐一次。
        val ink = if (backdrop == 0xffffffff.toInt()) 0xff000000.toInt() else 0xffffffff.toInt()
        for (view in listOf(ui.titleView, ui.dateView, ui.weekDayView, ui.weekView,
                ui.importBtn, ui.shareBtn, ui.moreBtn)) {
            view.setTextColor(ink)
        }
        ui.navBtn.setColorFilter(ink)
        ui.content.draw(canvas)
        canvas.restore()
        val file = File("../../_crop/$outName")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
