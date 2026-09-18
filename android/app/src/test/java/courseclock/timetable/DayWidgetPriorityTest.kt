package courseclock.timetable

import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.utils.CourseTimes
import courseclock.timetable.utils.DayWidgetSchedule
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DayWidgetPriorityTest {
    private val times = CourseTimes.of(listOf(
            TimeDetailBean(1, "08:15", "09:35", 1),
            TimeDetailBean(2, "10:15", "11:35", 1),
            TimeDetailBean(3, "13:00", "14:00", 1),
            TimeDetailBean(4, "15:00", "16:00", 1),
            TimeDetailBean(3, "14:15", "15:15", 1, "C")))

    private fun course(node: Int, group: String = "") = CourseBean(
            id = node, courseName = "Course $node $group", day = 1, room = "", teacher = "",
            startNode = node, step = 1, startWeek = 1, endWeek = 30, type = 0,
            color = "", tableId = 1, timeGroup = group)

    private fun ordered(courses: List<CourseBean>, hour: Int, minute: Int): List<CourseBean> {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        return DayWidgetSchedule.displayOrder(times, courses, { now })
    }

    @Test
    fun currentThenUpcomingWithoutFinished() {
        val courses = (1..4).map { course(it) }
        assertEquals(listOf(courses[2], courses[3]),
                ordered(courses, 13, 25))
        assertEquals(listOf(courses[3]),
                ordered(courses, 14, 0))
        assertEquals(courses, ordered(courses.reversed(), 8, 0))
        assertEquals(emptyList<CourseBean>(), ordered(courses.reversed(), 16, 0))
    }

    @Test
    fun staggeredTimesOverrideNodeOrderAndRefreshAtBoundaries() {
        val earlierNode = course(3, "C")
        val current = course(4)
        val courses = listOf(earlierNode, current)
        assertEquals(listOf(earlierNode, current), ordered(courses, 15, 0))
        assertEquals(listOf(current), ordered(courses, 15, 15))
        assertEquals(emptyList<CourseBean>(), ordered(emptyList(), 13, 25))
        val unknown = course(99)
        assertEquals(listOf(current, unknown),
                ordered(listOf(unknown, earlierNode, current), 15, 15))
    }

    @Test
    fun completedHistoryIncludesBreaksAndFinishedDays() {
        val courses = (1..4).map { course(it) }
        fun completed(hour: Int, minute: Int): List<CourseBean> {
            val now = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }.timeInMillis
            return DayWidgetSchedule.completedCourses(times, courses, { now })
        }
        assertEquals(courses.take(2), completed(13, 25))
        assertEquals(courses.take(3), completed(14, 30))
        assertEquals(courses.take(3), completed(15, 0))
        assertEquals(courses, completed(16, 0))
    }
}
