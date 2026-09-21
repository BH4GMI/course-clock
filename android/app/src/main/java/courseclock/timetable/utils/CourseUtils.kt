package courseclock.timetable.utils

import android.content.Context
import androidx.lifecycle.MutableLiveData
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.CourseEditBean
import courseclock.timetable.bean.TimeBean
import courseclock.timetable.schedule_import.Common
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

object CourseUtils {
    fun getDayStr(weekDay: Int): String {
        return when (weekDay) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7 -> "周日"
            else -> ""
        }
    }

    fun courseBean2DetailBean(c: CourseBean): CourseDetailBean {
        return CourseDetailBean(
                id = c.id, room = c.room, day = c.day, teacher = c.teacher,
                startNode = c.startNode, step = c.step, startWeek = c.startWeek,
                endWeek = c.endWeek, tableId = c.tableId, type = c.type
        )
    }

    fun editBean2DetailBeanList(editBean: CourseEditBean): MutableList<CourseDetailBean> {
        val result = mutableListOf<CourseDetailBean>()
        Common.weekIntList2WeekBeanList(editBean.weekList.value!!).forEach {
            result.add(CourseDetailBean(
                    id = editBean.id, room = editBean.room, teacher = editBean.teacher,
                    day = editBean.time.value!!.day, startNode = editBean.time.value!!.startNode,
                    step = editBean.time.value!!.endNode - editBean.time.value!!.startNode + 1,
                    startWeek = it.start, endWeek = it.end, type = it.type,
                    tableId = editBean.tableId
            ))

        }
        return result
    }

    fun detailBean2EditBean(c: CourseDetailBean): CourseEditBean {
        return CourseEditBean(
                id = c.id,
                time = MutableLiveData<TimeBean>().apply {
                    this.value = TimeBean(day = c.day, startNode = c.startNode, endNode = c.startNode + c.step - 1)
                },
                room = c.room, teacher = c.teacher,
                weekList = MutableLiveData<ArrayList<Int>>().apply {
                    this.value = ArrayList<Int>().apply {
                        when (c.type) {
                            0 -> {
                                for (i in c.startWeek..c.endWeek) {
                                    this.add(i)
                                }
                            }
                            else -> {
                                for (i in c.startWeek..c.endWeek step 2) {
                                    this.add(i)
                                }
                            }
                        }
                    }
                },
                tableId = c.tableId
        )
    }

    fun checkSelfUnique(list: List<CourseDetailBean>): Boolean {
        var flag = true
        for (i in 0 until list.size - 1) {
            for (j in i + 1 until list.size) {
                if (list[i].day == list[j].day
                        && list[i].startNode == list[j].startNode
                        && list[i].startWeek == list[j].startWeek
                        && list[i].type == list[j].type
                        && list[i].tableId == list[j].tableId) {
                    flag = false
                    return flag
                }
            }
        }
        return flag
    }

    fun getDateBefore(d: Date, day: Int): Date {
        val now = Calendar.getInstance()
        now.time = d
        now.set(Calendar.DATE, now.get(Calendar.DATE) - day)
        return now.time
    }

    fun getDateAfter(d: Date, day: Int): Date {
        val now = Calendar.getInstance()
        now.time = d
        now.set(Calendar.DATE, now.get(Calendar.DATE) + day)
        return now.time
    }

    /**
     * 开学日期到「基准日」之间隔了几个周首日。
     *
     * [basisMillis] 是「今天」的基准时刻，传 null 就用系统当前时间。课程提醒要为未来若干天
     * 预先排闹钟，必须能问「第 offset 天属于第几周」，所以这里必须可传基准 —— 否则那套算法
     * 就只能在"现在"这一个时间点上求值，滚动窗口根本没法定周次。
     */
    @Throws(ParseException::class)
    fun daysBetween(date: String, sundayFirst: Boolean, basisMillis: Long? = null): Int {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = Calendar.getInstance()
        cal.timeInMillis = basisMillis ?: CourseClock.nowMillis()
        if (sundayFirst) {
            cal.firstDayOfWeek = Calendar.SUNDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        } else {
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val time2 = cal.timeInMillis
        cal.time = sdf.parse(date)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // 开学日期也归一到所在周的周首日。time2 是周首日，time1 若不归一，两者之差就不再是
        // 7 的倍数，`/7` 的截断会让非周一（周日起始时非周日）开学日期的周次在边界上漂移
        // ±1：周三开学时，下一周的周一会被算成"第 1 周"（应为第 2 周）。
        if (sundayFirst) {
            cal.firstDayOfWeek = Calendar.SUNDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        } else {
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val time1 = cal.timeInMillis
        // 两侧都已是周首日，差恒为 7 的倍数，不需要任何负数特判。
        return ((time2 - time1) / (1000 * 3600 * 24)).toInt()
    }

    /**
     * 计算当前是第几周。
     *
     * [date] 为空串表示尚未设置开学日期，此时返回 0。这是全工程对「尚未设置学期」的唯一约定：
     * 调用方一律把 0 当作「未设置 / 还没开学」处理，不要当成第 0 周。
     *
     * [basisMillis] 同 [daysBetween]：滚动窗口要问「第 offset 天是第几周」，必须能传基准时刻。
     */
    @Throws(ParseException::class)
    fun countWeek(date: String, sundayFirst: Boolean, basisMillis: Long? = null): Int {
        if (date.isBlank()) return 0
        val during = daysBetween(date, sundayFirst, basisMillis)
        return during / 7 + 1
    }

    fun getWeekday(): String {
        val weekDay = getWeekdayInt()
        return getDayStr(weekDay)
    }

    fun getWeekdayInt(): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = CourseClock.nowMillis() }
        return calendarDayOfWeekToAppWeekday(cal.get(Calendar.DAY_OF_WEEK))
    }

    /**
     * [millis] 那一天的星期编号（周一 = 1 … 周日 = 7）。
     *
     * 课程提醒要为未来若干天预先排闹钟，"第 offset 天是星期几"必须能对任意基准时刻求值，
     * 不能只对"现在"求值 —— 所以把换算从 [getWeekdayInt] 里抽出来共用，两处规则必须一致。
     */
    fun getWeekdayIntAt(millis: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return calendarDayOfWeekToAppWeekday(cal.get(Calendar.DAY_OF_WEEK))
    }

    /** Calendar 的 SUNDAY = 1 … SATURDAY = 7 → 本工程的周一 = 1 … 周日 = 7。 */
    private fun calendarDayOfWeekToAppWeekday(calendarDayOfWeek: Int): Int =
            if (calendarDayOfWeek == Calendar.SUNDAY) {
                7
            } else {
                calendarDayOfWeek - 1
            }

    fun getTodayDate(): String {
        val dateFormat = SimpleDateFormat("M月d日", Locale.CHINA)
        return dateFormat.format(Date(CourseClock.nowMillis()))
    }

    /**
     * 「HH:mm」加 [min] 分钟后的墙钟时间；跨天按 24 小时回绕（23:30 + 50 分钟 → 00:20）。
     *
     * 旧实现用 `substring(0, 2)/(3, 5)` 取时分：输入没有前导零（"8:00"）就抛
     * NumberFormatException，跨天还会把结果钳成 00:00——两个都是会产出错误上课时间的坑
     * （`CourseReminderScheduler.minutesOfDay` 的文档里点过名）。现在统一走分钟数算术：
     * 解析复用 [CourseReminderScheduler.minutesOfDay]，输出复用 [clockOf]，解析不出时
     * 原样返回，绝不抛异常。
     */
    fun calAfterTime(time: String, min: Int): String {
        val minutes = CourseReminderScheduler.minutesOfDay(time) ?: return time
        return CourseReminderScheduler.clockOf(Math.floorMod(minutes + min, 24 * 60))
    }

    /**
     * 「第 N 周是哪几天」所用的周次基准。
     *
     * 开学日期为空（尚未设置）时根本没有学期周可言，此时把本周当作第 1 周，
     * 周次标题至少是真实的日历周，而不是从某个假想的开学日期推出来的日期。
     * 设置了开学日期就按真实学期周计算（含开学前 countWeek <= 0 的情形）。
     */
    fun weekBaseForDate(startDate: String, sundayFirst: Boolean): Int {
        return if (startDate.isBlank()) 1 else countWeek(startDate, sundayFirst)
    }

    fun getDateStringFromWeek(curWeek: Int, targetWeek: Int, sundayFirst: Boolean): List<String> {
        val calendar = Calendar.getInstance().apply { timeInMillis = CourseClock.nowMillis() }
        if (targetWeek == curWeek)
            return getDateStringFromCalendar(calendar, sundayFirst)
        val amount = targetWeek - curWeek
        calendar.add(Calendar.WEEK_OF_YEAR, amount)
        return getDateStringFromCalendar(calendar, sundayFirst)
    }

    private fun getDateStringFromCalendar(calendar: Calendar, sundayFirst: Boolean): List<String> {
        val dateList = ArrayList<String>()
        if (sundayFirst) {
            calendar.firstDayOfWeek = Calendar.SUNDAY
        } else {
            calendar.firstDayOfWeek = Calendar.MONDAY
        }
        while (calendar.get(Calendar.DAY_OF_WEEK) != calendar.firstDayOfWeek) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }
        dateList.add((calendar.get(Calendar.MONTH) + 1).toString())
        for (i in 0..6) {
            dateList.add(calendar.get(Calendar.DAY_OF_MONTH).toString())
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return dateList
    }
}
