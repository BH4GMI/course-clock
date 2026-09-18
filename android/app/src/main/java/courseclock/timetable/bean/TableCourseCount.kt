package courseclock.timetable.bean

/**
 * 每张课表的课程数（coursebasebean 里的行数），多课表页「N 门课程」副标题用。
 *
 * 只是被 TableDao 那条 group by 查询投影出来的查询结果，不是表。
 * 没有课的课表不会出现在结果里——使用方按 0 处理（设计稿的「空课表 · 还没有课程」）。
 */
data class TableCourseCount(
        val tableId: Int,
        val courseCount: Int
)
