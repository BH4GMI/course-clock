package courseclock.timetable.utils

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import courseclock.timetable.AppDatabase
import courseclock.timetable.R
import courseclock.timetable.bean.TableBean
import courseclock.timetable.schedule_appwidget.ScheduleAppWidget
import courseclock.timetable.schedule_appwidget.ScheduleAppWidgetService
import courseclock.timetable.today_appwidget.SmallTodayCourseAppWidget
import courseclock.timetable.today_appwidget.TodayColorfulService
import courseclock.timetable.today_appwidget.TodayCourseAppWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程级 fire-and-forget 作用域：不随任何界面生命周期取消（提醒重排这类"进程活着就要跑完"
 * 的后台活不能挂在 Activity 的 scope 上）。收口成一处而不是各处裸起
 * `CoroutineScope`/`GlobalScope`，崩溃日志才能归因到同一来源。
 */
val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 日视图的两个 provider：4×2「当天课程」与 2×2「当天课程（小）」。
 *
 * 两者共用同一份实现与渲染，只有**默认格数**不同 —— 桌面按 provider 决定"添加小部件时摆多大"
 * （`targetCellWidth/Height` 与 `minWidth/minHeight`），而一个 provider 只有一个默认尺寸，
 * 所以"选择器里能直接放一个小正方形"只能靠第二个 provider，见
 * `small_today_course_app_widget_info.xml` 的说明。
 *
 * 枚举实例时两个都要列上：枚举器只有这一份，漏一个就会出现"改了课表只有大的那个刷新、
 * 小的还挂着旧课"。
 */
internal val DAY_WIDGET_PROVIDERS = listOf(
        TodayCourseAppWidget::class.java,
        SmallTodayCourseAppWidget::class.java)

/**
 * 广播接收器的 goAsync 协程化。默认作用域是 [appScope]（SupervisorJob + IO）
 * 而不是 GlobalScope：行为不变（goAsync 的 10 秒预算内 Room 操作绰绰有余），
 * 但 fire-and-forget 收进了有主的结构，崩溃日志可归因。
 */
fun BroadcastReceiver.goAsync(
        coroutineScope: CoroutineScope = appScope,
        block: suspend () -> Unit
) {
    val result = goAsync()
    coroutineScope.launch {
        try {
            block()
        } finally {
            // Always call finish(), even if the coroutineScope was cancelled
            result.finish()
        }
    }
}

/**
 * PendingIntent flags。API 31 起 targetSdk≥31 必须声明可变性；本工程 targetSdk 29，
 * 但 minSdk 21 上 FLAG_IMMUTABLE 不存在，按版本给。提醒与小部件共用。
 */
fun pendingIntentFlags(base: Int = PendingIntent.FLAG_UPDATE_CURRENT): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            base or PendingIntent.FLAG_IMMUTABLE
        } else {
            base
        }

object AppWidgetUtils {
    private val daysArray = arrayOf("日", "一", "二", "三", "四", "五", "六", "日")

    /**
     * 把桌面上实际存在的两个小部件全部重画一遍。课表增删改、切表、改课表设置之后都调这里。
     *
     * **不能用 `ACTION_APPWIDGET_UPDATE` 广播**：它是受保护广播，只有系统能发，显式指定组件也不行。
     * 真机上（Android 16 / API 36）用 App 自己的 uid 发，系统直接拒：
     *
     * ```
     * W ActivityManager: Permission Denial: not allowed to send broadcast
     *     android.appwidget.action.APPWIDGET_UPDATE from unknown caller.
     *     at com.android.server.am.BroadcastController.broadcastIntentLockedTraced(BroadcastController.java:1133)
     * ```
     *
     * 旧实现正是靠这个广播（`updateWidget`），发的人拿不到异常、系统那边只留一行警告，
     * 所以它一直静默失效而没人发现。原生唯一可用的入口是 [AppWidgetManager.updateAppWidget]，
     * 也就是两个 provider 在 `onUpdate` 里做的事。
     *
     * 「刷新谁」一律用 [AppWidgetManager.getAppWidgetIds] 枚举平台实例：自建的 `AppWidgetBean`
     * 表只有周视图配置页走完才写入一行，实例还没登记进去时它是空的，拿它当实例清单会漏刷新
     * （真机上抓到过 0 行、实例一个都刷不到）。
     */
    suspend fun refreshAllWidgets(context: Context) {
        refreshScheduleWidgets(context)
        refreshTodayWidgets(context)
    }

    /**
     * 重画桌面上所有的周视图小部件。
     *
     * 实例清单以平台为准（见 [refreshAllWidgets]）；`AppWidgetBean` 只提供附加信息「这个实例配的是
     * 哪张课表」：有登记就按登记的课表画，查不到则回退默认课表；全无课表时明确显示空状态。
     */
    suspend fun refreshScheduleWidgets(context: Context) {
        val dataBase = AppDatabase.getDatabase(context)
        val manager = AppWidgetManager.getInstance(context)
        val configured = dataBase.appWidgetDao().getWidgetsByTypes(0, 0).associateBy { it.id }
        val ids = LinkedHashSet<Int>(configured.keys)
        ids.addAll(manager.getAppWidgetIds(ComponentName(context, ScheduleAppWidget::class.java)).toList())
        if (ids.isEmpty()) return

        val defaultTable = dataBase.tableDao().getDefaultTable()
        for (id in ids) {
            val table = resolveScheduleTable(dataBase, configured[id]?.info, defaultTable)
            refreshScheduleWidget(context, manager, id, table)
        }
    }

    /**
     * 重画**一个**周视图实例。
     *
     * 宿主（桌面）改了这个实例的尺寸时走这里。周视图是一整张位图（[ScheduleAppWidgetService]
     * 的 `getCount() == 1`，整周画进 `iv_schedule`），不重画的话格子尺寸会一直停在拖动前那一份，
     * 用户看到的是拉伸变形或糊掉的字。
     *
     * 只重画被改的那一个实例，不走 [refreshScheduleWidgets]：那会把桌面上所有周视图都重排一遍
     * （每个实例都要查库 + 画整周位图），改一个尺寸不该付这个代价。
     *
     * 取表口径与 [refreshScheduleWidgets] 共用 [resolveScheduleTable]，不另写一套。
     */
    suspend fun refreshScheduleWidgetFor(context: Context, appWidgetId: Int) {
        val dataBase = AppDatabase.getDatabase(context)
        val info = dataBase.appWidgetDao().getWidgetsByTypes(0, 0)
                .firstOrNull { it.id == appWidgetId }?.info
        val table = resolveScheduleTable(dataBase, info, dataBase.tableDao().getDefaultTable())
        refreshScheduleWidget(context, AppWidgetManager.getInstance(context), appWidgetId, table)
    }

    /**
     * 「这个周视图实例显示哪张课表」：有登记就按登记的课表，查不到（登记为空 / 坏值 / 课表已删）
     * 一律回退默认表；连默认表都没有时返回 null，由渲染层清除旧内容并显示无课表状态。
     *
     * 与 [ScheduleAppWidgetService.onDataSetChanged] 的取表口径对齐 —— 只回退一头会造成
     * 「表头画旧表、列表画默认表」的分裂；坏值不能让 `toInt()` 把整轮刷新打断。
     */
    private suspend fun resolveScheduleTable(dataBase: AppDatabase, info: String?,
                                             defaultTable: TableBean?): TableBean? =
            if (info.isNullOrEmpty()) {
                defaultTable
            } else {
                info.toIntOrNull()?.let { dataBase.tableDao().getTableById(it) } ?: defaultTable
            }

    /**
     * 重画桌面上所有的日视图小部件。日视图画的一律是默认课表，而且**一律是今天**。
     *
     * [widgetId] 非空时只重画那一个实例。原先这里还有一个 `nextDay` 参数（连同每个实例的
     * "在看哪一天"登记）：右上角箭头删除后，"看明天"这个状态不存在了。
     */
    suspend fun refreshTodayWidgets(context: Context, widgetId: Int? = null) {
        val dataBase = AppDatabase.getDatabase(context)
        val manager = AppWidgetManager.getInstance(context)
        // 实例清单同样以平台为准（见 refreshAllWidgets）。**两个日视图 provider 都要枚举**：
        // 2×2「当天课程（小）」是独立的 provider（桌面按 provider 决定默认格数，见其 info 的注释），
        // 漏掉一个就会出现"改了课表只有大的那个刷新、小的还挂着旧课"。
        val ids = LinkedHashSet<Int>(dataBase.appWidgetDao().getWidgetsByTypes(0, 1).map { it.id })
        for (provider in DAY_WIDGET_PROVIDERS) {
            ids.addAll(manager.getAppWidgetIds(ComponentName(context, provider)).toList())
        }
        for (id in ids) {
            if (widgetId == null || id == widgetId) {
                refreshTodayWidget(context, manager, id)
            }
        }
    }

    fun refreshScheduleWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, tableBean: TableBean?) {
        val mRemoteViews = RemoteViews(context.packageName, R.layout.schedule_app_widget)
        WidgetTheme.color(mRemoteViews, context, R.id.tv_date, "setTextColor", R.color.widget_panel_text)
        WidgetTheme.color(mRemoteViews, context, R.id.tv_week, "setTextColor", R.color.widget_panel_text_secondary)
        mRemoteViews.setTextViewText(R.id.tv_date, CourseUtils.getTodayDate())
        if (tableBean == null) {
            mRemoteViews.setTextViewText(R.id.tv_week, context.getString(R.string.table_missing))
            mRemoteViews.setViewVisibility(R.id.weekName, View.GONE)
            mRemoteViews.setViewVisibility(R.id.lv_schedule, View.GONE)
            appWidgetManager.updateAppWidget(appWidgetId, mRemoteViews)
            return
        }
        mRemoteViews.setViewVisibility(R.id.weekName, View.VISIBLE)
        mRemoteViews.setViewVisibility(R.id.lv_schedule, View.VISIBLE)
        // 表头文字必须跟底板主题（widget_panel_text），不能用 TableBean.widgetTextColor
        var week = safeCountWeek(tableBean.startDate, tableBean.sundayFirst)
        val date = CourseUtils.getTodayDate()
        val weekDay = CourseUtils.getWeekday()
        mRemoteViews.setTextViewTextSize(R.id.tv_date, TypedValue.COMPLEX_UNIT_SP, tableBean.widgetItemTextSize.toFloat() + 2)
        mRemoteViews.setTextViewTextSize(R.id.tv_week, TypedValue.COMPLEX_UNIT_SP, tableBean.widgetItemTextSize.toFloat())
        mRemoteViews.setTextViewText(R.id.tv_date, date)
        if (tableBean.tableName.isEmpty()) {
            tableBean.tableName = "我的课表"
        }
        if (week > 0) {
            mRemoteViews.setTextViewText(R.id.tv_week, "${tableBean.tableName} | 第${week}周    $weekDay")
        } else {
            mRemoteViews.setTextViewText(R.id.tv_week, "${tableBean.tableName} | 还没有开学哦")
            week = 1
        }

        if (tableBean.showSun) {
            if (tableBean.sundayFirst) {
                mRemoteViews.setViewVisibility(R.id.tv_title7, View.GONE)
                mRemoteViews.setViewVisibility(R.id.tv_title0_1, View.VISIBLE)
            } else {
                mRemoteViews.setViewVisibility(R.id.tv_title7, View.VISIBLE)
                mRemoteViews.setViewVisibility(R.id.tv_title0_1, View.GONE)
            }
        } else {
            mRemoteViews.setViewVisibility(R.id.tv_title7, View.GONE)
            mRemoteViews.setViewVisibility(R.id.tv_title0_1, View.GONE)
        }

        if (tableBean.showSat) {
            mRemoteViews.setViewVisibility(R.id.tv_title6, View.VISIBLE)
        } else {
            mRemoteViews.setViewVisibility(R.id.tv_title6, View.GONE)
        }

        val weekDate = CourseUtils.getDateStringFromWeek(
                maxOf(safeCountWeek(tableBean.startDate, tableBean.sundayFirst), 1),
                week, tableBean.sundayFirst)
        WidgetTheme.color(mRemoteViews, context, R.id.tv_title0, "setTextColor", R.color.widget_panel_text_secondary)
        mRemoteViews.setTextViewTextSize(R.id.tv_title0, TypedValue.COMPLEX_UNIT_SP, tableBean.widgetItemTextSize.toFloat())
        mRemoteViews.setTextViewText(R.id.tv_title0, weekDate[0] + "\n月")
        mRemoteViews.setTextViewText(R.id.tv_date, date)

        val day = CourseUtils.getWeekdayInt()

        if (tableBean.sundayFirst) {
            for (i in 0..6) {
                // 今天那一列用强调色，其余用次级色：这是设计稿「今天列表头 = 强调色」的口径，
                // 也和日视图一致（TodayColorfulService 用同一个 colorPrimary）。
                // 不用"全对比 vs 60% 透明"来表达今天 —— 那在深色底上几乎看不出差别。
                if (i == day || (i == 0 && day == 7)) {
                    WidgetTheme.color(mRemoteViews, context, R.id.tv_title0_1 + i, "setTextColor", R.color.colorPrimary)
                } else {
                    WidgetTheme.color(mRemoteViews, context, R.id.tv_title0_1 + i, "setTextColor", R.color.widget_panel_text_secondary)
                }
                mRemoteViews.setTextViewTextSize(R.id.tv_title0_1 + i, TypedValue.COMPLEX_UNIT_SP, tableBean.widgetItemTextSize.toFloat())
                mRemoteViews.setTextViewText(R.id.tv_title0_1 + i, daysArray[i] + "\n${weekDate[i + 1]}")
            }
        } else {
            for (i in 0..6) {
                if (i == day - 1) {
                    WidgetTheme.color(mRemoteViews, context, R.id.tv_title1 + i, "setTextColor", R.color.colorPrimary)
                } else {
                    WidgetTheme.color(mRemoteViews, context, R.id.tv_title1 + i, "setTextColor", R.color.widget_panel_text_secondary)
                }
                mRemoteViews.setTextViewTextSize(R.id.tv_title1 + i, TypedValue.COMPLEX_UNIT_SP, tableBean.widgetItemTextSize.toFloat())
                mRemoteViews.setTextViewText(R.id.tv_title1 + i, daysArray[i + 1] + "\n${weekDate[i + 1]}")
            }
        }
        val lvIntent = weekIntent(context, tableBean.id, appWidgetId)
        mRemoteViews.setRemoteAdapter(R.id.lv_schedule, lvIntent)
        // 与日视图同一个口径：小部件是纯展示，不挂任何会打开 App 的点击入口，
        // 否则「添加小部件」选择器里点一下会被它吃掉（见 refreshTodayWidget 里的说明）。

        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lv_schedule)
        appWidgetManager.updateAppWidget(appWidgetId, mRemoteViews)
    }

    /**
     * 绑定实例的完整日程内容。
     *
     * **只画今天。** 右上角那两个箭头（切明天 / 切回今天）已删除，随之删掉的是整套
     * "每个实例记住自己在看哪一天"的状态（`day_widget_tomorrow_<id>`、`isNextDay`、
     * data URI 里的 0/1）。留着它们就是没人会再写的死代码。
     *
     * [now] 是这一整幅表头的**唯一时刻来源**：日期、星期、课程计数都从它算。三个值原先各自
     * 读一次实时钟，同一幅图里就可能出现「日期是今天、计数按明天算」；预览图生成器把正文钉在
     * 固定时刻时，更会出现「3节 · 已上3节」配「正在上课」这种自相矛盾的画面。默认值保持生产
     * 行为不变（与正文工厂的 `nowMillis` 是同一个约定）。
     */
    fun refreshTodayWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int,
                           now: Long = CourseClock.nowMillis()) {
        val mRemoteViews = RemoteViews(context.packageName, R.layout.today_course_app_widget)
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(now))
        val weekDay = CourseUtils.getDayStr(CourseUtils.getWeekdayIntAt(now))
        WidgetTheme.color(mRemoteViews, context, R.id.tv_date, "setTextColor", R.color.widget_panel_text)
        WidgetTheme.color(mRemoteViews, context, R.id.tv_summary, "setTextColor", R.color.widget_panel_text_secondary)
        // 表头是设计稿 `.head` 的一行小字：左「标题」右「课程计数」，两边同一字号（12sp）。
        // 原先左边是 22sp 的大号日号，与设计稿逐字不符，也和横条正文左列那个大号日号重复。
        // 单位是 SP：字号本该跟随系统字体大小，写 DIP 就不会跟随。
        mRemoteViews.setTextViewTextSize(R.id.tv_date, TypedValue.COMPLEX_UNIT_SP, 12f)
        mRemoteViews.setTextViewTextSize(R.id.tv_summary, TypedValue.COMPLEX_UNIT_SP, 12f)
        // 标题随形态变，判据与正文**同一个**（TodayColorfulService.dayWidgetForm）—— 两处各判一次
        // 迟早会出现"表头说自己在画横条、正文画的却是小正方形"：
        //   横条（band）：设计稿写「今日日程」。它左列本来就有大号日号 + 星期，表头再写日期是重复；
        //   小正方形（focus）：正文没有日期，表头把日期给全 —— 「9月18日 · 周五」。
        // 大正方形（large，日视图被拖成大方块）这一档设计稿没有覆盖（它的大正方形是"整周课表"，
        // 属于另一个 provider），而这里仍然是某一天的日程，所以同样写「今日日程」。这是判断，不是照抄。
        val (cardWidthPx, cardHeightPx) = cardBoxPx(context, appWidgetId)
        val form = TodayColorfulService.dayWidgetForm(cardWidthPx, cardHeightPx,
                context.resources.displayMetrics.density)
        val fullTitle = if (form == TodayColorfulService.DayWidgetForm.Focus) "$date · $weekDay" else "今日日程"
        val metrics = context.resources.displayMetrics
        val availableWidth = cardWidthPx - 2 * context.resources.getDimensionPixelSize(R.dimen.widget_edge_padding) - 4 * metrics.density
        val paint = TextPaint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val title = if (paint.measureText(fullTitle) <= availableWidth) fullTitle else date
        val fullSummary = daySummary(TodayColorfulService.loadDay(context, now))
        val summary = fullSummary.takeIf {
            paint.measureText(title) + paint.measureText(it) + 4 * metrics.density <= availableWidth
        }.orEmpty()
        mRemoteViews.setTextViewText(R.id.tv_date, title)
        mRemoteViews.setContentDescription(R.id.tv_date, "$fullTitle，$fullSummary")
        mRemoteViews.setTextViewText(R.id.tv_summary, summary)
        // 小部件上**不挂任何点击入口**（用户实测：在「添加小部件」选择器里点一下，被小部件自己
        // 的点击吃掉，结果是打开 App 而不是把这个小部件放上去）。
        //
        // 之前这里挂了两处「点一下打开课表」：
        //   · setOnClickPendingIntent(R.id.tv_date, …)  —— 表头那一行；
        //   · setPendingIntentTemplate(R.id.lv_course, …) —— 课程列表整块（集合型 RemoteViews
        //     的原生做法，因为 ListView 是 AdapterView，直接 setOnClickListener 会在**宿主**的
        //     RemoteViews.apply 阶段抛 RuntimeException，provider 侧抓不到，用户只看到
        //     「载入窗口小部件时出现问题」）。
        // 两处合起来几乎覆盖整个小部件，选择器的预览又是一份真实 RemoteViews，于是点哪儿都是
        // 进 App。而这个点击**没法按场景区分**：RemoteViews 不知道自己在桌面上还是选择器里。
        //
        // 取舍：选择器"点一下就把小部件放上去"是添加路径上的硬需求（放不上去，后面全都无从谈起），
        // 而"点小部件进 App"只是顺手的便利 —— 桌面图标、抽屉入口都能进 App。所以便利让给需求，
        // 整个小部件改成纯展示，点击事件交回宿主。
        //
        // 改这里之前先想清楚：只要再挂回任意一个 PendingIntent，选择器那条路就又会被吃掉。

        // 只有一条列表，索引 0 就是表头那一天：日期与内容不可能再对不上。
        mRemoteViews.setRemoteAdapter(R.id.lv_course, dayIntent(context, dayUri(appWidgetId)))
        notifyCourseLists(appWidgetManager, appWidgetId, R.id.lv_course)
        appWidgetManager.updateAppWidget(appWidgetId, mRemoteViews)
    }

    /**
     * 日视图表头的课程计数：`3节 · 已上1节`（设计稿是 `3节 · 已上1`，用户要求两边都带单位，
     * 语义更完整 —— "已上"后面直接跟数字会被读成时刻或序号）。
     *
     * 没有课表、开学日期无效、当天没有课一律返回空串，不显示"0 节"这种废话 ——
     * 那几种情况正文的空态会说得更清楚（`今天没有课` / `请检查开学日期` / `还没有课表`）。
     */
    private fun daySummary(day: TodayColorfulService.DayContent): String = when {
        day.table == null || day.invalidDate || day.courses.isEmpty() -> ""
        // 数字与「节」之间没有空格：`3节 · 已上1节`。
        day.completed.isEmpty() -> "${day.courses.size}节"
        else -> "${day.courses.size}节 · 已上${day.completed.size}节"
    }

    private fun notifyCourseLists(appWidgetManager: AppWidgetManager, appWidgetId: Int, vararg viewIds: Int) {
        for (viewId in viewIds) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, viewId)
        }
    }

    /** 历史课表可能包含无效开学日期，按学期未开始处理。 */
    private fun safeCountWeek(startDate: String, sundayFirst: Boolean): Int =
            try {
                CourseUtils.countWeek(startDate, sundayFirst)
            } catch (e: ParseException) {
                0
            }

    /**
     * 日视图实例的 **data URI**：`"<appWidgetId>"`。
     *
     * ## 实例 id 为什么必须进 data，不能只放在 extras
     *
     * `RemoteViewsService` 的工厂由系统按 `Intent.FilterComparison` 缓存与复用，而
     * FilterComparison 只比较 action / data / type / identifier / package / component /
     * categories —— **extras 不参与比较**。两个日视图实例的 intent 若只在 extras 上不同，
     * 系统会把它们当成同一个服务请求，第二个实例拿到的就是第一个实例的工厂：格子尺寸、
     * `cellSizeCache`、这一整天算好的行槽位全是别人的。桌面上看到的就是"两个日视图一大一小，
     * 小的那个一直按大的尺寸画"。
     *
     * 日视图只需实例身份；周视图的课表与实例身份由 [weekIntent] 一起放入 data。
     */
    internal fun dayUri(appWidgetId: Int): Uri =
            Uri.fromParts("content", appWidgetId.toString(), null)

    /**
     * 把 appWidgetId 交给服务（它随 [dayUri] 一起进 data）。
     *
     * `internal` 而不是 `private`：单元测试要直接断言"两个实例的 intent 在
     * `Intent.filterEquals` 下不相等" —— 那正是系统决定复不复用工厂的判据，
     * 走 [refreshTodayWidget] 那条路去间接验证要搭一整套 AppWidgetManager 环境。
     */
    internal fun dayIntent(context: Context, uri: Uri) =
            Intent(context, TodayColorfulService::class.java).apply { data = uri }

    /** 同一课表的不同尺寸实例也必须有不同的 FilterComparison 身份。 */
    internal fun weekIntent(context: Context, tableId: Int, widgetId: Int) =
            Intent(context, ScheduleAppWidgetService::class.java).apply {
                data = Uri.fromParts("content", tableId.toString(), widgetId.toString())
            }

    /** 宿主尺寸范围按当前方向取一对；周视图和日视图不能各自猜测屏幕宽度。 */
    internal fun cardBoxPx(context: Context, widgetId: Int): Pair<Int, Int> {
        val manager = AppWidgetManager.getInstance(context)
        val options = if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) Bundle()
                else manager.getAppWidgetOptions(widgetId)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val widthKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
        val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        val provider = manager.getAppWidgetInfo(widgetId)
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val width = options.getInt(widthKey).takeIf { it > 0 }?.let { px(it) }
                ?: provider?.minWidth?.takeIf { it > 0 }
                ?: context.resources.getDimensionPixelSize(R.dimen.day_widget_default_width)
        val height = options.getInt(heightKey).takeIf { it > 0 }?.let { px(it) }
                ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 }?.let { px(it) }
                ?: provider?.minHeight?.takeIf { it > 0 }
                ?: context.resources.getDimensionPixelSize(R.dimen.day_widget_default_height)
        return width to height
    }
}
