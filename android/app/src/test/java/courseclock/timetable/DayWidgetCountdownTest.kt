package courseclock.timetable

import android.content.Context
import android.net.Uri
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

/**
 * 日视图小部件里那一行「还有 N 分钟下课」。
 *
 * ## 为什么非得有这么个测试
 *
 * `RemoteViewsService.getViewAt` 会把整行**画成一张 bitmap**，文字从此再也读不出来；而这一行
 * 又只在"某节课下课前 20 分钟内"存在。合起来就是：这个功能**只能在真机上、只在那个时间窗口里、
 * 用眼睛**看到一次，看不到时既说不清是"还没到时间"还是"代码没接上"。
 *
 * 所以这里把两个变量都换成可控的：课表用 Room 真库插进去（不是 mock），"现在"由
 * `TodayColorfulRemoteViewsFactory.nowMillis` 固定，然后直接看 [TodayColorfulService] 造出来的
 * 那一行里到底有没有那句话 —— 这正是用户在桌面上会看到的东西，只是绕开了 bitmap 与时间窗口。
 *
 * ## 只写一个测试方法
 *
 * `AppDatabase` 是单例，同一个测试类里各方法共享同一个库；种子数据用主键插入，第二次就会撞主键。
 * 与其在这里加一套清理逻辑，不如把"窗口内 / 窗口打开的那一刻 / 窗口外 / 刚好下课 / 明天视图"
 * 这五种情况放在同一个场景里断言 —— 它们本来就是同一个功能的不同时刻。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class DayWidgetCountdownTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 下课时刻。与 [at] 用的是同一天，所以只有时分会参与计算。 */
    private val endTime = "10:35"

    private fun at(hour: Int, minute: Int): Long =
            LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 递归收集这一行里的全部文字（行本身是一整块画出来的 View 树）。 */
    private fun recursiveTexts(view: View): List<String> {
        val found = ArrayList<String>()
        if (view is TextView && view.text.isNotEmpty()) {
            found.add(view.text.toString())
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                found.addAll(recursiveTexts(view.getChildAt(index)))
            }
        }
        return found
    }

    /** 递归收集这一行里所有形如「还有 N 分钟下课」的文字。 */
    private fun countdownTexts(view: View): List<String> =
            recursiveTexts(view).filter { it.contains("分钟下课") }

    private suspend fun seedDatabase() {
        val dataBase = AppDatabase.getDatabase(context)
        val tableId = 1
        // AppDatabase 是单例：其它用例（稀疏日/渲染探针）可能已插入 id=1，必须先清再种，
        // 否则 ConstraintException 与「代码算错了」在结果里长得不一样但都会让用例红。
        dataBase.tableDao().clearAllTables()
        dataBase.timeTableDao().clearAllTimeTables()
        // 外键：TableBean.timeTable -> TimeTableBean，CourseDetailBean -> CourseBaseBean，
        // Room 默认打开外键，所以三层都要先建出来。
        dataBase.timeTableDao().insertTimeTable(TimeTableBean(id = 1, name = "倒计时用例"))
        dataBase.timeDetailDao().insertTimeList(listOf(
                TimeDetailBean(node = 1, startTime = "08:00", endTime = "08:40", timeTable = 1),
                TimeDetailBean(node = 2, startTime = "08:50", endTime = "09:30", timeTable = 1),
                TimeDetailBean(node = 3, startTime = "09:55", endTime = endTime, timeTable = 1),
                TimeDetailBean(node = 4, startTime = "09:55", endTime = endTime, timeTable = 1)))
        dataBase.tableDao().insertTable(TableBean(
                id = tableId,
                tableName = "倒计时用例",
                nodes = 15,
                timeTable = 1,
                // 本周一：让"现在是第 1 周"，与下面 startWeek/endWeek 覆盖到。
                startDate = LocalDate.now()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                maxWeek = 30,
                type = 1))
        // 周一~周日每天都排同一门课：测试不依赖"今天星期几"。
        // 第 3~4 节两节连上，与本校真实排课形态一致。
        dataBase.courseDao().insertCourses(
                listOf(CourseBaseBean(id = 0, courseName = "倒计时用例",
                        color = "#ff2979ff", tableId = tableId)),
                (1..7).map { day ->
                    CourseDetailBean(id = 0, day = day, room = "B210", teacher = "测试",
                            startNode = 3, step = 2, startWeek = 1, endWeek = 30,
                            type = 0, tableId = tableId, timeGroup = "")
                })
        dataBase.courseDao().insertCourses(
                (1..2).map { id -> CourseBaseBean(id = id, courseName = "已上课程$id",
                        color = "#ff2979ff", tableId = tableId) },
                (1..7).flatMap { day -> (1..2).map { id ->
                    CourseDetailBean(id = id, day = day, room = "A101", teacher = "测试",
                            startNode = id, step = 1, startWeek = 1, endWeek = 30,
                            type = 0, tableId = tableId, timeGroup = "")
                } })
    }

    /**
     * 造一个行工厂。data 就是 `AppWidgetUtils.refreshTodayWidget` 交给 RemoteViews 的那个 URI ——
     * 箭头删除后它只剩实例身份（`AppWidgetUtils.dayUri`），"今天/明天"不再由它区分。
     */
    private fun factory(now: Long = at(10, 25), widgetHeightDp: Int = 176): TodayColorfulService.TodayColorfulRemoteViewsFactory {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val widgetId = org.robolectric.Shadows.shadowOf(manager).createWidget(
                courseclock.timetable.today_appwidget.TodayCourseAppWidget::class.java,
                R.layout.today_course_app_widget)
        manager.updateAppWidgetOptions(widgetId, android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, widgetHeightDp)
        })
        val service = Robolectric.buildService(TodayColorfulService::class.java).create().get()
        val intent = android.content.Intent(context, TodayColorfulService::class.java).apply {
            data = Uri.fromParts("content", widgetId.toString(), null)
        }
        val created = service.onGetViewFactory(intent)
                as TodayColorfulService.TodayColorfulRemoteViewsFactory
        // 工厂不会自己加载数据：真实运行时由 RemoteViews 框架在第一次出图前调 onDataSetChanged。
        // 测试里把这一步显式做掉，否则 table / courseList 还是空的。
        // **时钟必须先定好**：onDataSetChanged 要按"现在"判定哪节是当前/下一节（强调色卡），
        // 用真实时钟跑这一步，断言会随跑测试的时刻漂移。
        // （库是 allowMainThreadQueries 建的，所以这里可以同步读。）
        created.nowMillis = { now }
        created.onDataSetChanged()
        return created
    }

    /** 换一个"现在"，并按真实刷新路径重新取数（「下一节」的判定在这里发生）。 */
    private fun TodayColorfulService.TodayColorfulRemoteViewsFactory.setNow(millis: Long) {
        nowMillis = { millis }
        onDataSetChanged()
    }

    @Test
    fun countdownLineAppearsOnlyInTheLastTwentyMinutes() = runBlocking {
        seedDatabase()
        val factory = factory()

        // 窗口内：10:25 → 10:35 还有 10 分钟。
        // 断言"恰好一行带这句话"，不要求整行等于这句话。
        //
        // 口径已按设计稿改：倒计时那一行是**大号数字 + 「分钟下课」两行**，不再是
        // 一整句「还有 N 分钟下课」。所以这里断言的是标签那行，数字另测（见下一条）。
        factory.setNow(at(10, 25))
        assertCountdown(factory.initView(context, 0), "分钟下课")
        val combined = factory.initView(context, 0)
        val texts = recursiveTexts(combined)
        assertTrue("大号数字必须单独出现（设计稿的倒计时是数字 + 标签两行）: $texts",
                texts.contains("10"))
        assertTrue("最后一课上方应补充已结束摘要: $texts",
                texts.any { it.startsWith("已结束 ") && it.contains(" 节 · ") })
        // 口径已按设计稿改：窄形态里「教室」与「时段」是**两行**（原先并成 `时段 · 教室` 一行），
        // 且时段用 en dash。断言的含义不变：当前课的时间与教室一个字都不能丢。
        assertTrue("当前课程的时段必须保留: $texts", texts.contains("09:55–10:35"))
        assertTrue("当前课程的教室必须保留: $texts", texts.contains("B210"))
        val (width, height) = factory.emptyCardSizePx()
        combined.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("摘要不能挤出当前课程", combined.measuredHeight <= height)
        val compactTexts = recursiveTexts(factory(widgetHeightDp = 100).initView(context, 0))
        // 摘要的文案已按设计稿改成 `已结束 N 节 · 名称`，所以前缀判定也要跟着改 ——
        // 原来判 `已结束 ·` 在改文案之后会**永远成立**（摘要没被移除也照样过），等于失守。
        assertTrue("空间不足时先让出历史摘要: $compactTexts",
                compactTexts.none { it.startsWith("已结束 ") })
        assertTrue("空间不足仍优先保留当前课的时段与教室: $compactTexts",
                compactTexts.contains("09:55–10:35") && compactTexts.contains("B210"))

        // 窗口打开的那一刻：整 20 分钟数字第一次出现。
        factory.setNow(at(10, 15))
        assertCountdown(factory.initView(context, 0), "分钟下课")

        // 窗口之外一分钟：21 分钟 → 不写。这一行与改动前逐像素一致。
        factory.setNow(at(10, 14))
        assertEquals(emptyList<String>(), countdownTexts(factory.initView(context, 0)))

        // 最后一分钟：10:34 → 还有 1 分钟，课还在列表里。
        factory.setNow(at(10, 34))
        assertCountdown(factory.initView(context, 0), "分钟下课")
        assertEquals(1, factory.visibleCourseCount())

        // 正好下课：`remaining` 立刻清空，倒计时那句话自然也没了。
        // 边界只有一分钟之差，写反了就会在真机上看到"刚下课那节还亮着强调色卡"。
        factory.setNow(at(10, 35))
        assertEquals("下课时立即移除课程", 0, factory.visibleCourseCount())
        assertEquals("全部结束后保留一行空状态", 1, factory.count)
        // 全部上完给的是**空态**（设计稿 `今天的课都上完了`），不是把最后一节已结束的课留在那里。
        val done = factory.initView(context, 0)
        val doneTexts = recursiveTexts(done)
        assertTrue("全部上完后应当显示空态文案：$doneTexts", doneTexts.contains("今天的课都上完了"))
        assertTrue("空态不该再出现任何课程：$doneTexts", doneTexts.none { it.contains("倒计时用例") })
    }

    /** 这一行里应当**恰好有一行**含 [phrase]，且不含别的倒计时。 */
    private fun assertCountdown(view: View, phrase: String) {
        val lines = countdownTexts(view)
        assertEquals("带倒计时的行数不对：$lines", 1, lines.size)
        assertTrue("倒计时那句话不对：$lines", lines[0].contains(phrase))
    }
}
