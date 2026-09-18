package courseclock.timetable.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Intent
import android.os.Build
import android.util.Log
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.today_appwidget.TodayCourseAppWidget
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 上课提醒的全量重排。
 *
 * ## 为什么要有这个类
 *
 * 提醒原先只在 `TodayCourseAppWidget.onUpdate()` 里注册，而 `onUpdate` 由
 * `res/xml/` 下那几个 app_widget_info 的 `updatePeriodMillis`（3 小时）驱动。AOSP 对它的实现是
 * `setInexactRepeating(ELAPSED_REALTIME_WAKEUP, ...)`：非精确、用开机时长而非墙上时钟、
 * 且只在组件首次绑定时注册一次。于是设备整夜 Doze 时，那次更新可能推迟到用户摸手机之后
 * 才执行 —— 早上 8 点的课，提醒会静默消失。
 *
 * 这里把"提醒什么时候排"从"小部件什么时候刷新"里拆出来：本类是提醒的唯一注册入口，
 * 每次调用都做**幂等的全量重排**（先取消本模块注册过的所有闹钟，再按当前状态重排一遍）。
 *
 * ## 排出来的闹钟只有两类
 *
 * - **上课提醒 + 下课提醒**：未来 **[WINDOW_DAYS] 天滚动窗口**内、所有尚未到点的课，**每节课两枚**
 *   （上课一枚、下课一枚），各自独立判断是否已过点：
 *   - 上课提醒：触发时刻 = 该节**开始**时刻 − [KEY_REMINDER_BEFORE_START] 分钟
 *   - 下课提醒：触发时刻 = 该节**结束**时刻 − [KEY_REMINDER_BEFORE_END] 分钟（默认 0）
 *
 *   两枚都用 `setExactAndAllowWhileIdle(RTC_WAKEUP, ...)`。**不用 `setAlarmClock`**：它会往系统
 *   状态栏塞"下一个闹钟"图标，官方也明确说它 extremely expensive on battery use。
 *
 *   开始/结束时刻一律走 `CourseTimes` 那套**分组感知**的取时刻方式，绝不按 node 索引自己算 ——
 *   本校上午第 3~5 节按楼宇错峰，(节次, 分组) 才唯一决定时刻。
 * - **跨天闹钟**：**任何情况下有且只有一枚**，定在下一个 00:05，动作是"把日视图复位回今天
 *   并再调一次 [reschedule]"。它把自己续期到明天，于是形成闭环。
 *
 * ## 为什么是「滚动窗口」，而不是「今天剩余」
 *
 * 只排"今天剩余"的话，**当天提醒的注册就依赖一个每日触发器**（跨天闹钟或那次小部件更新）
 * 必须先跑起来。而那个触发器本身就是非精确的 `set` 闹钟，Doze 整夜时会被推迟到用户早上
 * 摸手机那一刻才派发 —— 于是那天早上的提醒从没被注册过，正好是本文开头那个缺陷本身。
 *
 * 排满一个 [WINDOW_DAYS] 天窗口之后，就算 00:05 那枚、`onUpdate` 那枚、开机那枚**全都没跑**，
 * 未来一周的提醒也已经躺在系统里了，当天提醒的注册**不再依赖任何每日触发器**。
 * 注册成本约等于零：不唤醒、不耗电，只是往 system_server 的闹钟表里插记录。
 *
 * ## 跨天闹钟的职责（降级为杂务，别再让它当"当天提醒的注册点"）
 *
 * 提醒已经由滚动窗口自己扛住了，所以这枚闹钟只负责两件晚一点做也无害的杂务：
 * **把小部件的日期刷成新的今天**、**把滚动窗口向前滚一天并清掉已过期的闹钟**。
 *
 * 但"杂务"不等于"可以用非精确闹钟"。这里原先写着"正因为无害，它才配用非精确的 `set`，
 * 不为它付一次强制唤醒" —— 这句话在真机上被证伪：这台 Xiaomi/HyperOS 设备给非精确闹钟加
 * 了一条 MIUI 私有的 `power_pending` 策略，把 `whenElapsed` 统一改成 `requester + 3 天`，
 * 于是"省下的那次唤醒"变成"这枚闹钟根本没在 00:05 响过"，跨天闭环整个断掉。
 * 实测数据见 [setExact]。一天多一枚精确唤醒换回闭环真的成立，这笔账是划算的。
 *
 * ## 刻意不做的事
 *
 * 不加 `SCHEDULE_EXACT_ALARM`：targetSdk 29 用精确闹钟本来就不需要权限，声明它反而会让
 * App Standby 桶被钉在 WORKING_SET 或更低。不用 WorkManager / JobScheduler：它们内部就是
 * JobScheduler，Doze 下不运行。不加前台服务 / 常驻服务 / 保活 —— 本 App 现在零常驻组件，
 * 这是优点。不监听 `ACTION_DATE_CHANGED`：它不在官方隐式广播豁免清单里，targetSdk ≥ 26 的
 * 清单接收器收不到，跨天只能靠闹钟。
 */
object CourseReminderScheduler {

    private const val TAG = "CourseReminder"

    /** 上课提醒广播的 action。接收方是 [TodayCourseAppWidget.onReceive]，发通知的代码在那里。 */
    const val ACTION_REMIND_COURSE = "WAKEUP_REMIND_COURSE"

    /**
     * 这一枚提醒是上课还是下课。
     *
     * 一边是"该去上课了"、另一边是"这节下课了"，两条通知的文案不同，requestCode 也分属两个
     * 区段（见 [REQUEST_KIND_STRIDE]），互不覆盖。
     */
    enum class ReminderKind {
        /** 上课提醒：触发时刻 = 开始时刻 − 上课提前量。 */
        START,

        /** 下课提醒：触发时刻 = 结束时刻 − 下课提前量（默认 0，即"下课时间到"就提醒）。 */
        END
    }

    /** 上课提醒的默认提前量，沿用原先那枚 `KEY_REMINDER_TIME` 的默认值 20 分钟的体感。 */
    const val DEFAULT_BEFORE_START = 20

    /** 下课提醒的默认提前量：0，下课时间到就提醒。 */
    const val DEFAULT_BEFORE_END = 0

    /**
     * 提醒相关的偏好键。
     *
     * 刻意放在这里而不是 `Const`：`Const` 是另一个任务正在动的共享文件，而这些键只被
     * 「本调度器 + 设置页」这一对读写方使用，放在同一个类里能保证读写的字符串永远同源。
     * 旧的那枚 `KEY_REMINDER_TIME`（"reminder_min"）已被 [KEY_REMINDER_BEFORE_START] /
     * [KEY_REMINDER_BEFORE_END] 取代，个人 fork 放弃向后兼容，不做迁移。
     */
    const val KEY_REMINDER_BEFORE_START = "reminder_before_start"
    const val KEY_REMINDER_BEFORE_END = "reminder_before_end"

    /** 上课提醒开关。总闸仍是 `Const.KEY_COURSE_REMIND`，本开关在总闸之下生效。 */
    const val KEY_REMINDER_START_ENABLED = "reminder_start_enabled"

    /** 下课提醒开关。 */
    const val KEY_REMINDER_END_ENABLED = "reminder_end_enabled"

    /** 上下课合并开关：相邻两节连堂时，把"下一节"并进上一节的下课提醒并抑制下一节的上课提醒。 */
    const val KEY_REMINDER_MERGE_ENABLED = "reminder_merge_enabled"

    /** 三个开关的默认值：都开。 */
    const val DEFAULT_REMINDER_KIND_ENABLED = true
    const val DEFAULT_MERGE_ENABLED = true

    /**
     * 「连堂」的最大空档分钟数。相邻两节的实际空档 ≤ 本值才算连堂，才做合并。
     *
     * 20 这个数是按本校作息表实测算出来的，不是拍的：`SuesEamsImporter` 里三套上午方案 +
     * 公共时段排出来之后，同一天内真实存在的空档**只有 5 / 15 / 20 三档**：
     *
     * - **5 分钟**：19:30→19:35（第 12-13 节 → 第 14 节）、20:15→20:20（第 14 → 第 15 节）
     * - **15 分钟**：16:20→16:35（第 8-9 节 → 第 10-11 节）、17:55→18:10（第 10-11 → 第 12-13 节）
     * - **20 分钟**：09:35→09:55（第 1-2 节 → 第 3 节）、14:40→15:00（第 6-7 节 → 第 8-9 节）
     * - **80 分钟**：12:00→13:20（上午最后一节 → 下午第一节，即午休）
     *
     * 也就是说 20 分钟**就是一个课间的上界**，而午休 80 分钟远在它之外。取 ≤ 20 的含义是
     * "只隔一个课间"才算连在一起；多于一概不算，午休因此天然被排除，不需要为它写任何特例。
     * **改这个值前先回到作息表核一遍上面这组数字。**
     */
    const val ADJACENT_BREAK_MAX_MINUTES = 20

    /**
     * 跨天闹钟的 action。
     *
     * 原先这里复用了日视图「返回今天」按钮的 `WAKEUP_BACK_TIME`，理由是"两件事都在把小部件
     * 复位回今天"。那个理由只对了一半：跨天还要**重排全部提醒**，而按钮不该顺带重排几十枚闹钟；
     * 两件事共用一个字符串之后，任何一侧改动都会让另一侧静默失效（真机上表现为"返回按钮按了
     * 没反应"）。现在各有各的 action。
     */
    const val ACTION_ROLLOVER = "WAKEUP_ROLLOVER"

    /**
     * 重排完成后用来即时刷新小部件视图的一次性广播。
     *
     * 重排不产生唤醒（唤醒由闹钟本身负责），所以这里可以放心地用闹钟而不是直接调刷新：
     * 它让广播接收结束之后才刷新，不把 `goAsync` 的生命周期拖到 `onUpdate`/`onReceive` 之外。
     */
    const val ACTION_REFRESH_TODAY = "WAKEUP_REFRESH_TODAY"

    /** 提醒通知上「我知道啦」按钮的广播 action。接收方同样是 [CourseReminderReceiver]。 */
    const val ACTION_CANCEL_REMINDER = "WAKEUP_CANCEL_REMINDER"

    /*
     * 提醒从"闹钟"走到"通知"所携带的线格式（Intent extras 的键）。
     *
     * 发送方是 [registerCourseReminders]，接收方是 [CourseReminderReceiver]，中间隔着
     * 一个由系统持有的 PendingIntent。这几个字符串就是那条边的契约，所以两侧共用同一组
     * 常量，而不是各写一遍字面量 —— 后者在改名时不会报错，只会静默丢字段。
     *
     * 刻意**没有** `time` / `weekDay`：它们从加进来到现在没有任何读者，通知文案用的是
     * [ReminderAlarm.targetAt] 现算的剩余分钟（见 [notificationText]）。
     */
    const val EXTRA_INDEX = "index"
    const val EXTRA_KIND = "kind"
    const val EXTRA_TARGET_AT = "targetAt"
    const val EXTRA_COURSE_NAME = "courseName"
    const val EXTRA_ROOM = "room"
    const val EXTRA_NEXT_COURSE_NAME = "nextCourseName"
    const val EXTRA_NEXT_ROOM = "nextRoom"

    /**
     * 从提醒广播的 Intent 里还原出通知所需的全部内容。
     *
     * 只有 [EXTRA_KIND] 有兜底（缺了按上课提醒处理）；其余字段缺失时退化成空串 / 0，
     * 通知照发不误 —— 一条缺了教室的提醒仍然有用，静默不发才是更坏的结果。
     */
    fun payloadOf(intent: Intent): ReminderPayload = ReminderPayload(
            index = intent.getIntExtra(EXTRA_INDEX, 0),
            kind = if (intent.getStringExtra(EXTRA_KIND) == ReminderKind.END.name) {
                ReminderKind.END
            } else {
                ReminderKind.START
            },
            targetAt = intent.getLongExtra(EXTRA_TARGET_AT, 0L),
            course = CourseDetail(
                    intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty(),
                    intent.getStringExtra(EXTRA_ROOM).orEmpty()),
            next = intent.getStringExtra(EXTRA_NEXT_COURSE_NAME)?.let {
                CourseDetail(it, intent.getStringExtra(EXTRA_NEXT_ROOM).orEmpty())
            })

    /** requestCode 基址：本模块的闹钟从 0x5700 起编号，远离小部件点击用的 0/1/2。 */
    private const val REQUEST_BASE = 0x5700

    /** 跨天闹钟的 requestCode。 */
    private const val REQUEST_NEXT_DAY = 0x57FF

    /** 首次重排用的即刻广播 requestCode，用完即取消。 */
    private const val REQUEST_REFRESH_TODAY = 0x57FE

    /**
     * 小部件「还有 N 分钟下课」倒计时窗口的长度，单位分钟。
     *
     * 刻意**不复用** [KEY_REMINDER_BEFORE_END]（下课前几分钟提醒）：那个值是"什么时候弹一条
     * 通知"的提前量，默认 0（下课时间到才提醒）；倒计时窗口是"什么时候开始在小部件上写数字"。
     * 两者绑在一起的话，用户把下课提醒设成 0 分钟，倒计时就会全程消失 —— 一个开关悄悄改变了
     * 另一个功能的可见时机。宁可多一个常量。
     */
    const val COUNTDOWN_WINDOW_MINUTES = 20

    /**
     * 倒计时刷新链的 requestCode。
     *
     * 落在 START（`0x5700..0x57F9`）与 END（`0x5800..0x58F9`）两段之外。
     *
     * ## 为什么整条链只占这一个编号
     *
     * 一分钟一枚、一节课 21 枚，七天排满会有两千多枚 —— 直接撞上 AOSP 的
     * `max_alarms_per_uid=500`（本机 `dumpsys alarm` 的 `Settings` 里读到过这个值）。
     * 所以这里不做"预排满窗口"，而是**只排下一环**：每次触发时重新算下一个该变的时刻、
     * 用同一个 requestCode 顶掉自己。任一时刻系统闹钟表里最多只有这一枚倒计时闹钟。
     *
     * 链断了会怎样：小部件上的数字停在上一次的取值上不动。这是**纯显示**问题，不影响提醒
     * 本身；而且 [reschedule]（每次打开 App、小部件每 3 小时一次的更新、每天 00:05）都会
     * 重新接上。拿这个降级换掉两千枚闹钟，是划算的。
     */
    private const val REQUEST_COUNTDOWN = 0x5900

    /**
     * 每一类提醒（上课 / 下课）**各自**能注册的枚数上限，同时也是 [cancelAll] 的扫描上界。
     *
     * ## 为什么两个区段必须分开
     *
     * 否则一节课的上课提醒和下课提醒会去抢同一个 requestCode，后者把前者覆盖掉 ——
     * `PendingIntent.getBroadcast` 的 identity 只由 component + action + requestCode 决定，
     * extras 不同不算不同的 PendingIntent。
     *
     * 编号实算：上课 = `0x5700 + 0..249` = `0x5700..0x57F9`，下课 = `0x5800 + 0..249`
     * = `0x5800..0x58F9`，两段互不重叠。跨天/刷新那两枚是 `0x57FF`/`0x57FE`，它们在上课的
     * 区间之外（250 枚才到 0x57F9），安全。
     *
     * ## 为什么它是"上限"同时也是"扫描上界"
     *
     * 这两个用途必须是同一个数，否则会静默丢闹钟：闹钟的 requestCode 是 `区段基址 + index`，
     * 而取消时只能扫 `0 until MAX_REMINDERS_PER_KIND`。若注册能产出更大的 index，那几枚就
     * **永远取消不掉** —— 用户关掉提醒后它们照旧响，反复重排还会在系统闹钟表里堆副本。
     * 索引因此必须是**每类独立**的（见 [registerCourseReminders] 的发射循环），不能用
     * "全量排序后的全局下标"：那样 END 的下标会挤进 START 的区段。
     *
     * 实际需要的上界远小于这个数：本校一天最多 15 节，7 天窗口 = 105 节课 = 210 枚，触不到
     * 250。留着它是为了让"注册量 ≤ 可取消量"成为**结构性**约束，而不是靠"课表不会那么满"
     * 的巧合。AOSP 每个 uid 的同时闹钟上限是 `MAX_ALARMS_PER_UID = 500`；两段加上跨天/
     * 刷新/倒计时三枚固定闹钟是 503，所以 500 这条线真被逼近时先撞上的是它——但按真实
     * 课表的规模，这里到不了那个量级。
     */
    private const val MAX_REMINDERS_PER_KIND = 250

    /**
     * 上课 / 下课两个 requestCode 区段之间的间距：256。
     *
     * `requestCode = REQUEST_BASE + kindOrdinal * REQUEST_KIND_STRIDE + index`，
     * 于是上课落在 `[0x5700, 0x5800)`、下课落在 `[0x5800, 0x5900)`，两段绝不重叠。
     */
    private const val REQUEST_KIND_STRIDE = 0x0100

    /**
     * 滚动窗口长度：今天 + 之后 6 天，共 7 天。
     *
     * 这个窗口是"当天提醒一定会被注册"的唯一保证（见类文档）。改小它 = 把可靠性重新押在
     * 某个每日触发器会不会按时跑上。
     */
    const val WINDOW_DAYS = 7

    /** 跨天闹钟的时刻：每天 00:05。 */
    val NEXT_DAY_HOUR = 0
    val NEXT_DAY_MINUTE = 5

    /** [reschedule] 的互斥锁：该序列必须串行，见 [reschedule] 的注释。 */
    private val rescheduleLock = Any()

    /**
     * 幂等的全量重排。任何触发点（小部件更新、开机、改时间、改时区、改设置、跨天）都调它。
     *
     * 幂等只对**串行**调用成立：冷启动与小部件更新会在 IO 池上真并发地各跑一份
     * 「先全量取消、再全量注册」——交错时序下，一侧可能把闹钟注册到刚被另一侧 cancel()
     * 的死 PendingIntent 上，那枚提醒会静默丢到下一轮触发点。整个序列必须互斥。
     */
    fun reschedule(context: Context): Unit = synchronized(rescheduleLock) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(ALARM_SERVICE) as AlarmManager

        cancelAll(appContext, alarmManager)

        val now = System.currentTimeMillis()
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
        setExact(alarmManager, System.currentTimeMillis() + 1000, refreshTodayPendingIntent(appContext))

        // 下课倒计时只保留下一次该变的那一刻。它与上面两枚互不相干，所以放在最后单独接。
        armNextCountdownRefresh(appContext)

        // 把 API 级别一并打出来：这台设备（Xiaomi/HyperOS，装有 LSPosed）的 `getprop` 报
        // ro.build.version.sdk=21、release=6.0.1，与它真实的框架版本矛盾（而且它连
        // ro.build.fingerprint 都查不到）。而 SDK_INT 决定了下面用 setExactAndAllowWhileIdle
        // 还是降级的 setExact —— 后者在 Doze 下会被推迟，正是本类要根治的那个缺陷。
        // 日志里留一份自证，比事后猜可靠。
        appContext.getPrefer().edit().putLong(KEY_LAST_RESCHEDULE_AT, System.currentTimeMillis()).apply()

        Log.i(TAG, "重排完成：课程提醒 $registered 枚，跨天闹钟 1 枚（${format(nextDayAt)}）" +
                "，SDK_INT=${Build.VERSION.SDK_INT}（release ${Build.VERSION.RELEASE}）" +
                "，精确闹钟=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    "setExactAndAllowWhileIdle" else "setExact"}")
    }

    /** 上次全量重排完成时的墙上时钟，仅用于 [rescheduleIfStale] 的节流判断。 */
    private const val KEY_LAST_RESCHEDULE_AT = "last_reschedule_at"

    /** 「进程又活了」这种触发点在窗口内视为冗余，跳过重排。 */
    private const val COLD_START_STALE_MS = 10 * 60 * 1000L

    /**
     * 给「进程启动」这种触发点用的节流版 [reschedule]。
     *
     * ## 为什么只节流这一条路径
     *
     * 全量重排幂等但不免费：固定扫几百个 requestCode（每个约 3 次 binder）+ 同步读三张表。
     * 本 App 零常驻组件，每枚提醒触发、每次小部件刷新都可能把进程重新拉起来，于是「任何进程
     * 启动都全量重排」一天要被白跑十几次——闹钟注册跟进程生死无关，上次排好的还躺在系统闹钟
     * 表里，而进程死着的这段时间里库不会被别人改。
     *
     * 以下触发点**不走**这里、永远全量：开机/改时间/改时区/覆盖安装（重启后闹钟表是空的，
     * 必须重排）、课程数据变更（watcher）、设置变更、00:05 跨天、3 小时小部件栅格。
     */
    fun rescheduleIfStale(context: Context) {
        val appContext = context.applicationContext
        val last = appContext.getPrefer().getLong(KEY_LAST_RESCHEDULE_AT, 0L)
        if (System.currentTimeMillis() - last < COLD_START_STALE_MS) {
            Log.i(TAG, "距上次全量重排不足 10 分钟，跳过本次进程启动触发的重排")
            return
        }
        reschedule(appContext)
    }

    /**
     * 取消本模块注册过的所有闹钟。
     *
     * ## 为什么这里**不用** `FLAG_NO_CREATE`（真机崩溃的根因）
     *
     * `FLAG_NO_CREATE` 的语义是"不存在就返回 null"，于是三个 PendingIntent 工厂都被要求返回
     * 可空类型。它们被声明成了非空 `PendingIntent`，Kotlin 就在每次调用后插入一个 null 检查
     * —— 而全新安装时 `0..63` 里第一个码就不存在，`getBroadcast` 返回 null，直接抛
     * NullPointerException。`reschedule` 于是**每次调用都崩在第一轮取消上**，提醒从来没有被
     * 注册成功过（真机栈：`getBroadcast(...) must not be null` ← `remindPendingIntent`
     * ← `cancelAll` ← `reschedule`）。单元测试碰不到它，因为这条路径需要真的 Context 与
     * AlarmManager。
     *
     * 取消一个闹钟并不要求它先存在：PendingIntent 的身份是 component + action + requestCode，
     * 用普通 flags 取到（或按需建出）同一个身份再取消即可，本来就空的那次是一条无害的空操作，
     * 且 `cancel()` 之后不留残留。这样"可能为 null"这条路径整体消失，工厂函数声明非空才是真的
     * —— 修在类型契约层，而不是给返回类型补一个 `?` 让每个调用点都去处理 null。
     */
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
    }

    /**
     * 取默认课表；没有默认课表时返回 null。
     *
     * `TableDao.getDefaultTableSync()` 现在如实返回 `TableBean?`（SQL 是 `where type = 1`，
     * 用户把课表删光之后一行都没有），所以这里只是把"课表可能不存在"这件事显式接住，
     * 不需要再兜 NPE —— 以前类型说谎时，`try { ... } catch (NullPointerException)` 是接不住的，
     * 因为非空断言在**使用点**才炸，异常抛在 try 之外。
     */
    private fun defaultTableOrNull(context: Context): TableBean? =
            AppDatabase.getDatabase(context).tableDao().getDefaultTableSync()

    /**
     * 只重排"倒计时刷新链"的下一环，不动其它任何闹钟。幂等，可随时调用。
     *
     * 三个调用点，缺一不可：
     * - [reschedule]：打开 App、小部件每 3 小时的更新、每天 00:05 都会走这里 —— 链断了靠它接回；
     * - [TodayCourseAppWidget] 处理 `ACTION_REFRESH_TODAY` 时：**链的下一环**就是在那里接上的，
     *   每一环刷新完小部件之后立刻安排下一环；
     * - 每一环触发时先取消自己那一位（[setExactDisplay] 覆盖同一 requestCode 即可），所以不必额外清理。
     *
     * ## 没有日视图小部件就完全不排
     *
     * 倒计时是**给桌面看的**，桌面上没有这个小部件时，为一串没人看的数字每天唤醒上百次毫无意义。
     * 这不构成"提醒依赖小部件"：提醒的注册完全不看这里（见类文档），本函数只影响倒计时本身。
     */
    fun armNextCountdownRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(ALARM_SERVICE) as AlarmManager
        // 先做这条**不碰数据库**的判定，再谈其它：它是"这个功能此刻有没有意义"的开关，
        // 而后面每一步都要开库读数。把它放在最前面，桌面没有日视图小部件时这里一次 I/O 都不做。
        val hasWidget = AppWidgetManager.getInstance(appContext)
                .getAppWidgetIds(ComponentName(appContext, TodayCourseAppWidget::class.java))
                .isNotEmpty()
        val next = if (hasWidget) nextCountdownInstant(appContext) else null
        if (next == null) {
            // 今天已经没有该变的时刻（或根本没有小部件）：取消原来那一枚，否则它会带着
            // 旧时刻继续留在系统闹钟表里。
            cancelAlarm(alarmManager, countdownPendingIntent(appContext))
            return
        }
        setExactDisplay(alarmManager, next, countdownPendingIntent(appContext))
    }

    /** 下一个"小部件上的倒计时数字该变了"的绝对毫秒；今天已经没有该变的时刻时返回 null。 */
    private fun nextCountdownInstant(context: Context): Long? {
        val dataBase = AppDatabase.getDatabase(context)
        val table = defaultTableOrNull(context) ?: return null
        val day = try {
            dayOf(table.startDate, table.sundayFirst, System.currentTimeMillis())
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
        val now = System.currentTimeMillis()
        return countdownInstants(courses, times, startOfDayMillis(now), now).firstOrNull()
    }

    /**
     * 某一枚提醒的 requestCode。
     *
     * 上课与下课分处两个区段，所以"取消干净"必须两种都扫一遍 —— 只扫一种会漏掉另一半，
     * 表现为"关掉提醒后下课通知还在响"。
     */
    private fun requestCodeOf(kind: ReminderKind, index: Int): Int =
            REQUEST_BASE + kind.ordinal * REQUEST_KIND_STRIDE + index

    /**
     * 取消一枚闹钟并释放它的 PendingIntent。
     *
     * 两件事都要做：`alarmManager.cancel` 摘掉闹钟表的记录，`pendingIntent.cancel` 释放
     * PendingIntent 本身。目标不存在时两者都是空操作。
     */
    private fun cancelAlarm(alarmManager: AlarmManager, pendingIntent: PendingIntent) {
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /**
     * 排一枚**精确**闹钟。本类排出的每一枚闹钟都必须走这里，任何一处退回 `AlarmManager.set` 都是缺陷。
     *
     * ## 为什么"精确"不是优化，而是能不能响的前提（真机实证）
     *
     * 这台设备（HyperOS 3.0 真机，2026-09-15 采集）的 `dumpsys alarm` 里，每条闹钟
     * 都多打印一行 MIUI 私有的 `policyWhenElapsed`，其中 `power_pending` 是本类闹钟被推迟的元凶。
     * 最终 `whenElapsed` 取各策略的最大值，所以只要 `power_pending` 更大，原定时刻就被丢掉：
     *
     * ```
     * RTC_WAKEUP ... courseclock.timetable
     *   tag=*walarm*:WAKEUP_BACK_TIME
     *   type=RTC_WAKEUP origWhen=2026-09-16 00:05:00.000 window=+1h0m0s0ms
     *   policyWhenElapsed: requester=+4h56m43s956ms ... power_pending=+3d4h56m43s956ms
     *   whenElapsed=+3d4h56m43s47ms maxWhenElapsed=+3d4h56m43s47ms      ← 本该 4h56m 后触发
     * ```
     *
     * 规律很干净：**非精确闹钟（`window > 0`）一律被改成 `requester + 3 天`；精确闹钟
     * （`window = 0`）的 `power_pending` 恒为 `--`，`whenElapsed` 与 `requester` 一模一样。**
     * 把 18 条被推迟的与全部 `window=0` 的逐条比过：后者包含第三方 App 的精确闹钟
     * （`com.tencent.mm` 的 900 秒重复闹钟、`com.tencent.mobileqq` 的 MSF 闹钟），
     * 也就是说这条豁免与"是不是系统应用"无关，我们自己的精确闹钟同样在上面。
     *
     * 旁证：`WAKEUP_REFRESH_TODAY` 用 `set()` 排的 `now + 1000`，`dumpsys alarm` 的
     * `Alarm manager stats` 显示它实际在 `last -1h6m58s`（18:01:12）才跑，比设定时刻晚了
     * 5 小时 42 分。这不是抖动，是策略。
     *
     * 于是"用非精确闹钟省一次唤醒"在本设备上是自欺：省下的不是电，是这条提醒本身。
     *
     * ## 为什么不用 `setAlarmClock`
     *
     * 它会在状态栏常驻一个"下一个闹钟"图标、并进系统的闹钟列表，用户要的是**通知**而不是
     * 日程/闹钟；官方文档也明确说它 extremely expensive on battery use。本 App 一条都不用。
     *
     * ## API 分支
     *
     * `setExactAndAllowWhileIdle` 从 API 23 起存在，minSdk 21 上还剩 21~22 这一段，
     * 那些版本只有 `setExact`（Doze 下会被推迟，但那些版本本来也没有 Doze）。
     */
    private fun setExact(alarmManager: AlarmManager, triggerAt: Long, pendingIntent: PendingIntent) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            else ->
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    /**
     * 排一枚**精确但不豁免 Doze** 的闹钟：只给「倒计时刷新链」用。
     *
     * 与 [setExact] 的唯一差别是不带 AllowWhileIdle：设备进 Doze（灭屏静置）时这一枚会被
     * 推迟到维护窗口或亮屏，不强制唤醒。倒计时数字只在屏幕亮着时有人看，灭屏时保持它的
     * 分钟级精度等于花电买没人消费的准时；亮屏/使用中设备不在 Doze，`setExact` 照样准点，
     * 数字跳动的观感不变。
     *
     * 仍然是**精确**闹钟（window=0）：MIUI `power_pending` 只改写非精确闹钟（见 [setExact]
     * 的 dumpsys 实证），这里不会像 `set()` 那样被推 3 天——灭屏期间最多推迟到下一个
     * 维护窗口，亮屏瞬间补发。
     */
    private fun setExactDisplay(alarmManager: AlarmManager, triggerAt: Long, pendingIntent: PendingIntent) {
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    /**
     * 排未来 [WINDOW_DAYS] 天的上课提醒，返回实际排出的枚数。
     *
     * 逐天查课、逐天算周次：不能只查一次"今天"再把结果平移，因为跨周之后单双周（type）会翻转，
     * 同一门课在窗口里可能只出现一半的天数。
     */
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
        val startEnabled = prefer.getBoolean(KEY_REMINDER_START_ENABLED, DEFAULT_REMINDER_KIND_ENABLED)
        val endEnabled = prefer.getBoolean(KEY_REMINDER_END_ENABLED, DEFAULT_REMINDER_KIND_ENABLED)
        val mergeEnabled = prefer.getBoolean(KEY_REMINDER_MERGE_ENABLED, DEFAULT_MERGE_ENABLED)
        if (!startEnabled && !endEnabled) {
            Log.i(TAG, "上课与下课提醒都关着，本次只排跨天闹钟")
            return 0
        }
        val todayStart = startOfDayMillis(now)

        val alarms = ArrayList<ReminderAlarm>()
        for (dayOffset in 0 until WINDOW_DAYS) {
            val dayStart = startOfDayAfter(todayStart, dayOffset)
            val day = try {
                dayOf(table.startDate, table.sundayFirst, dayStart)
            } catch (e: ParseException) {
                // 开学日期不是 yyyy-MM-dd 时查不出课，但**不能**因此连跨天闹钟都不排：
                // 那会让 App 永远停在坏状态里。排下跨天闹钟，下次再试。
                Log.w(TAG, "开学日期无法解析，本次只排跨天闹钟", e)
                return 0
            }
            if (day.week < 0) {
                continue
            }

            // 免听课不排提醒：用户明确不去听，响了只会是噪音。
            val courses = courseDao.getCourseByDayOfTableSync(
                    day.weekday, day.week, day.type, table.id)
                    .filter { !it.notAttend }
            // 连堂判定与"抑制谁"都在 alarmsForDay 内部完成：抑制某节课的上课提醒，前提是承载
            // "下一节 …" 的那枚下课提醒真的排得出来，而那取决于下课开关与下课提醒有没有过点，
            // 调用方在这里看不到。把两件事拆开过一次，代价是丢提醒（见 alarmsForDay 的注释）。
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
            val payload = ReminderPayload(
                    // 通知 id 与取消广播的 requestCode 都用闹钟自己的 requestCode：它同时唯一标识
                    // (类别, 序号) 两个维度，也随闹钟一起冻结。用"注册那一刻的排序下标"的话，
                    // 任何一次重排都会让同一节课换一个 id，旧通知取消不掉、堆在通知栏里。
                    index = requestCode,
                    kind = alarm.kind,
                    // 剩余分钟要在通知弹出那一刻用 target − now 现算，所以必须把目标时刻带过去。
                    // 传的是时刻而不是分钟数：闹钟被系统推迟之后，分钟数会变成一句假话。
                    targetAt = alarm.targetAt,
                    // CourseBean 的这两个字段是可空的。原先它们靠 `putExtra(String, Serializable?)`
                    // 这个重载把 null 塞进 Intent（编译期看不出来），现在收进有类型的 [CourseDetail]，
                    // 就必须在这里显式归一成空串 —— 与 [payloadOf] 读出来时的 `.orEmpty()` 对称。
                    course = CourseDetail(alarm.course.courseName.orEmpty(), alarm.course.room.orEmpty()),
                    // 连堂合并时本节下课提醒要写的"下一节"。没有下一节时是 null。
                    next = alarm.nextCourse?.let {
                        CourseDetail(it.courseName.orEmpty(), it.room.orEmpty())
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

    /**
     * 本模块三个 PendingIntent 的唯一造法，一律"取或建"，**永不返回 null**。
     *
     * 不要给它们加 `FLAG_NO_CREATE`：那个标志会让"不存在"变成 null，于是返回类型必须可空，
     * 而取消路径（[cancelAll]）在全新安装上必然遇到不存在的情况 —— 曾因此每次重排都抛
     * "getBroadcast(...) must not be null"。要精确控制唯一性就靠 requestCode 分段，
     * 不要靠"查不到就算了"。
     */
    private fun nextDayPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_NEXT_DAY,
                    Intent(context, TodayCourseAppWidget::class.java).apply { action = ACTION_ROLLOVER },
                    pendingIntentFlags())

    private fun refreshTodayPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_REFRESH_TODAY,
                    Intent(context, TodayCourseAppWidget::class.java).apply { action = ACTION_REFRESH_TODAY },
                    pendingIntentFlags())

    /**
     * 倒计时刷新链的那一枚闹钟。
     *
     * 复用 `ACTION_REFRESH_TODAY`：这一条 action 的语义本来就是"把日视图重新画一遍"，
     * 而倒计时要做的正好就是这一件事 —— 区别只在于是谁安排的、安排了几枚。接收方
     * [TodayCourseAppWidget] 在刷完之后会接上下一环（见 `armNextCountdownRefresh` 的文档）。
     *
     * 与 [refreshTodayPendingIntent] 共用 component + action，靠 requestCode 区分身份：
     * `PendingIntent` 的 identity 是 component + action + requestCode，`0x5900` 与 `0x57FE`
     * 是两个不同的 PendingIntent，各自独立取消，不会互相覆盖。
     */
    private fun countdownPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_COUNTDOWN,
                    Intent(context, TodayCourseAppWidget::class.java).apply { action = ACTION_REFRESH_TODAY },
                    pendingIntentFlags())

    private fun remindPendingIntent(context: Context, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(context, requestCode,
                    Intent(context, CourseReminderReceiver::class.java).apply { action = ACTION_REMIND_COURSE },
                    pendingIntentFlags())

    // ---- 纯计算：不碰 Context、不读系统时钟，[now] 一律由调用方传入，单测直接断言这些函数 ----

    /** 窗口内某一节课的一枚提醒：时刻 + 类型 + 要发给通知的全部内容。 */
    data class ReminderAlarm(            val triggerAt: Long,
            val course: CourseBean,
            val weekdayName: String,
            val kind: ReminderKind,
            /** 该枚提醒对应的时间点（上课枚 = 开始时刻，下课枚 = 结束时刻），用于通知文案。 */
            val time: String,
            /**
             * 该枚提醒的目标时刻（上课枚 = 上课时刻，下课枚 = 下课时刻）的绝对毫秒。
             *
             * 带进 intent 是**硬要求**：剩余分钟必须由"目标时刻 − 通知弹出那一刻"现算，
             * 不能回放设置里的分钟数，否则闹钟被系统推迟后通知就在说谎。
             */
            val targetAt: Long = 0L,
            /** 连堂合并时，本节的下课提醒要写出的"下一节"。 */
            val nextCourse: CourseBean? = null
    )

    /** 某一天在学期里的位置：星期几、第几周、以及该周对应的单双周 type。 */
    data class CourseDayInWeek(val weekday: Int, val week: Int, val type: Int, val weekdayName: String)

    /**
     * 一枚提醒在广播里携带的全部内容，以及它唯一的序列化入口 [toIntent]。
     *
     * 存在的意义是**把线格式收在同一个类里**：发送方（[registerCourseReminders]）只构造它，
     * 接收方（[CourseReminderReceiver]）只通过 [payloadOf] 还原它，两侧都不碰 `EXTRA_*`
     * 字面量。于是"键改名"这件事只有一个地方会出错，而且编译器会当场报出来。
     *
     * 刻意不含课名以外的时间文本：通知要说的是"还有几分钟"，那必须由
     * [remainingMinutes] 在弹出那一刻现算。
     */
    data class ReminderPayload(
            val index: Int,
            val kind: ReminderKind,
            val targetAt: Long,
            val course: CourseDetail,
            val next: CourseDetail?
    ) {
        /**
         * 序列化成广播 Intent。
         *
         * 收件方是 [CourseReminderReceiver]，**不是** `TodayCourseAppWidget` —— 提醒与小部件
         * 之间没有依赖，关掉小部件不该让提醒一起消失。
         */
        fun toIntent(context: Context): Intent =
                Intent(context, CourseReminderReceiver::class.java).apply {
                    action = ACTION_REMIND_COURSE
                    putExtra(EXTRA_INDEX, index)
                    putExtra(EXTRA_KIND, kind.name)
                    putExtra(EXTRA_TARGET_AT, targetAt)
                    putExtra(EXTRA_COURSE_NAME, course.name)
                    putExtra(EXTRA_ROOM, course.room)
                    next?.let {
                        putExtra(EXTRA_NEXT_COURSE_NAME, it.name)
                        putExtra(EXTRA_NEXT_ROOM, it.room)
                    }
                }
    }

    /** 课程的展示片段：课名与教室。两者都可能为空串，拼接时要能退化成只剩一个。 */
    data class CourseDetail(val name: String, val room: String)

    /** 纯文字的展示内容：标题、正文、以及正文里"下一节"那一段（没有则为空串）。 */
    data class NotificationText(val title: String, val body: String, val next: String = "")

    /**
     * 今天所有"小部件上的倒计时数字该变了"的时刻，升序，已过去的不含。
     *
     * 对每节课，候选是 `结束时刻 − k 分钟`，k 取 0 到 [COUNTDOWN_WINDOW_MINUTES]：
     *
     * - `k = COUNTDOWN_WINDOW_MINUTES` 是**窗口打开**的那一刻（数字第一次出现，显示窗口长度）；
     * - 中间的每个 k 是一个分钟边界（数字减一）；
     * - `k = 0` 是**下课时刻**，那一下把它抹掉（剩余 0 分钟就不该再写"还有 0 分钟下课"）。
     *
     * 两节课同一分钟下课只需要醒一次，所以结果**去重**（用有序集合，同时保证升序）。
     *
     * 时刻一律走 [CourseTimes] 的分组感知取法（`endOfNode(startNode + step - 1, timeGroup)`）：
     * 本校上午第 3~5 节按楼宇错峰，(节次, 分组) 才唯一决定时刻，不能按 node 索引自己算。
     */
    fun countdownInstants(
            courses: List<CourseBean>,
            times: CourseTimes,
            dayStart: Long,
            now: Long
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
        }
        return instants.toList()
    }

    /**
     * 这节课现在该在小部件上写"还有几分钟下课"；不该写时返回 null。
     *
     * 边界是刻意的：剩余 0 分钟（正好下课）与超过窗口的都不写 —— 前者写出来是"还有 0 分钟
     * 下课"这种废话，后者是用户没要求的信息。窗口内用 [remainingMinutes] 向上取整，
     * 与通知文案共用同一套取整规则，两处不会给出互相矛盾的分钟数。
     *
     * [endTime] 是「HH:mm」，[now] 由调用方传入（展示侧传 `System.currentTimeMillis()`），
     * 所以这个函数本身可以单测。
     */
    fun countdownMinutesLeft(endTime: String, now: Long): Long? {
        val endAt = reminderFreeze(endTime, startOfDayMillis(now), 0) ?: return null
        val remaining = remainingMinutes(endAt, now)
        return if (remaining in 1..COUNTDOWN_WINDOW_MINUTES.toLong()) remaining else null
    }

    /**
     * 「还有 N 分钟下课」这一句。
     *
     * 小部件那一行和下课通知的标题是**同一句话**：用户可能同时看着桌面小部件和通知栏，
     * 两处只要有一个字或一个数字不一样，就会开始怀疑哪个是真的。抽成一个函数成本为零，
     * 换来的是以后改文案只改一处、且由单测钉住两处一致。
     */
    fun countdownText(minutesLeft: Long): String = "还有 $minutesLeft 分钟下课"

    /**
     * 距目标时刻还剩几分钟，**向上取整**。
     *
     * 向上取整是刻意的：`setExactAndAllowWhileIdle` 仍可能被系统推迟几秒到几十秒，
     * 用 `delta / 60000` 地板除会把"还剩 4 分 01 秒"显示成"还有 4 分钟"，用户按这个数字
     * 出门就会迟到；向上取整让 4 分 01 秒显示"还有 5 分钟"，只有真到 4 分整才降一档。
     *
     * **必须用 `Math.floorDiv`，不能写 `-((-delta) / 60000)`**：Kotlin/Java 的 `/` 对负数是
     * 向零截断而不是向下取整，过点方向上会差一格（4 分 01 秒会算成 4 而不是 5）——
     * 这正是单测抓出来的那个错。
     *
     * 返回值语义：`delta >= 0` → ≥ 0（0 表示不足一分钟）；`delta < 0` → ≤ 0（不足一分钟的
     * 过点也返回 0，调用方一律按"≤ 0 即已到时间"处理文案）。
     */
    fun remainingMinutes(targetMillis: Long, nowMillis: Long): Long =
            Math.floorDiv(targetMillis - nowMillis + 59_999L, 60_000L)

    /**
     * 通知的标题与正文。     *
     * [remainingMinutes] 必须是**触发那一刻现算**出来的，不能回放设置里的分钟数 ——
     * 闹钟被推迟之后回放就等于说谎。正文里的课名/教室、以及"下一节"的课名/教室都由
     * 调用方从 intent 里带过来，这个函数只负责选词与拼接，不读任何全局状态。
     *
     * 拼接规则：课名与教室用 " · " 相连，**空的那一侧直接省略**，绝不留下孤零零的分隔符
     * （用户要求字少）。课名过长不做手工截断，交给 `setContentText` 单行 ellipsize。
     */
    fun notificationText(
            kind: ReminderKind,
            remainingMinutes: Long,
            course: CourseDetail,
            next: CourseDetail?
    ): NotificationText {
        val title = when (kind) {
            ReminderKind.START ->
                if (remainingMinutes >= 1) "还有 $remainingMinutes 分钟上课" else "已到上课时间"
            ReminderKind.END ->
                if (remainingMinutes >= 1) countdownText(remainingMinutes) else "下课时间到"
        }
        // 下课提醒不合并时正文留空：用户只要"还剩几分钟下课"这一个信息。
        val body = when (kind) {
            ReminderKind.START -> join(course)
            ReminderKind.END -> if (next == null) "" else join(next).takeIf { it.isNotEmpty() }
                    ?.let { "下一节 $it" } ?: ""
        }
        return NotificationText(title, body, if (kind == ReminderKind.END) body else "")
    }

    /** 课名与教室拼成一段文字，空的一侧省略；两侧都空则返回空串。 */
    fun join(detail: CourseDetail): String = when {
        detail.name.isNotEmpty() && detail.room.isNotEmpty() -> "${detail.name} · ${detail.room}"
        detail.name.isNotEmpty() -> detail.name
        else -> detail.room
    }

    /**
     * [course] 后面**紧挨着**的下一节课；不存在或不构成连堂时返回 null。
     *
     * 判定规则（缺一不可）：
     * - 同一天（本函数只接收同一天的候选，跨天由调用方按天分组天然排除 ——
     *   一天的最后一节与次日第一节**不算连堂**）；
     * - **不是同一门课**：`courseName` 相等即视为同一门课，不合并。用户在课表上看到的是
     *   "同一门课连上两节"，合并提醒只会说一句废话；只有"这门课接着那门课"才值得并成一条。
     * - `B 开始 > A 结束`（不重叠；同时刻视为重叠，不合并）；
     * - 空档 `B 开始 − A 结束 ≤ [maxGapMinutes]`。
     *
     * 时刻一律走 [CourseTimes] 的**分组感知**取法，不按节次索引自己算。
     *
     * 多个候选时**先剔除课名与 A 相同的**，再取开始时刻最早者；若仍并列，取 `startNode`
     * 最小的，保证同一份课表每次得到同一个结果（不引入随机性）。剔除后为空则不合并。
     */
    fun adjacentAfter(
            course: CourseBean,
            sameDayCourses: List<CourseBean>,
            times: CourseTimes,
            maxGapMinutes: Int
    ): CourseBean? {
        val endOfA = minutesOfDay(times.endOfNode(course.startNode + course.step - 1, course.timeGroup))
                ?: return null
        var best: CourseBean? = null
        var bestStart = 0
        for (candidate in sameDayCourses) {
            if (candidate === course) {
                continue
            }
            // 同一门课不算连堂：课名相等即同一门课。
            if (candidate.courseName == course.courseName) {
                continue
            }
            val startOfB = minutesOfDay(times.startOfNode(candidate.startNode, candidate.timeGroup))
                    ?: continue
            if (startOfB <= endOfA || startOfB - endOfA > maxGapMinutes) {
                continue
            }
            val current = best
            if (current == null ||
                    startOfB < bestStart ||
                    (startOfB == bestStart && candidate.startNode < current.startNode)) {
                best = candidate
                bestStart = startOfB
            }
        }
        return best
    }

    /**
     * 这一天每一节的"下一节"，按课程顺序与之对应。
     *
     * 是 [adjacentAfter] 的批量入口，单测与生产都走它，避免两处各写一遍候选筛选。
     */
    fun adjacencyOf(
            courses: List<CourseBean>,
            times: CourseTimes,
            maxGapMinutes: Int
    ): Map<CourseBean, CourseBean?> = courses.associateWith { adjacentAfter(it, courses, times, maxGapMinutes) }

    /**
     * 一整天该排的提醒：[courses] 里每节课的上课/下课提醒，已按开关过滤、并按连堂规则抑制掉
     * 该抑制的上课提醒。**连堂判定的单位是一天**，所以入口按天而不是按课。
     *
     * 抑制是**根本不生成那一枚**（不注册闹钟），不是"注册了再判断不通知" —— 后者会白占一次
     * 精确唤醒，违背省电的初衷。
     *
     * ## 为什么抑制必须在这里算，而不是由调用方算好传进来
     *
     * 抑制某节课的上课提醒，唯一的依据是"上一节的下课提醒会替它说『下一节 …』"。这个前提要
     * 成立，那枚下课提醒必须**真的排出来了**，而这取决于两件调用方在当天循环里看不到的事：
     * 下课提醒开关是否打开，以及那枚下课提醒是不是已经过点了（[addIfPending] 会跳过它）。
     *
     * 早先的版本把抑制集合交给调用方预先算好（`adjacency.values.filterNotNull().toSet()`），
     * 于是有两条真会丢提醒的路径：
     *
     * 1. **下课提醒关掉、合并开着**：被抑制的那节课两头都没人提醒 —— 上一节的下课提醒不存在，
     *    自己的上课提醒又被抑制了。这是用户按你的要求把两个开关分开之后立刻能摆出来的状态。
     * 2. **下课提前量 > 上课提前量**：上一节的下课提醒已过点（不排），而下一节的上课提醒还没
     *    到点（本该排却被抑制）。例：下课前 10 分钟提醒、上课前 0 分钟提醒，08:00-08:45 之后
     *    08:50 接一节，08:36 时前者已过、后者未到。
     *
     * 现在改成两遍扫描：**先排下课提醒并记下真正排出来的那些课**，再据此决定抑制谁。抑制与
     * 承载它的那条通知于是在同一个函数里绑定，调用方无从把它们拆开。
     *
     * @param startEnabled 上课提醒开关；false 时 START 一枚都不生成
     * @param endEnabled 下课提醒开关；false 时 END 一枚都不生成，也就没有任何合并
     * @param mergeEnabled 合并开关；false 时连堂判定整体不参与
     * @param maxGapMinutes 构成连堂的最大空档分钟数
     */
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
            mergeEnabled: Boolean = true,
            maxGapMinutes: Int = ADJACENT_BREAK_MAX_MINUTES
    ): List<ReminderAlarm> {
        val result = ArrayList<ReminderAlarm>(courses.size * 2)
        // 三个开关缺一不可：没有下课提醒就没有承载"下一节"的那一条；没有上课提醒就没有可抑制
        // 的对象；合并关掉时连堂判定整体不参与。任缺其一，adjacency 一律为空 —— 不抑制任何人。
        val adjacency = if (mergeEnabled && startEnabled && endEnabled) {
            adjacencyOf(courses, times, maxGapMinutes)
        } else {
            emptyMap()
        }

        // 第一遍：只排下课提醒，并记下**确实排出来**的那些课。
        val carried = LinkedHashSet<CourseBean>()
        if (endEnabled) {
            for (course in courses) {
                val endTime = times.endOfNode(course.startNode + course.step - 1, course.timeGroup)
                if (addIfPending(result, course, day, dayStart, now, ReminderKind.END, endTime,
                                beforeEndMinutes, adjacency[course])) {
                    carried.add(course)
                }
            }
        }

        // 第二遍：排上课提醒。只有"上一节的下课提醒真的排出来了"的那节课才抑制本节的。
        if (startEnabled) {
            val suppressed = carried.mapNotNull { adjacency[it] }.toSet()
            for (course in courses) {
                if (course in suppressed) {
                    continue
                }
                val startTime = times.startOfNode(course.startNode, course.timeGroup)
                addIfPending(result, course, day, dayStart, now, ReminderKind.START, startTime,
                        beforeStartMinutes)
            }
        }
        return result
    }

    /**
     * 排一枚提醒，返回**是否真的排出来了**。
     *
     * 返回值不是装饰：调用方要靠它区分"这枚提醒会响"和"这枚提醒已过点/时刻格式不对"，进而
     * 决定能不能把"下一节 …"托付给它（见 [alarmsForDay]）。时刻已过点或格式非法都返回 false。
     */
    private fun addIfPending(
            into: MutableList<ReminderAlarm>,
            course: CourseBean,
            day: CourseDayInWeek,
            dayStart: Long,
            now: Long,
            kind: ReminderKind,
            time: String,
            beforeMinutes: Int,
            nextCourse: CourseBean? = null
    ): Boolean {
        val targetAt = reminderFreeze(time, dayStart, 0) ?: return false
        val triggerAt = reminderFreeze(time, dayStart, beforeMinutes) ?: return false
        if (triggerAt <= now) {
            return false
        }
        into.add(ReminderAlarm(triggerAt, course, day.weekdayName, kind, time, targetAt, nextCourse))
        return true
    }

    /**
     * 某个时间点在 [dayStart] 那一天的提醒时刻毫秒数。
     *
     * 名字里的 Freeze 是"冻结成绝对时刻"：把"HH:mm − 提前 N 分钟"这个相对量落到具体某一天的
     * 绝对毫秒上。已过点与否**不在这里判断** —— 那是调用方用 `now` 做的决定，这个函数只负责换算。
     */
    fun reminderFreeze(time: String, dayStart: Long, beforeMinutes: Int): Long? {
        val minutes = minutesOfDay(time) ?: return null
        return dayStart + (minutes - beforeMinutes) * 60_000L
    }

    /**
     * 算出 [basisMillis] 所在那一天在学期里的位置。
     *
     * 星期、周次、单双周 type 三者的算法必须与界面和其它查询完全一致，所以这里**复用**
     * [CourseUtils.getWeekdayIntAt] / [CourseUtils.countWeek]，只有那个 `type` 的取法是从
     * `TodayCourseAppWidget` 原来的查询里搬过来的：`type = if (week % 2 == 0) 2 else 1`，
     * 单周取 1、双周取 2，配合 `(type = 0 or type = :type)` 的 SQL 表达"每周都上 + 对应单双周"。
     */
    @Throws(ParseException::class)
    fun dayOf(startDate: String, sundayFirst: Boolean, basisMillis: Long): CourseDayInWeek {
        val week = CourseUtils.countWeek(startDate, sundayFirst, basisMillis)
        val weekday = CourseUtils.getWeekdayIntAt(basisMillis)
        return CourseDayInWeek(weekday, week, if (week % 2 == 0) 2 else 1, CourseUtils.getDayStr(weekday))
    }

    /**
     * 下一个「每天 [hour]:[minute]」的绝对毫秒数。
     *
     * 必须用 `Calendar.add(DAY_OF_YEAR, 1)` 而不是 `+ 86400000`：后者在夏令时切换日会偏一小时，
     * 也会在闰秒/时区规则变化时错位。
     */
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

    /**
     * 「HH:mm」里的当日第几分钟；格式不对返回 null，绝不抛异常。
     *
     * 自己解析而不调 `CourseUtils.calAfterTime`：后者的跨天分支会把 23:50 提前 20 分钟这种
     * 输入静默改成 00:00，那是个会产出错误提醒时刻的坑，不该带进新代码。
     */
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

    /** 当日第几分钟还原成「HH:mm」。 */
    fun clockOf(minutes: Int): String =
            String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    /**
     * 这节课该不该排：只有"提醒时刻严格晚于此刻"才排。
     *
     * 正好等于触发时刻算已经到点了 —— 排一个"现在触发"的精确闹钟没有意义，而且
     * `setExactAndAllowWhileIdle` 接受过去的时刻会立刻触发，那不是用户要的语义。
     */
    fun shouldSchedule(time: String, now: Long, beforeMinutes: Int): Boolean =
            reminderFreeze(time, startOfDayMillis(now), beforeMinutes)?.let { it > now } ?: false

    /**
     * 同一种提醒（上课或下课）的一组时间点，换算成"尚未到点"的提醒时刻，升序。
     *
     * 「已过点的不排」这条规则只在这一处实现，生产代码与单测都调它，避免两边各写一遍
     * 过滤条件而慢慢长歪。
     */
    fun reminderMillisFor(times: List<String>, now: Long, beforeMinutes: Int): List<Long> {
        val dayStart = startOfDayMillis(now)
        return times.mapNotNull { reminderFreeze(it, dayStart, beforeMinutes) }
                .filter { it > now }
                .sorted()
    }

    /** [now] 当天的 00:00:00.000。用 Calendar 而非取模，夏令时切换日的一天不是 86400000 毫秒。 */
    fun startOfDayMillis(now: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * 从 [basisMillis] 起往后推 [days] 天的当天零点。
     *
     * 用 `Calendar.add(DAY_OF_YEAR, days)` 而不是 `+ days * 86400000`：夏令时切换日的一天
     * 不是 86400000 毫秒，用固定毫秒推进会把"第 N 天"算到前一天的 23:00 去。
     */
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

    /** [millis] 所在日期的星期编号：周一 = 1 … 周日 = 7。 */
    fun weekdayOf(millis: Long): Int = CourseUtils.getWeekdayIntAt(millis)

    /** 调试/自检用：把毫秒数转成可读时间，避免日志里只有一串数字。 */
    fun format(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return format.format(millis)
    }
}
