package courseclock.timetable.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import courseclock.timetable.utils.goAsync

/**
 * 提醒模块的**唯一广播入口**。两条职责，来源不同，互不相干：
 *
 * ## 一、系统事件 → 重排闹钟
 *
 * 这几条 action **都在官方隐式广播豁免清单里**，所以 targetSdk ≥ 26 时清单注册依然有效：
 *
 * - `BOOT_COMPLETED`：重启会清空 `AlarmManager` 里的所有闹钟，不重排就再也没有提醒。
 *   需要 `RECEIVE_BOOT_COMPLETED`（normal 权限，不弹框）。
 * - `TIME_SET`：用户改了系统时间，原来那些按绝对时刻排的闹钟全部错位。
 * - `TIMEZONE_CHANGED`：时区一改，绝对时刻没变但本地时间变了，同样错位。
 * - `MY_PACKAGE_REPLACED`：覆盖安装会清掉进程与待处理的 PendingIntent，顺手重排一次。
 *
 * 刻意**不**监听 `ACTION_DATE_CHANGED`：它比上面几条热得多，却不在豁免清单里（AOSP 发送时
 * 也没带 `FLAG_RECEIVER_INCLUDE_BACKGROUND`），清单接收器收不到，写了等于没写。跨天由
 * [CourseReminderScheduler] 的 00:05 精确闹钟负责。
 *
 * ## 二、提醒闹钟 → 弹通知
 *
 * [CourseReminderScheduler.ACTION_REMIND_COURSE] 由 # 模块排出的精确闹钟在本类触发，
 * 转交 [CourseReminderNotifier] 发通知；[CourseReminderScheduler.ACTION_CANCEL_REMINDER]
 * 由通知上「我知道啦」按钮触发，撤掉那条通知。
 *
 * ## 为什么接收方是它，而不是 `TodayCourseAppWidget`
 *
 * 这两条 action 原先都由 `TodayCourseAppWidget`（一个 `AppWidgetProvider`）处理，于是提醒
 * 被绑在了小部件上：设置页据此要求"必须先放一个日视图小部件才能开提醒"，用户不想要小部件
 * 就无路可走。提醒只用 `AlarmManager` + `NotificationManager`，与桌面放不放东西无关，
 * 所以投递方搬到本类。小部件的点击/刷新广播仍由两个 `AppWidgetProvider` 自己处理。
 */
class CourseReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "收到 ${intent.action}，重排提醒")
                // 重排要同步读三张表再循环几百枚 PendingIntent，goAsync + 协程把它挪出
                // 广播的主线程；finish() 由扩展函数保证。
                goAsync {
                    CourseReminderScheduler.reschedule(context)
                    try {
                        AppWidgetUtils.refreshAllWidgets(context)
                    } catch (e: Exception) {
                        Log.w(TAG, "系统事件后刷新小部件失败", e)
                    }
                }
            }

            CourseReminderScheduler.ACTION_REMIND_COURSE -> {
                // 这里再查一次总闸，而不是"排了闹钟就一定发"：用户关掉提醒时 reschedule 会
                // 取消已在系统里的闹钟，但取消与本条广播之间存在竞态窗口（闹钟已经派发、
                // 广播还在队列里）。此时唯一正确的行为是安静地不发。
                if (!context.getPrefer().getBoolean(Const.KEY_COURSE_REMIND, false)) {
                    Log.i(TAG, "总闸已关，丢弃这一枚提醒广播")
                    return
                }
                val payload = CourseReminderScheduler.payloadOf(intent)
                CourseReminderNotifier.remind(
                        context,
                        index = payload.index,
                        kind = payload.kind,
                        targetAt = payload.targetAt,
                        now = System.currentTimeMillis(),
                        course = payload.course,
                        next = payload.next)
            }

            CourseReminderScheduler.ACTION_CANCEL_REMINDER ->
                CourseReminderNotifier.cancel(
                        context, intent.getIntExtra(CourseReminderScheduler.EXTRA_INDEX, 0))
        }
    }

    private companion object {
        const val TAG = "CourseReminder"
    }
}
