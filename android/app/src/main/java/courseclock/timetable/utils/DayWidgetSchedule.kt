package courseclock.timetable.utils

import courseclock.timetable.bean.CourseBean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日程共享的课程状态、实际作息、倒计时及日期格式。 */
object DayWidgetSchedule {

    /** 所有已结束课程按真实开始时间排列，具体显示容量由日程布局测量决定。 */
    fun completedCourses(times: CourseTimes, courses: List<CourseBean>,
                               nowMillis: () -> Long): List<CourseBean> {
        val now = nowMillis()
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        return courses.filter { times.isFinished(it, clock) }
                .sortedBy { CourseReminderScheduler.minutesOfDay(times.startOf(it)) }
    }

    /** 当前课程优先，其次按真实开课时间显示未来课程；今天已结束的课程不再显示。 */
    fun displayOrder(times: CourseTimes, courses: List<CourseBean>, nowMillis: () -> Long): List<CourseBean> {
        val now = nowMinutes(nowMillis)
        fun priority(course: CourseBean): Int {
            if (now == null) return 1
            val end = CourseReminderScheduler.minutesOfDay(times.endOf(course))
            val start = CourseReminderScheduler.minutesOfDay(times.startOf(course))
            return when {
                end != null && end <= now -> 2
                start != null && end != null && start <= now && now < end -> 0
                else -> 1
            }
        }
        return courses.filter { priority(it) != 2 }.sortedWith(compareBy<CourseBean> { priority(it) }
                .thenBy { CourseReminderScheduler.minutesOfDay(times.startOf(it)) ?: Int.MAX_VALUE })
    }

    /** 今天尚未结束的课程，保持输入顺序；历史课程由 completedCourses 独立提供。 */
    fun remainingToday(times: CourseTimes, courses: List<CourseBean>, nowMillis: () -> Long): List<CourseBean> {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMillis()))
        return courses.filterNot { times.isFinished(it, now) }
    }

    /**
     * 这节课是不是正在上。
     *
     * 判据是上课时刻而不是开始节次：本校上午第 3~5 节按楼错峰，同一个节次在不同分组下的上课
     * 时刻不同（见 [CourseTimes]）。时刻读不出来时算「还没开始」—— 少一个「正在上课」的标记，
     * 总好过把一节还没开始的课写成正在上。
     */
    fun isOngoing(times: CourseTimes, course: CourseBean, nowMillis: () -> Long): Boolean {
        val start = CourseReminderScheduler.minutesOfDay(times.startOf(course)) ?: return false
        val end = CourseReminderScheduler.minutesOfDay(times.endOf(course)) ?: return false
        val now = nowMinutes(nowMillis) ?: return false
        return start <= now && now < end
    }

    /**
     * 离下课还有几分钟；不在下课前的窗口内（见 [CourseReminderScheduler.COUNTDOWN_WINDOW_MINUTES]）
     * 或时刻读不出来时为 null。
     *
     * 直接调 [CourseReminderScheduler.countdownMinutesLeft]，与下课通知用的是**同一套取整规则** ——
     * 两处各自算一次的话，通知说「还有 9 分钟」而卡片说「还有 10 分钟」只是时间问题。
     */
    fun countdownMinutes(times: CourseTimes, course: CourseBean, nowMillis: () -> Long): Long? =
            CourseReminderScheduler.countdownMinutesLeft(
                    times.endOfNode(course.startNode + course.step - 1, course.timeGroup), nowMillis())

    /**
     * 卡片第二行左边那段时间，「09:55-11:15」。
     *
     * 只有两头都读得出来才写成区间；只有一头就写那一头（总比写「09:55-null」强），两头都没有就
     * 返回空串，调用方不显示这一段，绝不留一个孤零零的「-」在卡片上。
     */
    fun timeRange(times: CourseTimes, course: CourseBean): String {
        val start = times.startOf(course).orEmpty()
        val end = times.endOf(course).orEmpty()
        return when {
            start.isNotEmpty() && end.isNotEmpty() -> "$start-$end"
            start.isNotEmpty() -> start
            else -> end
        }
    }

    /**
     * 卡片第二行右边那段「教室 · 教师」。
     *
     * 两部分各自可能没有（手工建的课可以只写课名），有的才拼进来，中间用「 · 」。两段都没有时
     * 返回空串，调用方把整个 TextView 收起来，而不是显示一个孤零零的分隔点。
     */
    fun roomAndTeacher(course: CourseBean): String =
            listOfNotNull(course.room, course.teacher).filter { it.isNotEmpty() }.joinToString(" · ")

    /** 课前超过 60 分钟用一位小数小时，其余用分钟；下课前 20 分钟保持原有倒计时。 */
    fun statusText(times: CourseTimes, course: CourseBean, nowMillis: () -> Long): String {
        if (isOngoing(times, course, nowMillis)) {
            return countdownMinutes(times, course, nowMillis)
                    ?.let { CourseReminderScheduler.countdownText(it) } ?: "正在上课"
        }
        return CourseReminderScheduler.startCountdownMinutesLeft(times.startOf(course), nowMillis())
                ?.let { CourseReminderScheduler.startCountdownText(it) } ?: "时间未设置"
    }

    /**
     * 把月份、星期与课程数拼成表头第二行；取不出来的部分自动略过。
     *
     * 表头**现在恢复显示课程计数**（设计稿 2026-09-18 的表头口径：`9月 · 周五 · 3 节 · 已上 1`；
     * 落地的文案是 `3节 · 已上1节`，见 [AppWidgetUtils.daySummary]）；
     * 前一版曾经去掉计数，理由是正文已经用「另有 N 节待上」表达过余课 —— 这两处说的不是一件事：
     * 表头给的是"今天总共几节、已上几节"，正文给的是"当前这节之外还剩几节"。
     * 正式表头由 `AppWidgetUtils.daySummary` 直接拼（它要拿到同一份装载结果），本函数留给预览图。
     */
    fun summaryText(monthDay: String, weekDay: String, courseCount: Int): String {
        val today = if (courseCount > 0) "今天 $courseCount 节课" else "今天没有课"
        return listOf(monthDay, weekDay, today).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /**
     * 头部那个大字号日期：「9月15日」取出「15」。
     *
     * 取不出来（换语言、换了日期格式）时原样返回整串 —— 显示成「9月15日」也比显示空白好，
     * 而且这条路径不该抛异常。
     */
    fun dayOfMonth(date: String): String =
            Regex("(\\d+)\\s*日").find(date)?.groupValues?.get(1)?.plus("日") ?: date

    /**
     * 大字号日期左边那行小字的月份：「9月15日」→「9月」；取不出来时返回空串，调用方不显示。
     *
     * 与 [dayOfMonth] 是同一串日期的两个部分：大字拿日、小字拿月。
     */
    fun monthOf(date: String): String =
            Regex("(\\d+)\\s*月").find(date)?.let { "${it.groupValues[1]}月" } ?: ""

    private fun nowMinutes(nowMillis: () -> Long): Int? {
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMillis()))
        return CourseReminderScheduler.minutesOfDay(clock)
    }
}
