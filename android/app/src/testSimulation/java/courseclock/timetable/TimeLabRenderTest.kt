package courseclock.timetable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import courseclock.timetable.testing.TimeLabActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimeLabRenderTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun narrowScreenContainsCompleteControls() = checkLayout("narrow")

    @Test @Config(qualifiers = "zh-rCN-w640dp-h360dp-mdpi")
    fun landscapeControlsRemainScrollable() = checkLayout("landscape")

    private fun checkLayout(name: String) {
        val controller = Robolectric.buildActivity(TimeLabActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val metrics = activity.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        val views = children(root)
        assertEquals(86399, views.filterIsInstance<SeekBar>().single().max)
        assertEquals(9, views.filterIsInstance<Spinner>().single().count)
        assertTrue(views.any { it.contentDescription == "播放" })
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "恢复真实时间" })
        views.filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach {
            val layout = it.layout
            assertNotNull("文字必须完成布局：${it.text}", layout)
            for (line in 0 until layout.lineCount) {
                assertEquals("文字不得省略：${it.text}", 0, layout.getEllipsisCount(line))
                assertTrue("文字不得横向溢出：${it.text}", layout.getLineWidth(line) <= it.width - it.compoundPaddingLeft - it.compoundPaddingRight + 1)
            }
        }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(androidx.core.content.ContextCompat.getColor(activity, R.color.page_background))
        root.draw(Canvas(bitmap))
        val file = File("build/reports/time-lab/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }
}
