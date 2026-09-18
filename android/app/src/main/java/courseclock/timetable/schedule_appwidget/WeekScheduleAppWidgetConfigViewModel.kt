package courseclock.timetable.schedule_appwidget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.AppWidgetBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TableSelectBean

class WeekScheduleAppWidgetConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val dataBase = AppDatabase.getDatabase(application)
    private val tableDao = dataBase.tableDao()
    private val widgetDao = dataBase.appWidgetDao()

    suspend fun getDefaultTable(): TableBean? {
        return tableDao.getDefaultTable()
    }

    suspend fun getTableById(id: Int): TableBean? {
        return tableDao.getTableById(id)
    }

    suspend fun insertWeekAppWidgetData(appWidget: AppWidgetBean) {
        widgetDao.insertAppWidget(appWidget)
    }

    suspend fun getTableList(): List<TableSelectBean> {
        return tableDao.getTableSelectList()
    }
}