package courseclock.timetable

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.schedule_appwidget.ScheduleAppWidget
import courseclock.timetable.schedule_appwidget.ScheduleAppWidgetService
import courseclock.timetable.today_appwidget.SmallTodayCourseAppWidget
import courseclock.timetable.today_appwidget.TodayColorfulService
import courseclock.timetable.today_appwidget.TodayCourseAppWidget
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.appScope
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 生成选择器里的**预览图**（`previewImage`）：`day_widget_preview` / `small_day_widget_preview`
 * / `week_widget_preview`，直接写回 `res/drawable-nodpi/`。
 *
 * ## 为什么上一版是错的
 *
 * 上一版也是"从真实布局渲染"，但它 inflate 完就直接画 —— 而小部件的正文在真实布局里是一个
 * **空的 `ListView`**，内容由 `RemoteViewsService` 在宿主侧填充。选择器不连这个 Service，
 * 于是画出来只有表头一行字、正文一片空白（`today_course_app_widget_info.xml` 里那句
 * "选择器不能拿空 ListView 作预览"说的就是这件事，上一版正好踩了）。
 *
 * ## 这一版怎么合成
 *
 * 两步都取**真实产物**，不手绘、不照抄常量：
 *
 * 1. 表头与整卡框架：调真实的 `AppWidgetUtils.refresh*()`，再让 Robolectric 的
 *    `ShadowAppWidgetManager.getViewFor()` 把那份 `RemoteViews` 真的 inflate 出来 ——
 *    表头文案、配色、形态判断因此全部来自生产代码，测试里一个字都不重复。
 * 2. 正文：向真实的 `RemoteViewsService` 工厂要那一张正文位图
 *    （日视图 `courseRowBitmap`、周视图 `getViewAt(0)` 里 `iv_schedule` 的那张图）。
 *
 * 然后把框架里那个空 `ListView` 换成装着正文位图的 `ImageView`（同一个位置、同一份
 * LayoutParams），再按整卡像素量出来画成 PNG。合成出来的东西与桌面上小部件看到的**同一份**。
 *
 * ## 尺寸
 *
 * 密度取 xxxhdpi（4x），于是"官方建议的 4 倍最小尺寸"正好落在整数上：
 * 4×2 日视图 250×110dp → 1000×440；2×2 日视图 110×110dp → 440×440；4×4 周视图
 * 250×250dp → 1000×1000。**每个 provider 一张**：2×2 曾经直接复用 4×2 那张，
 * 形状（比例 2:1 对 1:1）和内容都不对。
 *
 * ## 浅色与深色各一套
 *
 * `previewImage` 是 `@drawable` 引用，会按 `-night` 限定符解析，所以深色模式必须有自己的
 * 一套图 —— 三个 provider 都只声明 `previewImage`（详情页托管 `previewLayout` 时桌面会给
 * 预览控件挂"打开应用"的点击，见 today_course_app_widget_info.xml 顶部说明），于是更没有
 * 退回正式布局的余地。两套图都从同一份真实布局渲染，只是限定符不同。
 *
 * ## 示例数据
 *
 * 固定三门课、时间钉在 10:25（第 2 节正在进行），所以四张图的内容每次都一样，
 * 差别只在形态 —— 预览要展示的是"这个尺寸下长什么样"，不是某一天的课。
 * **表头与正文共用这一个时刻**：表头走 `refreshTodayWidget` 的 `now` 参数，正文走工厂的
 * `nowMillis`；只钉住一个就会出现「3节 · 已上3节」配「正在上课」这种图里自相矛盾的状态。
 * `assertHeaderMatchesBody` 把这条不变量钉住。
 *
 * ## 一条 Robolectric 侧的噪声（不是缺陷）
 *
 * `ShadowAppWidgetManager.createWidgets/updateAppWidgetOptions` 是**直接调用** provider 回调的
 * （字节码里就是 `AppWidgetProvider.onReceive` / `onAppWidgetOptionsChanged`），没有真实的广播
 * 分派，于是 `BroadcastReceiver.getPendingResult()` 是 null —— 生产代码里
 * `goAsync { … }` 的 `finally { result.finish() }` 会在后台线程抛 NPE 并打到 stderr。
 * 真机上 `AppWidgetProvider` 的回调一律由 `onReceive` 分派而来，`goAsync()` 必定有结果，
 * 所以这条只出现在测试里，属于"绕过了框架"的假象，不去为它加生产侧的判空分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPreviewImageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density

    /** 钉死的"现在"：第 2 节（09:55–11:15）正在进行，于是预览里同时有已结束、正在上、接下来。 */
    private val now: Long =
            LocalDateTime.of(LocalDate.now(), LocalTime.parse("10:25"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun seedSample() {
        // 先等 `Application.onCreate` 那批启动协程落盘：它们也在 appScope 里写同一个库，
        // 不等的话，本方法关库 + 删文件会正撞在它们的写入上（实测爆 UNIQUE / 后台线程异常）。
        awaitPendingRefreshes()
        // `AppDatabase` 是静态单例，而 Robolectric 的沙箱按 SDK 复用、数据目录每个用例都是新的：
        // 单例里留着的旧连接会让"清空 + 重新插入 id=1"撞 UNIQUE。按 `LegacyDb.seed` 的既有做法，
        // 先关掉单例再删库文件，让 `getDatabase` 重新走一遍建库 —— 这也让重复执行本用例结果稳定。
        val existing = AppDatabase.getDatabase(context)
        if (existing.isOpen) existing.close()
        context.getDatabasePath("wakeup").delete()
        val database = AppDatabase.getDatabase(context)
        runBlocking {
            database.tableDao().clearAllTables()
            database.timeTableDao().clearAllTimeTables()
            database.timeTableDao().insertTimeTable(TimeTableBean(id = 1, name = "示例作息"))
            database.timeDetailDao().insertTimeList(listOf(
                    "08:15" to "09:35", "09:55" to "11:15",
                    "13:00" to "14:30", "15:00" to "16:30"
            ).mapIndexed { i, pair ->
                TimeDetailBean(node = i + 1, startTime = pair.first, endTime = pair.second, timeTable = 1)
            })
            database.tableDao().insertTable(TableBean(
                    id = 1, tableName = "我的课表", nodes = 10, timeTable = 1,
                    startDate = LocalDate.now()
                            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                    maxWeek = 20, type = 1, widgetItemTextSize = 12))
            val names = listOf("高等数学", "线性代数", "大学英语")
            database.courseDao().insertCourses(
                    names.mapIndexed { i, name ->
                        CourseBaseBean(id = i + 1, courseName = name, color = "#2979ff", tableId = 1)
                    },
                    names.indices.flatMap { i ->
                        (1..7).map { day ->
                            CourseDetailBean(id = i + 1, day = day, room = "B-21${i + 1}", teacher = "王老师",
                                    startNode = i + 1, step = 1, startWeek = 1, endWeek = 20,
                                    type = 0, tableId = 1, timeGroup = "")
                        }
                    })
        }
    }

    private fun options(cellWidthDp: Int, cellHeightDp: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, cellWidthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, cellWidthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, cellHeightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, cellHeightDp)
    }

    /**
     * 把整卡里的空列表换成装着 [body] 的 `ImageView`，再按 [listId] 原来的 LayoutParams 摆好。
     *
     * 这是"预览"与"真机"唯一的差别：真机上列表由宿主的 `RemoteViewsService` 连上来填，
     * 选择器里连不上，所以这里直接把它该显示的那张位图放进去。
     */
    private fun compose(card: View, listId: Int, body: Bitmap, widthPx: Int, heightPx: Int): Bitmap {
        val group = card as ViewGroup
        val list = group.findViewById<View>(listId)
        val parent = list.parent as ViewGroup
        val params = list.layoutParams
        parent.removeView(list)
        parent.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(body)
        }, params)

        card.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, widthPx, heightPx)
        // 不铺背景：圆角以外留透明，选择器自己合成到桌面/预览底色上。
        return Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                .also { card.draw(Canvas(it)) }
    }

    /**
     * 等 [appScope] 里已经启动的小部件刷新跑完。
     *
     * 改尺寸会触发 provider 的 `onAppWidgetOptionsChanged`，它用 `goAsync` 在 appScope
     * （`Dispatchers.IO`）里异步把这个实例重画一遍，**而且用的是真实时钟**。不等它跑完，
     * 它就会盖掉紧接着那次"钉住时刻"的渲染 —— 表头于是显示真实时刻的「已上 N」，
     * 与正文对不上。这不是等待超时，是等一个已知的、已经启动的协程结束。
     */
    private fun awaitPendingRefreshes() {
        val job = appScope.coroutineContext[Job] ?: return
        runBlocking { job.children.toList().forEach { it.join() } }
    }

    /**
     * 表头（`tv_summary` 的「N 节 · 已上 M」）必须与**同一时刻**装载出来的正文同源。
     *
     * 这条断言来自一次真实事故：`updateAppWidgetOptions` 触发的异步重画会按真实时钟再画一遍，
     * 于是预览图上出现「3节 · 已上3节」配「正在上课」。断言让它一旦回归就当场失败，而不是
     * 悄悄生成一张自相矛盾的图。
     */
    private fun assertHeaderMatchesBody(card: View) {
        val summary = card.findViewById<TextView>(R.id.tv_summary).text.toString()
        if (summary.isEmpty()) return
        val day = TodayColorfulService.loadDay(context, now)
        assertTrue("表头「$summary」与同一时刻的装载结果对不上（已上${day.completed.size}节）",
                summary.endsWith("已上${day.completed.size}节"))
    }

    /** 日视图（4×2 与 2×2 共用一份实现，只是 provider 与目标格数不同）。 */
    private fun dayPreview(provider: Class<out AppWidgetProvider>, cellWidthDp: Int,
                           cellHeightDp: Int): Bitmap {
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(provider, R.layout.today_course_app_widget)
        manager.updateAppWidgetOptions(id, options(cellWidthDp, cellHeightDp))
        awaitPendingRefreshes()
        AppWidgetUtils.refreshTodayWidget(context, manager, id, now)

        val service = Robolectric.buildService(TodayColorfulService::class.java).create().get()
        val factory = service.onGetViewFactory(
                AppWidgetUtils.dayIntent(context, AppWidgetUtils.dayUri(id)))
                as TodayColorfulService.TodayColorfulRemoteViewsFactory
        factory.nowMillis = { now }
        factory.onDataSetChanged()
        val (bodyWidth, bodyHeight) = factory.emptyCardSizePx()
        val body = factory.courseRowBitmap(0, bodyWidth, bodyHeight)

        val (cardWidth, cardHeight) = TodayColorfulService.cardBoxPx(context, id)
        val card = shadowOf(manager).getViewFor(id)
        assertHeaderMatchesBody(card)
        return compose(card, R.id.lv_course, body, cardWidth, cardHeight)
    }

    /** 周视图。正文是"整周课表"那一张位图（`ScheduleAppWidgetService` 把它整个画成一张图）。 */
    private fun weekPreview(cellWidthDp: Int, cellHeightDp: Int): Bitmap {
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(ScheduleAppWidget::class.java, R.layout.schedule_app_widget)
        manager.updateAppWidgetOptions(id, options(cellWidthDp, cellHeightDp))
        awaitPendingRefreshes()
        val table = AppDatabase.getDatabase(context).tableDao().getDefaultTableSync()!!
        AppWidgetUtils.refreshScheduleWidget(context, manager, id, table)

        val service = Robolectric.buildService(ScheduleAppWidgetService::class.java).create().get()
        val factory = service.onGetViewFactory(AppWidgetUtils.weekIntent(context, table.id, id))
        factory.onCreate()
        factory.onDataSetChanged()
        val item = factory.getViewAt(0).apply(context, null)
        val body = (item.findViewById<ImageView>(R.id.iv_schedule).drawable as BitmapDrawable).bitmap

        val card = shadowOf(manager).getViewFor(id)
        card.measure(View.MeasureSpec.makeMeasureSpec((cellWidthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((cellHeightDp * density).toInt(), View.MeasureSpec.EXACTLY))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val list = card.findViewById<View>(R.id.lv_schedule)
        val visible = Bitmap.createBitmap(body.width, minOf(body.height, list.height), Bitmap.Config.ARGB_8888)
        Canvas(visible).drawBitmap(body, 0f, 0f, null)
        return compose(card, R.id.lv_schedule, visible,
                (cellWidthDp * density).toInt(), (cellHeightDp * density).toInt())
    }

    private fun write(dir: String, name: String, bitmap: Bitmap) {
        val file = File("src/main/res/$dir/$name.png")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("预览图 $dir/$name.png = ${bitmap.width}×${bitmap.height}")
    }

    /** 三个 provider 的预览图各渲染一张，写进 [dir]（浅色 `drawable-nodpi`／深色 `drawable-night-nodpi`）。 */
    private fun renderAll(dir: String) {
        write(dir, "day_widget_preview", dayPreview(TodayCourseAppWidget::class.java, 250, 110))
        write(dir, "small_day_widget_preview", dayPreview(SmallTodayCourseAppWidget::class.java, 110, 110))
        write(dir, "week_widget_preview", weekPreview(250, 250))
    }

    /**
     * 两套图在**同一个用例**里生成：切限定符只切一次配置，播种与选课表用同一份数据。
     * 拆成两个用例反而会踩 `AppDatabase` 静态单例跨用例复用（沙箱按 SDK 复用，数据目录却
     * 每个用例都是新的），第二个用例拿到的是上一个用例留下的死连接。
     */
    @Test
    @Config(qualifiers = "zh-rCN-xxxhdpi")
    fun 生成三种形态的小部件预览图() {
        seedSample()
        renderAll("drawable-nodpi")
        RuntimeEnvironment.setQualifiers("+night")
        renderAll("drawable-night-nodpi")
    }
}
