package courseclock.timetable.schedule

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseFragment
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.Haptics
import courseclock.timetable.utils.ViewUtils
import courseclock.timetable.utils.getPrefer
import courseclock.timetable.widget.TipTextView
import es.dmoral.toasty.Toasty
import splitties.dimensions.dip

class ScheduleFragment : BaseFragment() {

    private var week = 0
    private var weekDay = 1
    private lateinit var weekDate: List<String>
    private val viewModel by activityViewModels<ScheduleViewModel>()
    private lateinit var ui: ScheduleUI
    private lateinit var showCourseNumber: LiveData<Int>
    /** table 尚未加载时为 true；此时 ui 未初始化，生命周期回调必须短路。 */
    private var awaitingTable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            week = it.getInt("week")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // 进程恢复时 FragmentManager 可能在 initView 的协程跑完之前就恢复本页。
        // table/timeList 都是 lateinit，直接读必炸；先出空容器，initViewPage 会重建。
        if (!viewModel.isTableReady()) {
            awaitingTable = true
            return FrameLayout(requireContext())
        }
        awaitingTable = false
        weekDay = CourseUtils.getWeekdayInt()
        ui = ScheduleUI(requireContext(), viewModel.table, if (week == viewModel.currentWeek) weekDay else -1,
                times = viewModel.courseTimes)
        ui.showTimeDetail = requireContext().getPrefer().getBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, true)
        showCourseNumber = viewModel.getShowCourseNumber(week)
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (awaitingTable) return
        // 尚未开学（countWeek = 0）时按第 1 周对齐：curWeek 传 0 会让头部日期被推到
        // 未来第 week 周，显示的是没有意义的日期。
        weekDate = CourseUtils.getDateStringFromWeek(
                maxOf(CourseUtils.countWeek(viewModel.table.startDate, viewModel.table.sundayFirst), 1),
                week, viewModel.table.sundayFirst)
        // 星期轴的文字全部走 ScheduleUI.setWeekAxisCell：字号层级（小字"周几" + 大字日期）和
        // "今天用品牌色"都在那边，这里只负责决定每一格写哪一天。
        ui.setWeekAxisCell(0, "月", weekDate[0])
        for (i in 1..7) {
            if (ui.dayMap[i] == -1) continue
            val column = ui.dayMap[i]
            val date = when {
                i == 7 && !viewModel.table.showSat && !viewModel.table.sundayFirst -> weekDate[7]
                !viewModel.table.showSun && viewModel.table.sundayFirst && i != 7 -> weekDate[column + 1]
                else -> weekDate[column]
            }
            ui.setWeekAxisCell(column, "周" + viewModel.daysArray[i], date)
        }
        if (viewModel.isTimeListReady() && viewModel.timeList.isNotEmpty()) {
            fillTimeColumn()
        }
        // 课程数据在**视图创建时**就挂上观察者，不等 onResume：ViewPager 的邻页（offscreen
        // 缓存里那一周）此时已经创建但不曾 resume，拖动到一半时页面内容就该是现成的，
        // 落定后再装配就是用户看到的「滑完才出来」。Activity 保留 ViewPager 最低邻页缓存，
        // 不再提供额外预加载选项，也不延迟本页的数据装配。
        for (i in 1..7) {
            viewModel.allCourseList[i - 1].observe(viewLifecycleOwner, Observer {
                initWeekPanel(it, i, viewModel.table)
            })
        }
        showCourseNumber.observe(viewLifecycleOwner, Observer {
            if (it == 0) {
                ui.content.visibility = View.GONE
                if (ui.root.getViewById(R.id.anko_empty_view) != null) {
                    return@Observer
                }
                val img = AppCompatImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_schedule_empty)
                }
                ui.root.addView(LinearLayoutCompat(requireContext()).apply {
                    id = R.id.anko_empty_view
                    orientation = LinearLayoutCompat.VERTICAL
                    if (context.getPrefer().getBoolean(Const.KEY_SHOW_EMPTY_VIEW, true)) {
                        addView(img, LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(240))
                    }
                    addView(AppCompatTextView(context).apply {
                        text = "本周没有课程哦"
                        setTextColor(viewModel.table.textColor)
                        gravity = Gravity.CENTER
                    }, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.MATCH_PARENT, LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dip(16)
                    })
                }, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                        ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                    startToStart = ConstraintSet.PARENT_ID
                    endToEnd = ConstraintSet.PARENT_ID
                    topToBottom = R.id.anko_tv_title0
                    bottomToBottom = ConstraintSet.PARENT_ID
                    marginStart = requireContext().dip(32)
                    marginEnd = requireContext().dip(32)
                })
            } else {
                ui.content.visibility = View.VISIBLE
                ui.root.getViewById(R.id.anko_empty_view)?.let { emptyView ->
                    ui.root.removeView(emptyView)
                }
            }
        })
    }

    /**
     * 左侧时间栏的文字：**按节次取，不能按下标**。
     *
     * 导入出来的时间表里同一个节次可能有多行（默认分组 + 各教学楼分组，主键是
     * `(node, timeTable, timeGroup)`），而 DAO 只按 `order by node` 返回；`timeList[i]`
     * 拿到的是"第 i 行"而不是"第 i+1 节"。真机表现：第 6 节的课显示成 08:15，
     * 看起来"每节课都是八点十五分"。
     *
     * 文字一律填上，显示与否由 [ScheduleUI.applyPreferences] 按设置控制 —— 这样"节数栏显示
     * 具体时间"这个开关才能当场生效，而不是等重建视图。
     */
    private fun fillTimeColumn() {
        val times = viewModel.courseTimes
        for (i in 0 until viewModel.table.nodes) {
            val row = times.defaultTimeForNode(i + 1) ?: continue
            // 同 ScheduleAppWidgetService：节点行按 `table.nodes` 动态生成，取不到就跳过。
            val nodeRow = ui.content.getViewById(R.id.anko_tv_node1 + i) as? FrameLayout ?: continue
            nodeRow.findViewById<AppCompatTextView>(R.id.tv_start).text = row.startTime
            nodeRow.findViewById<AppCompatTextView>(R.id.tv_end).text = row.endTime
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingTable || !::ui.isInitialized) return
        // 设置页改的是 SharedPreferences，课表视图却是一次性搭好的：回到前台时同步一次，
        // 「课表显示虚线网格」「节数栏显示具体时间」「课表下方增加留白区域」才是当场生效。
        ui.applyPreferences()
    }

    companion object {
        @JvmStatic
        fun newInstance(week: Int) =
                ScheduleFragment().apply {
                    arguments = Bundle().apply {
                        putInt("week", week)
                    }
                }
    }

    private fun initWeekPanel(data: List<CourseBean>?, day: Int, table: TableBean) {
        val ll = ui.content.getViewById(R.id.anko_ll_week_panel_0 + ui.dayMap[day] - 1) as FrameLayout?
                ?: return
        ll.removeAllViews()
        if (data == null || data.isEmpty()) return

        data class Slot(val course: CourseBean, val isOtherWeek: Boolean, val priority: Int)

        val prepared = ArrayList<Slot>()
        for (c in data) {
            if (c.endWeek < week) continue
            val isOtherWeek = (week % 2 == 0 && c.type == 1) || (week % 2 == 1 && c.type == 2)
                    || (c.startWeek > week)
            if (!table.showOtherWeekCourse && isOtherWeek) continue

            if (c.step <= 0) {
                c.step = 1
                Toasty.info(requireContext(), R.string.error_course_data, Toast.LENGTH_LONG).show()
            }
            if (c.startNode <= 0) {
                c.startNode = 1
                Toasty.info(requireContext(), R.string.error_course_data, Toast.LENGTH_LONG).show()
            }
            if (c.startNode > table.nodes) {
                c.startNode = table.nodes
                Toasty.info(requireContext(), R.string.error_course_node, Toast.LENGTH_LONG).show()
            }
            if (c.startNode + c.step - 1 > table.nodes) {
                c.step = table.nodes - c.startNode + 1
                Toasty.info(requireContext(), R.string.error_course_node, Toast.LENGTH_LONG).show()
            }
            prepared.add(Slot(c, isOtherWeek, viewModel.overlapPriority(c, week)))
        }
        if (prepared.isEmpty()) return

        // 按优先级从高到低：先到的课程占住时间区间，重叠课程由详情切换入口展示。
        // 不再做「露边叠卡」——上一版露边把非本周/免听的淡化整卡都摊在格子上，反而更糊。
        val ordered = prepared.sortedBy { it.priority }
        val winners = ArrayList<Slot>()

        for (slot in ordered) {
            val covered = winners.any { viewModel.coursesOverlap(it.course, slot.course) }
            if (!covered) {
                winners.add(slot)
            }
        }

        // 先加隐藏块（INVISIBLE，不抢点击），再加赢家，保证赢家在上层。
        for (item in ordered) {
            val c = item.course
            val isOtherWeek = item.isOtherWeek
            val isHidden = winners.none { it.course === c }

            val strBuilder = StringBuilder()
            if (c.notAttend) {
                strBuilder.append("[免听]")
            }
            strBuilder.append(c.courseName)
            if (c.room != "") {
                strBuilder.append("\n@${c.room}")
            }
            when (c.type) {
                1 -> strBuilder.append("\n单周")
                2 -> strBuilder.append("\n双周")
            }
            if (isOtherWeek) {
                strBuilder.append("[非本周]")
            }
            if (table.showTime && viewModel.isTimeListReady() && viewModel.timeList.isNotEmpty()) {
                strBuilder.insert(0, viewModel.courseTimes.startOfNode(c.startNode, c.timeGroup) + "\n")
            }

            if (c.color.isEmpty()) {
                c.color = "#${Integer.toHexString(ViewUtils.getCustomizedColor(requireActivity(), c.id % 9))}"
            }

            val textView = TipTextView(requireContext())
            textView.setPadding(requireContext().dip(4))
            textView.init(
                    text = strBuilder.toString(),
                    txtSize = table.itemTextSize,
                    txtColor = table.courseTextColor,
                    bgColor = ViewUtils.parseCourseColor(c.color,
                            ViewUtils.getCustomizedColor(requireActivity(), c.id % 9)),
                    bgAlpha = viewModel.alphaInt,
                    stroke = table.strokeColor
            )

            val box = ui.blockBoxOf(viewModel.courseTimes, c.startNode, c.step, c.timeGroup)
            ll.addView(textView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    box.height).apply {
                gravity = Gravity.TOP
                topMargin = box.top
            })

            if (isHidden) {
                // 被完全遮挡：不接收点击，可从重叠课程详情中选择。
                textView.visibility = View.INVISIBLE
            } else {
                if (isOtherWeek) {
                    textView.tipVisibility = TipTextView.TIP_OTHER_WEEK
                } else if (c.notAttend) {
                    textView.notAttendCard = true
                }

                val overlaps = if (c.inWeek(week)) viewModel.getMultiCourse(week, day, c).count { it != c } else 0
                if (overlaps > 0) {
                    textView.showMultiTip = true
                    textView.setOnClickListener {
                        // 课表格子是这一屏最主要的目标：按下去立刻弹出详情，给一下明确回执。
                        Haptics.tap(textView)
                        MultiCourseFragment.newInstance(week, day, c)
                                .show(parentFragmentManager, "multi")
                    }
                } else {
                    textView.setOnClickListener {
                        Haptics.tap(textView)
                        try {
                            CourseDetailFragment.newInstance(c, week)
                                    .show(parentFragmentManager, "courseDetail")
                        } catch (e: Exception) {
                            Toasty.error(requireActivity().applicationContext,
                                    "这条课程的数据有问题：${e.message ?: e.javaClass.simpleName}\n" +
                                            "可在「多课表管理」里删除这节课后重新添加", Toasty.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

}
