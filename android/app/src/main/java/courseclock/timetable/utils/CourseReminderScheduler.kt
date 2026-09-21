package courseclock.timetable.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.util.Log
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.today_appwidget.TodayCourseAppWidget
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.parcelize.Parcelize

/** 滚动预排提醒，按课程边界更新状态；小组件的分钟刷新与通知生命周期互不依赖。 */
object CourseReminderScheduler {

    private const val TAG = "CourseReminder"

    const val ACTION_REMIND_COURSE = "WAKEUP_REMIND_COURSE"

    enum class ReminderKind {
        START,

        END
    }

    const val DEFAULT_BEFORE_START = 20

    const val DEFAULT_BEFORE_END = 0

    const val KEY_REMINDER_BEFORE_START = "reminder_before_start"
    const val KEY_REMINDER_BEFORE_END = "reminder_before_end"

    const val KEY_REMINDER_START_ENABLED = "reminder_start_enabled"

    const val KEY_REMINDER_END_ENABLED = "reminder_end_enabled"

    const val KEY_REMINDER_MERGE_ENABLED = "reminder_merge_enabled"

    const val DEFAULT_REMINDER_KIND_ENABLED = true
    const val DEFAULT_MERGE_ENABLED = true

    const val ACTION_ROLLOVER = "WAKEUP_ROLLOVER"

    const val ACTION_REFRESH_TODAY = "WAKEUP_REFRESH_TODAY"

    const val ACTION_REFRESH_COUNTDOWN = "WAKEUP_REFRESH_COUNTDOWN"

    const val ACTION_CANCEL_REMINDER = "WAKEUP_CANCEL_REMINDER"
    const val ACTION_REFRESH_STATUS = "WAKEUP_REFRESH_STATUS"
    const val ACTION_HIDE_STATUS = "WAKEUP_HIDE_STATUS"
    const val ACTION_DISMISS_STATUS = "WAKEUP_DISMISS_STATUS"
    const val EXTRA_STATUS_VALID_UNTIL = "status_valid_until"
    const val EXTRA_ENTRIES = "notification_entries"
    const val EXTRA_HIDDEN_KEYS = "hidden_occurrences"
    private const val KEY_HIDDEN = "hidden_course_occurrences"
    private const val REQUEST_STATUS = 0x5901
    private const val REQUEST_STATUS_BOUNDARY = 0x5902

    // 广播携带完整课程实例，接收时再与当前课表及偏好校验。
    const val EXTRA_INDEX = "index"

    fun payloadOf(intent: Intent): ReminderPayload = ReminderPayload(
            intent.getIntExtra(EXTRA_INDEX, 0),
            intent.getParcelableArrayListExtra<ReminderEntry>(EXTRA_ENTRIES).orEmpty())

    private const val REQUEST_BASE = 0x5700

    private const val REQUEST_NEXT_DAY = 0x57FF

    private const val REQUEST_REFRESH_TODAY = 0x57FE

    const val COUNTDOWN_WINDOW_MINUTES = 20
    const val START_COUNTDOWN_WINDOW_MINUTES = 60

    private const val REQUEST_COUNTDOWN = 0x5900

    private const val MAX_REMINDERS_PER_KIND = 250

    private const val REQUEST_KIND_STRIDE = 0x0100

    const val WINDOW_DAYS = 7

    val NEXT_DAY_HOUR = 0
    val NEXT_DAY_MINUTE = 5

    private val rescheduleLock = Any()

    fun changeTimeSource(context: Context, resetHistory: Boolean, change: () -> Unit) = synchronized(rescheduleLock) {
        change()
        if (resetHistory) {
            context.getPrefer().edit().remove(KEY_HIDDEN).apply()
            CourseReminderNotifier.resetTimeline(context)
        }
        reschedule(context)
        CourseReminderNotifier.reconcileReminders(context, forceRefresh = true)
    }

    fun reschedule(context: Context): Unit = synchronized(rescheduleLock) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(ALARM_SERVICE) as AlarmManager

        cancelAll(appContext, alarmManager)

        val now = CourseClock.nowMillis()
        val nextDayAt = nextTriggerMillisAt(now)

        val registered = if (appContext.getPrefer().getBoolean(Const.KEY_COURSE_REMIND, false)) {
            registerCourseReminders(appContext, alarmManager, now)
        } else {
            0
        }

        // 任何情况下都注册且只注册这一枚跨天闹钟，闭环由它自己续期。
        setExact(alarmManager, nextDayAt, nextDayPendingIntent(appContext))

        // 同上，无条件排一枚"立刻刷新视图"的一次性广播，让设置改完之后小部件马上显示新状态，
        // 不必等下一次跨天。这一枚同样必须是精确的：原先用 `set()`，真机上被 `power_pending`
        // 推了 5 小时 42 分才跑（12:19:32 设为 now+1000，18:01:12 才执行），"马上刷新"是空话。
        //
        // 时刻按**排它的这一刻**算，而不是复用函数开头捕获的 now：上面 [registerCourseReminders]
        // 要读库，耗时可能超过 1 秒（全新进程里光是建 Room 实例就够），用旧的 now 会得到一枚
        // 已经过点的闹钟。系统的 min_futurity=5s 会兜住它，但那是平台的宽容，不该当成设计。
        setExact(alarmManager, CourseClock.nowMillis() + 1000, refreshTodayPendingIntent(appContext))

        // 下课倒计时只保留下一次该变的那一刻。它与上面两枚互不相干，所以放在最后单独接。
        armNextCountdownRefresh(appContext)
        CourseReminderNotifier.reconcileReminders(appContext)
        refreshStatus(appContext)

        // 把 API 级别一并打出来：这台设备（HyperOS 3.0，装有 LSPosed 类兼容模块）在 **shell 里**
        // `getprop ro.build.version.sdk` / `release` 报 21 / 6.0.1 —— 那是模块改写出来的兼容值，
        // 不是框架真身，排查时按它判断会得出错误结论。App 进程内读到的是真值，本行日志即自证：
        // SDK_INT=36、release=16；与 ro.system/ro.vendor.build.version.sdk=36、
        // ro.build.fingerprint（`…/pandora:16/…`）三者一致。
        // SDK_INT 决定了下面用 setExactAndAllowWhileIdle 还是降级的 setExact —— 后者在 Doze 下
        // 会被推迟，正是本类要根治的那个缺陷，所以这里必须留一份不依赖 shell 的自证。
        appContext.getPrefer().edit().putLong(KEY_LAST_RESCHEDULE_AT, System.currentTimeMillis()).apply()

        Log.i(TAG, "重排完成：课程提醒 $registered 枚，跨天闹钟 1 枚（${format(nextDayAt)}）" +
                "，SDK_INT=${Build.VERSION.SDK_INT}（release ${Build.VERSION.RELEASE}）" +
                "，精确闹钟=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    "setExactAndAllowWhileIdle" else "setExact"}")
    }

    private const val KEY_LAST_RESCHEDULE_AT = "last_reschedule_at"

    private const val COLD_START_STALE_MS = 10 * 60 * 1000L

    fun rescheduleIfStale(context: Context) {
        val appContext = context.applicationContext
        if (CourseClock.simulated) {
            reschedule(appContext)
            return
        }
        val last = appContext.getPrefer().getLong(KEY_LAST_RESCHEDULE_AT, 0L)
        if (System.currentTimeMillis() - last < COLD_START_STALE_MS) {
            Log.i(TAG, "距上次全量重排不足 10 分钟，跳过本次进程启动触发的重排")
            return
        }
        reschedule(appContext)
    }

    private fun cancelAll(context: Context, alarmManager: AlarmManager) {
        // 上游老版本把提醒注册在 requestCode 0..63 上，曾在这一步逐枚清掉。本 fork 从 squash
        // 提交起步、从未发布过带旧接收组件的版本，不存在需要清理的存量闹钟——那段扫描是每次
        // 重排白付的 64×3 次 binder，已删除。
        for (kind in ReminderKind.values()) {
            for (index in 0 until MAX_REMINDERS_PER_KIND) {
                cancelAlarm(alarmManager, remindPendingIntent(context, requestCodeOf(kind, index)))
            }
        }
        cancelAlarm(alarmManager, nextDayPendingIntent(context))
        cancelAlarm(alarmManager, refreshTodayPendingIntent(context))
        cancelAlarm(alarmManager, countdownPendingIntent(context))
        cancelAlarm(alarmManager, statusPendingIntent(context))
        cancelAlarm(alarmManager, statusPendingIntent(context, boundary = true))
    }

    private fun defaultTableOrNull(context: Context): TableBean? =
            AppDatabase.getDatabase(context).tableDao().getDefaultTableSync()

    fun armNextCountdownRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(ALARM_SERVICE) as AlarmManager
        // 无小组件时不读数据库，也不排显示刷新。
        val manager = AppWidgetManager.getInstance(appContext)
        val hasWidget = DAY_WIDGET_PROVIDERS.any { manager.getAppWidgetIds(ComponentName(appContext, it)).isNotEmpty() }
        // 小组件的课前/下课分钟链独立排程，不依赖通知是否开启。
        val next = if (hasWidget) nextCountdownInstant(appContext, true) else null
        if (next == null) {
            // 今天已经没有该变的时刻（或没有日视图实例）：取消原来那一枚，否则它会带着
            // 旧时刻继续留在系统闹钟表里。
            cancelAlarm(alarmManager, countdownPendingIntent(appContext))
            return
        }
        setExactDisplay(alarmManager, next, countdownPendingIntent(appContext))
    }

    fun ongoingEnabled(context: Context): Boolean {
        return CourseNotificationSettings.enabled(context) &&
                CourseNotificationSettings.mode(context) != CourseNotificationSettings.StatusMode.OFF
    }

    private fun nextCountdownInstant(context: Context, includeStart: Boolean): Long? {
        val dataBase = AppDatabase.getDatabase(context)
        val table = defaultTableOrNull(context) ?: return null
        val day = try {
            dayOf(table.startDate, table.sundayFirst, CourseClock.nowMillis())
        } catch (e: ParseException) {
            return null
        }
        if (day.week < 0) {
            return null
        }
        val courses = dataBase.courseDao()
                .getCourseByDayOfTableSync(day.weekday, day.week, day.type, table.id)
                .filter { !it.notAttend }
        val times = CourseTimes.ofPreferred(context, dataBase.timeDetailDao().getTimeListSync(table.timeTable),
                dataBase.courseDao().getDetailOfTableSync(table.id))
        val now = CourseClock.nowMillis()
        return countdownInstants(courses, times, startOfDayMillis(now), now, includeStart).firstOrNull()
    }

    private fun requestCodeOf(kind: ReminderKind, index: Int): Int =
            REQUEST_BASE + kind.ordinal * REQUEST_KIND_STRIDE + index

    private fun cancelAlarm(alarmManager: AlarmManager, pendingIntent: PendingIntent) {
        CourseClock.cancel(alarmManager, pendingIntent)
        pendingIntent.cancel()
    }

    private fun setExact(alarmManager: AlarmManager, triggerAt: Long, pendingIntent: PendingIntent) {
        CourseClock.schedule(alarmManager, triggerAt, pendingIntent)
    }

    private fun setExactDisplay(alarmManager: AlarmManager, triggerAt: Long, pendingIntent: PendingIntent) {
        CourseClock.schedule(alarmManager, triggerAt, pendingIntent, idle = false)
    }

    private fun registerCourseReminders(context: Context, alarmManager: AlarmManager, now: Long): Int {
        val dataBase = AppDatabase.getDatabase(context)
        val courseDao = dataBase.courseDao()
        val timeDao = dataBase.timeDetailDao()

        // 必须接住"没有默认课表"：在这个位置让异常冒出去，连无条件该排的跨天闹钟都排不上，
        // App 会永远卡在「没有提醒」的状态。见 [defaultTableOrNull]。
        val table = defaultTableOrNull(context) ?: run {
            Log.w(TAG, "没有默认课表，本次只排跨天闹钟")
            return 0
        }

        val times = CourseTimes.ofPreferred(context, timeDao.getTimeListSync(table.timeTable),
                dataBase.courseDao().getDetailOfTableSync(table.id))
        val prefer = context.getPrefer()
        val beforeStart = prefer.getInt(KEY_REMINDER_BEFORE_START, DEFAULT_BEFORE_START)
        val beforeEnd = prefer.getInt(KEY_REMINDER_BEFORE_END, DEFAULT_BEFORE_END)
        // 关掉的类别根本不注册闹钟，而不是"注册了再判断不通知" —— 后者会白占一次精确唤醒。
        val startEnabled = CourseNotificationSettings.kindEnabled(context, ReminderKind.START)
        val endEnabled = CourseNotificationSettings.kindEnabled(context, ReminderKind.END)
        val mergeEnabled = prefer.getBoolean(KEY_REMINDER_MERGE_ENABLED, DEFAULT_MERGE_ENABLED)
        if (!startEnabled && !endEnabled) {
            Log.i(TAG, "上课与下课提醒都关着，本次只排跨天闹钟")
            return 0
        }
        val todayStart = startOfDayMillis(now)

        val alarms = ArrayList<ReminderAlarm>()
        for (dayOffset in -1 until WINDOW_DAYS) {
            val dayStart = startOfDayAfter(todayStart, dayOffset)
            val day = try {
                dayOf(table.startDate, table.sundayFirst, dayStart)
            } catch (e: ParseException) {
                // 开学日期不是 yyyy-MM-dd 时查不出课，但**不能**因此连跨天闹钟都不排：
                // 那会让 App 永远停在坏状态里。排下跨天闹钟，下次再试。
                Log.w(TAG, "开学日期无法解析，本次只排跨天闹钟", e)
                return 0
            }
            if (day.week !in 1..table.maxWeek) {
                continue
            }

            // 免听课不排提醒：用户明确不去听，响了只会是噪音。
            val courses = courseDao.getCourseByDayOfTableSync(
                    day.weekday, day.week, day.type, table.id)
                    .filter { !it.notAttend }
            // 只合并同一触发时刻，保留每门课程自己的提前量。
            alarms.addAll(alarmsForDay(
                    courses, day, times, dayStart, now, beforeStart, beforeEnd,
                    startEnabled, endEnabled, mergeEnabled))
        }

        alarms.sortBy { it.triggerAt }

        // 下标**每类独立**：requestCode 是 `区段基址 + index`，用"排序后的全局下标"会让 END 的
        // 下标挤进 START 的区段（两类差 256，全局下标一旦超过 256 就真的撞上），而 cancelAll
        // 只扫 0 until MAX_REMINDERS_PER_KIND，超出的那几枚永远取消不掉。
        val kindIndices = IntArray(ReminderKind.values().size)
        for (alarm in alarms) {
            val ordinal = alarm.kind.ordinal
            val index = kindIndices[ordinal]
            if (index >= MAX_REMINDERS_PER_KIND) {
                Log.w(TAG, "${alarm.kind} 提醒数已达上限 $MAX_REMINDERS_PER_KIND，后面的本次不排")
                continue
            }
            kindIndices[ordinal] = index + 1
            val requestCode = requestCodeOf(alarm.kind, index)
            val payload = ReminderPayload(requestCode, (listOf(alarm) + alarm.simultaneous).map {
                ReminderEntry(it.kind, it.triggerAt, it.occurrence)
            })
            val pi = PendingIntent.getBroadcast(
                    context, requestCode, payload.toIntent(context), pendingIntentFlags())

            // 精确性是硬要求：上课提醒早一分钟有意义、晚一小时就废了。唯一决定"精确"这件事的
            // 地方是 [setExact]，不要在这里再抄一遍版本分支。
            setExact(alarmManager, alarm.triggerAt, pi)
        }

        // 小部件刷新不产生唤醒（唤醒由闹钟本身负责），由 reschedule 统一排一枚一次性广播，
        // 不要在这里再排一遍。
        return kindIndices.sum()
    }

    private fun nextDayPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_NEXT_DAY,
                    CourseClock.stamp(Intent(context, TodayCourseAppWidget::class.java).apply { action = ACTION_ROLLOVER }),
                    pendingIntentFlags())

    private fun refreshTodayPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_REFRESH_TODAY,
                    Intent(context, TodayCourseAppWidget::class.java).apply { action = ACTION_REFRESH_TODAY },
                    pendingIntentFlags())

    private fun countdownPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_COUNTDOWN,
                    Intent(context, CourseReminderReceiver::class.java)
                            .apply { action = ACTION_REFRESH_COUNTDOWN },
                    pendingIntentFlags())

    private fun remindPendingIntent(context: Context, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(context, requestCode,
                    Intent(context, CourseReminderReceiver::class.java).apply { action = ACTION_REMIND_COURSE },
                    pendingIntentFlags())

    private fun statusPendingIntent(context: Context, boundary: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
            context, if (boundary) REQUEST_STATUS_BOUNDARY else REQUEST_STATUS,
            Intent(context, CourseReminderReceiver::class.java).apply {
                action = ACTION_REFRESH_STATUS
            }, pendingIntentFlags())

    // ---- 纯计算：不碰 Context、不读系统时钟，[now] 一律由调用方传入，单测直接断言这些函数 ----

    data class ReminderAlarm(val triggerAt: Long,
            val course: CourseBean,
            val weekdayName: String,
            val kind: ReminderKind,
            val time: String,
            val targetAt: Long,
            val occurrence: Occurrence,
            val simultaneous: List<ReminderAlarm> = emptyList()
    )

    data class CourseDayInWeek(val weekday: Int, val week: Int, val type: Int, val weekdayName: String)

    data class ReminderPayload(
            val index: Int,
            val entries: List<ReminderEntry>
    ) {
        fun toIntent(context: Context): Intent =
                Intent(context, CourseReminderReceiver::class.java).apply {
                    action = ACTION_REMIND_COURSE
                    CourseClock.stamp(this)
                    putExtra(EXTRA_INDEX, index)
                    putParcelableArrayListExtra(EXTRA_ENTRIES, ArrayList(entries))
                }
    }

    @Parcelize
    data class ReminderEntry(val kind: ReminderKind, val triggerAt: Long, val occurrence: Occurrence) : Parcelable {
        val targetAt: Long get() = if (kind == ReminderKind.START) occurrence.startAt else occurrence.endAt
        val expiresAt: Long get() = when (kind) {
            ReminderKind.START -> if (triggerAt == targetAt) minOf(targetAt + 5 * 60_000L, occurrence.endAt) else targetAt
            ReminderKind.END -> targetAt + 5 * 60_000L
        }
    }

    @Parcelize
    data class Occurrence(val key: String, val course: CourseDetail, val startAt: Long, val endAt: Long) : Parcelable {
        fun timeRange(): String = "${clockAt(startAt)}-${clockAt(endAt)}"
        fun summary(): String = listOf(course.name, timeRange(), course.room).filter { it.isNotBlank() }.joinToString(" · ")
    }

    @Parcelize
    data class CourseDetail(val name: String, val room: String) : Parcelable

    fun countdownInstants(
            courses: List<CourseBean>,
            times: CourseTimes,
            dayStart: Long,
            now: Long,
            includeStart: Boolean = false
    ): List<Long> {
        val instants = sortedSetOf<Long>()
        for (course in courses) {
            val endTime = times.endOfNode(course.startNode + course.step - 1, course.timeGroup)
            val endAt = reminderFreeze(endTime, dayStart, 0) ?: continue
            for (k in 0..COUNTDOWN_WINDOW_MINUTES) {
                val at = endAt - k * 60_000L
                if (at > now) {
                    instants.add(at)
                }
            }
            if (includeStart) {
                val startAt = reminderFreeze(times.startOf(course), dayStart, 0) ?: continue
                for (k in 0..START_COUNTDOWN_WINDOW_MINUTES) {
                    val at = startAt - k * 60_000L
                    if (at > now) {
                        instants.add(at)
                    }
                }
                // 小时保留一位小数，只有显示值变化时刷新，不额外枚举每一分钟。
                val minutes = remainingMinutes(startAt, now)
                // 0.1 小时 = 6 分钟；四舍五入的变化发生在 63->62、69->68 等边界。
                for (k in (START_COUNTDOWN_WINDOW_MINUTES + 3L)..minutes step 6) {
                    val at = startAt - (k - 1) * 60_000L
                    if (at > now) instants.add(at)
                }
            }
        }
        return instants.toList()
    }

    fun countdownMinutesLeft(endTime: String, now: Long): Long? {
        val endAt = reminderFreeze(endTime, startOfDayMillis(now), 0) ?: return null
        val remaining = remainingMinutes(endAt, now)
        return if (remaining in 1..COUNTDOWN_WINDOW_MINUTES.toLong()) remaining else null
    }

    fun countdownText(minutesLeft: Long): String = "还有 $minutesLeft 分钟下课"

    fun startCountdownMinutesLeft(startTime: String, now: Long): Long? {
        val startAt = reminderFreeze(startTime, startOfDayMillis(now), 0) ?: return null
        return remainingMinutes(startAt, now).takeIf { it > 0 }
    }

    fun startCountdownText(minutesLeft: Long): String = if (minutesLeft <= START_COUNTDOWN_WINDOW_MINUTES)
        "还有${minutesLeft}分钟上课"
    else ((minutesLeft + 3) / 6).let { tenths -> "约 ${tenths / 10}.${tenths % 10} 小时" }

    fun notificationCountdownText(minutesLeft: Long): String =
            "距离上课 ${minutesLeft / 60} 小时 ${minutesLeft % 60} 分钟"

    fun ongoingStateOf(courses: List<CourseBean>, times: CourseTimes, now: Long): OngoingState? {
        return stateOf(courses.filter { !it.notAttend }.mapNotNull {
            occurrenceOf(it, times, startOfDayMillis(now))
        }, now)
    }

    data class OngoingState(val entries: List<Occurrence>, val inClass: Boolean) {
        val title: String get() = if (entries.size == 1) entries.single().course.name.ifBlank { "课程" }
                else "${entries.size} 门课程${if (inClass) "同时进行" else "即将开始"}"
        val status: String get() = if (inClass) "正在上课" else "即将上课"
        val transitionAt: Long get() = entries.minOf { if (inClass) it.endAt else it.startAt }
    }

    fun stateOf(occurrences: List<Occurrence>, now: Long, hidden: Set<String> = emptySet()): OngoingState? {
        val visible = occurrences.filter { it.key !in hidden && it.endAt > now }
                .sortedWith(compareBy<Occurrence> { it.startAt }.thenBy { it.key })
        val current = visible.filter { it.startAt <= now }
        if (current.isNotEmpty()) return OngoingState(current, true)
        val next = visible.firstOrNull() ?: return null
        return OngoingState(visible.filter { it.startAt == next.startAt }, false)
    }

    fun occurrenceOf(course: CourseBean, times: CourseTimes, dayStart: Long): Occurrence? {
        val start = reminderFreeze(times.startOf(course), dayStart, 0) ?: return null
        val endTime = times.endOfNode(course.startNode + course.step - 1, course.timeGroup)
        val sameDayEnd = reminderFreeze(endTime, dayStart, 0)
                ?: return null
        if (sameDayEnd == start) {
            Log.w(TAG, "课程起止时间无效：${course.courseName}")
            return null
        }
        // 下课墙钟早于上课表示跨午夜，使用次日本地日期而不是固定加 24 小时。
        val end = if (sameDayEnd < start) Calendar.getInstance().apply {
            timeInMillis = sameDayEnd
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis else sameDayEnd
        val key = java.net.URI("course", "${course.tableId}/${course.id}/$start/$end/${course.room.orEmpty()}", null)
                .toASCIIString()
        return Occurrence(key, detailOf(course), start, end)
    }

    fun clockAt(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(at)

    fun nearbyOccurrences(context: Context, now: Long): List<Occurrence> {
        val db = AppDatabase.getDatabase(context)
        val table = defaultTableOrNull(context) ?: return emptyList()
        val times = CourseTimes.ofPreferred(context, db.timeDetailDao().getTimeListSync(table.timeTable),
                db.courseDao().getDetailOfTableSync(table.id))
        return (-1..1).flatMap { offset ->
            val dayStart = startOfDayAfter(now, offset)
            val day = try {
                dayOf(table.startDate, table.sundayFirst, dayStart)
            } catch (e: ParseException) {
                Log.w(TAG, "开学日期无效，无法计算课程通知", e)
                return emptyList()
            }
            if (day.week !in 1..table.maxWeek) emptyList() else db.courseDao()
                    .getCourseByDayOfTableSync(day.weekday, day.week, day.type, table.id)
                    .filter { !it.notAttend }.mapNotNull { occurrenceOf(it, times, dayStart) }
        }
    }

    /** 每条安排只展开最近的有效实例，不逐日查库，也不截断周末、单双周或尚未开学的间隔。 */
    internal fun upcomingOccurrences(courses: List<CourseBean>, times: CourseTimes, table: TableBean,
                                     now: Long, hidden: Set<String> = emptySet()): List<Occurrence> {
        if (table.startDate.isBlank()) return emptyList()
        val yesterday = startOfDayAfter(now, -1)
        return courses.filter { !it.notAttend && it.day in 1..7 }.flatMap { course ->
            val firstDate = startOfDayAfter(yesterday, Math.floorMod(course.day - weekdayOf(yesterday), 7))
            val firstWeek = dayOf(table.startDate, table.sundayFirst, firstDate).week
            val firstAllowed = maxOf(1, course.startWeek, firstWeek)
            val lastAllowed = minOf(table.maxWeek, course.endWeek)
            val pending = ArrayList<Occurrence>()
            for (week in firstAllowed..lastAllowed) {
                if (!course.inWeek(week)) continue
                val occurrence = occurrenceOf(course, times, startOfDayAfter(firstDate, (week - firstWeek) * 7))
                        ?: continue
                if (occurrence.endAt <= now) continue
                pending.add(occurrence)
                if (occurrence.key !in hidden) break
            }
            pending
        }
    }

    private fun statusOccurrences(context: Context, now: Long): List<Occurrence> {
        val db = AppDatabase.getDatabase(context)
        return db.runInTransaction(java.util.concurrent.Callable {
            val table = defaultTableOrNull(context) ?: return@Callable emptyList<Occurrence>()
            val courses = db.courseDao().getCourseOfTableSync(table.id)
            val times = CourseTimes.ofPreferred(context, db.timeDetailDao().getTimeListSync(table.timeTable),
                    db.courseDao().getDetailOfTableSync(table.id))
            try {
                upcomingOccurrences(courses, times, table, now,
                        context.getPrefer().getStringSet(KEY_HIDDEN, emptySet()).orEmpty())
            } catch (e: ParseException) {
                Log.w(TAG, "开学日期无效，无法计算下一节课", e)
                emptyList()
            }
        })
    }

    fun validEntries(context: Context, entries: List<ReminderEntry>, now: Long): List<ReminderEntry> {
        if (!CourseNotificationSettings.enabled(context)) return emptyList()
        val current = nearbyOccurrences(context, now).associateBy { it.key }
        return entries.filter { entry ->
            val before = context.getPrefer().getInt(if (entry.kind == ReminderKind.START)
                KEY_REMINDER_BEFORE_START else KEY_REMINDER_BEFORE_END,
                    if (entry.kind == ReminderKind.START) DEFAULT_BEFORE_START else DEFAULT_BEFORE_END)
            CourseNotificationSettings.kindEnabled(context, entry.kind) && now >= entry.triggerAt &&
                    now < entry.expiresAt && current[entry.occurrence.key] == entry.occurrence &&
                    entry.triggerAt == entry.targetAt - before * 60_000L
        }
    }

    fun hideStatus(context: Context, keys: Set<String>) {
        val preferences = context.getPrefer()
        val liveKeys = statusOccurrences(context, CourseClock.nowMillis()).map { it.key }.toSet()
        val hidden = (preferences.getStringSet(KEY_HIDDEN, emptySet()).orEmpty() + keys).intersect(liveKeys)
        preferences.edit().putStringSet(KEY_HIDDEN, hidden).apply()
        refreshStatus(context)
    }

    fun dismissStatus(context: Context, keys: Set<String>, validUntil: Long, now: Long = CourseClock.nowMillis()) {
        if (now < validUntil) hideStatus(context, keys) else refreshStatus(context)
    }

    fun refreshStatus(context: Context) {
        val manager = context.getSystemService(ALARM_SERVICE) as AlarmManager
        val now = CourseClock.nowMillis()
        val occurrences = if (ongoingEnabled(context) &&
                CourseReminderNotifier.canPost(context, CourseReminderNotifier.ONGOING_CHANNEL_ID))
            statusOccurrences(context, now) else emptyList()
        val state = stateOf(occurrences, now, context.getPrefer().getStringSet(KEY_HIDDEN, emptySet()).orEmpty())
        CourseReminderNotifier.refreshOngoing(context, now, state)
        // 边界独立保留唤醒闹钟；不能让非唤醒的文字刷新链负责续排，否则休眠会丢失开课切换。
        val next = statusBoundaries(occurrences, now).firstOrNull()
        if (next == null) cancelAlarm(manager, statusPendingIntent(context, boundary = true))
        else setExact(manager, next, statusPendingIntent(context, boundary = true))
        val displayAt = statusDisplayAt(state, now)
        if (displayAt == null) cancelAlarm(manager, statusPendingIntent(context))
        else setExactDisplay(manager, displayAt, statusPendingIntent(context))
    }

    fun statusBoundaries(occurrences: List<Occurrence>, now: Long): List<Long> = occurrences.flatMap {
        listOf(it.startAt, it.endAt)
    }.filter { it > now }.distinct().sorted()

    internal fun statusDisplayAt(state: OngoingState?, now: Long): Long? =
            state?.takeUnless { it.inClass }?.transitionAt?.let { start ->
                (start - (remainingMinutes(start, now) - 1) * 60_000L).takeIf { it < start }
            }

    private fun detailOf(course: CourseBean): CourseDetail =
            CourseDetail(course.courseName.orEmpty(), course.room.orEmpty())

    fun currentOngoingState(context: Context, now: Long = CourseClock.nowMillis()): OngoingState? {
        return stateOf(statusOccurrences(context, now), now,
                context.getPrefer().getStringSet(KEY_HIDDEN, emptySet()).orEmpty())
    }

    fun remainingMinutes(targetMillis: Long, nowMillis: Long): Long =
            Math.floorDiv(targetMillis - nowMillis + 59_999L, 60_000L)

    /** 每条事件保留原触发时刻；只合并完全同时的事件，不按课间距离抑制提醒。 */
    fun alarmsForDay(
            courses: List<CourseBean>,
            day: CourseDayInWeek,
            times: CourseTimes,
            dayStart: Long,
            now: Long,
            beforeStartMinutes: Int,
            beforeEndMinutes: Int,
            startEnabled: Boolean = true,
            endEnabled: Boolean = true,
            mergeEnabled: Boolean = true
    ): List<ReminderAlarm> {
        val result = ArrayList<ReminderAlarm>(courses.size * 2)
        for (course in courses.filter { !it.notAttend }) {
            val occurrence = occurrenceOf(course, times, dayStart) ?: continue
            for (kind in ReminderKind.values()) {
                if ((kind == ReminderKind.START && !startEnabled) || (kind == ReminderKind.END && !endEnabled)) continue
                val target = if (kind == ReminderKind.START) occurrence.startAt else occurrence.endAt
                val before = if (kind == ReminderKind.START) beforeStartMinutes else beforeEndMinutes
                val trigger = target - before * 60_000L
                if (trigger > now) result.add(ReminderAlarm(trigger, course, day.weekdayName,
                        kind, clockAt(target), target, occurrence))
            }
        }
        return if (!mergeEnabled) result.sortedBy { it.triggerAt } else result.groupBy { it.triggerAt }
                .values.map { it.first().copy(simultaneous = it.drop(1)) }.sortedBy { it.triggerAt }
    }

    fun reminderFreeze(time: String, dayStart: Long, beforeMinutes: Int): Long? {
        val minutes = minutesOfDay(time) ?: return null
        return Calendar.getInstance().apply {
            timeInMillis = dayStart
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, -beforeMinutes)
        }.timeInMillis
    }

    @Throws(ParseException::class)
    fun dayOf(startDate: String, sundayFirst: Boolean, basisMillis: Long): CourseDayInWeek {
        val week = CourseUtils.countWeek(startDate, sundayFirst, basisMillis)
        val weekday = CourseUtils.getWeekdayIntAt(basisMillis)
        return CourseDayInWeek(weekday, week, if (week % 2 == 0) 2 else 1, CourseUtils.getDayStr(weekday))
    }

    fun nextTriggerMillisAt(now: Long, hour: Int = NEXT_DAY_HOUR, minute: Int = NEXT_DAY_MINUTE): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    fun minutesOfDay(startTime: String): Int? {
        val parts = startTime.split(":")
        if (parts.size < 2) {
            return null
        }
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) {
            return null
        }
        return hour * 60 + minute
    }

    fun clockOf(minutes: Int): String =
            String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    fun shouldSchedule(time: String, now: Long, beforeMinutes: Int): Boolean =
            reminderFreeze(time, startOfDayMillis(now), beforeMinutes)?.let { it > now } ?: false

    fun reminderMillisFor(times: List<String>, now: Long, beforeMinutes: Int): List<Long> {
        val dayStart = startOfDayMillis(now)
        return times.mapNotNull { reminderFreeze(it, dayStart, beforeMinutes) }
                .filter { it > now }
                .sorted()
    }

    fun startOfDayMillis(now: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun startOfDayAfter(basisMillis: Long, days: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = basisMillis
        calendar.add(Calendar.DAY_OF_YEAR, days)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun weekdayOf(millis: Long): Int = CourseUtils.getWeekdayIntAt(millis)

    fun format(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return format.format(millis)
    }
}
