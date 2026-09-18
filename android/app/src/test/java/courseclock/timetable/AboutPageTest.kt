package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import courseclock.timetable.intro.AboutActivity
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 关于页的排版与文案契约（2026-09-18 用户要求"排版和文案要更加正式"）。
 *
 * "正式"不是形容词，落成四条可检查的规则：
 * 1. 四个节标题（使用说明 / 权限说明 / 开源许可）存在，口语标题（怎么用 / 应用会用到）不许回潮；
 * 2. 四张卡片的圆角与内边距读同一对 token —— 以前第一张 24dp、后两张 20dp，同页并排像没对齐；
 * 3. 开源声明是一张带节标题的卡（Apache-2.0 第 4 条要求的署名），不是页面底部的居中灰字；
 * 4. 版权行与仓库根目录 NOTICE 的署名一致。
 *
 * 出图（[出图_关于页]）是这一页的主观部分唯一的验收方式：字号层级、留白、分隔线是否成立，
 * 只能看图，不能靠断言描述。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutPageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density

    private fun dip(value: Int): Int = (value * density).toInt()

    /**
     * 关于页的内容根（`android.R.id.content` 的第一个子视图）。
     *
     * 不取 decorView：那样连状态栏留白一起量进来，图的顶上也多一条系统栏 —— 这一页要看的正是
     * 它自己的留白。
     */
    private fun contentRoot(activity: AboutActivity): View =
            activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun launch(heightDp: Int = 780): AboutActivity {
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val root = contentRoot(activity)
        root.measure(View.MeasureSpec.makeMeasureSpec(dip(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dip(heightDp), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return activity
    }

    private fun <T> collect(root: View, type: Class<T>): List<T> {
        val out = mutableListOf<T>()
        fun walk(view: View) {
            if (type.isInstance(view)) out += type.cast(view)!!
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun texts(activity: AboutActivity): List<String> =
            collect(contentRoot(activity), TextView::class.java).mapNotNull { it.text?.toString() }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 文案是书面口径而不是口语() {
        val activity = launch()
        val drawn = texts(activity)

        for (heading in listOf("使用说明", "权限说明")) {
            assertTrue("关于页缺少节标题「$heading」：$drawn", drawn.contains(heading))
        }
        for (colloquial in listOf("怎么用", "应用会用到", "开机自动恢复", "开源许可")) {
            assertTrue("口语化的旧文案回潮了：$colloquial", !drawn.contains(colloquial))
        }
        for (sentence in listOf(
                "在「导入课程」中选择「从学校教务导入」。",
                "在弹出的页面中登录学校 WebVPN。",
                "返回课表页面，点击右下角按钮获取课程。")) {
            assertTrue("步骤应是完整陈述句：「$sentence」", drawn.contains(sentence))
        }
        assertTrue("权限说明要写清用途，而不是只写权限名：$drawn",
                drawn.contains("仅用于从学校教务系统导入课表数据")
                        && drawn.contains("用于在课程开始前发送上课提醒")
                        && drawn.contains("用于在设备重启后重新安排课程提醒"))
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 三张卡片的圆角与内边距完全一致() {
        val activity = launch()
        val cards = collect(contentRoot(activity), MaterialCardView::class.java)
        assertEquals("关于页只该有三张卡片：应用信息 / 使用说明 / 权限说明", 3, cards.size)

        val expected = activity.resources.getDimensionPixelSize(R.dimen.about_card_radius)
        for (card in cards) {
            assertEquals("卡片圆角必须读 about_card_radius", expected.toFloat(), card.radius, 0.5f)
        }

        val inset = activity.resources.getDimensionPixelSize(R.dimen.about_card_inset)
        for (card in cards) {
            val content = card.getChildAt(0) as View
            assertEquals("卡片内边距必须读 about_card_inset（左边）", inset, content.paddingLeft)
            assertEquals("卡片内边距必须读 about_card_inset（右边）", inset, content.paddingRight)
        }
    }

    /**
     * 全页一条分隔线都没有。
     *
     * 用户原话：「关于中分的栏太多了」。上一版的解法是"每个栏目一张卡 + 卡里再画细线"，
     * 结果四张卡 + 四个标题 + 三条 1dp 细线，同页并排像一张被切碎的表格。现在只留三张卡、
     * 两个节标题，栏目之间与权限三行之间都靠留白分开。
     *
     * 判据是"页面里有没有裸 `android.view.View`"：本工程的细线一律写成
     * `<View android:layout_height="1dp" android:background="@color/list_divider" />`，
     * 而卡片、文字、图标分别是 MaterialCardView / TextView / ImageView，都不会命中。
     * 谁再把线加回来，这条就红。
     */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 全页没有分隔线只有留白() {
        val activity = launch()
        val dividers = collect(contentRoot(activity), View::class.java)
                .filter { it.javaClass == View::class.java }
        assertEquals("关于页又出现了分隔线（${dividers.size} 条），栏目应该靠留白分开", 0, dividers.size)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 项目地址写在应用信息卡里且可点() {
        val activity = launch()
        val project = activity.findViewById<TextView>(R.id.tv_project)
        assertTrue("应用信息卡里没有项目地址", project != null)
        assertEquals(activity.getString(R.string.about_project), project.text.toString())
        assertTrue("项目地址里没有仓库地址：${project.text}", project.text.contains("github.com/BH4GMI/course-clock"))

        // autoLink=web 会把它变成 ClickableSpan；没有链接掩码说明 autoLink 掉了，用户点不动。
        val spannable = project.text as? android.text.Spannable
        assertTrue("项目地址不可点（autoLink 没生效）",
                spannable != null && spannable.getSpans(0, spannable.length,
                        android.text.style.ClickableSpan::class.java).isNotEmpty())

        // 它必须真的在应用信息卡（第一张卡）里 —— 那是用户找"这东西哪来的"时会看的地方。
        val first = collect(contentRoot(activity), MaterialCardView::class.java).first()
        assertTrue("项目地址不在应用信息卡里", collect(first, TextView::class.java).contains(project))
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 开源署名收进页脚但内容完整() {
        val activity = launch()
        val drawn = texts(activity)
        assertTrue("开源署名仍然找不到：$drawn",
                drawn.any { it.contains("Cerbur/WakeupSchedule_Kotlin") && it.contains("Apache License 2.0") })
        assertTrue("缺少版权行（署名必须与仓库根目录 NOTICE 一致）：$drawn",
                drawn.contains("© 2026 The CourseClock Project"))
        assertTrue("缺少免费声明：$drawn", drawn.any { it.contains("完全免费") })
        assertTrue("「开源许可」不该再单独占一节", !drawn.contains("开源许可"))

        // 署名不在卡片里：它是页脚。放回卡片就等于又加了一栏，正是用户要去掉的东西。
        val cards = collect(contentRoot(activity), MaterialCardView::class.java)
        for (card in cards) {
            assertTrue("版权行不该在卡片里",
                    collect(card, TextView::class.java).none { it.text?.toString() == "© 2026 The CourseClock Project" })
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 版本号仍然显示在应用信息卡里() {
        val activity = launch()
        val version = activity.findViewById<TextView>(R.id.tv_version)
        assertTrue("版本号没写进去：${version?.text}", version?.text?.toString()?.startsWith("版本 ") == true)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 出图_关于页() = render("about_page_light.png")

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 出图_关于页_深色() = render("about_page_dark.png")

    private fun render(outName: String) {
        // 出图用比屏幕高的画布：这一页要整页看（四张卡的间距与分节是否匀称），
        // 按屏幕高截一半就看不到下半张的排版。
        val activity = launch(heightDp = 1560)
        val root = contentRoot(activity)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../../_crop/$outName")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
