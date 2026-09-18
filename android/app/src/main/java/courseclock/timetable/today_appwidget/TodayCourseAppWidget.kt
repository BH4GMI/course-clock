package courseclock.timetable.today_appwidget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import courseclock.timetable.AppDatabase
import courseclock.timetable.utils.*

/**
 * 日视图小部件（今日课程）。
 *
 * ## 只画今天
 *
 * 右上角那两个箭头（切明天 / 切回今天）已删除，连同"每个实例记住自己在看哪一天"的登记
 * （`day_widget_tomorrow_<id>`）一起删干净。理由不是功能不好，而是**它占的宽度正好是表头
 * 最需要的地方**：2×2 那一格里箭头把握手挤到只剩约 60dp，`9月 · 周五` 都会被截成 `9月 …`。
 *
 * ## 这里只做小部件自己的事
 *
 * 两条广播，全部与"视图画什么"有关：跨天闹钟、下课倒计时的每分钟刷新。**上课/下课提醒不在这里** ——
 * 提醒的投递方是 [courseclock.timetable.utils.CourseReminderNotifier]，接收方是
 * [courseclock.timetable.utils.CourseReminderReceiver]。
 *
 * 原先提醒的通知代码长在本类里，后果不只是文件放错了位置：提醒的 `PendingIntent` 指向
 * 一个 `AppWidgetProvider`，设置页据此认定"必须先放一个日视图小部件才能开提醒"，
 * 于是用户不想要小部件就一并失去了提醒。提醒只用 `AlarmManager` + `NotificationManager`，
 * 与小部件无关，不该由这里承担。
 *
 * ## 它是日视图两个入口的**共同实现**
 *
 * 本类同时是 4×2「当天课程」这个 provider。2×2「当天课程（小）」
 * （[SmallTodayCourseAppWidget]）继承它，只承担一个身份 —— 因为它要能被继承，所以是 `open`。
 * 这样做而不是复制一份：**逻辑一行不重复**，两个入口共用同一套广播处理、同一份
 * [AppWidgetUtils] 刷新路径、同一个 [TodayColorfulService] 渲染。
 * 之所以必须是两个 provider，是平台的约束（桌面按 provider 决定默认格数，
 * 而同一个 `<receiver android:name>` 不能在清单里出现两次），不是设计选择。
 */
open class TodayCourseAppWidget : AppWidgetProvider() {

    @SuppressLint("NewApi")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CourseReminderScheduler.ACTION_ROLLOVER) {
            // 每天 00:05 的跨天闹钟：重排闹钟 + 重查今天的课，结束时会排一枚 +1 秒的
            // ACTION_REFRESH_TODAY 广播把视图刷成"今天"（见 reschedule）。
            //
            // 重排要同步读三张表再循环几百枚 PendingIntent，走 goAsync + 协程，
            // 不占广播的主线程十分钟额度（App.renewReminderWindow 同理在 IO 上跑）。
            goAsync { CourseReminderScheduler.reschedule(context) }
        }
        if (intent.action == CourseReminderScheduler.ACTION_REFRESH_TODAY) {
            // 两个来源：reschedule 结尾那枚 +1 秒的一次性广播（只负责把视图刷成"今天"，
            // 这里**不要**再调 reschedule —— 那会取消并重建刚刚排好的闹钟，白折腾一轮），
            // 以及下课倒计时刷新链的每一环。
            //
            // 两种情况都接上链的下一环，而且**每一条刷新路径都接**是刻意的：这样任何一次
            // 刷新都会顺带修复断掉的链，不必为"链断了"再单独写一条恢复逻辑。
            goAsync {
                AppWidgetUtils.refreshTodayWidgets(context)
                CourseReminderScheduler.armNextCountdownRefresh(context)
            }
        }
        super.onReceive(context, intent)
    }

    @SuppressLint("NewApi")
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 小部件更新只是"顺手重排一次"的触发点之一，不再承担"注册提醒"的职责。
        //
        // 以前提醒是在这里注册的，而这里由 updatePeriodMillis（3 小时）驱动，AOSP 用的是
        // 非精确的 ELAPSED_REALTIME 重复闹钟：Doze 下被推迟、不对齐午夜、只在首次绑定时注册
        // 一次、重启和改时间后无人重排。现在提醒的排法完全由 CourseReminderScheduler 决定，
        // 这里只保留"刷新小部件"的职责；顺手的重排与刷新一起进 goAsync，不占主线程。
        goAsync {
            CourseReminderScheduler.reschedule(context)
            AppWidgetUtils.refreshTodayWidgets(context)
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

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager,
                                          appWidgetId: Int, newOptions: android.os.Bundle) {
        // 形态由**实际可用空间**决定（见 TodayColorfulService），所以改尺寸必须重画这一个实例。
        goAsync { AppWidgetUtils.refreshTodayWidgets(context, widgetId = appWidgetId) }
    }
}
