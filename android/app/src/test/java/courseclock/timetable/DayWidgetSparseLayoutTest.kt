package courseclock.timetable

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.*
import courseclock.timetable.today_appwidget.TodayColorfulService
import courseclock.timetable.today_appwidget.TodayCourseAppWidget
import courseclock.timetable.utils.AppWidgetUtils
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 三种形态、中间尺寸与稀疏数据均走 Android 原生测量/绘制，不用 HTML 冒充产品渲染。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DayWidgetSparseLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database get() = AppDatabase.getDatabase(context)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun at(value: String): Long = LocalDateTime.of(LocalDate.now(), LocalTime.parse(value))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun seed(count: Int, font: Int = 12, longText: Boolean = false,
                             missingRoom: Boolean = false, missingTimes: Boolean = false) {
        database.tableDao().clearAllTables()
        database.timeTableDao().clearAllTimeTables()
        database.timeTableDao().insertTimeTable(TimeTableBean(id = 1, name = "形态验证"))
        if (!missingTimes) database.timeDetailDao().insertTimeList(listOf(
                "08:15" to "09:35", "09:55" to "11:15", "13:00" to "14:30", "15:00" to "16:30"
        ).mapIndexed { i, pair -> TimeDetailBean(node = i + 1, startTime = pair.first, endTime = pair.second, timeTable = 1) })
        database.tableDao().insertTable(TableBean(id = 1, tableName = "形态验证", nodes = 10, timeTable = 1,
                startDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                maxWeek = 30, type = 1, widgetItemTextSize = font))
        if (count > 0) database.courseDao().insertCourses(
                (1..count).map { CourseBaseBean(id = it, courseName = if (longText) "智能网联汽车仿真技术与工程应用" else listOf("高等数学", "线性代数", "大学英语", "工程力学")[it - 1],
                        color = "#2979ff", tableId = 1) },
                (1..count).flatMap { id -> (1..7).map { day ->
                    CourseDetailBean(id = id, day = day, room = if (missingRoom) "" else if (longText) "现代交通工程中心8B317" else "B-21$id",
                            teacher = "王老师", startNode = id, step = 1, startWeek = 1, endWeek = 30, type = 0, tableId = 1, timeGroup = "")
                } })
    }

    private fun factory(id: Int, clock: String): TodayColorfulService.TodayColorfulRemoteViewsFactory {
        val service = Robolectric.buildService(TodayColorfulService::class.java).create().get()
        return (service.onGetViewFactory(AppWidgetUtils.dayIntent(context, AppWidgetUtils.dayUri(id)))
                as TodayColorfulService.TodayColorfulRemoteViewsFactory).apply {
            nowMillis = { at(clock) }
            onDataSetChanged()
        }
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun bounds(view: View, name: String) {
        if (view is TextView && view.text.isNotEmpty()) {
            assertTrue("$name 文字高度不足：${view.text}, ${view.height}, ${view.layout?.height}",
                    (view.layout?.height ?: 0) <= view.height - view.paddingTop - view.paddingBottom)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            assertTrue("$name 子项越界 ${texts(child)} top=${child.top} bottom=${child.bottom} parent=${view.height}",
                    child.top >= 0 && child.left >= 0 && child.bottom <= view.height && child.right <= view.width)
            bounds(child, name)
        }
    }

    private fun renderMatrix(theme: String) = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(TodayCourseAppWidget::class.java, R.layout.today_course_app_widget)
        val sizes = listOf(220 to 220, 360 to 180, 360 to 360, 180 to 180, 280 to 280, 420 to 180, 320 to 480)
        // 场景只由「几门课 + 现在几点」两个变量描述。原先还带第三个字段表示"这一格是明天视图"，
        // 但**明天视图已经不存在了**：日视图改为永远显示今天，表头与正文都不再有"明天"状态
        // （箭头随之删除）。那个字段留着只会让下面几条断言继续按一套不存在的状态判定。
        val scenarios = listOf(
                1 to "07:00", 1 to "08:30", 1 to "09:25",
                2 to "07:00", 2 to "08:30", 2 to "09:45", 2 to "11:05",
                3 to "07:00", 3 to "08:30", 3 to "10:25", 3 to "13:15",
                3 to "17:00", 0 to "10:25", 3 to "10:25",
                4 to "10:25", 1 to "08:30", 2 to "10:25",
                2 to "10:25", 0 to "10:25", 0 to "10:25")
        var checked = 0
        for (font in listOf(12, 16)) for ((scenario, data) in scenarios.withIndex()) {
            seed(data.first, font, longText = scenario == 15, missingRoom = scenario == 16, missingTimes = scenario == 17)
            // 18 = 连课表都没有（空库），19 = 有课表但今天没课。这两条空态的文案必须不同。
            if (scenario == 18) database.tableDao().clearAllTables()
            for ((width, height) in sizes) {
                manager.updateAppWidgetOptions(id, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                })
                val factory = factory(id, data.second)
                val (w, h) = factory.emptyCardSizePx()
                assertEquals(1, factory.count)
                val body = factory.initView(context, 0)
                body.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                body.layout(0, 0, w, h)
                val name = "$theme $scenario $width x $height 字号$font"
                bounds(body, name)
                val copy = texts(body)
                assertFalse("$name 不该出现空态以外的文案：$copy",
                        copy.any { it.contains("给明天留") || it.contains("没有课哦") })
                if (scenario == 19) assertTrue("$name 缺「今天没有课」：$copy", copy.contains("今天没有课"))
                if (scenario == 18) assertTrue("$name 缺「还没有课表」：$copy", copy.contains("还没有课表"))
                if (scenario == 19) assertFalse("$name 今天没课，不该出现倒计时/已结束：$copy",
                        copy.any { it.contains("分钟下课") || it.contains("已结束") })
                if (scenario == 9 && width == 360 && height == 360) {
                    assertTrue(copy.indexOf("高等数学") < copy.indexOf("线性代数"))
                    assertTrue(copy.contains("正在上课"))
                }
                // 11 = 三门课全在 14:30 前上完，17:00 打开：今天是"全部上完"的空态。
                if (scenario == 11) {
                    assertTrue("$name 全部上完要给空态：$copy", copy.contains("今天的课都上完了"))
                    // 「整体居中」：正文块的上下留白之差不超过 1px（除不尽时的取整）。
                    // 只测上留白会让"内容堆在顶上"这种错悄悄过去，所以要两头一起量。
                    val group = body as ViewGroup
                    val first = (0 until group.childCount).firstOrNull { group.getChildAt(it).height > 0 }
                    val last = (group.childCount - 1 downTo 0).firstOrNull { group.getChildAt(it).height > 0 }
                    if (first != null && last != null) {
                        val top = group.getChildAt(first).top
                        val bottom = group.height - group.getChildAt(last).bottom
                        assertTrue("$name 空态没有整体居中：上留白 ${top}px、下留白 ${bottom}px",
                                Math.abs(top - bottom) <= 1)
                    }
                }
                if (font == 12 && width in listOf(220, 360)) {
                    // 表头按设计稿：横条（宽格）写「今日日程」+ 课程计数；小正方形写日期 + 计数。
                    // 出图是给人看的，所以这里照渲染时该有的值写，而不是留一串占位文字。
                    val panel = LayoutInflater.from(context).inflate(R.layout.today_course_app_widget, null)
                    panel.findViewById<TextView>(R.id.tv_date).text =
                            if (width >= 250) "今日日程" else "9月18日 · 周五"
                    panel.findViewById<TextView>(R.id.tv_summary).text = "3节 · 已上1节"
                    panel.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
                    panel.layout(0, 0, dp(width), dp(height))
                    val list = panel.findViewById<View>(R.id.lv_course)
                    val result = Bitmap.createBitmap(dp(width), dp(height), Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(result)
                    panel.draw(canvas)
                    canvas.save()
                    canvas.translate(list.left.toFloat(), list.top.toFloat())
                    body.draw(canvas)
                    canvas.restore()
                    val file = File("../../_crop/morphology-$theme-$scenario-$width-$height.png")
                    requireNotNull(file.parentFile).mkdirs()
                    file.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    result.recycle()
                }
                checked++
            }
        }
        println("原生布局检查 $theme：$checked 组")
    }

    @Test fun 浅色多场景原生布局() { renderMatrix("light") }
    @Test @Config(qualifiers = "zh-rCN-night-xhdpi")
    fun 深色多场景原生布局() { renderMatrix("dark") }

    @Test fun 实际高度不能擅自抬高且同实例改变尺寸后重算() = runBlocking {
        seed(1)
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(TodayCourseAppWidget::class.java, R.layout.today_course_app_widget)
        val factory = factory(id, "08:30")
        for (height in listOf(110, 180, 360)) {
            manager.updateAppWidgetOptions(id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            })
            factory.onDataSetChanged()
            assertEquals(factory.emptyCardSizePx(dp(360), dp(height)), factory.emptyCardSizePx())
        }
    }

    @Test fun 删除默认课表后同一工厂不保留旧课程() = runBlocking {
        seed(3)
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(TodayCourseAppWidget::class.java, R.layout.today_course_app_widget)
        val factory = factory(id, "10:25")
        assertTrue(factory.visibleCourseCount() > 0)
        database.tableDao().clearAllTables()
        factory.onDataSetChanged()
        assertEquals(0, factory.visibleCourseCount())
        assertEquals(listOf("还没有课表"), texts(factory.initView(context, 0)))
    }
}
