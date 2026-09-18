package courseclock.timetable.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.utils.CourseUtils

class TimeSettingsViewModel(application: Application) : AndroidViewModel(application) {

    lateinit var table: TableBean

    private val dataBase = AppDatabase.getDatabase(application)
    private val timeDao = dataBase.timeDetailDao()
    private val timeTableDao = dataBase.timeTableDao()
    private val tableDao = dataBase.tableDao()
    val timeTableList = arrayListOf<TimeTableBean>()
    val timeList = arrayListOf<TimeDetailBean>()
    val timeSelectList = arrayListOf<String>()

    var entryPosition = 0
    var selectedId = 1

    suspend fun addNewTimeTable(name: String) {
        timeTableDao.initTimeTable(TimeTableBean(id = 0, name = name))
    }

    suspend fun initTimeTableData(id: Int) {
        val timeList = listOf(
                TimeDetailBean(1, "08:00", "08:50", id),
                TimeDetailBean(2, "09:00", "09:50", id),
                TimeDetailBean(3, "10:10", "11:00", id),
                TimeDetailBean(4, "11:10", "12:00", id),
                TimeDetailBean(5, "13:30", "14:20", id),
                TimeDetailBean(6, "14:30", "15:20", id),
                TimeDetailBean(7, "15:40", "16:30", id),
                TimeDetailBean(8, "16:40", "17:30", id),
                TimeDetailBean(9, "18:30", "19:20", id),
                TimeDetailBean(10, "19:30", "20:20", id),
                TimeDetailBean(11, "20:30", "21:20", id),
                TimeDetailBean(12, "00:00", "00:00", id),
                TimeDetailBean(13, "00:00", "00:00", id),
                TimeDetailBean(14, "00:00", "00:00", id),
                TimeDetailBean(15, "00:00", "00:00", id),
                TimeDetailBean(16, "00:00", "00:00", id),
                TimeDetailBean(17, "00:00", "00:00", id),
                TimeDetailBean(18, "00:00", "00:00", id),
                TimeDetailBean(19, "00:00", "00:00", id),
                TimeDetailBean(20, "00:00", "00:00", id),
                TimeDetailBean(21, "00:00", "00:00", id),
                TimeDetailBean(22, "00:00", "00:00", id),
                TimeDetailBean(23, "00:00", "00:00", id),
                TimeDetailBean(24, "00:00", "00:00", id),
                TimeDetailBean(25, "00:00", "00:00", id),
                TimeDetailBean(26, "00:00", "00:00", id),
                TimeDetailBean(27, "00:00", "00:00", id),
                TimeDetailBean(28, "00:00", "00:00", id),
                TimeDetailBean(29, "00:00", "00:00", id),
                TimeDetailBean(30, "00:00", "00:00", id)
        )
        timeDao.insertTimeList(timeList)
    }

    suspend fun deleteTimeTable(timeTableBean: TimeTableBean) {
        // 外键是 SET_DEFAULT：删一张被引用的时间表不会报错，只会把引用它的课表静默改指
        // 默认时间表（上课时间全变）。保护必须在删除入口做，UI 的 catch 拦不住不抛的错。
        val usedBy = tableDao.countTablesUsingTimeTable(timeTableBean.id)
        if (usedBy > 0) {
            throw Exception("该时间表正被 $usedBy 张课表使用，请先在课表设置里改用其他时间表")
        }
        timeTableDao.deleteTimeTable(timeTableBean)
    }

    fun getTimeTableList(): LiveData<List<TimeTableBean>> {
        return timeTableDao.getTimeTableList()
    }

    /**
     * 每张时间表的一句人话摘要（设计稿 10）：「第 1-12 节 · 每节约 45 分钟」。
     *
     * 摘要按**默认分组**（timeGroup 为空）算——带教学楼分组的时间由导入产生，行数更多但
     * 节次范围一致，不该让摘要随分组数变化。占位行（起止都是 00:00）不算节次；
     * 全是占位行的时间表摘要是 null（界面显示「还没有设置作息」）。
     */
    suspend fun timeTableSummaries(): Map<Int, Pair<Int, Int>> {
        val placeholder = "00:00"
        return timeDao.getAllTimeDetails()
                .filter { it.timeGroup.isEmpty() }
                .filter { it.startTime != placeholder && it.endTime != placeholder }
                .groupBy { it.timeTable }
                .mapValues { (_, rows) ->
                    val first = rows.minByOrNull { it.node }!!
                    val last = rows.maxByOrNull { it.node }!!
                    Pair(last.node, durationMinutes(first.startTime, first.endTime))
                }
    }

    /** "08:00" 到 "08:50" = 50。解析不动就按 0，别让一行脏数据把整个摘要页炸掉。 */
    private fun durationMinutes(start: String, end: String): Int {
        fun minutes(t: String): Int? {
            val parts = t.split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return h * 60 + m
        }
        val s = minutes(start) ?: return 0
        val e = minutes(end) ?: return 0
        return (e - s).coerceAtLeast(0)
    }

    fun getTimeData(id: Int): LiveData<List<TimeDetailBean>> {
        return timeDao.getTimeListLiveData(id)
    }

    suspend fun saveDetailData(tablePosition: Int) {
        timeTableDao.updateTimeTable(timeTableList[tablePosition])
        timeDao.updateTimeDetailList(timeList)
    }

    fun initTimeSelectList() {
        for (i in 6..23) {
            for (j in 0..55 step 5) {
                val h = if (i < 10) "0$i" else i.toString()
                val m = if (j < 10) "0$j" else j.toString()
                timeSelectList.add("$h:$m")
            }
        }
    }

    /**
     * 把「每节时长」应用到整张时间表。
     *
     * 只动真实行：`00:00-00:00` 的占位行（第 12~30 节在默认时间表里的形状）跳过。旧实现对
     * 30 行全部重算，占位行的结束时间被改写成 00:45 之类的脏数据并随「保存」入库；之后把
     * 课表的「一天课程节数」调过 11，这些行就成了真实的（错误的）上课时间。
     */
    fun refreshEndTime(min: Int) {
        timeList.forEach { detail ->
            if (detail.startTime == PLACEHOLDER_TIME && detail.endTime == PLACEHOLDER_TIME) {
                return@forEach
            }
            detail.endTime = CourseUtils.calAfterTime(detail.startTime, min)
        }
    }

    companion object {
        /** 未编辑过的占位行的形状：起止都是 00:00。 */
        private const val PLACEHOLDER_TIME = "00:00"
    }
}