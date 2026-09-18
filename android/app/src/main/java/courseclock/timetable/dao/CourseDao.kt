package courseclock.timetable.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.CourseDetailBean

@Dao
interface CourseDao {

    @Transaction
    suspend fun insertSingleCourse(courseBaseBean: CourseBaseBean, courseDetailList: List<CourseDetailBean>) {
        insertCourseBase(courseBaseBean)
        insertDetailList(courseDetailList)
    }

    @Transaction
    suspend fun updateSingleCourse(courseBaseBean: CourseBaseBean, courseDetailList: List<CourseDetailBean>) {
        updateCourseBaseBean(courseBaseBean)
        deleteDetailByIdOfTable(courseBaseBean.id, courseBaseBean.tableId)
        insertDetailList(courseDetailList)
    }

    @Transaction
    suspend fun updateSameCourse(courseBaseBean: CourseBaseBean, courseDetailList: List<CourseDetailBean>) {
        updateCourseBaseBean(courseBaseBean)
        insertDetailList(courseDetailList)
    }

    @Transaction
    suspend fun insertCourses(courseBaseList: List<CourseBaseBean>, courseDetailList: List<CourseDetailBean>) {
        insertBaseList(courseBaseList)
        insertDetailList(courseDetailList)
    }

    @Delete
    suspend fun deleteCourseDetail(courseDetailBean: CourseDetailBean)

    @Query("select * from coursebasebean where tableId = :tableId")
    suspend fun getCourseBaseBeanOfTable(tableId: Int): List<CourseBaseBean>

    /** 课表里全部课程安排（基础信息 join 每条时间段），课程管理页的卡片按基础课程分组展示。 */
    @Query("select * from coursebasebean natural join coursedetailbean where tableId = :tableId")
    suspend fun getCourseOfTable(tableId: Int): List<CourseBean>

    @Query("select * from coursebasebean natural join coursedetailbean where tableId = :tableId")
    fun getCourseOfTableLiveData(tableId: Int): LiveData<List<CourseBean>>

    @Query("select * from coursebasebean natural join coursedetailbean where day = :day and tableId = :tableId")
    fun getCourseByDayOfTableLiveData(day: Int, tableId: Int): LiveData<List<CourseBean>>

    @Query("select * from coursebasebean natural join coursedetailbean where day = :day and tableId = :tableId")
    suspend fun getCourseByDayOfTable(day: Int, tableId: Int): List<CourseBean>

    @Query("select * from coursebasebean natural join coursedetailbean where day = :day and tableId = :tableId")
    fun getCourseByDayOfTableSync(day: Int, tableId: Int): List<CourseBean>

    @Query("select * from coursebasebean natural join coursedetailbean where day = :day and tableId = :tableId and startWeek <= :week and endWeek >= :week and (type = 0 or type = :type)")
    suspend fun getCourseByDayOfTable(day: Int, week: Int, type: Int, tableId: Int): List<CourseBean>

    @Query("select * from coursebasebean natural join coursedetailbean where day = :day and tableId = :tableId and startWeek <= :week and endWeek >= :week and (type = 0 or type = :type)")
    fun getCourseByDayOfTableSync(day: Int, week: Int, type: Int, tableId: Int): List<CourseBean>

    @Query("select * from coursebasebean where id = :id and tableId = :tableId")
    suspend fun getCourseByIdOfTable(id: Int, tableId: Int): CourseBaseBean

    @Query("select max(id) from coursebasebean where tableId = :tableId")
    suspend fun getLastIdOfTable(tableId: Int): Int?

    @Query("delete from coursebasebean where id = :id and tableId = :tableId")
    suspend fun deleteCourseBaseBeanOfTable(id: Int, tableId: Int)

    @Query("select * from coursebasebean natural join coursedetailbean where courseName = :name and tableId = :tableId")
    suspend fun checkSameNameInTable(name: String, tableId: Int): CourseBaseBean?

    @Query("delete from coursebasebean where tableId = :tableId")
    suspend fun removeCourseBaseBeanOfTable(tableId: Int)

    @Query("delete from coursedetailbean where id = :id and tableId = :tableId")
    suspend fun deleteDetailByIdOfTable(id: Int, tableId: Int)

    @Query("select * from coursedetailbean where id = :id and tableId = :tableId")
    suspend fun getDetailByIdOfTable(id: Int, tableId: Int): List<CourseDetailBean>

    @Query("select * from coursedetailbean where tableId = :tableId")
    suspend fun getDetailOfTable(tableId: Int): List<CourseDetailBean>

    /**
     * 与 [getDetailOfTable] 同一份数据，给**已经在后台线程上**的调用方用（小部件工厂、
     * 闹钟重排）：它们要按这张课表现有的安排算「时间栏用哪套作息」，而那条路径上没法挂起。
     */
    @Query("select * from coursedetailbean where tableId = :tableId")
    fun getDetailOfTableSync(tableId: Int): List<CourseDetailBean>

    // teacher/room 列可空（手工建课或外部数据仍可能写入 NULL 行）：不过滤会把 NULL 混进
    // 自动补全列表，且 length(NULL) 为 NULL，排序结果不稳定。
    @Query("select distinct teacher from coursedetailbean where tableId = :tableId and teacher is not null order by length(teacher)")
    suspend fun getExistedTeachers(tableId: Int): List<String>

    @Query("select distinct room from coursedetailbean where tableId = :tableId and room is not null order by length(room)")
    suspend fun getExistedRooms(tableId: Int): List<String>

    @Query("SELECT COUNT(*) FROM coursedetailbean WHERE tableId=:tableId AND ((startWeek<=:week AND endWeek>=:week) AND (type=0 OR (:week % 2=0 AND type=2) OR (:week % 2=1 AND type=1)))")
    fun getShowCourseNumber(tableId: Int, week: Int): LiveData<Int>

    @Query("SELECT COUNT(*) FROM coursedetailbean WHERE tableId=:tableId AND endWeek>=:week")
    fun getShowCourseNumberWithOtherWeek(tableId: Int, week: Int): LiveData<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBaseList(courseBaseList: List<CourseBaseBean>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourseBase(courseBaseBean: CourseBaseBean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetailList(courseDetailList: List<CourseDetailBean>)

    @Update
    suspend fun updateCourseBaseBean(course: CourseBaseBean)

}
