package courseclock.timetable

import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.utils.CourseTimes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 日视图小部件只显示"还没上完的课"（用户要求：上完的课要消失）。
 *
 * 判断用的是**这节课自己的真实下课时间**：本校上午第 3~5 节按楼错峰，E 楼 3~4 节是
 * 10:15~11:35，而默认分组同一行是 09:55~11:15 —— 按"第几节"或按时间栏那一行判断，
 * 都会把还没下课的课提前抹掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DayWidgetRemainingCoursesTest {

    private fun row(node: Int, start: String, end: String, group: String = "") =
            TimeDetailBean(node = node, startTime = start, endTime = end, timeTable = 1, timeGroup = group)

    private fun times() = CourseTimes.of(listOf(
            row(3, "09:55", "11:15"), row(4, "09:55", "11:15"),
            row(3, "10:15", "11:35", "C"), row(4, "10:15", "11:35", "C")))

    private fun course(startNode: Int, step: Int, group: String = "", name: String = "课") =
            CourseBean(id = 1, courseName = name, day = 1, room = "B210", teacher = "测试",
                    startNode = startNode, step = step, startWeek = 1, endWeek = 30, type = 0,
                    color = "#ff2979ff", tableId = 1, timeGroup = group)

    @Test
    fun 时间的写法不补零也不会判错() {
        // 库里的时间可能是 "9:55" 这种没补零的写法；按字符串比会把 9:55 判成晚于 10:35。
        val t = CourseTimes.of(listOf(row(3, "9:55", "10:35")))
        assertEquals(1, t.remaining(listOf(course(3, 1)), "10:34").size)
        assertEquals(emptyList<CourseBean>(), t.remaining(listOf(course(3, 1)), "10:35"))
        assertEquals(emptyList<CourseBean>(), t.remaining(listOf(course(3, 1)), "10:36"))
    }

    @Test
    fun 上完的课消失没上完的留下() {
        val t = times()
        val morning = course(3, 2, name = "上午的课")        // 默认分组 09:55~11:15 下课
        val otherBuilding = course(3, 2, "C", "E楼的课")    // C 楼 10:15~11:35 下课

        // 两节都还没下课
        assertEquals(2, t.remaining(listOf(morning, otherBuilding), "10:30").size)
        // 11:20：默认分组那节已经上完（11:15），E 楼那节还有 15 分钟
        assertEquals(listOf(otherBuilding), t.remaining(listOf(morning, otherBuilding), "11:20"))
        // 11:40：都上完了
        assertEquals(emptyList<CourseBean>(), t.remaining(listOf(morning, otherBuilding), "11:40"))
    }
}
