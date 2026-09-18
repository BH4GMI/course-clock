package courseclock.timetable.schedule

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.BaseDialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import courseclock.timetable.R
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.course_add.AddCourseActivity
import courseclock.timetable.databinding.FragmentCourseDetailBinding
import courseclock.timetable.settings.SettingsActivity
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.CourseDetailText
import courseclock.timetable.utils.Haptics
import courseclock.timetable.utils.ViewUtils
import courseclock.timetable.utils.appScope
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One detail surface for both single courses and overlapping arrangements. */
open class CourseDetailFragment : BaseDialogFragment() {
    override val layoutId: Int get() = R.layout.fragment_course_detail
    protected val viewModel by activityViewModels<ScheduleViewModel>()
    private var courses = emptyList<CourseBean>()
    private var selected = 0
    private val course get() = courses[selected]
    private var deleting = false
    private var _binding: FragmentCourseDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val focus = arguments?.getParcelable<CourseBean>("course")
        courses = arguments?.getParcelableArrayList<CourseBean>("courses")?.toList()
                ?.takeIf { it.isNotEmpty() } ?: listOfNotNull(focus)
        selected = (savedInstanceState?.getInt("selected")
                ?: courses.indexOf(focus).coerceAtLeast(0)).coerceIn(0, (courses.size - 1).coerceAtLeast(0))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCourseDetailBinding.bind(view)
        if (courses.isEmpty()) {
            Toasty.error(requireContext(), "课程信息已失效，请重新打开").show()
            dismiss()
            return
        }
        // 这一屏每一次点击都会立刻改变界面（翻课、换重叠课程、进编辑、删除、跳设置），
        // 所以全部给一下 [Haptics.tap]；「上一门/下一门」在只有一门课时不响应，那种点击
        // 同样给回执 —— 它确实被按到了，只是没有下一门可翻。
        binding.closeDetail.setOnClickListener {
            Haptics.tap(binding.closeDetail)
            dismiss()
        }
        binding.previousCourse.setOnClickListener {
            Haptics.tap(binding.previousCourse)
            selectCourse(selected - 1)
        }
        binding.nextCourse.setOnClickListener {
            Haptics.tap(binding.nextCourse)
            selectCourse(selected + 1)
        }
        binding.courseCounter.setOnClickListener {
            Haptics.tap(binding.courseCounter)
            val labels = courses.map {
                listOfNotNull(it.courseName, CourseDetailText.timeText(it, viewModel.courseTimes),
                        it.room?.takeIf(String::isNotBlank), CourseDetailText.statusText(it)).joinToString(" · ")
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext()).setTitle("重叠课程")
                    .setSingleChoiceItems(labels, selected) { dialog, index ->
                        Haptics.tick(binding.courseCounter)
                        selectCourse(index)
                        dialog.dismiss()
                    }.setNegativeButton(R.string.cancel, null).show()
        }
        binding.rowReminder.setOnClickListener {
            Haptics.tap(binding.rowReminder)
            startActivity(Intent(requireContext(), SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_REMINDER))
        }
        binding.ibEdit.setOnClickListener {
            Haptics.tap(binding.ibEdit)
            if (!viewModel.isTableReady()) {
                Toasty.info(requireContext(), "课表正在加载，请稍后再试").show()
                return@setOnClickListener
            }
            startActivity(Intent(requireContext(), AddCourseActivity::class.java).apply {
                putExtra("id", course.id)
                putExtra("tableId", course.tableId)
                putExtra("maxWeek", viewModel.table.maxWeek)
                putExtra("nodes", viewModel.table.nodes)
            })
            dismiss()
        }
        binding.ibDeleteCourse.setOnClickListener {
            Haptics.longPress(binding.ibDeleteCourse)
            chooseDeletion()
        }
        TooltipCompat.setTooltipText(binding.ibEdit, "编辑课程")
        TooltipCompat.setTooltipText(binding.ibDeleteCourse, "删除课程")
        TooltipCompat.setTooltipText(binding.courseCounter, "选择重叠课程")
        showData()
    }

    override fun onStart() {
        super.onStart()
        // Cap the sheet instead of clipping long names, rooms or enlarged system fonts.
        val root = view ?: return
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val maxHeight = (metrics.heightPixels * 0.85f).toInt()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, minOf(root.measuredHeight, maxHeight))
    }

    private fun selectCourse(index: Int) {
        if (deleting || index !in courses.indices) return
        selected = index
        showData()
        binding.detailScroll.scrollTo(0, 0)
    }

    private fun showData() {
        binding.tvCourseName.text = course.courseName
        binding.tvWhen.text = CourseDetailText.whenText(course)
        binding.tvWeeks.text = CourseDetailText.weekText(course)
        binding.tvTimeValue.text = CourseDetailText.timeText(course, viewModel.courseTimes)
                .let { if (it == CourseDetailText.UNKNOWN) "时间未设置" else it }
        binding.tvRoomValue.text = course.room?.takeIf(String::isNotBlank) ?: "地点未设置"
        binding.tvTeacherValue.text = course.teacher?.takeIf(String::isNotBlank)?.let { "教师 · $it" }
        binding.tvTeacherValue.visibility = if (course.teacher.isNullOrBlank()) View.GONE else View.VISIBLE
        val week = arguments?.getInt("week", viewModel.selectedWeek) ?: viewModel.selectedWeek
        val status = listOfNotNull(CourseDetailText.statusText(course),
                if (!course.inWeek(week)) "非本周" else null).joinToString(" · ")
        binding.tvStatus.text = status
        binding.tvStatus.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        binding.vColorBar.background.mutate().setTint(ViewUtils.parseCourseColor(course.color,
                ContextCompat.getColor(requireContext(), R.color.colorPrimary)))
        binding.courseSwitcher.visibility = if (courses.size > 1) View.VISIBLE else View.GONE
        binding.courseCounter.text = "重叠课程  ${selected + 1} / ${courses.size}"
        binding.previousCourse.isEnabled = selected > 0
        binding.nextCourse.isEnabled = selected < courses.lastIndex
        binding.previousCourse.alpha = if (binding.previousCourse.isEnabled) 1f else 0.25f
        binding.nextCourse.alpha = if (binding.nextCourse.isEnabled) 1f else 0.25f
    }

    private fun chooseDeletion() {
        if (deleting) return
        val target = course.copy()
        MaterialAlertDialogBuilder(requireContext()).setTitle("删除「${target.courseName}」")
                .setItems(arrayOf("删除这条上课安排", "删除这门课的全部安排")) { _, which ->
                    val message = if (which == 0) {
                        "${CourseDetailText.whenText(target)} · ${CourseDetailText.weekText(target)}"
                    } else "这门课在当前课表中的所有上课安排都将被删除。"
                    MaterialAlertDialogBuilder(requireContext()).setTitle("确认删除「${target.courseName}」？")
                            .setMessage(message).setNegativeButton(R.string.cancel, null)
                            .setPositiveButton("删除") { _, _ -> deleteCourse(target, which == 1) }.show()
                }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun deleteCourse(target: CourseBean, all: Boolean) {
        if (deleting) return
        deleting = true
        binding.ibDeleteCourse.isEnabled = false
        binding.ibEdit.isEnabled = false
        val appContext = requireContext().applicationContext
        launch {
            try {
                if (all) viewModel.deleteCourseBaseBean(target.id, target.tableId)
                else viewModel.deleteCourseBean(target)
                // Refresh is independent of the dismissed sheet's lifecycle.
                appScope.launch {
                    try {
                        AppWidgetUtils.refreshAllWidgets(appContext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("CourseDetail", "Widget refresh failed after deletion", e)
                    }
                }
                Toasty.success(appContext, "已删除").show()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toasty.error(appContext, "删除失败，请重试").show()
            } finally {
                deleting = false
                _binding?.ibDeleteCourse?.isEnabled = true
                _binding?.ibEdit?.isEnabled = true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected", selected)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        @JvmStatic
        fun newInstance(course: CourseBean, week: Int = 1) = CourseDetailFragment().apply {
            arguments = Bundle().apply {
                putParcelable("course", course)
                putInt("week", week)
            }
        }
    }
}
