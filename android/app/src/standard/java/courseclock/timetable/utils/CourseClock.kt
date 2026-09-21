package courseclock.timetable.utils

/** 普通调试包与正式包只使用手机真实时间，不编译模拟器实现。 */
object CourseClock : CourseTime() {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
