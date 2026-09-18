package courseclock.timetable.schedule

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.ColorUtils
import courseclock.timetable.R
import courseclock.timetable.base_view.Ui
import courseclock.timetable.bean.TableBean
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseTimes
import courseclock.timetable.utils.getPrefer
import courseclock.timetable.utils.scheduleTextColor
import splitties.dimensions.dip
import splitties.dimensions.dp
import splitties.resources.color

// table / forWidget 必须是属性：applyPreferences() 是成员函数，看不到构造参数。
class ScheduleUI(override val ctx: Context, private val table: TableBean, private val day: Int,
                 private val forWidget: Boolean = false, times: CourseTimes? = null) : Ui {

    /** 课程色块上的那条线（裁剪到色块内）。深色主题下算出来是 0，就没有这一层。 */
    private var gridForeground: DashedGridDrawable? = null

    companion object {
        /**
         * 空白处线的强度。
         *
         * 原先是单层 0.30（浅色）/ 0.55（深色），两处都被反馈"太深/太显眼"。
         * 浅色压到 0.18、深色压到 0.35；被色块盖住的那部分由前景层补足，观感不变（见下）。
         */
        private const val ALPHA_GRID_LIGHT = 0.18f
        private const val ALPHA_GRID_DARK = 0.35f

        /**
         * 课程色块**覆盖处**期望的线强度。
         *
         * 浅色 0.12 = 单层 0.30 被 60% 不透明的色块盖住后剩下的（0.4 × 0.30），也就是用户认可的
         * "穿过课程"观感；深色 0.22 = 上一轮调亮后的 0.4 × 0.55，同样保持不动。
         */
        private const val ALPHA_UNDER_COURSE_LIGHT = 0.12f
        private const val ALPHA_UNDER_COURSE_DARK = 0.22f

        /** 课程色块默认不透明度（TableBean.itemAlpha 默认 60）。用户调透明后这条线会更明显，不追求逐值精确。 */
        private const val BLOCK_ALPHA = 0.6f

        /**
         * 星期轴的字号：上行"周几 / 月"小字，下行日期大字。
         *
         * 两行各是一个 TextView，不再把两个字号塞进同一个 TextView：同一个 TextView 里换字号时，
         * 每一行都按**整段里最大的那个字号**算行高（实测整行因此从 40dp 涨到 51.7dp），
         * 想压缩反而更占地方。分成两行以后行高就是两行字自己的高度。
         */
        private const val WEEK_AXIS_LABEL_SP = 11f
        private const val WEEK_AXIS_VALUE_SP = 16f

        /** 星期与日期之间的空隙，以及这一行上下的留白。合计约 40dp，与设计稿的 41dp 一致。 */
        private const val WEEK_AXIS_GAP_DP = 4
        private const val WEEK_AXIS_PADDING_TOP_DP = 3
        private const val WEEK_AXIS_PADDING_BOTTOM_DP = 2

        /** 星期轴上"星期几 / 月"的次要文字强度：正文色压到这个不透明度就是这套界面的次文字色。 */
        private const val AXIS_LABEL_ALPHA = 0.32f

        /** 今天那一列下面的滑块（设计稿：24×3dp 圆角条，骑在星期轴的横线上）。 */
        private const val TODAY_SLIDER_WIDTH_DP = 24
        private const val TODAY_SLIDER_HEIGHT_DP = 3
    }

    private var col = 6

    var showTimeDetail = true

    val dayMap = IntArray(8)
    val itemHeight = ctx.dip(if (forWidget) table.widgetItemHeight else table.itemHeight)

    /**
     * 行与行之间的行距（像素），**全 App 唯一一处行距来源**。
     *
     * 行视图的 topMargin 与课块的纵向摆放（[CourseTimes.blockBox] 的 gap）都读它，
     * 于是"格子"和"课块"用的是同一个数，不可能再各自漂移。
     *
     * 原来是两处各算一遍同一个 2dp：这里是 `dip(2)`（截断 → 6px），
     * ViewModel 里是 `getDimensionPixelSize`（四舍五入 → 7px）。520dpi 下密度 3.25，
     * 2dp = 6.5px 正好卡在中间，于是课块的行距比格子大 1px：**每向下一节多偏 1px**，
     * 第 6 节低 6px、第 12 节低 12px —— 用户报的"下午的每一节课整体偏下、
     * 上边没挨着上边框线、下边超出下边框"就是这个（上午只偏 1~5px，所以没被察觉）。
     */
    val rowGap = ctx.resources.getDimensionPixelSize(R.dimen.weekItemMarTop)

    /**
     * 课块该摆在哪 —— 格子尺寸的唯一出口。
     *
     * 主课表和周视图小部件都走这里，调用方拿不到"自己再算一份行高/行距"的机会：
     * 只要格子是 [itemHeight]/[rowGap] 摆的，课块就必然落在同一套格子里。
     */
    fun blockBoxOf(times: CourseTimes, startNode: Int, step: Int, timeGroup: String): CourseTimes.BlockBox =
            times.blockBox(startNode, step, timeGroup, itemHeight, rowGap)
    // 小部件文字直接读颜色资源：RemoteViewsService 的 ApplicationContext 没有 Activity 主题，
    // styledColor(R.attr.colorOnBackground) 会解析错；底板文字色见 widget_panel_text（含 night）。
    val textColor = if (forWidget) {
        androidx.core.content.ContextCompat.getColor(ctx, R.color.widget_panel_text)
    } else {
        // 与顶部栏/五个图标按钮同一个取色来源：深色模式下必须浅色，
        // 否则时间栏、节次、虚线也是一片纯黑，在深色底上直接消失。
        scheduleTextColor(ctx, table)
    }

    /** 星期轴上次要文字（星期几 / 「月」）的颜色：正文色压到 [AXIS_LABEL_ALPHA]。 */
    private val axisLabelColor = ColorUtils.setAlphaComponent(
            textColor, (AXIS_LABEL_ALPHA * (textColor shr 24 and 0xff)).toInt())

    /**
     * "今天"那一列的高亮色。
     *
     * 取主题的品牌色（@color/colorPrimary）而不是设计稿里的具体色值：设计稿的 accent 是一个
     * 角色，落地时指向主题的品牌色，将来换品牌色只改资源，不用回来改这里。
     */
    private val todayColor = ctx.color(R.color.colorPrimary)

    init {
        for (i in 1..7) {
            if (!table.sundayFirst || !table.showSun) {
                if (!table.showSat && i == 7) {
                    dayMap[i] = 6
                } else {
                    dayMap[i] = i
                }
            } else {
                if (i == 7) {
                    dayMap[i] = 1
                } else {
                    dayMap[i] = i + 1
                }
            }
        }
        if (table.showSat) {
            col++
        } else {
            dayMap[6] = -1
        }
        if (table.showSun) {
            col++
        } else {
            dayMap[7] = -1
        }
    }

    /**
     * 每一行下面要不要画一条网格横线；false 表示这一行与下一行属于**同一段连堂**。
     *
     * 判据在 [CourseTimes.hasGridLineAfter]（相邻两节起止时刻相同即同一格）。没有作息数据
     * （[times] 为 null，或该课表还没载入时间表）时全部为 true —— 观感与加这个功能之前一致，
     * 不会因为数据没到就把线全画没了。
     */
    private val gridLinesAfterRow: BooleanArray =
            BooleanArray(table.nodes) { index -> times?.hasGridLineAfter(index + 1) ?: true }

    /**
     * 这一行是不是它所在连堂的**首行**：开始时间只在首行显示一次。
     *
     * 起因（真机截图）：第 3、4 节同属 09:55~11:15 一格，两行各自都显示"开始/结束"，
     * 而连堂内部又不画分隔线，于是时间栏竖着读成 `11:15 → 09:55` —— 看起来时间倒流。
     * 学校印的课表也是一格只标一次上/下课时间，所以这里按格收口：
     * 首行给开始时间（[blockStartRow]），末行给结束时间（[gridLinesAfterRow]），
     * 中间那些行只留节次号。
     */
    private val blockStartRow: BooleanArray =
            BooleanArray(table.nodes) { index -> index == 0 || gridLinesAfterRow[index - 1] }

    /** 某一行的开始/结束时间要不要显示。设置关掉「节数栏显示具体时间」时两边都不显示。 */
    private fun timeLabelsVisible(index: Int, detailTime: Boolean): Pair<Boolean, Boolean> =
            (detailTime && blockStartRow[index]) to (detailTime && gridLinesAfterRow[index])

    val content = ConstraintLayout(ctx).apply {
        id = R.id.anko_cl_content_panel
        // 虚线网格画在 content 的背景上（背景不参与测量，不会像子视图那样把课表高度带塌）。
        // 拆两层：背景层管空白处，前景层裁剪到课程色块内补足"穿过课程"的差额 —— 见 DashedGridDrawable。
        // 深浅判断按**课表文字颜色**，不看系统夜间模式。
        //
        // App 的深色是它自己的设置（可以强制深色而系统仍是浅色，也能反过来），拿 resources 的
        // uiMode 判断会挑错分支：该用深色那套线值时用了浅色那套，用户看到的就是"改了跟没改一样"。
        // 课表文字本来就是这个界面用来自适应底色的信号（深色主题下它是白的），用它更准。
        val darkBackdrop = ColorUtils.calculateLuminance(textColor) > 0.5
        val backgroundAlpha = if (darkBackdrop) ALPHA_GRID_DARK else ALPHA_GRID_LIGHT
        val underCourseAlpha = if (darkBackdrop) ALPHA_UNDER_COURSE_DARK else ALPHA_UNDER_COURSE_LIGHT
        background = DashedGridDrawable(this, textColor, table.nodes, col - 1, backgroundAlpha,
                darkBackdrop = darkBackdrop, linesAfterRow = gridLinesAfterRow)
        // 课程上的线：背景层穿过色块（默认 60% 不透明）后只剩四成，前景层补足到原来的观感强度。
        val overCourse = underCourseAlpha - (1f - BLOCK_ALPHA) * backgroundAlpha
        gridForeground = if (overCourse > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            DashedGridDrawable(this, textColor, table.nodes, col - 1, overCourse,
                    clipToCourses = true, darkBackdrop = darkBackdrop,
                    linesAfterRow = gridLinesAfterRow).also { foreground = it }
        } else {
            null
        }
        val timeSize = when (col) {
            7 -> 9f
            6 -> 10f
            else -> 8f
        }
        for (i in 1..table.nodes) {
            val (startVisible, endVisible) = timeLabelsVisible(i - 1, showTimeDetail)
            addView(FrameLayout(context).apply {
                id = R.id.anko_tv_node1 + i - 1
                // 两个时间标签常驻，显不显示交给 applyPreferences()：与网格同理，
                // "加不加"的写法会让设置必须重建视图才生效。
                // 可见性还要叠上"连堂只在首行给开始、末行给结束"这一层（见 blockStartRow）。
                //
                // 注意：可见性必须设在**标签自己**身上。这里原来写成
                // `LayoutParams(...).apply { visibility = ... }`，而 FrameLayout.LayoutParams
                // 没有 visibility 这个成员，Kotlin 就把赋值落到了外层**行 FrameLayout** 上
                // ——标签自身从来没被控制过，行却会在 showTimeDetail=false 时整行隐藏
                // （连节次号一起消失）。布局参数里只放布局参数。
                addView(AppCompatTextView(context).apply {
                    id = R.id.tv_start
                    setTextColor(textColor)
                    //gravity = Gravity.CENTER
                    //textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setSingleLine()
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, timeSize)
                    visibility = if (startVisible) View.VISIBLE else View.GONE
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                })
                addView(AppCompatTextView(context).apply {
                    id = R.id.tv_end
                    setTextColor(textColor)
                    setSingleLine()
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, timeSize)
                    visibility = if (endVisible) View.VISIBLE else View.GONE
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                })
                addView(AppCompatTextView(context).apply {
                    setTextColor(textColor)
                    text = i.toString()
                    textSize = 12f
                    setSingleLine()
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                })
            }, ConstraintLayout.LayoutParams(0, itemHeight).apply {
                topMargin = rowGap
                endToStart = R.id.anko_ll_week_panel_0
                horizontalWeight = 0.5f
                startToStart = ConstraintSet.PARENT_ID
                when (i) {
                    1 -> {
                        bottomToTop = R.id.anko_tv_node1 + i
                        topToTop = ConstraintSet.PARENT_ID
                        verticalBias = 0f
                        verticalChainStyle = ConstraintSet.CHAIN_PACKED
                    }
                    table.nodes -> {
                        //bottomToTop = R.id.anko_navigation_bar_view
                        bottomToBottom = ConstraintSet.PARENT_ID
                        topToBottom = R.id.anko_tv_node1 + i - 2
                    }
                    else -> {
                        bottomToTop = R.id.anko_tv_node1 + i
                        topToBottom = R.id.anko_tv_node1 + i - 2
                    }
                }
            })
        }

        // 留白区只在需要时**真的加进来**：它在约束里是 bottomToBottom=PARENT 的一个块，
        // 留个 GONE 的占位会让 wrap_content 的高度计算多一层不必要的纠结。
        if (!forWidget && context.getPrefer().getBoolean(Const.KEY_SCHEDULE_BLANK_AREA, true)) {
            addView(createBlankArea(), blankAreaParams())
        }

        for (i in 0 until col - 1) {
            addView(FrameLayout(context).apply { id = R.id.anko_ll_week_panel_0 + i }, ConstraintLayout.LayoutParams(0,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dip(1)
                marginEnd = dip(1)
                horizontalWeight = 1f
                when (i) {
                    0 -> {
                        startToEnd = R.id.anko_tv_node1
                        endToStart = R.id.anko_ll_week_panel_0 + i + 1
                    }
                    col - 2 -> {
                        startToEnd = R.id.anko_ll_week_panel_0 + i - 1
                        endToEnd = ConstraintSet.PARENT_ID
                        if (!forWidget) {
                            marginEnd = if (col < 8) {
                                dip(8)
                            } else {
                                dip(4)
                            }
                        }
                    }
                    else -> {
                        startToEnd = R.id.anko_ll_week_panel_0 + i - 1
                        endToStart = R.id.anko_ll_week_panel_0 + i + 1
                    }
                }
            })
        }
    }

    val scrollView = ScrollView(ctx).apply {
        id = R.id.anko_sv_schedule
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(content)
    }

    /**
     * 把「设置」里那几项课表显示选项**当场**应用到已经搭好的视图上。
     *
     * 课表视图是 [ScheduleFragment.onCreateView] 一次性搭出来的，而设置页改的是 SharedPreferences
     * ——「加不加视图」的写法必须重建整个课表才生效。真机上就是这么被问"为什么勾了虚线不立刻生效"
     * 的：用户勾完开关回到课表，什么都没变，只能靠重启 App。
     *
     * 所以网格、时间标签、留白区三个视图都常驻，这里只改它们的显示状态；调用点是
     * [ScheduleFragment.onResume]，也就是"从设置页回来"的那一刻。
     */
    fun applyPreferences() {
        val prefer = ctx.getPrefer()
        (content.background as? DashedGridDrawable)?.syncWithPreference()
        gridForeground?.syncWithPreference()

        val detailTime = prefer.getBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, true)
        showTimeDetail = detailTime
        for (i in 0 until table.nodes) {
            val row = content.findViewById<FrameLayout>(R.id.anko_tv_node1 + i) ?: continue
            // 与构造时同一套判据：连堂首行才显示开始时间、末行才显示结束时间
            val (startVisible, endVisible) = timeLabelsVisible(i, detailTime)
            row.findViewById<View>(R.id.tv_start)?.visibility =
                    if (startVisible) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.tv_end)?.visibility =
                    if (endVisible) View.VISIBLE else View.GONE
        }

        // 留白区是按需存在的（见构造函数里的说明），所以这里要真的加/删，而不能只改 visibility。
        if (!forWidget) {
            val wanted = prefer.getBoolean(Const.KEY_SCHEDULE_BLANK_AREA, true)
            val existing = content.findViewById<View>(R.id.anko_schedule_blank_area)
            when {
                wanted && existing == null -> content.addView(createBlankArea(), blankAreaParams())
                !wanted && existing != null -> content.removeView(existing)
            }
        }
    }

    private fun createBlankArea(): View = View(ctx).apply { id = R.id.anko_schedule_blank_area }

    private fun blankAreaParams(): ConstraintLayout.LayoutParams =
            ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, itemHeight * 4).apply {
                topToBottom = R.id.anko_tv_node1 + table.nodes - 1
                bottomToBottom = ConstraintSet.PARENT_ID
                startToStart = ConstraintSet.PARENT_ID
                endToEnd = ConstraintSet.PARENT_ID
            }

    /** 某一列（`anko_tv_title0` 的后缀）是不是今天。文字取色与滑块共用这一条判据。 */
    private fun isTodayColumn(column: Int): Boolean = day > 0 && column == dayMap[day]

    /**
     * 往星期轴上写一格：上行小字是"周几"（月份格写「月」），下行大字是日期。
     *
     * 写什么由 [courseclock.timetable.schedule.ScheduleFragment] 决定（只有它知道这一格是哪一天、
     * 这一周从哪天开始），怎么显示留在这里：字号层级、加粗、"今天用品牌色"都只有这一份定义。
     */
    fun setWeekAxisCell(column: Int, label: String, value: String) {
        val cell = root.findViewById<View>(R.id.anko_tv_title0 + column) ?: return
        val today = isTodayColumn(column)
        cell.findViewById<AppCompatTextView>(R.id.anko_tv_title_label).apply {
            text = label
            setTextColor(if (today) todayColor else axisLabelColor)
        }
        cell.findViewById<AppCompatTextView>(R.id.anko_tv_title_value).apply {
            text = value
            setTextColor(if (today) todayColor else textColor)
        }
    }

    override val root = ConstraintLayout(ctx).apply {
        for (i in 0 until col) {
            // 一格 = 上下两行，各是一个 TextView：同一个 TextView 里塞两种字号时，
            // 每行的行高按整段里最大的字号算，整行会比两行字本身高出一大截（实测 51.7dp vs 40dp）。
            addView(LinearLayoutCompat(context).apply {
                id = R.id.anko_tv_title0 + i
                orientation = LinearLayoutCompat.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dip(WEEK_AXIS_PADDING_TOP_DP), 0, dip(WEEK_AXIS_PADDING_BOTTOM_DP))
                addView(AppCompatTextView(context).apply {
                    id = R.id.anko_tv_title_label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, WEEK_AXIS_LABEL_SP)
                    // 关掉字体自带的上下留白：它不由内容决定，只会把这一行撑高。
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                }, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT,
                        LinearLayoutCompat.LayoutParams.WRAP_CONTENT))
                addView(AppCompatTextView(context).apply {
                    id = R.id.anko_tv_title_value
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, WEEK_AXIS_VALUE_SP)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT,
                        LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dip(WEEK_AXIS_GAP_DP)
                })
            }, ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                when (i) {
                    0 -> {
                        horizontalWeight = 0.5f
                        startToStart = ConstraintSet.PARENT_ID
                        topToTop = ConstraintSet.PARENT_ID
                        endToStart = R.id.anko_tv_title0 + i + 1
                    }
                    col - 1 -> {
                        horizontalWeight = 1f
                        startToEnd = R.id.anko_tv_title0 + i - 1
                        endToEnd = ConstraintSet.PARENT_ID
                        baselineToBaseline = R.id.anko_tv_title0 + i - 1
                        if (!forWidget) {
                            marginEnd = if (col < 8) {
                                dip(8)
                            } else {
                                dip(4)
                            }
                        }
                    }
                    else -> {
                        horizontalWeight = 1f
                        startToEnd = R.id.anko_tv_title0 + i - 1
                        endToStart = R.id.anko_tv_title0 + i + 1
                        baselineToBaseline = R.id.anko_tv_title0 + i - 1
                    }
                }
            })
        }

        // 今天那一列的滑块：宽度固定、跟着列居中，正好骑在这一行的下沿（也就是网格顶线）上。
        // 没有"今天"（看的是别的周）时不加这个视图，省得留个看不见的占位。
        if (day > 0 && dayMap[day] in 1 until col) {
            val column = R.id.anko_tv_title0 + dayMap[day]
            addView(View(context).apply {
                id = R.id.anko_tv_today_slider
                // 上移半个高度：线在这一行的下沿，滑块要骑在线上而不是压在线上方。
                translationY = dip(TODAY_SLIDER_HEIGHT_DP).toFloat() / 2
                background = GradientDrawable().apply {
                    setColor(todayColor)
                    cornerRadius = dip(TODAY_SLIDER_HEIGHT_DP).toFloat() / 2
                }
            }, ConstraintLayout.LayoutParams(dip(TODAY_SLIDER_WIDTH_DP), dip(TODAY_SLIDER_HEIGHT_DP)).apply {
                startToStart = column
                endToEnd = column
                bottomToBottom = column
            })
        }

        addView(scrollView, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT).apply {
            bottomToBottom = ConstraintSet.PARENT_ID
            // 网格从**整行**的下沿开始，而不是从第 0 格的下沿开始。星期轴各格的文字结构不完全
            // 一样，字号一变，格子的高矮就会差几个像素；把 ScrollView 钉在某一格上，只要那一格
            // 比别人矮，日期就会压进课表里（这正是这一版第一次改完实测到的 8px 重叠）。
            // Barrier 取的是所有格子里最低的那条边，谁高就跟谁走。
            topToBottom = R.id.anko_week_axis_bottom
            startToStart = ConstraintSet.PARENT_ID
            endToEnd = ConstraintSet.PARENT_ID
        })
        addView(Barrier(context).apply {
            id = R.id.anko_week_axis_bottom
            type = Barrier.BOTTOM
            referencedIds = IntArray(col) { R.id.anko_tv_title0 + it }
        })
    }
}

/**
 * 竖线网格的横坐标：**每一列的左边界 + 最后一列的右边界**，共 N+1 条。
 *
 * 抽成纯函数是因为这条几何契约（"N 天要被 N+1 条线框住"）值得单独钉住：
 * 少画最后一条，外面看着只是"少一条外框线"；少画某一列的左边界（例如曾经把最后一列
 * 改成取右边界），**列与列之间的那条线就没了** —— 真机上就是"周六与周日之间缺竖虚线"。
 */
internal fun verticalGridLineXs(columns: List<Pair<Float, Float>>): List<Float> {
    if (columns.isEmpty()) return emptyList()
    return columns.map { it.first } + columns.last().second
}

/**
 * 课表虚线网格，画在 [ScheduleUI.content] 的**背景**上。
 *
 * ## 为什么必须是背景，不能是一个子视图
 *
 * 它一开始是 `content` 的一个子视图（MATCH_PARENT × MATCH_PARENT，四条边都贴 parent）。
 * 后果是灾难性的：`content` 是被 ScrollView 以"高度不限"测量的，而一个 MATCH_PARENT 的子视图
 * 会让 ConstraintLayout 的高度**直接塌成 0** —— 主课表整张空白，周视图小部件每次出图都抛
 * `IllegalArgumentException: width and height must be > 0`
 * （`ViewUtils.getViewBitmap` 把子视图高度加起来当位图高度），进程被反复打死。
 *
 * 背景不参与测量，从根上不可能影响课表高度；绘制顺序上背景也在所有子视图之前，
 * 课程色块照样压在网格线上面。
 *
 * 线的位置不去手算，而是**读兄弟视图的最终布局**。节点行与星期列都是 [ScheduleUI.content]
 * 的直接子视图，横坐标里有权重（时间列 0.5 份、每天 1 份）、列间距、以及显示/隐藏周六周日
 * 带来的列数变化，纵坐标里有行高与行距；手算迟早会和约束的实际结果对不上，读 `left/right/bottom`
 * 则永远一致。
 *
 * 画不画由设置决定，且**可以当场切换**（[syncWithPreference] 由 [ScheduleUI.applyPreferences]
 * 在回到前台时调用）。
 */
private class DashedGridDrawable(
        private val host: ViewGroup,
        textColor: Int,
        private val rowCount: Int,
        private val columnCount: Int,
        /** 线的不透明度。 */
        private val lineAlpha: Float,
        /**
         * true = 只在**课程色块覆盖到的区域**画（前景层）。
         *
         * 网格要拆两层是因为一件事：空白处看到的是线的全强度，而色块下面只剩
         * `1 − 色块不透明度`（默认 40%）——同一层线没法既让空白处浅、又让穿过课程那部分保持原样。
         * 背景层管空白处，这一层裁剪到色块矩形内补足差额，两层加起来才是"穿过课程"的观感。
         */
        private val clipToCourses: Boolean = false,

        /** 深色底上线的对比度本来就低，加粗一点。由调用方按课表文字颜色判断后传进来。 */
        private val darkBackdrop: Boolean = false,

        /**
         * 第 i 行下面要不要画横线（false = 这一行与下一行是同一段连堂，见
         * [CourseTimes.hasGridLineAfter]）。缺省全部画，保持没有作息数据时的旧观感。
         */
        private val linesAfterRow: BooleanArray? = null
) : Drawable() {

    private val linePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = (if (darkBackdrop) 1.5f else 1f) * host.resources.displayMetrics.density
        color = ColorUtils.setAlphaComponent(textColor, (lineAlpha * (textColor shr 24 and 0xff)).toInt())
        pathEffect = DashPathEffect(
                floatArrayOf(host.context.dip(4).toFloat(), host.context.dip(3).toFloat()), 0f)
    }

    /**
     * 外框线用的**实线**画笔：颜色、粗细、透明度与 [linePaint] 完全一致，只去掉虚线效果。
     *
     * 「虚线网格」指的是**格内**的横线（课程行之间的分隔）；顶边、时间栏右侧那条竖线、
     * 以及最右侧的外框线属于**外框** —— 它们用虚线画出来像是"没画完"，用户报的正是这三处。
     * 于是外框实线、格内虚线：一张实线框住的网格，而不是一张虚线网。
     * 设置项仍叫「显示虚线网格」，它管的还是格内那层。
     */
    private val framePaint = Paint(linePaint).apply { pathEffect = null }

    /** 兄弟视图只在第一次绘制时解析一次；[ScheduleUI.content] 的子视图集合此后不再变化。 */
    private var rows: List<View> = emptyList()
    private var columns: List<View> = emptyList()

    private var enabled = false

    init {
        syncWithPreference()
    }

    /** 重新读一次设置；值变了就重画。 */
    fun syncWithPreference() {
        val on = host.context.getPrefer().getBoolean(Const.KEY_SCHEDULE_GRID, true)
        if (on != enabled) {
            enabled = on
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        if (!enabled) return
        if (rows.isEmpty()) {
            rows = (0 until rowCount).mapNotNull { host.findViewById<View>(R.id.anko_tv_node1 + it) }
            columns = (0 until columnCount).mapNotNull { host.findViewById<View>(R.id.anko_ll_week_panel_0 + it) }
        }
        val clip = if (clipToCourses) courseClipPath() else null
        if (clipToCourses && (clip == null || clip.isEmpty)) return
        if (clip != null) {
            canvas.save()
            canvas.clipPath(clip)
        }
        val top = (rows.firstOrNull()?.top ?: 0).toFloat()
        val bottom = (rows.lastOrNull()?.bottom ?: 0).toFloat()
        // 顶边一条：原来网格没有上边框，第一节的课看起来"上面没有线"（用户提的）。
        // 画在第一节的上边界上，于是第一节的课块上边正好压在它上面。
        // 用实线：它是外框，不是格内的分隔线。
        rows.firstOrNull()?.let {
            canvas.drawLine(0f, top, bounds.width().toFloat(), top, framePaint)
        }
        if (bottom > top) {
            // 竖线：每一列的**左边界**都画，最后再补最后一列的右边界 —— N 天是 N+1 条线。
            //
            // 原来写成"每列取左边界，最后一列改取右边界"，意图是"N 天恰好 N 条竖线"，
            // 但那样最后一列自己的左边界被跳过了：7 天时画出来的是
            // 时间列|一、一|二、…、五|六、以及周日右侧那条外框，**周六与周日之间没有线**
            // （用户看到的就是这个）。每列都收左边 + 末尾收右边，才把每一天都框住。
            val xs = verticalGridLineXs(columns.filter { it.width > 0 }
                    .map { view -> view.left.toFloat() to view.right.toFloat() })
            xs.forEachIndexed { index, x ->
                // 首尾两条是外框（时间栏右侧、最右侧），用实线；中间的列分隔线仍随虚线网格。
                val paint = if (index == 0 || index == xs.lastIndex) framePaint else linePaint
                canvas.drawLine(x, top, x, bottom, paint)
            }
        }
        // 横线画在**行距正中**，而不是上一行的下边界。
        //
        // 课块的顶边 == 行视图的顶边（见 CourseTimes.naturalBox：top = (节点-1)*pitch + 行距），
        // 所以线要画在行距正中，课块上下各留半个行距，两边看着都"挨着"线；
        // 线要是画在上一行的下边界，课块上边就永远差一个整行距（下边倒是正好压着线）。
        // 行距从兄弟视图量出来（相邻两行 top 与 bottom 之差），不额外传常量 ——
        // 它与 ScheduleUI.rowGap、与课块的 gap 是同一个数，格子线因此永远跟着真实的行走。
        val gapPx = if (rows.size > 1) (rows[1].top - rows[0].bottom) else 0
        for ((index, row) in rows.withIndex()) {
            // 连堂内部不画线：第 1、2 节共用一格（8:15~9:35），中间那条虚线会把
            // 一次连堂读成两节课。判据见 CourseTimes.hasGridLineAfter。
            if (linesAfterRow?.getOrNull(index) == false) continue
            val y = row.bottom + gapPx / 2f
            canvas.drawLine(0f, y, bounds.width().toFloat(), y, linePaint)
        }
        if (clip != null) canvas.restore()
    }

    /** 所有可见课程色块的并集，用 [ScheduleUI.content] 的坐标系表示。 */
    private fun courseClipPath(): Path? {
        val path = Path()
        val rect = RectF()
        columns.forEach { column ->
            if (column !is ViewGroup) return@forEach
            for (index in 0 until column.childCount) {
                val block = column.getChildAt(index)
                if (block.visibility != View.VISIBLE || block.width == 0 || block.height == 0) continue
                rect.set((column.left + block.left).toFloat(), (column.top + block.top).toFloat(),
                        (column.left + block.right).toFloat(), (column.top + block.bottom).toFloat())
                path.addRect(rect, Path.Direction.CW)
            }
        }
        return path
    }

    override fun setAlpha(alpha: Int) {
        linePaint.alpha = alpha
        framePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        linePaint.colorFilter = colorFilter
        framePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
