package courseclock.timetable.schedule_appwidget

import android.content.Intent
import android.appwidget.AppWidgetManager
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import courseclock.timetable.AppDatabase
import courseclock.timetable.R
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.schedule.ScheduleUI
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.CourseTimes
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.CourseUtils.countWeek
import courseclock.timetable.utils.DayWidgetSchedule
import courseclock.timetable.utils.ViewUtils
import courseclock.timetable.utils.getPrefer
import courseclock.timetable.widget.TipTextView
import splitties.dimensions.dip
import java.text.ParseException
import kotlin.math.roundToInt

class ScheduleAppWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent?): RemoteViewsFactory {
        val tableId = intent?.data?.schemeSpecificPart?.toIntOrNull() ?: -1
        val widgetId = intent?.data?.fragment?.toIntOrNull() ?: AppWidgetManager.INVALID_APPWIDGET_ID
        return ScheduleRemoteViewsFactory(tableId, widgetId)
    }

    internal inner class ScheduleRemoteViewsFactory(val tableId: Int = -1,
                                                    private val widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID) : RemoteViewsFactory {
        private var table: TableBean? = null
        private var week = 0
        private var alphaInt = 255
        private val dataBase = AppDatabase.getDatabase(applicationContext)
        private val tableDao = dataBase.tableDao()
        private val courseDao = dataBase.courseDao()
        private val timeDao = dataBase.timeDetailDao()
        private val timeList = arrayListOf<TimeDetailBean>()

        /**
         * 按 (节次, 分组) 查时间的入口，与 [timeList] 同步重建。
         *
         * [timeList] 是该时间表下**所有分组**的原始行（同一个节次可能有多行），所以显示时间
         * 一律走这里，不能按下标取 —— 那正是"每节课都显示 08:15"的来源。
         */
        private var times: CourseTimes = CourseTimes.of(emptyList())
        private val weekDay = CourseUtils.getWeekdayInt()
        private val allCourseList = Array(7) { listOf<CourseBean>() }

        override fun onCreate() {

        }

        override fun onDataSetChanged() {
            timeList.clear()
            val loaded = if (tableId == -1) {
                tableDao.getDefaultTableSync()
            } else {
                tableDao.getTableByIdSync(tableId) ?: tableDao.getDefaultTableSync()
            }
            table = loaded
            allCourseList.fill(emptyList())
            times = CourseTimes.of(emptyList())
            val table = loaded ?: return

            try {
                week = countWeek(table.startDate, table.sundayFirst)
            } catch (e: ParseException) {
                // 开学日期被改坏：记录并交给下面 week<=0 的既有分支，不要沿用旧值。
                Log.w("WeekWidget", "startDate 解析失败: " + table.startDate, e)
                week = 0
            }
            if (week <= 0) {
                week = 1
            }

            alphaInt = (255 * (table.widgetItemAlpha.toFloat() / 100)).roundToInt()

            for (i in 1..7) {
                allCourseList[i - 1] = courseDao.getCourseByDayOfTableSync(i, table.id)
            }

            timeList.clear()
            timeList.addAll(timeDao.getTimeListSync(table.timeTable))
            // 小部件与主课表用同一套"时间栏作息方案"：同一张课表在两处显示的时间必须一致。
            // 「自动」要按这张表现有的安排重算，所以要连安排一起读。
            times = CourseTimes.ofPreferred(applicationContext, timeList,
                    courseDao.getDetailOfTableSync(table.id))
        }

        override fun onDestroy() {
            timeList.clear()
        }

        override fun getCount(): Int {
            return 1
        }

        override fun getViewAt(position: Int): RemoteViews {
            val mRemoteViews = RemoteViews(applicationContext.packageName, R.layout.item_schedule_widget)
            initData(mRemoteViews)
            return mRemoteViews
        }

        override fun getLoadingView(): RemoteViews? {
            return null
        }

        override fun getViewTypeCount(): Int {
            return 1
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun hasStableIds(): Boolean {
            return false
        }

        fun initData(views: RemoteViews) {
            val scrollView = initView()
            if (scrollView == null) {
                views.setImageViewResource(R.id.iv_schedule, R.drawable.ic_schedule_empty)
                return
            }
            views.setBitmap(R.id.iv_schedule, "setImageBitmap", ViewUtils.getViewBitmap(scrollView))
            views.setContentDescription(R.id.iv_schedule, allCourseList.flatMap { it }.filter { it.inWeek(week) }
                    .joinToString("；") { "${CourseUtils.getDayStr(it.day)}，${it.courseName}，${DayWidgetSchedule.timeRange(times, it)}，${it.room.orEmpty()}" })
        }

        internal fun initView(): ScrollView? {
            val table = table ?: return null
            val ui = ScheduleUI(courseclock.timetable.utils.WidgetTheme.context(applicationContext), table, weekDay, true, times)
            val showTimeDetail = applicationContext.getPrefer().getBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, true)
            ui.showTimeDetail = showTimeDetail
            if (timeList.isNotEmpty() && showTimeDetail) {
                // 按节次取，不能按下标：同一个节次在时间表里可能有多行（默认分组 + 各教学楼
                // 分组），DAO 只按 `order by node` 返回，`timeList[i]` 不是"第 i+1 节"。
                for (i in 0 until table.nodes) {
                    val row = times.defaultTimeForNode(i + 1) ?: continue
                    // 节点行是 ScheduleUI 按 `table.nodes` 动态生成的，两边一旦不一致就没有这一格：
                    // 这里只负责填时间，取不到就跳过，不用 `as` 把一次越界变成崩溃。
                    val nodeRow = ui.content.getViewById(R.id.anko_tv_node1 + i) as? FrameLayout ?: continue
                    nodeRow.findViewById<TextView>(R.id.tv_start).text = row.startTime
                    nodeRow.findViewById<TextView>(R.id.tv_end).text = row.endTime
                }
            }
            for (i in 1..7) {
                initWeekPanel(ui, allCourseList[i - 1], i)
            }
            val scrollView = ui.scrollView
            val cardWidth = AppWidgetUtils.cardBoxPx(applicationContext, widgetId).first
            val width = (cardWidth - 2 * resources.getDimensionPixelSize(R.dimen.widget_edge_padding)).coerceAtLeast(1)
            // 保留可读字号和完整课程行。超出可视高度由原生 ListView 滚动，不缩放整屏截图。
            scrollView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            scrollView.layout(0, 0, width, scrollView.measuredHeight)
            return scrollView
        }

        private fun initWeekPanel(ui: ScheduleUI, data: List<CourseBean>?, day: Int) {
            val table = table ?: return
            val ll = ui.content.getViewById(R.id.anko_ll_week_panel_0 + ui.dayMap[day] - 1) as FrameLayout?
                    ?: return
            ll.removeAllViews()
            if (data == null || data.isEmpty()) return
            val occupied = ArrayList<Pair<Rect, TipTextView>>()
            for (c in data.sortedWith(compareBy<CourseBean> { !it.inWeek(week) }.thenBy { it.notAttend })) {

                // 过期的不显示
                if (c.endWeek < week) {
                    continue
                }

                val isOtherWeek = (week % 2 == 0 && c.type == 1) || (week % 2 == 1 && c.type == 2)
                        || (c.startWeek > week)

                if (!table.showOtherWeekCourse && isOtherWeek) continue

                var isError = false

                if (c.step <= 0) {
                    c.step = 1
                    isError = true
                }
                if (c.startNode <= 0) {
                    c.startNode = 1
                    isError = true
                }
                if (c.startNode > table.nodes) {
                    c.startNode = table.nodes
                    isError = true
                }
                if (c.startNode + c.step - 1 > table.nodes) {
                    c.step = table.nodes - c.startNode + 1
                    isError = true
                }

                val textView = TipTextView(applicationContext)

                val box = ui.blockBoxOf(times, c.startNode, c.step, c.timeGroup)
                val bounds = Rect(0, box.top, 1, box.top + box.height)
                val covered = occupied.filter { Rect.intersects(it.first, bounds) }
                if (covered.isNotEmpty()) {
                    // 按真实绘制区域防止叠字，连堂、错峰和不同开始节次同样有效。
                    covered.forEach { (_, winner) ->
                        winner.widgetOverlapCount++
                        winner.showMultiTip = true
                    }
                    continue
                }

                // 今天这一列、且此刻正在上的那一节：描边换成强调色（设计稿的"当前节次"标记），
                // 让它在满屏同色块里被一眼认出来。判据走 DayWidgetSchedule.isOngoing ——
                // 与日视图、下课提醒共用同一份"是不是正在上"，不在这里再写一遍时刻比较。
                if (day == weekDay && DayWidgetSchedule.isOngoing(times, c) { courseclock.timetable.utils.CourseClock.nowMillis() }) {
                    textView.accentStroke = true
                }

                textView.setPadding(dip(2))

                if (c.color.isEmpty()) {
                    c.color = "#${Integer.toHexString(ViewUtils.getCustomizedColor(applicationContext, c.id % 9))}"
                }

                if (isError) {
                    textView.tipVisibility = TipTextView.TIP_ERROR
                }

                if (!isOtherWeek) {
                    textView.tag = c.startNode
                } else {
                    textView.tipVisibility = TipTextView.TIP_OTHER_WEEK
                }

                textView.init(
                        text = c.courseName,
                        txtSize = table.widgetItemTextSize,
                        txtColor = table.widgetCourseTextColor,
                        bgColor = ViewUtils.parseCourseColor(c.color,
                                ViewUtils.getCustomizedColor(applicationContext, c.id % 9)),
                        bgAlpha = alphaInt,
                        stroke = table.widgetStrokeColor
                )
                textView.setWidgetContent(c.courseName, c.room.orEmpty(),
                        if (table.showTime) times.startOf(c) else "",
                        if (isOtherWeek) "非本周" else when (c.type) { 1 -> "单周"; 2 -> "双周"; else -> "" })
                textView.notAttendCard = c.notAttend

                // 与主课表同一套摆放规则：位置问 ui 要（ui.blockBoxOf），行高行距只有 ScheduleUI 一份。
                ll.addView(textView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        box.height).apply {
                    gravity = Gravity.TOP
                    topMargin = box.top
                })

                occupied.add(bounds to textView)
            }
        }

    }

}
