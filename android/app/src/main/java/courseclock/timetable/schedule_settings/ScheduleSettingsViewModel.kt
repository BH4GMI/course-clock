package courseclock.timetable.schedule_settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.TableBean
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.BackgroundStore
import java.util.*

class ScheduleSettingsViewModel(application: Application) : AndroidViewModel(application) {

    var mYear = 2018
    var mMonth = 9
    var mDay = 20
    lateinit var table: TableBean
    lateinit var termStartList: List<String>

    private val dataBase = AppDatabase.getDatabase(application)
    private val tableDao = dataBase.tableDao()
    private val replacedBackgrounds = mutableSetOf<String>()

    fun setBackground(background: String) {
        if (table.background != background) replacedBackgrounds.add(table.background)
        table.background = background
    }

    suspend fun saveSettings() {
        tableDao.updateTable(table)
        replacedBackgrounds.filter { it != table.background }.forEach {
            BackgroundStore.delete(getApplication(), it)
        }
        replacedBackgrounds.clear()
    }

    fun getCurrentWeek(): Int {
        return CourseUtils.countWeek(table.startDate, table.sundayFirst)
    }

    fun setCurrentWeek(week: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = courseclock.timetable.utils.CourseClock.nowMillis() }
        if (table.sundayFirst) {
            cal.firstDayOfWeek = Calendar.SUNDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        } else {
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        cal.add(Calendar.WEEK_OF_YEAR, -week + 1)
        mYear = cal.get(Calendar.YEAR)
        mMonth = cal.get(Calendar.MONTH) + 1
        mDay = cal.get(Calendar.DATE)
        table.startDate = "${mYear}-${mMonth}-${mDay}"
    }

}
