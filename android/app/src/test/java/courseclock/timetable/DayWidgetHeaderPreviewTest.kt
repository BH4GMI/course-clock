package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 日视图小部件头部那一栏的**实测预览**（不是断言型测试，出图给人看 + 钉住两条硬指标）。
 *
 * 用户要求「缩短『9月15日 第1周 周二 ‹』这一栏的 y 轴空间，但文字不要太靠近边框」。
 * 这种事在真机上看一次要装机、还得等桌面刷出来；这里直接把布局按 4×2 小部件的真实像素
 * 量一遍、画成 PNG，头部吃掉多少高度、文字离边多远都是数出来的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DayWidgetHeaderPreviewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun dip(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 头部高度与左边距() {
        val view = LayoutInflater.from(context).inflate(R.layout.today_course_app_widget, null)
        // 与 refreshTodayWidget 一致（设计稿 `.head`）：4×2 横条写「今日日程」，另一头是课程计数。
        view.findViewById<TextView>(R.id.tv_date).text = "今日日程"
        view.findViewById<TextView>(R.id.tv_summary).text = "3节 · 已上1节"

        // 4×2 小部件在这台机器上的实际像素：宽 ≈ 4 格，高 ≈ 2 格。
        val width = 1100
        val height = 420
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)

        val flipper = view.findViewById<View>(R.id.lv_course)
        val date = view.findViewById<TextView>(R.id.tv_date)
        File("../../_crop").mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xff2A2F3A.toInt())
        view.draw(canvas)
        FileOutputStream(File("../../_crop/day_widget_header.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        println("日视图小部件头部：到课程列表 ${flipper.top}px = " +
                "${flipper.top / context.resources.displayMetrics.density}dp；" +
                "日期左边距 ${date.left}px = ${date.left / context.resources.displayMetrics.density}dp；" +
                "日期行高 ${date.height}px")

        // 「文字不要太靠近边框」：左边留白 = 内边距 + 文字自身的外边距。
        assertTrue("日期文字离左边只有 ${date.left}px，太贴边了", date.left >= dip(12))
        // 「缩短 y 轴空间」：头部（到课程列表为止）不该再超过 46dp。
        assertTrue("头部高度 ${flipper.top}px ≈ ${flipper.top / context.resources.displayMetrics.density}dp，还是太高",
                flipper.top <= dip(46))
    }

    /**
     * 预览图（`previewImage`）的生成搬到了 [WidgetPreviewImageTest]。
     *
     * 搬走的原因不是"分文件好看"：这里原来直接 inflate 布局就画，而小部件正文是一个**空的
     * `ListView`**（内容由宿主的 `RemoteViewsService` 填，选择器连不上），于是画出来的
     * `previewImage` 只有表头一行字、正文全空。生成预览图现在需要"真实 RemoteViews + 真实正文
     * 位图"两步合成，那已经超出"头部几何"这个类的范围，单独成类。
     */
}
