package courseclock.timetable

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.today_appwidget.TodayColorfulService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 空状态卡片的尺寸：**必须等于列表可视区**，不能是写死的 88dp。
 *
 * 写死 88dp 时，卡片比可视区矮一截，`Gravity.CENTER` 就在那个偏矮的框里居中 —— 图标和
 * 「今天的课都上完了」整体偏上（用户原话："应该往下移一点"）。所以这里钉住两件事：
 * 卡片尺寸跟着**同一份布局**量出来的可视区走；并且一定比那个旧的写死值高。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-xxhdpi")
class DayWidgetEmptyCardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun dip(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun factory(widgetId: Int = 7): TodayColorfulService.TodayColorfulRemoteViewsFactory {
        val service = Robolectric.buildService(TodayColorfulService::class.java).create().get()
        val intent = Intent(context, TodayColorfulService::class.java).apply {
            data = Uri.fromParts("content", "0,$widgetId", null)
        }
        return service.onGetViewFactory(intent)
                as TodayColorfulService.TodayColorfulRemoteViewsFactory
    }

    @Test
    fun 空状态卡片等于列表可视区() {
        // 4×2 这一格在这台机器上的真实像素：
        // 日志 `Launcher.DeviceConfigs: getMiuiWidgetSizeSpec(4, 2, false) = (1077, 565)`。
        val (width, height) = factory().emptyCardSizePx(1077, 565)

        // 独立再量一遍：卡片必须正好是"小部件扣掉头部和内边距之后"留给列表的那块可视区。
        val root = LayoutInflater.from(context).inflate(R.layout.today_course_app_widget, null)
        root.measure(View.MeasureSpec.makeMeasureSpec(1077, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(565, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1077, 565)
        val list = root.findViewById<View>(R.id.lv_course)
        assertEquals("卡片宽度应当等于列表可视区宽度", list.width, width)
        assertEquals("卡片高度应当等于列表可视区高度", list.height, height)

        // 旧的写死值：88dp。比它矮就说明内容还是会被摆在偏上的位置。
        assertTrue("空状态卡片只有 ${height}px，不高于旧的 88dp（${dip(88)}px），图标和文字还是会偏上",
                height > dip(88))
        println("空状态卡片 ${width}x${height}px；旧写死值 ${dip(88)}px；" +
                "内容中心由 ${dip(88) / 2}px 下移到 ${height / 2}px")
    }
}
