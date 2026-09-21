package courseclock.timetable.utils

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import courseclock.timetable.R
import courseclock.timetable.schedule.ScheduleActivity
import courseclock.timetable.utils.CourseReminderScheduler.ReminderEntry
import courseclock.timetable.utils.CourseReminderScheduler.ReminderKind

/** 两个固定渠道：事件负责提醒，状态负责持续查看。焦点只是同一条状态通知的系统呈现。 */
object CourseReminderNotifier {
    const val CHANNEL_ID = "schedule_reminder"
    const val ONGOING_CHANNEL_ID = "schedule_ongoing"
    const val ONGOING_NOTIFICATION_ID = 0x5600
    const val ACTION_EXPIRE = "COURSE_NOTIFICATION_EXPIRE"
    const val EXTRA_TAG = "notification_tag"
    private const val EVENT_ID = 0x5601
    private const val TAG_PREFIX = "course_event:"
    private const val KEY_DELIVERED = "delivered_course_events"
    private const val KEY_DELIVERED_DAY = "delivered_course_day"
    private const val KEY_DELIVERED_PREVIOUS = "delivered_course_previous_day"
    private const val ACTIVE_STORE = "active_course_notifications"

    private fun activeStore(context: Context) = context.getSharedPreferences(ACTIVE_STORE, Context.MODE_PRIVATE)

    @Synchronized
    fun resetTimeline(context: Context) {
        activeStore(context).all.keys.toList().forEach { cancel(context, it) }
        NotificationManagerCompat.from(context).cancel(ONGOING_NOTIFICATION_ID)
        context.getPrefer().edit().remove(KEY_DELIVERED).remove(KEY_DELIVERED_DAY)
                .remove(KEY_DELIVERED_PREVIOUS).apply()
    }

    private fun storedEntries(context: Context, tag: String): List<ReminderEntry> {
        val encoded = activeStore(context).getString(tag, null) ?: return emptyList()
        return Gson().fromJson(encoded, object : TypeToken<List<ReminderEntry>>() {}.type)
    }

    fun canPost(context: Context, channel: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(channel)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, ScheduleActivity::class.java), pendingIntentFlags())

    private fun base(context: Context, channel: String): NotificationCompat.Builder =
            NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.wakeup)
                    .setContentIntent(open(context)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(NotificationCompat.Builder(context, channel)
                            .setSmallIcon(R.drawable.wakeup).setContentTitle("课程通知")
                            .setContentText("解锁后查看课程详情").build())

    private fun identity(entry: ReminderEntry) = "${entry.kind}:${entry.triggerAt}:${entry.occurrence.key}"

    @Synchronized
    @Suppress("MissingPermission")
    fun remind(context: Context, entries: List<ReminderEntry>, now: Long) {
        if (!canPost(context, CHANNEL_ID)) {
            Log.w("CourseReminder", "课程提醒未投递：系统通知权限或课程提醒渠道已关闭")
            return
        }
        val preferences = context.getPrefer()
        val day = CourseReminderScheduler.startOfDayMillis(now)
        val recordedDay = preferences.getLong(KEY_DELIVERED_DAY, 0)
        val delivered = if (recordedDay == day) preferences.getStringSet(KEY_DELIVERED, emptySet()).orEmpty() else emptySet()
        val previous = when (recordedDay) {
            day -> preferences.getStringSet(KEY_DELIVERED_PREVIOUS, emptySet()).orEmpty()
            CourseReminderScheduler.startOfDayAfter(day, -1) -> preferences.getStringSet(KEY_DELIVERED, emptySet()).orEmpty()
            else -> emptySet()
        }
        val pending = entries.filter { now in it.triggerAt until it.expiresAt && identity(it) !in delivered + previous }
        if (pending.isEmpty()) return
        val tag = TAG_PREFIX + pending.joinToString("|") { identity(it) }
        postReminder(context, tag, pending, now, false)
        preferences.edit().putLong(KEY_DELIVERED_DAY, day)
                .putStringSet(KEY_DELIVERED_PREVIOUS, previous)
                .putStringSet(KEY_DELIVERED, delivered + pending.map(::identity)).apply()
    }

    @Suppress("MissingPermission")
    private fun postReminder(context: Context, tag: String, entries: List<ReminderEntry>, now: Long, silent: Boolean) {
        val lines = entries.map { entry ->
            val verb = if (entry.kind == ReminderKind.START) "上课" else "下课"
            "$verb · ${entry.occurrence.summary()}"
        }
        val title = if (entries.size == 1) entries.single().occurrence.course.name.ifBlank { "课程提醒" }
                else "${entries.size} 项课程提醒"
        val dismiss = PendingIntent.getBroadcast(context, EVENT_ID,
                Intent(context, CourseReminderReceiver::class.java).apply {
                    CourseClock.stamp(this)
                    action = CourseReminderScheduler.ACTION_CANCEL_REMINDER
                    data = Uri.parse("courseclock://notification").buildUpon().appendPath(tag).build()
                    putExtra(EXTRA_TAG, tag)
                }, pendingIntentFlags())
        val notification = base(context, CHANNEL_ID).setContentTitle(title)
                .setContentText(entries.first().let {
                    listOf(if (it.kind == ReminderKind.START) "上课" else "下课", it.occurrence.timeRange(), it.occurrence.course.room)
                            .filter(String::isNotBlank).joinToString(" · ")
                }).setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
                .setOnlyAlertOnce(true).setWhen(now).setShowWhen(false)
                .setTimeoutAfter(if (CourseClock.simulated) CourseClock.timeoutUntil(entries.maxOf { it.expiresAt })
                        else entries.maxOf { it.expiresAt } - now)
                .addAction(R.drawable.wakeup, "知道了", dismiss).setDeleteIntent(dismiss)
                .addExtras(Bundle().apply {
                    putParcelableArrayList(CourseReminderScheduler.EXTRA_ENTRIES, ArrayList(entries))
                })
        if (silent) notification.setSilent(true)
        if (Build.VERSION.SDK_INT < 26) {
            notification.setPriority(NotificationCompat.PRIORITY_HIGH)
            if (!silent) notification.setDefaults(Notification.DEFAULT_ALL)
        }
        NotificationManagerCompat.from(context).notify(tag, EVENT_ID, notification.build())
        activeStore(context).edit().putString(tag, Gson().toJson(entries)).apply()
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 合并项可能不同时间失效；到最早边界移除过期项，不能让它们拖到最后一项才清除。
        CourseClock.schedule(manager, entries.minOf { it.expiresAt }, expiryIntent(context, tag), idle = false)
    }

    private fun expiryIntent(context: Context, tag: String): PendingIntent = PendingIntent.getBroadcast(
            context, EVENT_ID, Intent(context, CourseReminderReceiver::class.java).apply {
                CourseClock.stamp(this)
                action = ACTION_EXPIRE
                data = Uri.parse("courseclock://expiry").buildUpon().appendPath(tag).build()
                putExtra(EXTRA_TAG, tag)
            }, pendingIntentFlags())

    fun cancel(context: Context, tag: String) {
        NotificationManagerCompat.from(context).cancel(tag, EVENT_ID)
        val pending = expiryIntent(context, tag)
        CourseClock.cancel(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager, pending)
        pending.cancel()
        activeStore(context).edit().remove(tag).apply()
    }

    @Synchronized
    fun reconcileReminders(context: Context, onlyTag: String? = null, forceRefresh: Boolean = false) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val now = CourseClock.nowMillis()
        // API 21/22 没有 activeNotifications；自有投递记录保证旧系统同样能关闭和清理。
        val visible = if (Build.VERSION.SDK_INT >= 23) manager.activeNotifications.mapNotNull { it.tag }.toSet() else null
        for (tag in activeStore(context).all.keys) {
            if (onlyTag != null && onlyTag != tag) continue
            val entries = storedEntries(context, tag)
            val valid = if (canPost(context, CHANNEL_ID)) CourseReminderScheduler.validEntries(context, entries, now)
                else emptyList()
            if (valid.isEmpty() || (visible != null && tag !in visible)) cancel(context, tag)
            else if (forceRefresh || valid != entries) postReminder(context, tag, valid, now, true)
        }
    }

    @Synchronized
    @Suppress("MissingPermission")
    fun refreshOngoing(context: Context, now: Long = CourseClock.nowMillis(),
                       state: CourseReminderScheduler.OngoingState? = if (CourseReminderScheduler.ongoingEnabled(context) &&
                               canPost(context, ONGOING_CHANNEL_ID)) CourseReminderScheduler.currentOngoingState(context, now) else null) {
        val manager = NotificationManagerCompat.from(context)
        if (state == null) {
            manager.cancel(ONGOING_NOTIFICATION_ID)
            return
        }
        fun hideIntent(actionName: String) = PendingIntent.getBroadcast(context, ONGOING_NOTIFICATION_ID,
                Intent(context, CourseReminderReceiver::class.java).apply {
                    CourseClock.stamp(this)
                    action = actionName
                    // 身份包含本次课程集合，旧通知的划走事件不能误隐藏刚开始的下一门课。
                    data = Uri.parse("courseclock://status").buildUpon()
                            .appendPath(state.entries.joinToString("|") { it.key }).appendPath(CourseClock.revision.toString()).build()
                    putStringArrayListExtra(CourseReminderScheduler.EXTRA_HIDDEN_KEYS, ArrayList(state.entries.map { it.key }))
                    putExtra(CourseReminderScheduler.EXTRA_STATUS_VALID_UNTIL, state.transitionAt)
                }, pendingIntentFlags())
        val hide = hideIntent(CourseReminderScheduler.ACTION_HIDE_STATUS)
        fun datedRange(entry: CourseReminderScheduler.Occurrence): String =
                if (CourseReminderScheduler.startOfDayMillis(entry.startAt) == CourseReminderScheduler.startOfDayMillis(now))
                    entry.timeRange()
                else java.text.SimpleDateFormat("M月d日 ", java.util.Locale.CHINA).format(java.util.Date(entry.startAt)) + entry.timeRange()
        val detail = state.entries.joinToString("\n") {
            listOf(it.course.name, datedRange(it), it.course.room).filter(String::isNotBlank).joinToString(" · ")
        }
        val statusText = if (state.inClass) state.status else
            CourseReminderScheduler.notificationCountdownText(CourseReminderScheduler.remainingMinutes(state.transitionAt, now))
        val compact = listOf(statusText, datedRange(state.entries.first()), state.entries.first().course.room)
                .filter { it.isNotBlank() }.joinToString(" · ")
        val builder = base(context, ONGOING_CHANNEL_ID).setContentTitle(state.title)
                .setContentText(compact)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$statusText\n$detail"))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                // 系统超时也会发送 deleteIntent，必须与用户明确的隐藏操作分开。
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setDeleteIntent(hideIntent(CourseReminderScheduler.ACTION_DISMISS_STATUS))
                .addAction(R.drawable.wakeup, "隐藏本次状态", hide)
                .setTimeoutAfter(if (CourseClock.simulated) CourseClock.timeoutUntil(state.transitionAt) else state.transitionAt - now)
                .setWhen(state.transitionAt).setShowWhen(true)
        if (CourseClock.simulated) {
            builder.setShowWhen(false).setSubText("模拟时间 · 剩余 ${CourseReminderScheduler.remainingMinutes(state.transitionAt, now)} 分钟")
        } else if (Build.VERSION.SDK_INT >= 24) builder.setUsesChronometer(true).setChronometerCountDown(true)
        else builder.setShowWhen(false)
        val notification = AndroidLiveNotification.promote(context, builder.build(), state, now)
        manager.notify(ONGOING_NOTIFICATION_ID, notification)
    }
}
