package courseclock.timetable.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import courseclock.timetable.R
import courseclock.timetable.schedule.ScheduleActivity

/**
 * 上课 / 下课提醒的**通知**投递。
 *
 * ## 为什么单独一个类，而不是留在 `TodayCourseAppWidget` 里
 *
 * 这段代码原先写在 `TodayCourseAppWidget.onReceive` 的 `WAKEUP_REMIND_COURSE` 分支里。
 * 后果不只是"文件放得不对"，而是提醒在结构上长成了小部件的一个功能：
 *
 * - 提醒闹钟的 `PendingIntent` 指向 `TodayCourseAppWidget`（那是个 `AppWidgetProvider`）；
 * - 设置页据此认为"没放日视图小部件就不该开提醒"，于是**总闸被小部件卡住**
 *   （见 `SettingsActivity` 原先那条 `getWidgetsByTypes(0, 1)` 判定）；
 * - 用户不想要小部件、只想要提醒，就无路可走。
 *
 * 而提醒实际只用到两样东西：`AlarmManager`（[CourseReminderScheduler]）和
 * `NotificationManager`（本类）。与小部件没有任何关系。所以投递方独立成这里，
 * 广播接收方是 [CourseReminderReceiver]，小部件只负责画自己。
 *
 * ## 通知的形态
 *
 * 一个按钮「我知道啦」，它和通知主体的点击各自指向不同的 `PendingIntent`：前者取消这条
 * 通知，后者打开 App。原先还有一个「记得给手机静音哦」按钮，它和这个按钮**指向同一个
 * PendingIntent**（行为完全一样，都只是关掉通知），而那句文案承诺的事它做不到 —— App
 * 没有也拿不到改系统音量的能力。那是上游「静音提醒」功能的化石，已删除。
 *
 * 重要级 / 震动 / 铃声 / 灯在 API 26+ 只由通知渠道决定，`NotificationCompat` 上对应的
 * setter 一律被系统忽略，所以这里一个都不设。渠道在 `App.createNotificationChannel()` 里
 * 创建，那是唯一的真相来源；要改重要级或震动，改渠道本身。
 */
object CourseReminderNotifier {

    private const val TAG = "CourseReminder"

    /**
     * 通知渠道 id。
     *
     * 与 `App.createNotificationChannel()` 里创建的那个必须一致 —— 渠道不存在时
     * `NotificationManager.notify` 在 API 26+ 会直接丢弃这条通知（只打一条 log），
     * 所以这两个字符串是同一份契约的两端。
     */
    const val CHANNEL_ID = "schedule_reminder"

    /**
     * 发一条提醒通知。
     *
     * 剩余分钟由 [now] 与 [targetAt] **现算**（[CourseReminderScheduler.remainingMinutes]），
     * 不回放设置里的分钟数 —— 闹钟被系统推迟之后，回放就等于对用户说谎。
     *
     * [index] 既是闹钟的 requestCode 也是通知 id：它唯一标识 (上课/下课, 序号) 两个维度，
     * 且随闹钟一起冻结。若改用"注册那一刻的排序下标"，任何一次重排都会让同一节课换一个 id，
     * 旧通知取消不掉、堆在通知栏里。
     *
     * Suppress("MissingPermission")：函数体开头的 `areNotificationsEnabled()` 在 API 33+
     * 内部就是 POST_NOTIFICATIONS 的授权状态（androidx core 官方语义），且额外覆盖
     * "用户在系统设置里关通知"的场景；lint 的静态分析认不出这层 API 等价，再补一段
     * checkSelfPermission 只会变成同一件事的两处判断。
     */
    @Suppress("MissingPermission")
    fun remind(
            context: Context,
            index: Int,
            kind: CourseReminderScheduler.ReminderKind,
            targetAt: Long,
            now: Long,
            course: CourseReminderScheduler.CourseDetail,
            next: CourseReminderScheduler.CourseDetail?
    ) {
        val remaining = if (targetAt > 0L) {
            CourseReminderScheduler.remainingMinutes(targetAt, now)
        } else {
            0L
        }
        val text = CourseReminderScheduler.notificationText(kind, remaining, course, next)

        val cancelIntent = Intent(context, CourseReminderReceiver::class.java).apply {
            action = CourseReminderScheduler.ACTION_CANCEL_REMINDER
            putExtra(CourseReminderScheduler.EXTRA_INDEX, index)
        }
        val cancelPendingIntent: PendingIntent =
                PendingIntent.getBroadcast(context, index, cancelIntent, pendingIntentFlags())

        val openIntent = Intent(context, ScheduleActivity::class.java)
        val openPendingIntent: PendingIntent =
                PendingIntent.getActivity(context, 0, openIntent, pendingIntentFlags(0))

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setWhen(now)
                .setSmallIcon(R.drawable.wakeup)
                .setAutoCancel(false)
                .setOngoing(context.getPrefer().getBoolean(Const.KEY_REMINDER_ON_GOING, false))
                .addAction(R.drawable.wakeup, "我知道啦", cancelPendingIntent)
                .setContentIntent(openPendingIntent)

        val notificationManager = NotificationManagerCompat.from(context)
        // 权限被系统关掉后 notify() 是静默丢弃：精确闹钟照常唤醒设备，用户却什么都收不到，
        // 只会以为「App 不灵」。在投递口留下可诊断的痕迹，别让它无声无息地失败。
        // 用 compat 版判断：areNotificationsEnabled 在 API 24 以下没有系统实现，compat 统一返回 true。
        if (!notificationManager.areNotificationsEnabled()) {
            Log.w(TAG, "通知权限已关闭，提醒无法显示（requestCode=$index，${course.name}）")
            return
        }
        notificationManager.notify(index, notification.build())
    }

    /** 撤掉一条提醒通知。[index] 与 [remind] 用的是同一个 id。 */
    fun cancel(context: Context, index: Int) {
        NotificationManagerCompat.from(context).cancel(index)
    }
}
