package courseclock.timetable.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import courseclock.timetable.bean.AppWidgetBean

/**
 * `appwidgetbean`：记录「某个小部件实例配的是哪张课表」。
 *
 * 只有**周视图**的配置页会写它，而且只在"有多张课表可选"时才会走到（只有一张时配置页直接结束，
 * 见 `WeekScheduleAppWidgetConfigActivity`）。所以这张表**可能一行都没有**，读它的地方一律要
 * 容忍空表 —— `AppWidgetUtils.refreshScheduleWidgets` 的做法是「有登记就按登记的课表画，
 * 没有登记就用默认课表」，正是为此。
 */
@Dao
interface AppWidgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppWidget(appWidgetBean: AppWidgetBean)

    @Query("delete from appwidgetbean where id = :id")
    suspend fun deleteAppWidget(id: Int)

    @Query("select * from appwidgetbean where baseType = :baseType and detailType = :detailType")
    suspend fun getWidgetsByTypes(baseType: Int, detailType: Int): List<AppWidgetBean>
}
