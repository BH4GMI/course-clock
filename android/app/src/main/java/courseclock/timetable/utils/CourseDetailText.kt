package courseclock.timetable.utils

import courseclock.timetable.bean.CourseBean

/**
 * 课程详情面板上的文字（设计稿 4），纯计算、不碰视图。
 *
 * ## 为什么单独一个类
 *
 * 这些字符串（周次、节次、星期、免听状态）是这一页的**内容**，不是排版。放在 Fragment 里
 * 就只能靠"装到手机上看一眼"来验；而渲染测试要断言"摘要写的是周一第 1-2 节""免听课带状态"，
 * 若在测试里再写一遍同样的拼法，就成了两份各自演化的文案 —— 改了一处另一处照样绿。
 * 所以文案收在这里：界面与测试走同一条路径。取时刻仍然只用 [CourseTimes]（唯一的作息查询入口）。
 *
 * 星期表用 [ScheduleViewModel.daysArray] 同一份口径（下标 1 = 周一）；这里是 utils 层，
 * 不该反向依赖 schedule 包的 ViewModel，所以下标算术自己写一条：
 * `(day - 1 + 7) % 7`，day=1 → 一、day=7 → 日，越界也不抛异常。
 */
object CourseDetailText {

    /** 明细里的空值。教师/地点是可选字段，空着时要说"未填"，不能留一片空白。 */
    const val UNKNOWN = "未填"

    /** 教师/地点：空、null、全是空格都算没填。 */
    fun valueText(raw: String?): String = raw?.takeIf { it.isNotBlank() } ?: UNKNOWN

    /** 摘要左：`周一 第 1-2 节`。节次只出现在这里，明细里不再写一遍。 */
    fun whenText(course: CourseBean): String =
            "${weekdayText(course.day)} ${nodeText(course)}"

    /** `第 1-2 节`。 */
    fun nodeText(course: CourseBean): String =
            "第 ${course.startNode}-${course.startNode + course.step - 1} 节"

    /** 摘要右：`第 1-16 周`，单双周课程再缀上「单周」「双周」。 */
    fun weekText(course: CourseBean): String {
        val base = "第 ${course.startWeek}-${course.endWeek} 周"
        return when (course.type) {
            1 -> "$base 单周"
            2 -> "$base 双周"
            else -> base
        }
    }

    /**
     * 明细里的上课时刻：`08:15 - 09:35`。
     * 查不到作息时两端都是空串，这时返回 [UNKNOWN]，不显示一个只有连接符的怪值。
     */
    fun timeText(course: CourseBean, times: CourseTimes): String {
        val start = times.startOf(course)
        val end = times.endOf(course)
        if (start.isEmpty() || end.isEmpty()) return UNKNOWN
        return "$start - $end"
    }

    /** `周一` … `周日`；day 只在 1..7 内有意义，其他值原样退回，不抛异常。 */
    fun weekdayText(day: Int): String {
        if (day < 1 || day > 7) return "周$day"
        return "周" + "一二三四五六日"[(day - 1 + 7) % 7]
    }

    /**
     * 免听 / 重修状态标签；两者都没有时返回 null（调用方把那一枚标签整块藏掉）。
     *
     * 重修只在**免听**的语境里有意义（重修课不排提醒、课表格子上也压在正常课下面），
     * 所以两者都有时并成「免听 · 重修」，而不是各写一枚。
     */
    fun statusText(course: CourseBean): String? {
        val parts = ArrayList<String>(2)
        if (course.notAttend) parts.add("免听")
        if (course.retake) parts.add("重修")
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
