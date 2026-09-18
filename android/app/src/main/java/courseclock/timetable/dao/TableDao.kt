package courseclock.timetable.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TableCourseCount
import courseclock.timetable.bean.TableSelectBean

@Dao
interface TableDao {
    @Insert
    suspend fun insertTable(tableBean: TableBean): Long

    /**
     * 插入课表；**库里一张课表都没有时，新插入的这张直接接管默认标记**。
     *
     * 「全库恰好一行 `type = 1`」是全 App 依赖的不变量：首页取表、提醒排期、桌面小部件都按它来。
     * 全新安装不再预置课表之后（见 [courseclock.timetable.utils.UpdateUtils]），"用户建的第一张表"
     * 必须自己成为默认表 —— 否则建完表回到首页仍停在「去导入」的引导上，看着像没建成。
     *
     * 放在 DAO 层而不是各个调用点：这是"插入一张表"这件事本身该守住的不变量，
     * 交给调用方各写一遍，迟早会有新入口忘掉。
     */
    @Transaction
    suspend fun insertTableAsDefaultIfNone(tableBean: TableBean): Int {
        val id = insertTable(tableBean).toInt()
        if (getDefaultTableId() == null) setNewDefaultTable(id)
        return id
    }

    @Update
    suspend fun updateTable(tableBean: TableBean)

    @Query("select max(id) from tablebean")
    suspend fun getLastId(): Int?

    @Transaction
    suspend fun changeDefaultTable(oldId: Int, newId: Int) {
        resetOldDefaultTable(oldId)
        setNewDefaultTable(newId)
    }

    @Query("update tablebean set type = 0 where id = :oldId")
    suspend fun resetOldDefaultTable(oldId: Int)

    @Query("update tablebean set type = 1 where id = :newId")
    suspend fun setNewDefaultTable(newId: Int)

    @Query("select * from tablebean where id = :tableId")
    suspend fun getTableById(tableId: Int): TableBean?

    @Query("select * from tablebean where id = :tableId")
    fun getTableByIdSync(tableId: Int): TableBean?

    @Query("select id from tablebean where type = 1")
    suspend fun getDefaultTableId(): Int?

    /**
     * 默认课表；**没有默认课表时返回 null**。
     *
     * SQL 是 `where type = 1`，用户把课表全删掉之后一行都没有 —— 返回类型必须如实可空。
     * 以前这里写死成 `TableBean`，Room 照样塞回 null，然后在使用点抛
     * `NullPointerException`，调用方的 `try/catch` 根本接不住（判空发生在 try 之外）。
     * 同文件的 [getTableById] 一直是对的写法。
     */
    @Query("select * from tablebean where type = 1")
    suspend fun getDefaultTable(): TableBean?

    @Query("select * from tablebean where type = 1")
    fun getDefaultTableSync(): TableBean?

    @Query("select id, tableName, background, maxWeek, nodes, type from tablebean")
    fun getTableSelectListLiveData(): LiveData<List<TableSelectBean>>

    @Query("select id, tableName, background, maxWeek, nodes, type from tablebean")
    suspend fun getTableSelectList(): List<TableSelectBean>

    /**
     * 课表张数，**同步**读。
     *
     * 小部件配置页要在「画出来之前」决定弹不弹（只有一张课表就不该弹），而 `onCreate` 里起的协程
     * 最早也要让出一帧、页面会闪一下，所以这里必须能同步拿到。本工程的 Room 实例开了
     * `allowMainThreadQueries()`，且同文件已有 [getDefaultTableSync] / [getTableByIdSync]
     * 的先例，口径一致。
     *
     * 只数张数、不取整行：判定只需要知道"有没有得选"。
     */
    @Query("select count(*) from tablebean")
    fun countTablesSync(): Int

    /** 每张课表的课程数，多课表页的「N 门课程」副标题用；没课的表不在结果里，使用方按 0 处理。 */
    @Query("select tableId as tableId, count(*) as courseCount from coursebasebean group by tableId")
    suspend fun getCourseCountOfTables(): List<TableCourseCount>

    @Query("delete from tablebean where id = :id")
    suspend fun deleteTable(id: Int)

    /**
     * 清空课表（连带单双周的课）。**只给测试用**：`AppDatabase` 是单例，一个测试类里各方法共享
     * 同一个库，而播种用的是主键插入 —— 不清掉上一批就会撞主键，而"撞了主键"与"代码错了"在
     * 断言里长得一模一样。
     *
     * 外键是 ON DELETE CASCADE，所以课与上课安排会跟着课表一起没；时间表由
     * [TimeTableDao.clearAllTimeTables] 另行清掉（课表对它的外键是 SET_DEFAULT，不会级联删除）。
     */
    @Query("delete from tablebean")
    suspend fun clearAllTables()

    @Query("delete from coursebasebean where tableId = :id")
    suspend fun clearTable(id: Int)

    /**
     * 引用某张时间表的课表数量。时间表删除前的保护检查用：外键是 SET_DEFAULT，删掉一张
     * 被引用的时间表不会报错，只会把引用它的课表静默改指默认时间表（作息全变）。
     */
    @Query("select count(*) from tablebean where timeTable = :timeTableId")
    suspend fun countTablesUsingTimeTable(timeTableId: Int): Int

    /**
     * 删除课表并维护「全库恰好一行默认表」的不变量：删的是默认表时，把默认标记转移给
     * 剩余的第一张课表。此前删除不做转移，删掉默认表后 `where type = 1` 一行都不剩——
     * 首页落入「还没有课表」的导入引导、提醒停排，而其他课表明明还在。
     */
    @Transaction
    suspend fun deleteTableAndFixDefault(id: Int) {
        val wasDefault = getDefaultTableId() == id
        deleteTable(id)
        if (wasDefault) {
            getTableSelectList().firstOrNull()?.let { changeDefaultTable(id, it.id) }
        }
    }
}