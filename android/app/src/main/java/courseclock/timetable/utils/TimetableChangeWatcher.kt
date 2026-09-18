package courseclock.timetable.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.room.InvalidationTracker
import courseclock.timetable.AppDatabase
import kotlinx.coroutines.launch

/**
 * 课表相关表的写入观察者：一变就重排上课提醒。
 *
 * ## 为什么需要它
 *
 * 提醒的注册入口是 [CourseReminderScheduler.reschedule]，而它原先只被三类触发点调用：
 * 设置页（改提醒开关/提前量）、小部件的更新与点击、开机与改时间。
 * **课程表和作息表发生任何变更时都不重排**，于是：
 *
 * - **全新安装 → 导入课表 → 零闹钟**。不打开设置拨一下开关、不放小部件、不重启，
 *   就一条提醒都不会有。这是"导入完课表什么都没发生"的根因。
 * - 改一节课的时间 → 旧闹钟仍指向旧时刻，照旧按旧时间响。
 * - 删一节课 → 它的闹钟继续响。
 *
 * 修法不是在导入/增删改那几处各补一次 `reschedule`（那样将来任何新的写入方都必须记得调，
 * 这类 bug 会复发），而是把「课表变了 ⇒ 提醒必须重算」这条不变式交给 Room 自己的
 * [InvalidationTracker]：**任何**代码路径写这几张表都会触发重排，包括以后新增的。
 *
 * ## 为什么是"类 + 构造注入回调"而不是全局单例 + 可写全局
 *
 * 回调作为构造参数注入，测试可以直接 new 一个带计数回调的实例，断言"写入确实触发了回调"，
 * 不需要任何可写的全局状态或 test-only 开关。生产由 [courseclock.timetable.App] 持有实例
 * —— 持有本身是必须的：[InvalidationTracker] 对观察者是**弱引用**，没人强引用就会被回收，
 * 表现为"观察者装上了但永远不触发"，而且这种失败是静默的。
 *
 * ## 去抖
 *
 * 一次导入会在同一事务里写 TableBean + 若干 CourseBaseBean / CourseDetailBean，
 * Room 会分几次通知。所以延迟 [DEBOUNCE_MS] 合并成一次重排。用 `Handler` 配一个固定 token
 * 做定时，而不是自己存一个 Runnable 字段去 remove：`removeCallbacksAndMessages(token)`
 * 由 Handler 保证线程安全，而 [onInvalidated] 的回调线程不保证是主线程。
 */
class TimetableChangeWatcher(
        context: Context,
        private val onChange: (Context) -> Unit = CourseReminderScheduler::reschedule
) {

    private val appContext = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 定时用的 token：同一个 token 只会有一个待执行任务。 */
    private val debounceToken = Any()

    private val observer = object : InvalidationTracker.Observer(TABLES) {
        override fun onInvalidated(tables: Set<String>) {
            schedule()
        }
    }

    init {
        AppDatabase.getDatabase(appContext).invalidationTracker.addObserver(observer)
    }

    private fun schedule() {
        mainHandler.removeCallbacksAndMessages(debounceToken)
        mainHandler.postAtTime({
            Log.i(TAG, "课表已变更，重排上课提醒")
            // 重排要同步读三张表并做数百次 binder 往返，不能占主线程：去抖计时仍用主线程
            // Handler（token 去重语义不变），回调本体丢进进程级 IO 作用域执行。
            appScope.launch { onChange(appContext) }
        }, debounceToken, SystemClock.uptimeMillis() + DEBOUNCE_MS)
    }

    companion object {

        private const val TAG = "TimetableWatcher"

        /**
         * 这几张表任何一个发生变化都必须重排提醒。
         *
         * 名字是 Room 的默认表名，即 [androidx.room.Entity] 实体的类简名（实体上都没有写
         * `tableName=`）。**写错一个字的后果是静默的**：`InvalidationTracker` 不校验表名，
         * 观察一个不存在的表只会永远不触发，而且没有任何报错。所以有一条单测拿 `sqlite_master`
         * 里 Room 真正建出来的表名跟这份清单对账，改这里的名字会让它失败。
         *
         * - `CourseBaseBean` / `CourseDetailBean`：课程的增删改（课名、教室、节次、周次）
         * - `TableBean`：开学日期、当前课表、单双周规则 —— 决定"今天是第几周、星期几"
         * - `TimeDetailBean`：作息表各节的起止时刻 —— 决定提醒时刻
         */
        internal val TABLES = arrayOf(
                "CourseBaseBean",
                "CourseDetailBean",
                "TableBean",
                "TimeDetailBean"
        )

        /**
         * 合并同一批写入用的去抖时长。一次导入会在同一事务里写多张表（Room 分几次通知），
         * 手动连续编辑多门课也会各自触发一次重排——每次重排固定扫几百个 requestCode，
         * 3 秒窗口把一个编辑会话合并成一两次。提醒最多晚 3 秒注册，无可感知差异。
         */
        private const val DEBOUNCE_MS = 3000L
    }
}
