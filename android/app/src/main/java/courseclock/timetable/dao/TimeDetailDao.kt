package courseclock.timetable.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import courseclock.timetable.bean.TimeDetailBean

@Dao
interface TimeDetailDao {
    @Insert
    suspend fun insertTimeList(list: List<TimeDetailBean>)

    @Update
    suspend fun updateTimeDetailList(timeDetailBeanList: List<TimeDetailBean>)

    /**
     * 设置页的时间表编辑器用：只取默认分组。
     *
     * 带分组的时间由导入产生，不归用户手工编辑，所以不列进编辑器。
     */
    @Query("select * from timedetailbean where timeTable = :id and timeGroup = '' order by node")
    fun getTimeListLiveData(id: Int): LiveData<List<TimeDetailBean>>

    /** 运行时用：返回该时间表下所有分组的节次，由 CourseTimes 在内存里归组。 */
    @Query("select * from timedetailbean where timeTable = :id order by node")
    suspend fun getTimeList(id: Int): List<TimeDetailBean>

    /** 同上，供无法挂起的地方（桌面小部件）使用。 */
    @Query("select * from timedetailbean where timeTable = :id order by node")
    fun getTimeListSync(id: Int): List<TimeDetailBean>

    /**
     * 全库时间明细一次取回，选择时间表页算每张表的「第 1-N 节 · 每节 X 分钟」摘要用。
     * 表数 × 每表 30 行以内，一次全量比逐表查询 N 次便宜。
     */
    @Query("select * from timedetailbean")
    suspend fun getAllTimeDetails(): List<TimeDetailBean>
}
