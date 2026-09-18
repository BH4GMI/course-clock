package courseclock.timetable

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.schedule_appwidget.WeekScheduleAppWidgetConfigActivity
import courseclock.timetable.today_appwidget.TodayCourseAppWidget
import courseclock.timetable.utils.AppWidgetUtils
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 小部件 provider 的**对外输入**。
 *
 * 为什么必须有这一类：`APPWIDGET_UPDATE` 是**受保护广播**，真机上 shell/第三方发不出来
 * （实测 `Permission Denial: not allowed to send broadcast android.appwidget.action.APPWIDGET_UPDATE
 * from unknown caller`），所以"id 不存在会怎样"只能在单测里派发；而**我们自己的 action**
 * （`WAKEUP_NEXT_DAY` / `WAKEUP_SHOW_TODAY`）不受保护，任何 App 都能发 —— 那条面在真机上
 * 已经实测过（§21-5），这里把它钉住。
 *
 * provider 的活跑在 [AppWidgetUtils.appScope]（`Dispatchers.IO`）上：后台线程的异常**不会**
 * 让用例失败，在真机上却会杀进程，所以下面把未捕获异常单独接下来判定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WidgetProviderHostileInputTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val uncaught = AtomicReference<Throwable?>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun captureUncaught() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.compareAndSet(null, e) }
    }

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    /** 受保护广播：带一个绝不存在的 appWidgetId。 */
    @Test
    fun 不存在的appWidgetId不会把日视图provider打崩() {
        val intent = Intent(application, TodayCourseAppWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(999_999))
        }
        application.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
        settleAndAssertQuiet()
    }

    /** 自有 action：不受保护，外部 App 想发就能发（导出后的真实对外面）。 */
    @Test
    fun 任何App都能发的自有action不会打崩provider() {
        for (action in listOf("WAKEUP_NEXT_DAY", "WAKEUP_SHOW_TODAY")) {
            application.sendBroadcast(Intent(action).setClass(application, TodayCourseAppWidget::class.java))
            shadowOf(Looper.getMainLooper()).idle()
        }
        settleAndAssertQuiet()
    }

    /** 窗口内完全没有课表（也没有小部件实例）时刷新，是最容易空指针的一条路径。 */
    @Test
    fun 空库时刷新小部件不崩() = runBlocking {
        AppWidgetUtils.refreshTodayWidgets(application)
        AppWidgetUtils.refreshScheduleWidgets(application)
        settleAndAssertQuiet()
    }

    /**
     * 真的绑定了实例时刷新：这才是会去读库、建 RemoteViews、写回桌面的那条路径
     * （上面"空库"那条只走到提前返回，牙齿不够）。
     */
    @Test
    fun 绑定了实例时刷新走真实路径且不崩() = runBlocking {
        val component = ComponentName(application, TodayCourseAppWidget::class.java)
        val appWidgetManager = AppWidgetManager.getInstance(application)
        shadowOf(appWidgetManager).bindAppWidgetId(7, component)
        assertTrue("前提：Robolectric 要能把这个实例算进 getAppWidgetIds，否则本用例没走到真实路径",
                appWidgetManager.getAppWidgetIds(component).contains(7))

        AppWidgetUtils.refreshTodayWidgets(application)
        settleAndAssertQuiet()
    }

    /**
     * 配置页被外部以"没有 extras"的方式拉起来时必须自己收场，而不是崩或留下空白页。
     *
     * 现在会更早结束：`onCreate` 顶部先同步判课表张数，测试库里是 0 张 → 直接 finish
     * （连 `getAppWidgetInfo` 那条路都走不到）。分支变了，但对外可见的结论一样：结束自己。
     */
    @Test
    fun 缺extras启动小部件配置页会自行结束() {
        val controller = Robolectric
                .buildActivity(WeekScheduleAppWidgetConfigActivity::class.java)
                .setup()

        assertTrue("配置页在缺 appWidgetId / 没有课表时都应当结束自己",
                controller.get().isFinishing)
    }

    /**
     * 给后台协程收尾的时间；一旦抓到未捕获异常立即报出来。
     *
     * 这里是"等异步收工"，不是用它掩盖失败：断言的对象正是"没有任何异常发生"，
     * 所以必须留出一段稳定的观察窗口。
     */
    private fun settleAndAssertQuiet() {
        repeat(40) {
            val thrown = uncaught.get()
            if (thrown != null) {
                throw AssertionError("provider 的异步处理抛出了未捕获异常", thrown)
            }
            Thread.sleep(25)
        }
        assertNull("不应该有任何未捕获异常", uncaught.get())
    }
}
