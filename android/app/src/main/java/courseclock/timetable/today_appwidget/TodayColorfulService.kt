package courseclock.timetable.today_appwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import courseclock.timetable.AppDatabase
import courseclock.timetable.R
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.dao.CourseDao
import courseclock.timetable.utils.*
import java.text.ParseException
import java.util.Calendar
import kotlin.math.roundToInt

/** 按实例可用空间输出完整日程，而非让多个列表行各自猜测剩余高度。 */
class TodayColorfulService : RemoteViewsService() {
    /**
     * 一个日视图实例这一天要画的全部输入，由 [Companion.loadDay] 装载。
     *
     * 声明在类这一层而不是 companion 里面：表头（`AppWidgetUtils.refreshTodayWidget`）也要用它，
     * 而 companion 里的嵌套类对外只能写成 `TodayColorfulService.Companion.DayContent`。
     */
    internal class DayContent(
            val table: TableBean?,
            val times: CourseTimes,
            val courses: List<CourseBean>,
            val completed: List<CourseBean>,
            val remaining: List<CourseBean>,
            val invalidDate: Boolean)

    override fun onGetViewFactory(intent: Intent?): RemoteViewsFactory {
        // data URI 只剩实例身份（见 AppWidgetUtils.dayUri）：右上角箭头删除之后，
        // "这个实例在看哪一天"这个状态不存在了 —— 日视图只画今天。
        return TodayColorfulRemoteViewsFactory(
                intent?.data?.schemeSpecificPart?.toIntOrNull()
                        ?: AppWidgetManager.INVALID_APPWIDGET_ID)
    }

    internal inner class TodayColorfulRemoteViewsFactory(
            private val widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    ) : RemoteViewsFactory {
        private var table: TableBean? = null
        private var times = CourseTimes.of(emptyList())
        private var courses: List<CourseBean> = emptyList()
        private var completed: List<CourseBean> = emptyList()
        private var remaining: List<CourseBean> = emptyList()
        private var sizeCache: Pair<Int, Int>? = null
        internal var nowMillis: () -> Long = { courseclock.timetable.utils.CourseClock.nowMillis() }
        private var refreshMillis = 0L
        private var invalidDate = false
        private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
        private val font get() = (table?.widgetItemTextSize ?: 12).toFloat().coerceAtLeast(12f)

        override fun onCreate() = Unit
        override fun onDestroy() {
            courses = emptyList()
            completed = emptyList()
            remaining = emptyList()
        }

        override fun onDataSetChanged() {
            sizeCache = null
            refreshMillis = nowMillis()
            val day = loadDay(applicationContext, refreshMillis)
            table = day.table
            times = day.times
            courses = day.courses
            completed = day.completed
            remaining = day.remaining
            invalidDate = day.invalidDate
        }

        internal fun visibleCourseCount() = remaining.size
        override fun getCount() = 1
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = 0L
        override fun hasStableIds() = true

        /** min/max 是横竖屏范围，不可分别取最大值拼成不存在的格子。 */
        internal fun emptyCardSizePx(): Pair<Int, Int> {
            sizeCache?.let { return it }
            val box = hostBoxPx()
            return emptyCardSizePx(box.first, box.second).also { sizeCache = it }
        }

        /**
         * 宿主给这一格的**整卡**尺寸（像素）。
         *
         * 与 [emptyCardSizePx] 的区别只在"量哪一块"：那个量的是卡片里的课程列表（正文按它排版），
         * 这个量的是整张卡的框（**形态按它判定**，见 [content]）。两个入口的表头与正文都要它，
         * 实现只有 [cardBoxPx] 一份。
         */
        private fun hostBoxPx(): Pair<Int, Int> = cardBoxPx(applicationContext, widgetId)

        internal fun emptyCardSizePx(widthPx: Int, heightPx: Int): Pair<Int, Int> {
            val root = LayoutInflater.from(applicationContext).inflate(R.layout.today_course_app_widget, null)
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, widthPx, heightPx)
            val list = root.findViewById<View>(R.id.lv_course)
            return maxOf(1, list.width) to maxOf(1, list.height)
        }

        override fun getViewAt(position: Int): RemoteViews {
            val (width, height) = emptyCardSizePx()
            val view = content(WidgetTheme.context(applicationContext), width, height)
            val description = ArrayList<String>()
            fun collect(node: View) {
                if (node is TextView) description.add(node.text.toString())
                if (node is ViewGroup) for (i in 0 until node.childCount) collect(node.getChildAt(i))
            }
            if (courses.isEmpty()) collect(view) else courses.forEach { course ->
                description.add(listOf(course.courseName, range(course), room(course),
                        course.teacher.orEmpty(), status(course)).filter { it.isNotBlank() }.joinToString("，"))
            }
            return RemoteViews(packageName, R.layout.item_schedule_widget).apply {
                // 不挂 setOnClickFillInIntent：小部件整体是纯展示，列表上已经没有任何
                // PendingIntent 模板可填（见 AppWidgetUtils.refreshTodayWidget 的说明）。
                setImageViewBitmap(R.id.iv_schedule, bitmap(view, width, height))
                setContentDescription(R.id.iv_schedule, description.joinToString("，"))
            }
        }

        internal fun initView(context: Context, position: Int): View {
            require(position == 0) { "日程使用一个完整布局" }
            val (width, height) = emptyCardSizePx()
            return content(context, width, height)
        }

        internal fun courseRowBitmap(position: Int, widthPx: Int, heightPx: Int, slotHeightPx: Int = 0): Bitmap {
            require(position == 0)
            val height = if (slotHeightPx > 0) slotHeightPx else heightPx
            return bitmap(content(WidgetTheme.context(applicationContext), widthPx, height), widthPx, height)
        }

        private fun bitmap(view: View, width: Int, height: Int): Bitmap {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }

        private fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private fun label(context: Context, value: CharSequence, size: Float = font,
                          secondary: Boolean = false, lines: Int = 1) = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            text = value
            textSize = size
            setTextColor(ContextCompat.getColor(context, if (secondary) R.color.widget_panel_text_secondary else R.color.widget_panel_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
        private fun LinearLayout.line(view: View) = addView(view, LinearLayout.LayoutParams(-1, -2))
        private fun natural(view: View, width: Int): Int {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return view.measuredHeight
        }

        /** 前面的完整行不动，只在最后一行中间省略，保留课程分册和教室编号等尾部信息。 */
        private fun preserveEnds(view: TextView) {
            val layout = view.layout ?: return
            val last = minOf(view.maxLines, layout.lineCount) - 1
            if (last < 0 || layout.getEllipsisCount(last) == 0) return
            val original = view.text.toString()
            val start = layout.getLineStart(last)
            view.contentDescription = original
            val tail = TextUtils.ellipsize(original.substring(start).replace('\n', ' '), view.paint,
                    (view.measuredWidth - view.paddingLeft - view.paddingRight).toFloat(), TextUtils.TruncateAt.MIDDLE)
            view.text = if (start == 0) tail else original.substring(0, start).trimEnd() + "\n" + tail
        }
        /**
         * 时段的显示文案。
         *
         * 设计稿用 **en dash**（`09:55–11:15`），而全工程的 [DayWidgetSchedule.timeRange] 用半角
         * 连字符（`09:55-11:15`，主课表与导出都在用）。这里只换这一处显示字符，不动那个共用函数 ——
         * 改它会波及主课表、提醒文案与导出文件，那是"顺手改别人的东西"。
         * 时间串里只有数字与冒号，`replace` 不会误伤别的字符。
         */
        private fun range(course: CourseBean) = DayWidgetSchedule.timeRange(times, course)
                .replace('-', '–')
                .ifEmpty { "时间未设置" }
        private fun room(course: CourseBean) = course.room?.takeIf { it.isNotBlank() } ?: "教室未设置"

        /**
         * 当前课距离下课的分钟数；**不在倒计时窗口内返回 null**。
         *
         * 窗口（20 分钟）不在这里另写一遍：`DayWidgetSchedule.countdownMinutes` 就是提醒与
         * 每分钟刷新链用的那一份，两处一旦分叉就会出现"文字说正在上课、徽标在倒计时"。
         */
        private fun countdown(course: CourseBean): Long? =
                if (DayWidgetSchedule.isOngoing(times, course) { refreshMillis })
                    DayWidgetSchedule.countdownMinutes(times, course) { refreshMillis } else null

        /** 横条左侧保留大号日号和星期。 */
        private fun dateBlock(context: Context): View {
            val date = Calendar.getInstance().apply { timeInMillis = refreshMillis }
            return column(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                line(label(context, date.get(Calendar.DAY_OF_MONTH).toString(), 26f))
                line(label(context, CourseUtils.getWeekday(), font - 1, true))
            }
        }

        /**
         * 设计稿横条右侧的状态列：倒计时期间是「大号数字 + 分钟下课」两行，其余是一行文字状态。
         *
         * 状态本身不靠颜色区分：文字状态（正在上课 / 下一节 / 已结束 …）与数字都在，颜色只是加重。
         */
        private fun statusBadge(context: Context, course: CourseBean, maxWidth: Int): View {
            val minutes = countdown(course)
            if (minutes == null) {
                return statusLabel(context, course).apply {
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    this.maxWidth = maxWidth
                    fitStatus(this, maxWidth)
                }
            }
            return column(context).apply {
                gravity = Gravity.END
                line(label(context, minutes.toString(), 26f).apply {
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                })
                line(label(context, "分钟下课", font - 1, true).apply {
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                })
            }
        }
        private fun status(course: CourseBean): String = when {
            completed.contains(course) -> "已结束"
            else -> DayWidgetSchedule.statusText(times, course) { refreshMillis }
        }

        private fun statusLabel(context: Context, course: CourseBean, size: Float = font) =
                label(context, status(course), size, true).apply {
                    if (course !in completed && !DayWidgetSchedule.isOngoing(times, course) { refreshMillis }) {
                        setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                    }
                }

        /** 优先只收小数部分；仍放不下则允许换行，不能把倒计时数字省略。 */
        private fun fitStatus(view: TextView, width: Int) {
            if (view.text.startsWith("约 ") && view.paint.measureText(view.text.toString()) > width) {
                val decimal = view.text.indexOf('.')
                if (decimal >= 0) view.text = SpannableString(view.text).apply {
                    setSpan(RelativeSizeSpan(0.85f), decimal, decimal + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            view.maxLines = 2
            view.ellipsize = null
        }

        private fun content(context: Context, width: Int, height: Int): LinearLayout {
            // 形态只看**宿主给这一格的整卡尺寸**，而且表头与正文共用同一个判据（见 dayWidgetForm）。
            val box = hostBoxPx()
            val form = dayWidgetForm(box.first, box.second, resources.displayMetrics.density)
            val large = form == DayWidgetForm.Large
            val wide = form == DayWidgetForm.Band
            return column(context).apply {
                id = R.id.anko_layout
                when {
                    courses.isEmpty() -> empty(context, this, width, height, large)
                    // 今天全部上完：设计稿给的是**空态**（`今天的课都上完了`），不是把最后一节
                    // 已结束的课留在那里。`remaining` 为空就是"没有还没上的课了"；`courses` 非空
                    // 才轮得到这一支（上面那支已经接走了"今天没课"）。
                    remaining.isEmpty() -> empty(context, this, width, height, large)
                    large && courses.size == 1 -> singleLarge(context, this, courses.single(), width, height)
                    large -> timeline(context, this, width, height)
                    else -> focus(context, this, width, height, wide, large)
                }
            }
        }

        private fun singleLarge(context: Context, root: LinearLayout, course: CourseBean, width: Int, height: Int) {
            val heading = column(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                line(statusLabel(context, course).apply { fitStatus(this, width) })
                line(label(context, course.courseName, font + 12, lines = 2).apply { setPadding(0, dp(10), 0, 0) })
            }
            val timeBand = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            listOf("开始" to times.startOf(course), "结束" to times.endOf(course)).forEach { (caption, time) ->
                timeBand.addView(column(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    line(label(context, caption, font, true))
                    line(label(context, time.ifEmpty { "待定" }, 32f).apply {
                        setPadding(0, dp(8), 0, 0)
                        setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                    })
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }
            val location = column(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                line(label(context, room(course), font + 6, lines = 2))
                course.teacher?.takeIf { it.isNotBlank() }?.let {
                    line(label(context, it, font, true).apply { setPadding(0, dp(8), 0, 0) })
                }
            }
            val bands = listOf(heading, timeBand, location)
            val heights = bands.map { natural(it, width) }
            val extra = height - heights.sum()
            if (extra < 0) {
                focus(context, root, width, height, false, false)
                return
            }
            bands.forEachIndexed { index, view ->
                for (i in 0 until view.childCount) (view.getChildAt(i) as? TextView)?.let { preserveEnds(it) }
                root.addView(view, LinearLayout.LayoutParams(-1,
                        heights[index] + extra / bands.size + if (index < extra % bands.size) 1 else 0))
            }
        }

        private fun focus(context: Context, root: LinearLayout, width: Int, height: Int, wide: Boolean, large: Boolean) {
            val current = remaining.firstOrNull() ?: completed.last()
            val history = completed.filterNot { it == current }
            val future = remaining.filterNot { it == current }
            val main = column(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val title = label(context, current.courseName, font + if (large) 10 else 5, lines = 2)
            val minutes = countdown(current)
            val location = label(context, room(current), secondary = true, lines = 2)
            val time = label(context, range(current), secondary = true)
            val state = label(context, if (minutes != null) "距离下课" else status(current), font, true)
                    .apply { setTextColor(ContextCompat.getColor(context, R.color.colorPrimary)) }
            fitStatus(state, width)
            var hero: View? = null
            var infoWidth = width
            if (wide) {
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                val date = dateBlock(context)
                val badge = statusBadge(context, current, width / 3)
                fun preferredWidth(view: View): Int {
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    return view.measuredWidth
                }
                val dateWidth = preferredWidth(date)
                val badgeWidth = preferredWidth(badge)
                // 先为完整时段和至少六个课名字预留宽度；重复日期不能挤掉课程信息。
                val coreWidth = maxOf(time.paint.measureText(time.text.toString()),
                        title.paint.measureText("课程名称实验")).roundToInt()
                val showDate = width >= coreWidth + dateWidth + badgeWidth + dp(20)
                val showBadge = width >= coreWidth + badgeWidth + dp(10)
                if (showDate) row.addView(date, LinearLayout.LayoutParams(dateWidth, -2))
                val info = column(context)
                info.line(title)
                info.line(location.apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(2), 0, 0) })
                info.line(time.apply { setPadding(0, dp(2), 0, 0) })
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = if (showDate) dp(10) else 0
                })
                if (showBadge) row.addView(badge,
                        LinearLayout.LayoutParams(badgeWidth, -2).apply { marginStart = dp(10) })
                infoWidth = width - (if (showDate) dateWidth + dp(10) else 0) -
                        (if (showBadge) badgeWidth + dp(10) else 0)
                main.line(row)
            } else {
                main.line(state)
                val heroRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                heroRow.addView(label(context,
                        if (minutes != null) minutes.toString()
                        else times.startOf(current).ifEmpty { "待定" },
                        if (large) 48f else 32f), LinearLayout.LayoutParams(-2, -2))
                if (minutes != null) {
                    heroRow.addView(label(context, "分钟", font + 1).apply { setPadding(dp(4), 0, 0, 0) },
                            LinearLayout.LayoutParams(-2, -2))
                }
                main.line(heroRow.apply { setPadding(0, dp(8), 0, dp(8)) })
                hero = heroRow
                main.line(title)
                main.line(location.apply { setPadding(0, dp(6), 0, 0) })
                main.line(time.apply { setPadding(0, dp(8), 0, 0) })
            }
            val historyLabel = history.lastOrNull()?.let {
                // 设计稿逐字样例：`已结束 1 节 · 高等数学`。
                label(context, "已结束 ${history.size} 节 · ${it.courseName}", secondary = true)
                        .apply { setPadding(0, 0, 0, dp(8)) }
            }
            val nextLabel = future.firstOrNull()?.let {
                val prefix = if (DayWidgetSchedule.isOngoing(times, it) { refreshMillis }) "同时上课" else "接下来"
                label(context, "$prefix ${times.startOf(it).ifEmpty { "待定" }} · ${it.courseName}" +
                        if (future.size > 1) " · 另有${future.size - 1}节" else "",
                        secondary = true, lines = 1).apply { setPadding(0, dp(8), 0, 0) }
            }
            fitTime(time, infoWidth)
            val pastHeight = if (historyLabel == null) 0 else natural(historyLabel, width)
            val futureHeight = if (nextLabel == null) 0 else natural(nextLabel, width)
            var showPast = historyLabel != null
            var showFuture = nextLabel != null
            fun mainRoom() = height - (if (showPast) pastHeight else 0) - (if (showFuture) futureHeight else 0)
            // 原生测量决定取舍：先间距、历史、重复大数字，再次要摘要；不先删除地点和时段。
            if (natural(main, width) > mainRoom()) {
                fun tighten(view: View) {
                    view.setPadding(view.paddingLeft, 0, view.paddingRight, 0)
                    if (view is ViewGroup) for (i in 0 until view.childCount) tighten(view.getChildAt(i))
                }
                tighten(main)
            }
            if (natural(main, width) > mainRoom()) showPast = false
            if (hero != null && natural(main, width) > mainRoom()) {
                main.removeView(hero)
                state.text = if (minutes != null) "$minutes 分钟下课" else status(current)
            }
            if (natural(main, width) > mainRoom()) showFuture = false
            if (natural(main, width) > mainRoom()) {
                location.setSingleLine()
                location.ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            if (natural(main, width) > mainRoom()) main.removeView(state)
            if (natural(main, width) > mainRoom()) {
                title.textSize = font
            }
            // 用户字号仍是首选；极小格子只把核心三行收至 12sp，不把文字画成半行。
            if (natural(main, width) > mainRoom()) {
                listOf(title, location, time).forEach { it.textSize = 12f }
                fitTime(time, infoWidth)
            }
            if (natural(main, width) > mainRoom()) {
                title.setSingleLine()
                title.ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            natural(main, width)
            preserveEnds(title)
            preserveEnds(location)

            if (showPast && historyLabel != null) root.line(historyLabel)
            root.addView(main, LinearLayout.LayoutParams(-1, 0, 1f))
            if (showFuture && nextLabel != null) root.line(nextLabel)

            // 设计稿的「均匀排版」：内容区先扣掉各组文字的自然高度，再把**余量均分给每一组**，
            // 而不是像原来那样全交给 main 的 weight —— 那等于把余量留成一大片空白堆在另一头，
            // 内容全挤在一边（真机上看到的就是"上面挤满、下面空一大块"）。
            // singleLarge / timeline 早就是这种分法，focus 是漏掉的那一个。
            //
            // 放在最后：root 的孩子此刻才齐，`natural` 量到的才是每组的真实高度。
            // 这里只改 LayoutParams，真正的测量发生在 content() 返回之后，所以顺序无碍。
            val naturalTotal = (0 until root.childCount).sumOf { natural(root.getChildAt(it), width) }
            val slack = height - naturalTotal
            if (slack > 0 && main.childCount > 0) {
                val share = slack / main.childCount
                for (i in 0 until main.childCount) {
                    val child = main.getChildAt(i)
                    val params = child.layoutParams as LinearLayout.LayoutParams
                    params.height = child.measuredHeight + share + if (i < slack % main.childCount) 1 else 0
                }
            }
        }

        /** 时段不省略任意一端；极窄格子可收至 10sp，仍放不下才换行。 */
        private fun fitTime(view: TextView, width: Int) {
            if (view.paint.measureText(view.text.toString()) > width) view.textSize = 12f
            if (view.paint.measureText(view.text.toString()) > width) view.textSize = 10f
            if (view.paint.measureText(view.text.toString()) > width) {
                view.text = view.text.toString().replace('–', '\n')
                view.maxLines = 2
            }
            view.ellipsize = null
        }

        private fun timelineRow(context: Context, course: CourseBean): LinearLayout {
            val ended = completed.contains(course)
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val clock = column(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                line(label(context, times.startOf(course).ifEmpty { "待定" }, font + 3, ended))
                line(label(context, times.endOf(course).ifEmpty { "待定" }, font, true).apply { setPadding(0, dp(5), 0, 0) })
            }
            clock.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.addView(clock, LinearLayout.LayoutParams(clock.measuredWidth + dp(10), -1))
            val accent = if (ended) ContextCompat.getColor(context, R.color.widget_panel_text_secondary)
                    else if (getPrefer().getBoolean(Const.KEY_DAY_WIDGET_COLOR, true))
                        ViewUtils.parseCourseColor(course.color, 0xff2979ff.toInt())
                    else ContextCompat.getColor(context, R.color.colorPrimary)
            row.addView(View(context).apply { setBackgroundColor(accent) },
                    LinearLayout.LayoutParams(dp(2), -1).apply { marginEnd = dp(14); topMargin = dp(7); bottomMargin = dp(7) })
            row.addView(column(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                line(statusLabel(context, course, font - 1))
                line(label(context, course.courseName, font + 4, ended, 2).apply { setPadding(0, dp(5), 0, dp(5)) })
                line(label(context, room(course), font, true, 2))
            }, LinearLayout.LayoutParams(0, -1, 1f))
            row.setPadding(0, dp(6), 0, dp(6))
            return row
        }

        private fun timeline(context: Context, root: LinearLayout, width: Int, height: Int) {
            val rows = courses.associateWith { timelineRow(context, it) }
            val selected = LinkedHashSet<CourseBean>()
            val past = label(context, "", secondary = true)
            val future = label(context, "", secondary = true)
            fun summaryHeight(): Int {
                val hiddenPast = completed.count { it !in selected }
                val hiddenFuture = remaining.count { it !in selected }
                past.text = if (hiddenPast > 0) "已结束 $hiddenPast 节" else ""
                val hiddenOngoing = remaining.any { it !in selected && DayWidgetSchedule.isOngoing(times, it) { refreshMillis } }
                future.text = if (hiddenFuture > 0) "另有 $hiddenFuture 节${if (hiddenOngoing) "未结束" else "待上"}" else ""
                return (if (past.text.isNotEmpty()) natural(past, width) else 0) +
                        (if (future.text.isNotEmpty()) natural(future, width) else 0)
            }
            var used = 0
            rows.values.forEach { row ->
                val clockWidth = row.getChildAt(0).layoutParams.width
                val info = row.getChildAt(2) as LinearLayout
                fitStatus(info.getChildAt(0) as TextView, width - clockWidth - dp(16))
            }
            // 当前优先保证可见，之后选最近历史与后续；最终显示仍严格按时间排序。
            val ongoing = remaining.filter { DayWidgetSchedule.isOngoing(times, it) { refreshMillis } }
            val priority = (remaining.take(1) + ongoing + completed.takeLast(1) + remaining.drop(1) +
                    completed.dropLast(1).asReversed()).distinct()
            for (course in priority) {
                selected.add(course)
                val rowHeight = natural(rows.getValue(course), width)
                if (used + rowHeight + summaryHeight() <= height) used += rowHeight
                else {
                    selected.remove(course)
                    // 主课放不下就改用紧凑布局，不能跳过长课名而只留下短历史课程。
                    if (course == remaining.firstOrNull()) {
                        focus(context, root, width, height, false, true)
                        return
                    }
                }
            }
            if (selected.isEmpty()) {
                focus(context, root, width, height, false, true)
                return
            }
            val extra = height - used - summaryHeight()
            if (past.text.isNotEmpty()) root.line(past)
            val shown = courses.filter { it in selected }
            shown.forEachIndexed { index, course ->
                val row = rows.getValue(course)
                val rowHeight = natural(row, width)
                val info = row.getChildAt(2) as LinearLayout
                for (i in 1 until info.childCount) preserveEnds(info.getChildAt(i) as TextView)
                root.addView(row, LinearLayout.LayoutParams(-1, rowHeight +
                        extra / shown.size + if (index < extra % shown.size) 1 else 0))
            }
            if (future.text.isNotEmpty()) root.line(future)
        }

        private fun empty(context: Context, root: LinearLayout, width: Int, height: Int, large: Boolean) {
            // 「今天全部上完」也是一种空态：这时要说的话是"结束了"，所以文案与图标都走这一条。
            // 大卡也不画日历 —— 今天已经没有内容可看，摆一份月历在这里没有意义。
            // 必须同时要求"今天本来有课"：没课表、开学日期无效、今天压根没课这三种情况下
            // `remaining` 同样是空的，那不是"上完了"。
            val allDone = courses.isNotEmpty() && remaining.isEmpty()
            val message = when {
                table == null -> "还没有课表"
                invalidDate -> "请检查开学日期"
                allDone -> "今天的课都上完了"
                else -> "今天没有课"
            }
            // 整体居中：竖向靠 root 的 gravity 把"图标 + 文字"当成一整块居中，横向靠
            // "图标视图铺满（FIT_CENTER 把图摆在正中）+ 文字自身的 gravity=CENTER"。
            // 图标不能用 wrap_content：那样它会跟着文字的左边缘走，整块就偏到左边去了。
            root.gravity = Gravity.CENTER_VERTICAL
            if (large && table != null && !invalidDate && !allDone) {
                val date = Calendar.getInstance().apply { timeInMillis = refreshMillis }
                root.line(label(context, message, font + 8).apply { setPadding(0, 0, 0, dp(16)) })
                val selectedDay = date.get(Calendar.DAY_OF_MONTH)
                val days = date.getActualMaximum(Calendar.DAY_OF_MONTH)
                date.set(Calendar.DAY_OF_MONTH, 1)
                val sundayFirst = table?.sundayFirst == true
                val offset = if (sundayFirst) date.get(Calendar.DAY_OF_WEEK) - 1
                        else (date.get(Calendar.DAY_OF_WEEK) + 5) % 7
                val weeks = (offset + days + 6) / 7
                val calendar = column(context)
                fun weekRow(values: List<String>, marked: Int = -1) = LinearLayout(context).apply {
                    values.forEachIndexed { index, value ->
                        addView(label(context, value, font + 1, index != marked).apply {
                            gravity = Gravity.CENTER
                            if (index == marked) {
                                setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                                typeface = Typeface.DEFAULT_BOLD
                            }
                        }, LinearLayout.LayoutParams(0, -1, 1f))
                    }
                }
                val weekdays = if (sundayFirst) listOf("日", "一", "二", "三", "四", "五", "六")
                        else listOf("一", "二", "三", "四", "五", "六", "日")
                calendar.addView(weekRow(weekdays), LinearLayout.LayoutParams(-1, 0, 1f))
                for (week in 0 until weeks) {
                    val numbers = (0..6).map { week * 7 + it - offset + 1 }
                    calendar.addView(weekRow(numbers.map { if (it in 1..days) it.toString() else "" },
                            numbers.indexOf(selectedDay)), LinearLayout.LayoutParams(-1, 0, 1f))
                }
                root.addView(calendar, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                val messageView = label(context, message, font + 4, lines = 3).apply { gravity = Gravity.CENTER }
                if (getPrefer().getBoolean(Const.KEY_SHOW_EMPTY_VIEW, true) &&
                        natural(messageView, width) + dp(56) <= height) {
                    root.addView(ImageView(context).apply { setImageResource(R.drawable.ic_schedule_empty) },
                            LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })
                }
                if (natural(messageView, width) > height) messageView.textSize = 12f
                root.line(messageView)
            }
        }
    }

    /**
     * 日视图的三种形态。名字照设计稿的三种格数：2×2 小正方形、4×2 横向长条、4×4 大正方形。
     *
     * 放在类上而不是 `companion` 里：表头（[AppWidgetUtils.refreshTodayWidget]）也要用它，
     * 而 `companion` 里嵌套的类型对外要写成 `TodayColorfulService.Companion.DayWidgetForm`，
     * 那是个只会在调用点添乱的名字。
     */
    internal enum class DayWidgetForm { Focus, Band, Large }

    companion object {
        /**
         * 形态判据：只看宿主给这一格的**整卡**像素尺寸。
         *
         * 为什么是整卡、不是正文区：设计稿的三种形态按**格数**定义，而正文区已经扣掉表头与内边距，
         * 比例与卡片本身不同 —— 用正文区比例判会让 4×2 掉进窄形态（渲染探针把这件事打了出来）。
         *
         * 为什么表头也必须用它：表头文案随形态变（横条写「今日日程」、小正方形写日期），两处各判
         * 一次迟早会出现"表头说自己在画横条、正文画的却是小正方形"。
         *
         * 250dp 与 provider 的 4 格默认尺寸一致；宽高比用于区分横条和正方形。
         */
        internal fun dayWidgetForm(cardWidthPx: Int, cardHeightPx: Int, density: Float): DayWidgetForm {
            val widthDp = cardWidthPx / density
            val heightDp = cardHeightPx / density
            return when {
                widthDp >= 250f && heightDp >= 250f -> DayWidgetForm.Large
                widthDp >= 250f && widthDp >= heightDp * 1.65f -> DayWidgetForm.Band
                else -> DayWidgetForm.Focus
            }
        }

        /**
         * 宿主给这一格的**整卡**尺寸（像素）。
         *
         * 表头（[AppWidgetUtils.refreshTodayWidget]）与正文都要它，所以只留这一份：各读一次
         * `OPTION_APPWIDGET_*` 的话，两处会因为"宿主上报的时刻不同"而对不上。
         *
         * 竖屏取 minWidth / maxHeight，横屏取 maxWidth / minHeight；缺失时遵循 provider 的默认
         * 最小尺寸，不猜手机屏幕宽度。
         */
        internal fun cardBoxPx(context: Context, widgetId: Int): Pair<Int, Int> {
            return AppWidgetUtils.cardBoxPx(context, widgetId)
        }

        internal fun coursesForDay(courseDao: CourseDao, table: TableBean, week: Int): List<CourseBean> =
                courseDao.getCourseByDayOfTableSync(CourseUtils.getWeekdayInt(),
                        week, 1 + week % 2, table.id)

        /**
         * 装载「这一天的日程」：课表、作息、课程、以及已结束与未结束的划分。
         *
         * 表头（RemoteViews 里的 `tv_summary`，画「N 节 · 已上 M」）与正文（RemoteViewsService
         * 画的位图）**必须用同一份结果**：各自查一遍库的话，两处会因为"读的时刻不同"而对不上，
         * 而表头与正文对不上，恰恰是用户一眼就能看出来的那种错。所以装载只有这一份，
         * [TodayColorfulRemoteViewsFactory.onDataSetChanged] 与
         * [AppWidgetUtils.refreshTodayWidget] 都走它。
         */
        internal fun loadDay(context: Context, nowMillis: Long): DayContent {
            val empty = CourseTimes.of(emptyList())
            val database = AppDatabase.getDatabase(context)
            val table = database.tableDao().getDefaultTableSync()
                    ?: return DayContent(null, empty, emptyList(), emptyList(), emptyList(), false)
            val week = try {
                CourseUtils.countWeek(table.startDate, table.sundayFirst)
            } catch (error: ParseException) {
                Log.w("TodayColorful", "开学日期无法解析", error)
                return DayContent(table, empty, emptyList(), emptyList(), emptyList(), true)
            }
            val times = CourseTimes.ofPreferred(context,
                    database.timeDetailDao().getTimeListSync(table.timeTable),
                    database.courseDao().getDetailOfTableSync(table.id))
            val courses = coursesForDay(database.courseDao(), table, week)
                    .sortedBy { CourseReminderScheduler.minutesOfDay(times.startOf(it)) ?: Int.MAX_VALUE }
            return DayContent(table, times, courses,
                    DayWidgetSchedule.completedCourses(times, courses) { nowMillis },
                    DayWidgetSchedule.displayOrder(times, courses) { nowMillis },
                    false)
        }
    }
}
