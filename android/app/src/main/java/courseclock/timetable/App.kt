package courseclock.timetable

import android.annotation.TargetApi
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import courseclock.timetable.utils.BackgroundImageLoader
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseReminderNotifier
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.TimetableChangeWatcher
import courseclock.timetable.utils.UpdateUtils
import courseclock.timetable.utils.appScope
import courseclock.timetable.utils.getPrefer
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.launch

class App : Application() {

    /**
     * 课表变更观察者。
     *
     * 必须由这里强引用着：[androidx.room.InvalidationTracker] 对观察者是弱引用，没人引用
     * 就会被 GC 掉，表现为"观察者装上了却永远不触发"，而且是静默失败。
     *
     * 放在 `onCreate` 而不是某个 Activity 里：任何组件（小部件点击、开机广播）拉起进程时都会
     * 先跑这里，于是"课表一变就重排提醒"这条不变式覆盖到全部入口，而不是只有用户从桌面点开
     * App 的那条路径。
     */
    private var timetableWatcher: TimetableChangeWatcher? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        appScope.launch {
            try {
                courseclock.timetable.utils.AppWidgetUtils.refreshAllWidgets(this@App)
            } catch (e: Exception) {
                android.util.Log.w("WidgetTheme", "Unable to refresh widgets after configuration change", e)
            }
        }
    }

    /**
     * 系统报内存吃紧（或界面已不可见）时交出背景图缓存。
     *
     * 规则就是**任何一档都清**，刻意不去分辨档位：这份缓存是纯装饰的 ——
     * [BackgroundImageLoader] 存它只图"再滑回来时不用重新解码"，全丢掉最多让下一次显示多花
     * 几十毫秒，**不会让任何画面变空**（正在显示的那几张已经被各自 View 的 Drawable 持有）。
     * 反过来，为了省这一次重解码去跟系统讨价还价并不划算：缓存上限只有几 MB，而
     * `onTrimMemory` 的档位常量**不是单调线性的一把尺** —— `TRIM_MEMORY_UI_HIDDEN` 是 20，
     * 比 `TRIM_MEMORY_BACKGROUND` 的 40 还小，所以 `level >= 某个值` 这种写法会悄悄表达错
     * 语义（lint 的 `SwitchIntDef` 也正是靠这一点抓出了逐档列举必然漏项）。这里本来就不需要
     * 区分档位，最简规则才是不容易写错的那一个。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BackgroundImageLoader.clearMemory()
    }

    override fun onCreate() {
        super.onCreate()
        courseclock.timetable.utils.CourseClock.initialize(this)
        courseclock.timetable.utils.CourseNotificationSettings.initialize(this)
        Toasty.Config.getInstance()
                .setToastTypeface(Typeface.DEFAULT_BOLD)
                .setTextSize(12)
                .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(this, CourseReminderNotifier.CHANNEL_ID, "课程提醒",
                    NotificationManager.IMPORTANCE_HIGH, showBadge = true)
            // 状态与一次性提醒分渠道；状态更新不发声音、振动、横幅或角标。
            createNotificationChannel(this, CourseReminderNotifier.ONGOING_CHANNEL_ID, "课程状态",
                    NotificationManager.IMPORTANCE_LOW, showBadge = false)
        }
        timetableWatcher = TimetableChangeWatcher(this)
        renewReminderWindow()
        seedDefaultData()
        when (getPrefer().getInt(Const.KEY_DAY_NIGHT_THEME, 2)) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            2 -> {
                when {
                    Build.VERSION.SDK_INT >= 29 -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                    Build.VERSION.SDK_INT >= 23 -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
                    }
                    else -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    }
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(context: Context, channelId: String, channelName: String,
                                          importance: Int, showBadge: Boolean) {
        val channel = NotificationChannel(channelId, channelName, importance)
        channel.setShowBadge(showBadge)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 每次进程启动都把提醒的滚动窗口续期一遍。
     *
     * ## 为什么必须有这一条
     *
     * 提醒本身是**精确闹钟**（[CourseReminderScheduler]），排在未来 7 天的滚动窗口里，所以
     * "当天提醒会不会响"不依赖任何每日触发器 —— 前提是那个窗口**被续过期**。而在本 App
     * 零常驻组件的设计下（实测：按 HOME 后 3 秒内进程就被回收，`dumpsys activity services`
     * 对我们的包为空），续期只可能来自这几个触发器：
     *
     * - 小部件 `onUpdate`（系统 uid 的 3 小时闹钟，实测 `power_pending=--`，是可靠的）；
     * - `BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` / 覆盖安装；
     * - **用户自己点开 App** —— 这是最频繁、也最该生效的一条，原先却什么都不做。
     *
     * 于是出现一个反直觉的失效模式：用户装了、开了、看了，然后把 App 放几个月不碰，某个触发器
     * 一旦漏掉，7 天窗口到期之后再没有任何东西把它补上，提醒就**永久**停了 —— 而用户以为
     * 它一直在工作。挂在 `onCreate` 上之后，任何入口（图标、小部件、任意广播）拉起进程都会
     * 把窗口续期一遍；10 分钟内重复拉起只重排一次（见 `rescheduleIfStale` 的节流），
     * 单次成本只是几百次 `PendingIntent` 与闹钟表的读写，不产生唤醒、不碰网络。
     *
     * 幂等性由 [CourseReminderScheduler.reschedule] 自己保证：它先全量取消、再按当前状态重排，
     * 重复调用只是多跑一轮，不会堆出重复闹钟。
     *
     * ## 为什么放在协程里
     *
     * `reschedule` 要**同步**读 Room 的默认课表 / 课程 / 节次三张表（DAO 的 `...Sync()` 接口），
     * 不该占着 `Application.onCreate` 的主线程去等磁盘。这里 fire-and-forget 就够了：排不上
     * 也不会让进程崩，下一次启动还会再来一遍。
     */
    private fun renewReminderWindow() {
        appScope.launch {
            CourseReminderScheduler.rescheduleIfStale(this@App)
        }
    }

    /**
     * 种默认作息与默认作息表。
     *
     * 这一步原先挂在 `SplashActivity` 里、并被 `await` 过之后才进首页。那个开屏 Activity 已经去掉
     * （它导致"每次从桌面进入都重放一次开屏"，原因见 AndroidManifest 中 ScheduleActivity 的说明），
     * 于是改挂在这里 —— 与 [renewReminderWindow] 同一套模式，也和小部件那条路
     * （`ScheduleAppWidget`）一致：fire-and-forget。
     *
     * [UpdateUtils.initDefaultData] 自身幂等，且只在"全新安装 / 库被重建"时才真正写库；它要读 Room，
     * 所以放进 [appScope]（IO）而不是占着 `Application.onCreate` 的主线程等磁盘。
     */
    private fun seedDefaultData() {
        appScope.launch {
            UpdateUtils.initDefaultData(this@App)
        }
    }

}
