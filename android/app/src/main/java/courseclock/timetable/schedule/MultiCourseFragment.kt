package courseclock.timetable.schedule

import android.os.Bundle
import courseclock.timetable.bean.CourseBean

class MultiCourseFragment : CourseDetailFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val args = requireArguments()
        args.getParcelable<CourseBean>("course")?.let { focus ->
            val courses = viewModel.getMultiCourse(args.getInt("week"), args.getInt("day"), focus)
            args.putParcelableArrayList("courses", ArrayList(courses.ifEmpty { listOf(focus) }))
        }
        super.onCreate(savedInstanceState)
    }

    companion object {
        @JvmStatic
        fun newInstance(weekParam: Int, dayParam: Int, focusParam: CourseBean) = MultiCourseFragment().apply {
            arguments = Bundle().apply {
                putInt("week", weekParam)
                putInt("day", dayParam)
                putParcelable("course", focusParam)
            }
        }
    }
}
