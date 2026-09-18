package courseclock.timetable

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.today_appwidget.TodayColorfulService
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.CourseTimes
import courseclock.timetable.utils.DayWidgetSchedule
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 4×2「今日课程」小部件的**整幅画面**渲染与几何断言（浅色 + 深色各出一张 PNG）。
 *
 * 这张卡的观感只在桌面上能看到，改一次就得装机、还得等桌面刷出来；而"整块多高、文字离边多远、
 * 色卡有没有越界"这些恰恰是能数的。所以在单测里把**这条真实路径**跑一遍并出图：
 *
 *   `TodayColorfulService.onGetViewFactory(...).onDataSetChanged()` → 头部那一栏（RemoteViews 布局）
 *   + 每一行（[TodayColorfulService.TodayColorfulRemoteViewsFactory.courseRowBitmap]）
 *
 * 沿用 [DayWidgetHeaderPreviewTest] 的做法（`@GraphicsMode(NATIVE)`、量完再 layout、把 PNG 写到
 * `_crop` 目录）：Robolectric 的 NATIVE 模式下 Canvas 是真的在画，写出来的就是手机会画的样子。
 *
 * 为什么不用"整屏像素"（1220）量：那是修复前的做法 —— 位图比可视区高、还被 fitCenter 缩一次，
 * 于是这一行的字号比头部小一成多。真实路径量的是**这一格**的宽高（见 `emptyCardSizePx`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DayWidgetRenderProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun dip(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** 卡片内边距 / 文字离边的下限，与设计稿的 14dp 一致，也写在 [R.layout.today_course_app_widget] 里。 */
    // HyperOS 美学规范 2.2 的内容安全区：1080p 需 ≥42px（14dp@3x 恰好压线），
    // 2K 需 ≥55px —— 布局用的是 16dp（3x=48px / 3.5x=56px），两档都达标。
    private val insetDp = 16

    /**
     * 4×2 一格按设计稿的可视高度：**整块 176dp**。
     *
     * 桌面按格子算出来的实际像素不一定正好是它（HyperOS 这台是 1077×565 ≈ 201dp 高），所以这个值
     * 在这里的用途是**给内容定一个上限**：头部 + 每一行加起来不许超过它。启动器给得更多时卡片只是
     * 下方多点空，给得更少时列表自己滚动 —— 那部分不由 App 决定，见测试报告里"剩余风险"一段。
     */
    private val cardHeightDp = 176

    /**
     * 这一格的像素宽 × 高，**浅色深色用同一组数**：4×2 那一格在真机上报上来的就是 1077×565
     * （日志 `Launcher.DeviceConfigs: getMiuiWidgetSizeSpec(4, 2, false) = (1077, 565)`），
     * 也就是 AppWidgetUtils / emptyCardSizePx 读 `OPTION_APPWIDGET_MIN_WIDTH` 的那条路。
     *
     * 为什么不用屏幕宽（1080）：那是"启动器没上报尺寸"时的兜底，宽度与高度会被各自缩放一次，
     * 出图与桌上的样子对不上；而且浅色/深色两张图的像素宽本来就不该因为屏幕配置而不同。
     */
    private val cellWidthPx = 1077

    private val cellHeightPx = 565

    /** 内容必须装得下的那个高：4×2 一格按设计稿的 176dp。 */
    private val usableHeightPx: Int by lazy { dip(cardHeightDp) }

    private val fixture: List<FixtureRow> = listOf(
            // 已经上完的一节（08:15-09:35）。"今天"视图会把它剔掉，所以它不该出现在卡片上。
            FixtureRow("高等数学", "A-101", "王强", 1, 2, "#3B7DFF"),
            // 正在上的一节（09:55-11:15）：10:25 时既没结束、也不是"下一节"。
            FixtureRow("线性代数", "B-210", "李娜", 3, 2, "#FF6B35"),
            // 还没开始的一节（11:20-12:00）。
            FixtureRow("大学英语", "C-305", "赵敏", 5, 1, "#2FBF71"))

    private data class FixtureRow(val name: String, val room: String, val teacher: String,
                                  val startNode: Int, val step: Int, val color: String)

    /** 作息：与夹具一致（第 1-2 节连堂、3-4 节连堂、第 5 节单独一节）。 */
    private fun timeDetails(): List<TimeDetailBean> = listOf(
            TimeDetailBean(node = 1, startTime = "08:15", endTime = "09:35", timeTable = 1),
            TimeDetailBean(node = 2, startTime = "08:15", endTime = "09:35", timeTable = 1),
            TimeDetailBean(node = 3, startTime = "09:55", endTime = "11:15", timeTable = 1),
            TimeDetailBean(node = 4, startTime = "09:55", endTime = "11:15", timeTable = 1),
            TimeDetailBean(node = 5, startTime = "11:20", endTime = "12:00", timeTable = 1))

    private fun at(hour: Int, minute: Int): Long =
            LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun seedDatabase() {
        val dataBase = AppDatabase.getDatabase(context)
        // 主键插入 + `AppDatabase` 是单例：先清空（外键 CASCADE 会带走课与上课安排），
        // 否则第二次播种会撞主键，而"撞主键"和"代码算错了"在断言里长得一模一样。
        dataBase.appWidgetDao().deleteAppWidget(1)
        dataBase.tableDao().clearAllTables()
        dataBase.timeTableDao().clearAllTimeTables()
        val tableId = 1
        dataBase.timeTableDao().insertTimeTable(TimeTableBean(id = 1, name = "渲染预览"))
        dataBase.timeDetailDao().insertTimeList(timeDetails())
        dataBase.tableDao().insertTable(TableBean(
                id = tableId,
                tableName = "渲染预览",
                nodes = 10,
                timeTable = 1,
                // 本周一：让"现在是第 3 周"，胶囊里就该写「第3周」。
                startDate = LocalDate.now()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .minusWeeks(2)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                maxWeek = 30,
                type = 1,
                // 打开「显示日视图背景」：色卡走课程自己的颜色（这一版设计要的就是整块色卡）。
                widgetItemAlpha = 60))
        // 课程与它的上课安排靠 `CourseDetailBean.id` = `CourseBaseBean.id` 相连（两张表的主键都含 id，
        // 查询用的是 natural join）。所以这里必须给每门课一个**不同**的 id，并让详情行引用同一个 id：
        // 全写 0 的话，后插入的那门课会把前面的覆盖掉，于是每一行都显示同一个课名 ——
        // 而"课名对不上"和"代码算错了行"在断言里长得一模一样。
        dataBase.courseDao().insertCourses(
                fixture.mapIndexed { index, row ->
                    CourseBaseBean(id = index + 1, courseName = row.name, color = row.color, tableId = tableId)
                },
                // 周一到周日每天都排同样的三节：这一条测试不依赖"今天星期几"。
                (1..7).flatMap { day ->
                    fixture.mapIndexed { index, row ->
                        CourseDetailBean(id = index + 1, day = day, room = row.room, teacher = row.teacher,
                                startNode = row.startNode, step = row.step, startWeek = 1, endWeek = 30,
                                type = 0, tableId = tableId, timeGroup = "")
                    }
                })
    }

    /**
     * 按真实路径造一个行工厂：`content:0` = 今天，时钟固定在 10:25。
     *
     * 10:25 这一刻三种情况齐了：高等数学已上完（被剔掉）、线性代数正在上（09:55-11:15）、
     * 大学英语还没开始（11:20）。所以断言里既能看到"上完的不出现"，也能看到"正在上课"与
     * "下一节几点"这两条状态文案。
     */
    private fun factory(): TodayColorfulService.TodayColorfulRemoteViewsFactory {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val widgetId = org.robolectric.Shadows.shadowOf(manager).createWidget(
                courseclock.timetable.today_appwidget.TodayCourseAppWidget::class.java,
                R.layout.today_course_app_widget)
        manager.updateAppWidgetOptions(widgetId, android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                    (cellWidthPx / context.resources.displayMetrics.density).toInt())
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, cardHeightDp)
        })
        val service = Robolectric.buildService(TodayColorfulService::class.java).create().get()
        // URI 用工程自己的构造器：手写 `"0,$widgetId"` 是旧格式（当初 data 里还带"看哪一天"），
        // 现在 data 只剩实例 id，服务端 `schemeSpecificPart.toIntOrNull()` 会解析失败 →
        // `widgetId` 变成无效值 → `hostBoxPx()` 回落到 provider 默认的 250×110dp。
        // 那样这一格量到的就不是 4×2，断言与出图全都在错尺寸上跑（曾经的 654×144 就是它）。
        val intent = AppWidgetUtils.dayIntent(context, AppWidgetUtils.dayUri(widgetId))
        val created = service.onGetViewFactory(intent)
                as TodayColorfulService.TodayColorfulRemoteViewsFactory
        created.nowMillis = { at(10, 25) }
        created.onDataSetChanged()
        return created
    }

    private fun recursiveTexts(view: View): List<String> {
        val found = ArrayList<String>()
        if (view is TextView) found.add(view.text.toString())
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) found.addAll(recursiveTexts(view.getChildAt(index)))
        }
        return found
    }

    private fun writePng(bitmap: android.graphics.Bitmap, name: String) {
        val file = File("../../_crop/$name")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(outName: String) = runBlocking {
        seedDatabase()
        val factory = factory()
        val (width, height) = factory.emptyCardSizePx()
        val row = factory.initView(context, 0)
        val texts = recursiveTexts(row)
        assertEquals("整块日程只占一个列表项，不能依靠滚动", 1, factory.count)
        assertTrue("当前课程应当可见", texts.any { it.contains("线性代数") })
        assertTrue("时间与地点是两行（设计稿横向长条的中列三行），实际渲染：$texts / 尺寸 ${width}x${height}",
                texts.contains("09:55–11:15") && texts.contains("B-210"))
        assertTrue("状态可见", texts.contains("正在上课"))
        // 先确认"已结束"真的存在，再比先后：原来直接比 indexOfFirst，两处都取不到时是
        // `-1 < -1` 为假反而拦住；而只丢掉"已结束"时变成 `-1 < 2` 照样通过 —— 断言会失守。
        val pastIndex = texts.indexOfFirst { it.startsWith("已结束 ") }
        assertTrue("已结束摘要必须出现：$texts", pastIndex >= 0)
        assertTrue("已结束信息在当前课程前：$texts", pastIndex < texts.indexOfFirst { it.contains("线性代数") })
        assertTrue("下一节可见或明确显示余课数量，实际渲染：$texts / 尺寸 ${width}x${height}",
                texts.any { it.contains("大学英语") || it.contains("另有1节") })
        val bitmap = factory.courseRowBitmap(0, width, height)
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        writePng(bitmap, outName)
        bitmap
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 浅色下整幅画面() {
        render("day_widget_light.png")
    }

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 深色下整幅画面() {
        // 深色只换资源限定符（main_background / card_background / widget_panel_text...），
        // 布局与代码一行都不改，所以几何断言完全相同 —— 它跑得过就说明"深浅色都正常"里的
        // "文字没消失、宽度没变"这一半成立；颜色本身在图里。
        render("day_widget_dark.png")
    }

    /** 顺手钉一句：色卡的颜色就是这门课自己的颜色，与时段一起由同一个夹具驱动。 */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 时段文案与课程颜色一一对应() {
        val times = CourseTimes.of(timeDetails())
        assertEquals("第 5 节那门课的时段应当读得出来", "11:20-12:00",
                DayWidgetSchedule.timeRange(times, courseBean(startNode = 5, step = 1)))
        assertEquals("第 3-4 节连堂的时段应当读得出来", "09:55-11:15",
                DayWidgetSchedule.timeRange(times, courseBean(startNode = 3, step = 2)))
        assertEquals("课名、教室、教师与状态拼成的两行文案",
                "线性代数 · B-210 · 李娜",
                listOf("线性代数", DayWidgetSchedule.roomAndTeacher(courseBean(
                        startNode = 3, step = 2, name = "线性代数", room = "B-210", teacher = "李娜")))
                        .filter { it.isNotEmpty() }.joinToString(" · "))
        assertEquals("已经上完的那节（08:15-09:35）在 10:25 不该留在列表里",
                0, DayWidgetSchedule.remainingToday(times, listOf(courseBean(startNode = 1, step = 2)),
                        { at(10, 25) }).size)
    }

    private fun courseBean(startNode: Int, step: Int, name: String = "课", room: String = "",
                           teacher: String = "", color: String = "#2FBF71") =
            courseclock.timetable.bean.CourseBean(id = 1, courseName = name, day = 1, room = room, teacher = teacher,
                    startNode = startNode, step = step, startWeek = 1, endWeek = 30, type = 0,
                    color = color, tableId = 1, timeGroup = "")
}
