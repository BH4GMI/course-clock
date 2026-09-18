package courseclock.timetable.course_add

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.CourseEditBean
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.ViewUtils

class AddCourseViewModel(application: Application) : AndroidViewModel(application) {

    val editList = mutableListOf<CourseEditBean>()
    val baseBean: CourseBaseBean by lazy(LazyThreadSafetyMode.NONE) {
        CourseBaseBean(-1, "", "", tableId)
    }

    var teacherList: ArrayList<String>? = null
    var roomList: ArrayList<String>? = null

    private val dataBase = AppDatabase.getDatabase(application)
    private val courseDao = dataBase.courseDao()
    private val saveList = mutableListOf<CourseDetailBean>()

    var updateFlag = true
    var newId = -1
    var tableId = 0
    var maxWeek = 30
    var nodes = 11

    fun judgeType(list: ArrayList<Int>): Int {
        val oddListCount = list.count {
            it % 2 == 1
        }
        val evenCount = maxWeek / 2
        val oddCount = maxWeek - evenCount
        // 0表示不是全部的单周也不是全部的双周
        if (oddCount == oddListCount && oddCount == list.size) {
            return 1
        }
        if (evenCount == list.size && oddListCount == 0) {
            return 2
        }
        return 0
    }

    suspend fun preSaveData(isSame: Boolean = false) {
        saveList.clear()
        if (baseBean.id == -1) {
            updateFlag = false
            baseBean.id = newId
            editList.forEach {
                it.id = newId
            }
        } else {
            editList.forEach {
                it.id = baseBean.id
            }
        }
        if (baseBean.color == "") {
            baseBean.color = "#${Integer.toHexString(ViewUtils.getCustomizedColor(getApplication(), baseBean.id % 9))}"
        }
        for (i in editList.indices) {
            saveList.addAll(CourseUtils.editBean2DetailBeanList(editList[i]))
        }

        val selfUnique = CourseUtils.checkSelfUnique(saveList)

        if (selfUnique) {
            saveData(isSame)
        } else {
            throw Exception("此处填写的时间有重复，请仔细检查")
        }
    }

    private suspend fun saveData(isSame: Boolean = false) {
        if (isSame) {
            courseDao.updateSameCourse(baseBean, saveList)
            return
        }
        if (updateFlag) {
            courseDao.updateSingleCourse(baseBean, saveList)
        } else {
            courseDao.insertSingleCourse(baseBean, saveList)
        }
    }

    suspend fun checkSameName(): CourseBaseBean? {
        return courseDao.checkSameNameInTable(baseBean.courseName, baseBean.tableId)
    }

    fun initData(maxWeek: Int): MutableList<CourseEditBean> {
        editList.add(CourseEditBean(
                tableId = tableId,
                weekList = MutableLiveData<ArrayList<Int>>().apply {
                    this.value = ArrayList<Int>().apply {
                        for (i in 1..maxWeek) {
                            this.add(i)
                        }
                    }
                }))
        return editList
    }

    suspend fun initData(id: Int, tableId: Int): List<CourseDetailBean> {
        return courseDao.getDetailByIdOfTable(id, tableId)
    }

    suspend fun getLastId(): Int? {
        return courseDao.getLastIdOfTable(tableId)
    }

    suspend fun initBaseData(id: Int): CourseBaseBean {
        return courseDao.getCourseByIdOfTable(id, tableId)
    }

    suspend fun getExistedTeachers(): ArrayList<String> {
        return ArrayList(courseDao.getExistedTeachers(tableId))
    }

    suspend fun getExistedRooms(): ArrayList<String> {
        return ArrayList(courseDao.getExistedRooms(tableId))
    }
}