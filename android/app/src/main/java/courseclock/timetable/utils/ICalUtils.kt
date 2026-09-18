package courseclock.timetable.utils

import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.util.Frequency
import biweekly.util.Recurrence
import courseclock.timetable.bean.CourseBean
import java.util.*

object ICalUtils {

    /**
     * 把一门课的周次展开成日历事件写进 [ical]，返回**实际新增的事件数**。
     *
     * 返回数不是装饰：调用方（ICS 导出）要靠"0 个事件"识别"这门课没能写入"——
     * 时刻数据异常时旧实现静默跳过，导出的日历少课而用户毫不知情。
     */
    fun getClassEvents(ical: ICalendar, times: CourseTimes,
                       maxWeek: Int,
                       course: CourseBean,
                       termStart: Date,
                       sundayFirst: Boolean): Int {
        // 周锚点必须与课表内周次计算（CourseUtils.daysBetween）同口径：sundayFirst 的表第 1 周
        // 从「开学日所在周的周日」开始，若不归一，下面的日历定位与 UNTIL 的天数加减会各自
        // 漂移，整份日历系统性错一周。先把开学日归一到正确的周首日再往下传。
        val anchor = weekStartOf(termStart, sundayFirst)
        var i = 1
        var added = 0
        while (i <= maxWeek) {
            if (course.inWeek(i)) {
                var j = i
                while (course.inWeek(++j));
                j--
                val event = getClassEvent(times, course, 1, i, j, anchor, sundayFirst)
                if (event != null) {
                    ical.addEvent(event)
                    added++
                }
                i += j - i
            }
            i++
        }
        return added
    }

    private fun getClassEvent(times: CourseTimes,
                              course: CourseBean,
                              currentWeek: Int,
                              startWeek: Int,
                              endWeek: Int,
                              termStart: Date,
                              sundayFirst: Boolean
    ): VEvent? {
        val dayBefore = (currentWeek - startWeek) * 7
        val dayAfter = (endWeek - currentWeek) * 7 + course.day

        // repeat every week until endDate
        val recur = Recurrence.Builder(Frequency.WEEKLY).interval(1)
                .until(CourseUtils.getDateAfter(termStart, dayAfter))
                .build()
//        Recur(Recur.WEEKLY, DateTime(CourseUtils.getDateAfter(termStart, dayAfter)))
//        val rule = RRule(recur)

        // 起止时间按该次安排所属的作息分组取：同一节次在不同场景下可能不同，
        // 也可能拿不到时间（返回空串），此时跳过这条事件而不是写一个 0 点的事件。
        val startTime = timeToCalendar(times.startOfNode(course.startNode, course.timeGroup))
                ?: return null
        val endTime = timeToCalendar(times.endOfNode(course.startNode + course.step - 1, course.timeGroup))
                ?: return null
        // 默认作息第 12~30 节是 00:00~00:00 占位行：排在占位节次上的课没有真实时刻，
        // 与「拿不到时间就跳过」的约定一致，而不是写出 DTSTART==DTEND 的零时长日历项。
        if (isPlaceholderNode(times, course.startNode, course.timeGroup) ||
                isPlaceholderNode(times, course.startNode + course.step - 1, course.timeGroup)) {
            return null
        }

        // 周定位必须显式钉在该表真实使用的周首日：不设 firstDayOfWeek 的话用的是 locale
        // 默认值，美式 locale（周日开头）下"周日"那天的课会整周偏移到前一周。termStart 已在
        // 入口归一到同一周首日，这里钉住它，set(DAY_OF_WEEK) 才落在正确的那一周。
        val weekStart = if (sundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        val dailyStart = Calendar.getInstance()
        dailyStart.time = CourseUtils.getDateBefore(termStart, dayBefore)
        dailyStart.firstDayOfWeek = weekStart
        dailyStart.set(Calendar.HOUR_OF_DAY, startTime.get(Calendar.HOUR_OF_DAY))
        dailyStart.set(Calendar.MINUTE, startTime.get(Calendar.MINUTE))
        dailyStart.set(Calendar.DAY_OF_WEEK, weekDayConvert(course.day))

        val dailyEnd = Calendar.getInstance()
        dailyEnd.time = CourseUtils.getDateBefore(termStart, dayBefore)
        dailyEnd.firstDayOfWeek = weekStart
        dailyEnd.set(Calendar.HOUR_OF_DAY, endTime.get(Calendar.HOUR_OF_DAY))
        dailyEnd.set(Calendar.MINUTE, endTime.get(Calendar.MINUTE))
        dailyEnd.set(Calendar.DAY_OF_WEEK, weekDayConvert(course.day))
        // 跨天作息（如 23:50~00:20）的结束时刻落在次日凌晨：set 只改时分不动日期，直接写出
        // 会得到 DTEND < DTSTART 的负时长事件。结束的时分早于开始的时分即视为次日回绕。
        if (endTime.get(Calendar.HOUR_OF_DAY) * 60 + endTime.get(Calendar.MINUTE) <
                startTime.get(Calendar.HOUR_OF_DAY) * 60 + startTime.get(Calendar.MINUTE)) {
            dailyEnd.add(Calendar.DATE, 1)
        }

        // create event, repeat weekly
        val event = VEvent()
        event.setUid(stableUid(course, startWeek, endWeek))
        event.setSummary(course.courseName)
        event.setDateStart(dailyStart.time)
        event.setDateEnd(dailyEnd.time)
        event.setRecurrenceRule(recur)
        // room/teacher 是可空字段：空串/外部数据的 null 不该拼成 "J301 " 或字面 "null"，
        // 空段不拼，两处都为空时干脆不设这个属性。
        val location = listOfNotNull(course.room, course.teacher)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        if (location.isNotEmpty()) event.setLocation(location)
        val description = listOfNotNull(course.getNodeString(), course.room, course.teacher)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        if (description.isNotEmpty()) event.setDescription(description)

        return event
    }

    /**
     * 事件的 UID：**由内容决定，不用随机值**。
     *
     * ## 为什么必须稳定
     *
     * UID 是日历应用认定"这是不是同一个日程"的唯一依据。旧实现写的是
     * `Uid.random()`，于是**同一份课表导出两次就是两套 UID**，日历里出现两份完全重复的课程；
     * 用户改完课表再导一次，重复不是被更新而是又叠一层，只能进日历手动删。改成由内容推导之后，
     * 重新导出会让日历**更新**原来的日程。
     *
     * ## 取哪些字段
     *
     * 用的是"这一次安排的身份"：课表 id + 课程 id + 星期 + 起始节 + 跨节数 + 周次区间 + 单双周。
     * 刻意**不含课名**：改个名字不该变成两个日程。`tableId` 必须在内 —— 否则两张课表里
     * 同星期同节次的课会算出同一个 UID，导入同一个日历时被日历合并成一条。
     *
     * 组成串只含数字、连字符和 `.`/`@`，都是 iCalendar 文本里无需转义的字符，
     * 不会与 biweekly 的转义规则打架。
     *
     * 前缀用本应用自己的名字：上游名字（`WakeUpSchedule-`）按 NOTICE 不再沿用。
     */
    private fun stableUid(course: CourseBean, startWeek: Int, endWeek: Int): String {
        val identity = listOf(
                course.tableId, course.id, course.day, course.startNode,
                course.step, startWeek, endWeek, course.type
        ).joinToString("-")
        return "courseclock-$identity@courseclock.timetable"
    }

    private fun weekDayConvert(i: Int): Int {
        if (i in 1..7) {
            when (i) {
                1 -> return Calendar.MONDAY
                2 -> return Calendar.TUESDAY
                3 -> return Calendar.WEDNESDAY
                4 -> return Calendar.THURSDAY
                5 -> return Calendar.FRIDAY
                6 -> return Calendar.SATURDAY
                7 -> return Calendar.SUNDAY
            }
        }
        return -1
    }

    /** 把日期归一到它所在周的周首日（按课表的 sundayFirst）当天的 0 点。 */
    private fun weekStartOf(date: Date, sundayFirst: Boolean): Date {
        val cal = Calendar.getInstance()
        cal.time = date
        cal.firstDayOfWeek = if (sundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, if (sundayFirst) Calendar.SUNDAY else Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    /** 该节次在该分组下是不是 00:00~00:00 的占位行（默认作息 12~30 节就是这种）。 */
    private fun isPlaceholderNode(times: CourseTimes, node: Int, timeGroup: String): Boolean =
            times.startOfNode(node, timeGroup) == "00:00" && times.endOfNode(node, timeGroup) == "00:00"

    /** 把「HH:mm」转成只带时分秒的 Calendar；空串、格式不对或超出钟表范围时返回 null。 */
    private fun timeToCalendar(text: String): Calendar? {
        // 解析收敛到 CourseReminderScheduler.minutesOfDay：全工程只有一份时刻校验规则
        val minutes = CourseReminderScheduler.minutesOfDay(text) ?: return null
        val calendar = Calendar.getInstance()
        // 调用方只读这里的 HOUR_OF_DAY / MINUTE，日期部分没有语义（曾写死 YEAR=2016，
        // 看起来像有含义，实际是死赋值，误导读者）。
        calendar.set(Calendar.HOUR_OF_DAY, minutes / 60)
        calendar.set(Calendar.MINUTE, minutes % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar
    }
}