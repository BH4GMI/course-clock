package courseclock.timetable.schedule_manage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TableSelectBean

class ScheduleManageViewModel(application: Application) : AndroidViewModel(application) {

    private val dataBase = AppDatabase.getDatabase(application)
    private val tableDao = dataBase.tableDao()
    private val courseDao = dataBase.courseDao()

    suspend fun initTableSelectList(): MutableList<TableSelectBean> {
        return tableDao.getTableSelectList().toMutableList()
    }

    /** 每张课表的课程数（没课的表不在结果里 → 0），多课表卡片的「N 门课程」副标题用。 */
    suspend fun getCourseCounts(): Map<Int, Int> {
        return tableDao.getCourseCountOfTables().associate { it.tableId to it.courseCount }
    }

    /**
     * 把默认表切到 [newId]（多课表页「点一下切换」）。
     * 走 TableDao 的同一事务入口：先清旧默认标记再立新默认，「全库恰好一行默认表」不破。
     */
    suspend fun switchDefaultTable(oldId: Int, newId: Int) {
        tableDao.changeDefaultTable(oldId, newId)
    }

    suspend fun getCourseBaseBeanListByTable(tableId: Int): MutableList<CourseBaseBean> {
        return courseDao.getCourseBaseBeanOfTable(tableId).toMutableList()
    }

    /** 课表里全部课程安排（基础信息 + 每个时间段连在一起），课程管理页的两列卡片用。 */
    suspend fun getCourseListOfTable(tableId: Int): MutableList<CourseBean> {
        return courseDao.getCourseOfTable(tableId).toMutableList()
    }

    suspend fun getTableById(id: Int): TableBean? {
        return tableDao.getTableById(id)
    }

    suspend fun addBlankTable(tableName: String): Long {
        // 与首页的「新建课表」共用同一条不变量：库里一张表都没有时，新建的这张成为默认表。
        return tableDao.insertTableAsDefaultIfNone(TableBean(id = 0, tableName = tableName)).toLong()
    }

    suspend fun deleteTable(id: Int) {
        // 走 TableDao 的事务入口：删的是默认表时把默认标记转移给剩余第一张课表，
        // 避免"删一张表把 App 删瘫"（无默认表 = 首页引导导入 + 提醒停排 + 日视图空白）。
        tableDao.deleteTableAndFixDefault(id)
    }

    suspend fun clearTable(id: Int) {
        tableDao.clearTable(id)
    }

    suspend fun deleteCourse(course: CourseBaseBean) {
        courseDao.deleteCourseBaseBeanOfTable(course.id, course.tableId)
    }
}