package courseclock.timetable

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.*
import courseclock.timetable.today_appwidget.TodayColorfulService
import courseclock.timetable.today_appwidget.TodayCourseAppWidget
import courseclock.timetable.schedule_appwidget.ScheduleAppWidget
import courseclock.timetable.schedule_appwidget.ScheduleAppWidgetService
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.widget.TipTextView
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, qualifiers = "zh-rCN-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetAdaptiveLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database get() = AppDatabase.getDatabase(context)
    private val manager get() = AppWidgetManager.getInstance(context)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private var clock = LocalDateTime.of(LocalDate.now(), LocalTime.of(10, 25))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @After fun closeDatabase() { database.close() }

    private fun seed(font: Int = 12, longText: Boolean = false, count: Int = 3) = runBlocking {
        database.tableDao().clearAllTables()
        database.timeTableDao().clearAllTimeTables()
        database.timeTableDao().insertTimeTable(TimeTableBean(id = 1, name = "尺寸回归"))
        database.timeDetailDao().insertTimeList(listOf("08:15" to "09:35", "09:55" to "11:15",
                "13:00" to "14:30", "15:00" to "16:30").mapIndexed { i, time ->
            TimeDetailBean(node = i + 1, startTime = time.first, endTime = time.second, timeTable = 1)
        })
        database.tableDao().insertTable(TableBean(id = 1, type = 1, tableName = "尺寸回归", timeTable = 1,
                startDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                maxWeek = 30, widgetItemTextSize = font))
        database.courseDao().insertCourses((1..count).map { id ->
            CourseBaseBean(id = id, courseName = if (longText) "智能网联汽车仿真技术与工程应用（实验Ⅱ）" else "课程$id",
                    color = "#2979ff", tableId = 1)
        }, (1..count).flatMap { id -> (1..7).map { day ->
            CourseDetailBean(id = id, day = day, room = if (longText) "现代交通工程实验教学中心8B317" else "B-21$id",
                    teacher = "王老师", startNode = id, step = 1, startWeek = 1, endWeek = 30,
                    type = 0, tableId = 1, timeGroup = "")
        } })
    }

    private fun body(width: Int, height: Int): View {
        shadowOf(manager).bindAppWidgetId(7, ComponentName(context, TodayCourseAppWidget::class.java))
        manager.updateAppWidgetOptions(7, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        })
        val service = Robolectric.buildService(TodayColorfulService::class.java).create().get()
        val factory = service.onGetViewFactory(AppWidgetUtils.dayIntent(context, AppWidgetUtils.dayUri(7)))
                as TodayColorfulService.TodayColorfulRemoteViewsFactory
        factory.nowMillis = { clock }
        factory.onDataSetChanged()
        val (w, h) = factory.emptyCardSizePx()
        return factory.initView(context, 0).apply {
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
        }
    }

    private fun labels(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertBounds(view: View, scenario: String) {
        if (view is TextView && view.visibility == View.VISIBLE && view.text.isNotEmpty()) {
            assertTrue("$scenario 半行裁切：${view.text}",
                    view.layout.height <= view.height - view.paddingTop - view.paddingBottom)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility == View.GONE) continue
            assertTrue("$scenario 越界：${labels(child).map { it.text }}",
                    child.left >= 0 && child.top >= 0 && child.right <= view.width && child.bottom <= view.height)
            assertBounds(child, scenario)
        }
    }

    @Test fun 最小正方形仍保留课名时段教室() {
        seed()
        val view = body(110, 110)
        val texts = labels(view).map { it.text.toString() }
        assertTrue("课名丢失：$texts", texts.contains("课程2"))
        assertTrue("教室丢失：$texts", texts.contains("B-212"))
        assertTrue("时段丢失：$texts", texts.contains("09:55–11:15"))
        assertBounds(view, "110x110")
    }

    @Test fun 课前各尺寸统一蓝色并显示小时或分钟且不截断() {
        seed(count = 1)
        val start = LocalDate.now().atTime(8, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for ((width, height) in listOf(180 to 180, 320 to 150, 400 to 150, 320 to 320)) {
            for ((minutes, expected) in listOf(120 to "约 2.0 小时", 100 to "约 1.7 小时",
                    90 to "约 1.5 小时", 61 to "约 1.0 小时", 60 to "还有60分钟上课", 1 to "还有1分钟上课")) {
                clock = start - minutes * 60_000L
                val view = body(width, height)
                val status = labels(view).single { it.text.toString() == expected }
                assertEquals(androidx.core.content.ContextCompat.getColor(context, R.color.colorPrimary), status.currentTextColor)
                for (line in 0 until status.layout.lineCount) assertEquals(0, status.layout.getEllipsisCount(line))
                assertBounds(view, "$width x $height，$minutes 分钟")
            }
        }
    }

    @Test fun 最窄横条不能截断时段来保留重复日期() {
        seed()
        val view = body(250, 110)
        val time = labels(view).single { it.text.toString() == "09:55–11:15" }
        assertEquals("起止时刻必须完整可见", 0, time.layout.getEllipsisCount(0))
        assertBounds(view, "250x110")
    }

    @Test fun 默认四乘四必须进入大卡形态() {
        assertEquals(TodayColorfulService.DayWidgetForm.Large,
                TodayColorfulService.dayWidgetForm(dp(250), dp(250), context.resources.displayMetrics.density))
    }

    @Test fun 周视图按实例宽度出图而不是按屏幕缩放() {
        seed()
        shadowOf(manager).bindAppWidgetId(8, ComponentName(context, ScheduleAppWidget::class.java))
        val service = Robolectric.buildService(ScheduleAppWidgetService::class.java).create().get()
        val factory = service.onGetViewFactory(Intent(context, ScheduleAppWidgetService::class.java).apply {
            data = Uri.fromParts("content", "1", "8")
        })
        for (width in listOf(250, 320, 420)) {
            manager.updateAppWidgetOptions(8, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
            })
            factory.onDataSetChanged()
            val row = factory.getViewAt(0).apply(context, null)
            val bitmap = (row.findViewById<ImageView>(R.id.iv_schedule).drawable as BitmapDrawable).bitmap
            assertEquals("周视图不能缩放一张屏幕宽截图", dp(width - 32), bitmap.width)
        }
        runBlocking { database.tableDao().clearAllTables() }
        factory.onDataSetChanged()
        assertNull("删除课表后不得继续画旧课表",
                (factory as ScheduleAppWidgetService.ScheduleRemoteViewsFactory).initView())
    }

    @Test fun 周视图不同实例不会复用同一工厂() {
        assertFalse(AppWidgetUtils.weekIntent(context, 1, 7)
                .filterEquals(AppWidgetUtils.weekIntent(context, 1, 8)))
    }

    @Test fun 周课程块完整行与标题地点预算() {
        for (width in listOf(24, 30, 40, 55, 72)) for (height in listOf(40, 56, 110, 170)) {
            val block = TipTextView(context).apply {
                setPadding(dp(2), dp(2), dp(2), dp(2))
                init("高等数学", 12, android.graphics.Color.WHITE, android.graphics.Color.BLUE, 255, android.graphics.Color.WHITE)
                setWidgetContent("高等数学（实验Ⅱ）", "交通工程中心B-211", "09:55", "单周")
                measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
                layout(0, 0, dp(width), dp(height))
            }
            val text = block.widgetTextLayout()
            assertTrue("$width x $height 文字超出课程块", text.height <= block.height - block.paddingTop - block.paddingBottom)
            assertTrue("$width x $height 标题不应被地点挤掉：${text.text}", text.text.contains("高"))
            if (height >= 110) assertTrue("$width x $height 教室尾部编号必须保留：${text.text}", text.text.contains("11"))
        }
    }

    @Test fun 最小空态也不省略结束提示() {
        seed()
        clock = LocalDateTime.of(LocalDate.now(), LocalTime.of(20, 0))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for ((width, height) in listOf(110 to 110, 155 to 174, 250 to 110)) {
            val view = body(width, height)
            val message = labels(view).single { it.text.toString() == "今天的课都上完了" }
            assertBounds(view, "空态$width x $height")
            for (line in 0 until message.layout.lineCount) assertEquals(0, message.layout.getEllipsisCount(line))
        }
    }

    @Test fun 大卡空日历与无课表也遵守文字边界() {
        for (font in listOf(12, 16)) {
            seed(font = font, count = 0)
            for ((width, height) in listOf(110 to 110, 250 to 250, 360 to 360)) {
                assertBounds(body(width, height), "空日历$width x $height 字号$font")
            }
            runBlocking { database.tableDao().clearAllTables() }
            assertBounds(body(110, 110), "无课表110x110")
        }
    }

    @Test fun 周视图部分重叠也不能叠字() = runBlocking {
        seed(longText = true)
        database.courseDao().insertCourses(listOf(CourseBaseBean(id = 10, courseName = "交叠连堂实验",
                color = "#2979ff", tableId = 1)), (1..7).map { day ->
            CourseDetailBean(id = 10, day = day, room = "C208", teacher = "李老师", startNode = 1,
                    step = 2, startWeek = 1, endWeek = 30, type = 0, tableId = 1, timeGroup = "")
        })
        shadowOf(manager).bindAppWidgetId(8, ComponentName(context, ScheduleAppWidget::class.java))
        manager.updateAppWidgetOptions(8, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 360)
        })
        val service = Robolectric.buildService(ScheduleAppWidgetService::class.java).create().get()
        val factory = service.onGetViewFactory(AppWidgetUtils.weekIntent(context, 1, 8))
                as ScheduleAppWidgetService.ScheduleRemoteViewsFactory
        factory.onDataSetChanged()
        val grid = requireNotNull(factory.initView())
        fun check(view: View) {
            if (view !is ViewGroup) return
            val blocks = (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<TipTextView>()
            for ((index, block) in blocks.withIndex()) for (other in blocks.drop(index + 1)) {
                assertFalse("周课程块互相覆盖", block.top < other.bottom && other.top < block.bottom)
            }
            for (index in 0 until view.childCount) check(view.getChildAt(index))
        }
        check(grid)
    }

    @Test fun 系统放大字体仍无半行和越界() {
        try {
            for (scale in listOf(1.1f, 1.3f)) {
                RuntimeEnvironment.setFontScale(scale)
                seed(font = 16, longText = true)
                for ((width, height) in listOf(110 to 110, 160 to 160, 250 to 110, 320 to 110, 250 to 250, 360 to 360)) {
                    assertBounds(body(width, height), "字体比例$scale $width x $height")
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun matrix(theme: String) {
        val sizes = listOf(110 to 110, 160 to 160, 180 to 180, 220 to 220,
                250 to 110, 320 to 110, 360 to 180, 420 to 180,
                250 to 250, 280 to 280, 360 to 360, 320 to 480)
        for (font in listOf(12, 16)) for (long in listOf(false, true)) {
            seed(font, long)
            for ((width, height) in sizes) {
                val view = body(width, height)
                assertBounds(view, "$theme $width x $height 字号$font 长文$long")
                val file = File("build/reports/widget-adaptive/$theme-$width-$height-$font-$long.png")
                requireNotNull(file.parentFile).mkdirs()
                AppWidgetUtils.refreshTodayWidget(context, manager, 7, clock)
                val panel = shadowOf(manager).getViewFor(7)
                panel.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, dp(width), dp(height))
                val list = panel.findViewById<View>(R.id.lv_course)
                val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                list.visibility = View.INVISIBLE
                panel.draw(canvas)
                canvas.save()
                canvas.translate(list.left.toFloat(), list.top.toFloat())
                view.draw(canvas)
                canvas.restore()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun 浅色尺寸矩阵无越界() { matrix("light") }
    @Test @Config(qualifiers = "zh-rCN-night-xhdpi")
    fun 深色尺寸矩阵无越界() { matrix("dark") }
}
