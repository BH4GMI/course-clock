package courseclock.timetable

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.today_appwidget.TodayColorfulService
import courseclock.timetable.today_appwidget.TodayCourseAppWidget
import courseclock.timetable.utils.AppWidgetUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 日视图小部件**多实例**的身份。
 *
 * `RemoteViewsService` 的工厂由系统按 `Intent.FilterComparison` 缓存复用，而 FilterComparison
 * 只比较 action / data / type / identifier / package / component / categories —— **extras 不参与
 * 比较**。所以实例 id 只放在 extras 里时，两个日视图会被当成同一个服务请求：第二个实例拿到的
 * 是第一个实例的工厂，格子尺寸、`cellSizeCache`、算好的行槽位全是别人的。桌面上看到的就是
 * "两个日视图一大一小，小的那个一直按大的画"。
 *
 * 这两条测试钉住的正是那个判据本身：实例 id 必须进 **data**，且两个实例的 intent 在
 * `filterEquals` 下必须不相等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-xxhdpi")
class DayWidgetInstanceIdentityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createWidget(widthDp: Int, heightDp: Int): Int {
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(
                TodayCourseAppWidget::class.java, R.layout.today_course_app_widget)
        shadowOf(manager).bindAppWidgetId(id, android.content.ComponentName(context, TodayCourseAppWidget::class.java))
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        })
        return id
    }

    /** 模拟系统只按 data URI 找到服务时，服务为这个实例算出来的列表可视区。 */
    private fun cellSizeOf(appWidgetId: Int): Pair<Int, Int> {
        val service = Robolectric.buildService(TodayColorfulService::class.java).create().get()
        val intent = AppWidgetUtils.dayIntent(
                context, AppWidgetUtils.dayUri(appWidgetId))
        val factory = service.onGetViewFactory(intent)
                as TodayColorfulService.TodayColorfulRemoteViewsFactory
        return factory.emptyCardSizePx()
    }

    @Test
    fun twoInstancesOfTheSameDayAreNotTheSameServiceRequest() {
        val today = AppWidgetUtils.dayUri(appWidgetId = 1)
        val otherInstance = AppWidgetUtils.dayUri(appWidgetId = 2)

        assertFalse(
                "同一天的两个实例必须产生不同的 data，否则 extras 不参与 filterEquals，" +
                        "系统会把它们合并成同一个工厂",
                AppWidgetUtils.dayIntent(context, today)
                        .filterEquals(AppWidgetUtils.dayIntent(context, otherInstance)))
    }

    @Test
    fun eachInstanceMeasuresItsOwnCell() {
        val small = createWidget(widthDp = 180, heightDp = 90)
        val large = createWidget(widthDp = 360, heightDp = 180)

        val smallCell = cellSizeOf(small)
        val largeCell = cellSizeOf(large)

        assertNotEquals("小格子不能被按大格子的尺寸画", smallCell, largeCell)
    }
}
