package courseclock.timetable.schedule_appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import courseclock.timetable.AppDatabase
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.UpdateUtils
import courseclock.timetable.utils.goAsync

/**
 * 一周课程（周视图）小部件的入口。
 *
 * 只做「什么时候重画」，重画本身统一在 [AppWidgetUtils.refreshScheduleWidgets] 里 ——
 * 那份实现按平台实例枚举，两个 provider 与「课表变了」那几处调用点共用同一个入口。
 *
 * ## 没有箭头，也就没有 action
 *
 * 右上角的「下一周 / 回到本周」两个箭头已删除，本类随之不再有 `onReceive`：它原先只处理这两条
 * 广播，且"看哪一周"是每个实例各自的状态（`schedule_widget_next_week_<id>`）—— 那套状态连同
 * 箭头一起删掉了，周视图现在只画本周。
 *
 * 日视图（[courseclock.timetable.today_appwidget.TodayCourseAppWidget]）同样删掉了它那两个箭头，
 * 两边的理由一致：它们占掉的宽度正好是表头文字最需要的地方。
 */
class ScheduleAppWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // appWidgetIds 这个参数用不上：同一个 provider 的所有实例本来就要一起重画，
        // 枚举实例的活统一由 AppWidgetUtils.refreshScheduleWidgets 干（原因见那里的注释）。
        goAsync {
            UpdateUtils.initDefaultData(context.applicationContext)
            AppWidgetUtils.refreshScheduleWidgets(context)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val widgetDao = AppDatabase.getDatabase(context).appWidgetDao()
        goAsync {
            for (id in appWidgetIds) {
                widgetDao.deleteAppWidget(id)
            }
        }
    }

    /**
     * 宿主（桌面）改了这个实例的尺寸。
     *
     * 周视图是一整张位图（[ScheduleAppWidgetService] 的 `getCount() == 1`，整周画进
     * `iv_schedule`）：不重画就一直是拖动前那份格子尺寸，用户看到的是拉伸变形或糊掉的字。
     * 「按尺寸自适应」这件事在周视图上不是排版问题，而是**必须重画一次**的问题。
     *
     * 只重画被改的那一个实例（见 [AppWidgetUtils.refreshScheduleWidgetFor]）。
     */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager,
                                          appWidgetId: Int, newOptions: android.os.Bundle) {
        goAsync { AppWidgetUtils.refreshScheduleWidgetFor(context, appWidgetId) }
    }
}
