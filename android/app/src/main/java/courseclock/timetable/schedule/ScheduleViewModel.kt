package courseclock.timetable.schedule

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.distinctUntilChanged
import biweekly.Biweekly
import biweekly.ICalVersion
import biweekly.ICalendar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import courseclock.timetable.App
import courseclock.timetable.AppDatabase
import courseclock.timetable.R
import courseclock.timetable.bean.*
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.CourseTimes
import courseclock.timetable.utils.ICalUtils
import courseclock.timetable.utils.getPrefer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val dataBase = AppDatabase.getDatabase(application)
    private val courseDao = dataBase.courseDao()
    private val tableDao = dataBase.tableDao()
    private val timeTableDao = dataBase.timeTableDao()
    private val timeDao = dataBase.timeDetailDao()

    lateinit var table: TableBean
    lateinit var timeList: List<TimeDetailBean>

    /**
     * table 是否已从库中读出。
     *
     * 进程被杀后从最近任务恢复时，Activity 会在 onStart 里把还留着的 ScheduleFragment
     * 走一遍 onCreateView，而 initView 的协程此时往往还没跑完 —— 直接读 lateinit 必炸。
     * Fragment 一律先问这里，未就绪时只出空容器，等 initViewPage 重建页。
     */
    fun isTableReady(): Boolean = ::table.isInitialized

    /**
     * 就绪时返回当前课表，否则 null。需要读 [table] 的 UI 入口一律走这里拿，
     * 不要直接摸 lateinit —— 两个"表不存在"的窗口（冷启动加载中、库里一张表都没有）
     * 里直接读会抛 UninitializedPropertyAccessException。
     */
    fun tableOrNull(): TableBean? = if (::table.isInitialized) table else null

    fun isTimeListReady(): Boolean = ::timeList.isInitialized

    /**
     * 按分组查时间的入口。
     *
     * [timeList] 保留了该时间表下所有分组的原始行（导出要用），因此不能再按下标
     * 取时间；需要显示时间的地方一律走这里。
     */
    var courseTimes: CourseTimes = CourseTimes.of(emptyList())
    var selectedWeek = 1
    var alphaInt = 225
    val tableSelectList = arrayListOf<TableSelectBean>()
    val allCourseList = Array(7) { MutableLiveData<List<CourseBean>>() }
    private var observedTableId: Int? = null
    private var observedCourses: LiveData<List<CourseBean>>? = null
    val daysArray = arrayOf("日", "一", "二", "三", "四", "五", "六", "日")
    var currentWeek = 1

    fun initTableSelectList(): LiveData<List<TableSelectBean>> {
        return tableDao.getTableSelectListLiveData()
    }

    suspend fun getDefaultTable(): TableBean? {
        return tableDao.getDefaultTable()
    }

    suspend fun getTimeList(timeTableId: Int): List<TimeDetailBean> {
        return timeDao.getTimeList(timeTableId)
    }

    /** 这张课表的全部安排；「时间栏作息方案 = 自动」要靠它算上午 3~5 节的分布。 */
    suspend fun getDetailOfTable(tableId: Int): List<CourseDetailBean> {
        return courseDao.getDetailOfTable(tableId)
    }

    suspend fun addBlankTable(tableName: String) {
        // 走 DAO 的"没有默认表就接管默认表"：全新安装没有预置课表，用户建的第一张表
        // 必须自己成为默认表，否则建完回到首页还是「去导入」的引导。
        tableDao.insertTableAsDefaultIfNone(TableBean(id = 0, tableName = tableName))
    }

    suspend fun changeDefaultTable(id: Int) {
        tableDao.changeDefaultTable(table.id, id)
    }

    fun getRawCourseByDay(day: Int, tableId: Int): LiveData<List<CourseBean>> {
        return coursesOfTable(tableId).map { courses -> courses.filter { it.day == day } }
                .distinctUntilChanged()
    }

    // 七天与所有预加载周共用 Room 的同一个观察查询，避免每个消费者各查一次数据库。
    private fun coursesOfTable(tableId: Int): LiveData<List<CourseBean>> {
        if (observedTableId != tableId || observedCourses == null) {
            observedCourses = courseDao.getCourseOfTableLiveData(tableId)
            observedTableId = tableId
        }
        return requireNotNull(observedCourses)
    }

    /**
     * 重叠课的优先级：本周正常 > 本周免听 > 非本周正常 > 非本周免听。
     * 课表渲染与重叠课程详情共用同一顺序。
     */
    fun overlapPriority(c: CourseBean, week: Int): Int {
        val isOtherWeek = (week % 2 == 0 && c.type == 1) || (week % 2 == 1 && c.type == 2)
                || (c.startWeek > week)
        return when {
            !isOtherWeek && !c.notAttend -> 0
            !isOtherWeek && c.notAttend -> 1
            isOtherWeek && !c.notAttend -> 2
            else -> 3
        }
    }

    /**
     * 优先按各自作息分组的实际起止时间判断；首尾相接不算重叠。
     * 未设置时间时才回退到节次区间。
     */
    fun coursesOverlap(a: CourseBean, b: CourseBean): Boolean {
        if (a.day != b.day) return false
        val aStart = courseclock.timetable.utils.CourseReminderScheduler.minutesOfDay(courseTimes.startOf(a))
        val aFinish = courseclock.timetable.utils.CourseReminderScheduler.minutesOfDay(courseTimes.endOf(a))
        val bStart = courseclock.timetable.utils.CourseReminderScheduler.minutesOfDay(courseTimes.startOf(b))
        val bFinish = courseclock.timetable.utils.CourseReminderScheduler.minutesOfDay(courseTimes.endOf(b))
        if (aStart != null && aFinish != null && bStart != null && bFinish != null &&
                aFinish > aStart && bFinish > bStart) {
            return aStart < bFinish && bStart < aFinish
        }
        val aEnd = a.startNode + a.step - 1
        val bEnd = b.startNode + b.step - 1
        return a.startNode <= bEnd && b.startNode <= aEnd
    }

    /**
     * 与 [focus] 时间相交、且本周有安排的课（含免听）。
     *
     * 非本周的重叠课不进多课列表：点开卡片只应看到「这周这一天」的课，
     * 把还没开学/单双周错开的课混进来，用户会以为今天要上两门。
     * 按 [overlapPriority] 升序：赢家在第 0 页。
     */
    fun getMultiCourse(week: Int, day: Int, focus: CourseBean): List<CourseBean> {
        val list = allCourseList.getOrNull(day - 1)?.value ?: return emptyList()
        return list.asSequence()
                .filter { it.inWeek(week) && coursesOverlap(it, focus) }
                .sortedBy { overlapPriority(it, week) }
                .toList()
    }

    fun getShowCourseNumber(week: Int): LiveData<Int> {
        val showOtherWeek = table.showOtherWeekCourse
        return coursesOfTable(table.id).map { courses ->
            courses.count { if (showOtherWeek) it.endWeek >= week else it.inWeek(week) }
        }.distinctUntilChanged()
    }

    suspend fun deleteCourseBean(courseBean: CourseBean) {
        courseDao.deleteCourseDetail(CourseUtils.courseBean2DetailBean(courseBean))
        // 删掉最后一个时间段后，基础课行就成了谁也看不见的孤儿（课表和课程管理都是 join），
        // 却仍被课程数统计与同名认领读到——一并清掉。
        if (courseDao.getDetailByIdOfTable(courseBean.id, courseBean.tableId).isEmpty()) {
            courseDao.deleteCourseBaseBeanOfTable(courseBean.id, courseBean.tableId)
        }
    }

    suspend fun deleteCourseBaseBean(id: Int, tableId: Int) {
        courseDao.deleteCourseBaseBeanOfTable(id, tableId)
    }

    suspend fun exportData(uri: Uri?) {
        if (uri == null) throw Exception("无法获取文件")
        val gson = Gson()
        val strBuilder = StringBuilder()
        strBuilder.append(gson.toJson(timeTableDao.getTimeTable(table.timeTable)))
        strBuilder.append("\n${gson.toJson(timeList)}")
        strBuilder.append("\n${gson.toJson(table)}")
        strBuilder.append("\n${gson.toJson(courseDao.getCourseBaseBeanOfTable(table.id))}")
        strBuilder.append("\n${gson.toJson(courseDao.getDetailOfTable(table.id))}")
        withContext(Dispatchers.IO) {
            // 流必须关闭：write 对本地文件"碰巧"生效，但云同步类 DocumentsProvider 要到 close
            // 才提交内容；不 close 还会泄漏文件描述符。目标打不开时报错，而不是静默写出空文件。
            getApplication<App>().contentResolver.openOutputStream(uri)?.use { output ->
                output.write(strBuilder.toString().toByteArray())
            } ?: throw Exception("无法打开导出目标")
        }
    }

    /**
     * 导出 ICS 日历，返回**没能写入的课名列表**（部分成功必须可见：静默少课等于让用户
     * 拿着一份不完整的日历却不自知）。列表为空 = 全部课程都写进去了。
     */
    suspend fun exportICS(uri: Uri?): List<String> {
        if (uri == null) throw Exception("无法获取文件")
        val ical = ICalendar()
        val skipped = mutableListOf<String>()
        withContext(Dispatchers.Default) {
            ical.setProductId("-//YZune//WakeUpSchedule//EN")
            // 开学日期未设置时，任何一门课都算不出日期；给出可读原因，不要让 ParseException 漏给用户
            if (table.startDate.isBlank()) throw Exception("还没有设置开学日期，无法导出日历")
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            // parse 有两种失败方式，两条都得接住：格式不对抛 ParseException（会把英文栈信息
            // 冒到界面），空值返回 null（以前被直接当 Date 传下去，在 ICalUtils 里炸成
            // NullPointerException，用户只看到"导出失败>_<null"）。开学日期是人手输的，
            // 必须说清是哪个值读不出来 —— 调用方会把 message 原样显示出来。
            val date = try {
                sdf.parse(table.startDate)
            } catch (e: ParseException) {
                null
            } ?: throw Exception("开学日期「${table.startDate}」无法识别，无法导出日历")
            allCourseList.forEach { dayList ->
                dayList.value?.forEach { course ->
                    val added = try {
                        ICalUtils.getClassEvents(ical, courseTimes, table.maxWeek, course, date, table.sundayFirst)
                    } catch (e: Exception) {
                        Log.w("日历", "课程「${course.courseName}」写入日历失败", e)
                        0
                    }
                    if (added == 0) skipped.add(course.courseName)
                }
            }
        }
        val warnings = ical.validate(ICalVersion.V2_0)
        Log.d("日历", warnings.toString())
        withContext(Dispatchers.IO) {
            // 同 exportData：写完必须 close，云同步提供端在 close 时才提交。
            getApplication<App>().contentResolver.openOutputStream(uri)?.use { output ->
                Biweekly.write(ical).go(output)
            } ?: throw Exception("无法打开导出目标")
        }
        return skipped
    }
}
