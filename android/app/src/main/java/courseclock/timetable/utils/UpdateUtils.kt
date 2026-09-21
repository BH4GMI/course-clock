package courseclock.timetable.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.bean.TimeTableBean

object UpdateUtils {

    @Throws(Exception::class)
    fun getVersionName(): String {
        return courseclock.timetable.BuildConfig.VERSION_NAME
    }

    /**
     * 全新安装的种子数据：**只种"默认作息"，不种课表**。
     *
     * 以前这里会插一行 `tableName = ""` 的课表并标成默认表，于是"刚装好的 App"和"课表被清空了的
     * App"长得一模一样：管理课表里挂着一张没有名字的表，用户以为是自己的数据出了问题。
     * 现在全新安装停在**无课表**状态，首页据此走「去导入」的引导（[courseclock.timetable.schedule.ScheduleActivity]
     * 的 `getDefaultTable() == null` 分支）。课表只由两条路产生：导入，或用户自己新建。
     *
     * 默认作息（`TimeTableBean(id = 1)`）仍然要种：`TableBean.timeTable` 的默认值是 1，
     * 外键是立即检查的，少这一行的话「新建课表」会直接因外键违约失败。
     *
     * **幂等**：本函数在冷启动、小部件更新时都会被调到，所以每一步都"先查后插"。
     */
    suspend fun initDefaultData(context: Context) {
        val prefer = context.getPrefer()
        val dataBase = AppDatabase.getDatabase(context)
        val timeTableDao = dataBase.timeTableDao()
        // "已经种过"的凭据是**默认作息还在不在**，不是"库里有没有课表"：全新安装本来就不建课表，
        // 拿课表当凭据会永远判成"没种过"，每次冷启动都白跑一趟。
        // 而 fallbackToDestructiveMigration 清库时连作息一起清掉，所以这个凭据同样能识别出
        // "库被重建过、必须补种"。
        val seeded = prefer.getBoolean(Const.KEY_HAS_ADJUST, false)
        if (seeded && timeTableDao.getTimeTable(1) != null) return
        if (!seeded && prefer.getBoolean("has_intro", false)) return
        val timeDao = dataBase.timeDetailDao()
        if (timeTableDao.getTimeTable(1) == null) {
            timeTableDao.insertTimeTable(TimeTableBean(id = 1, name = "默认"))
        }
        if (timeDao.getTimeList(1).isEmpty()) {
            try {
                timeDao.insertTimeList(defaultTimeList())
            } catch (e: Exception) {
                // 种子失败不置标记、下次启动重试。旧版在这里空 catch 后照样置位
                // has_adjust，播种失败的用户会永远停在「无课表」状态且无人知晓。
                Log.e(TAG, "默认作息播种失败，保留标记待下次启动重试", e)
                return
            }
        }
        prefer.edit {
            putBoolean(Const.KEY_HAS_ADJUST, true)
        }
    }

    private const val TAG = "UpdateUtils"

    /** 默认时间表第 1~11 节的真实作息 + 第 12~30 节的 00:00 占位行。 */
    private fun defaultTimeList(): List<TimeDetailBean> = listOf(
            TimeDetailBean(1, "08:00", "08:50", 1),
            TimeDetailBean(2, "09:00", "09:50", 1),
            TimeDetailBean(3, "10:10", "11:00", 1),
            TimeDetailBean(4, "11:10", "12:00", 1),
            TimeDetailBean(5, "13:30", "14:20", 1),
            TimeDetailBean(6, "14:30", "15:20", 1),
            TimeDetailBean(7, "15:40", "16:30", 1),
            TimeDetailBean(8, "16:40", "17:30", 1),
            TimeDetailBean(9, "18:30", "19:20", 1),
            TimeDetailBean(10, "19:30", "20:20", 1),
            TimeDetailBean(11, "20:30", "21:20", 1),
            TimeDetailBean(12, "00:00", "00:00", 1),
            TimeDetailBean(13, "00:00", "00:00", 1),
            TimeDetailBean(14, "00:00", "00:00", 1),
            TimeDetailBean(15, "00:00", "00:00", 1),
            TimeDetailBean(16, "00:00", "00:00", 1),
            TimeDetailBean(17, "00:00", "00:00", 1),
            TimeDetailBean(18, "00:00", "00:00", 1),
            TimeDetailBean(19, "00:00", "00:00", 1),
            TimeDetailBean(20, "00:00", "00:00", 1),
            TimeDetailBean(21, "00:00", "00:00", 1),
            TimeDetailBean(22, "00:00", "00:00", 1),
            TimeDetailBean(23, "00:00", "00:00", 1),
            TimeDetailBean(24, "00:00", "00:00", 1),
            TimeDetailBean(25, "00:00", "00:00", 1),
            TimeDetailBean(26, "00:00", "00:00", 1),
            TimeDetailBean(27, "00:00", "00:00", 1),
            TimeDetailBean(28, "00:00", "00:00", 1),
            TimeDetailBean(29, "00:00", "00:00", 1),
            TimeDetailBean(30, "00:00", "00:00", 1)
    )
}
