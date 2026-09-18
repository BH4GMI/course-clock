package courseclock.timetable

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.schedule.ScheduleActivity
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.getPrefer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * 空状态（一张课表都没有）下，主界面所有原先裸读 `viewModel.table`（lateinit）的入口
 * 都必须被 [ScheduleActivity] 的 requireTable 闸门拦下，而不是抛
 * UninitializedPropertyAccessException。
 *
 * 真机上复现过的路径：全新安装 → 首页是「去导入」引导 → 点右下角 + 号 → 必崩。
 * 同一类裸读还散布在顶栏日期行（周数面板）、分享导出、底部面板四个入口、
 * 侧栏表列表，这里全部逐个点一遍。
 *
 * 反向用例钉住闸门**不会拦错**：有课表时 + 号必须照常拉起加课页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EmptyStateEntryGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 空状态引导弹窗出现 = initView 的协程已跑完并确认没有课表（tableMissing 已置位）。 */
    private fun awaitImportPrompt(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            val shown = ShadowDialog.getShownDialogs().any { dialog ->
                dialog.isShowing && textsOf(dialog).any { it.contains("还没有课表") }
            }
            if (shown) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(20)
        }
    }

    private fun textsOf(dialog: Dialog): List<String> {
        val out = mutableListOf<String>()
        dialog.window?.decorView?.let { walk(it) { v -> if (v is TextView) v.text?.toString()?.let(out::add) } }
        return out
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
    }

    /** 屏幕上（含 Snackbar）能找到的指定文字。 */
    private fun textVisible(activity: Activity, needle: String): Boolean {
        var found = false
        walk(activity.window.decorView) { v ->
            if (v is TextView && v.text?.toString()?.contains(needle) == true) found = true
        }
        return found
    }

    private fun drainStartedActivities(): Intent? =
            shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    @Test
    fun 空状态下所有依赖课表的入口都不崩且给出引导() {
        context.getPrefer().edit().clear().putBoolean(Const.KEY_HAS_INTRO, true).commit()
        // 同一测试类的各方法共享同一个库（AppDatabase 是单例）：先清场，保证这里是真·空状态。
        runBlocking {
            AppDatabase.getDatabase(context).tableDao().clearAllTables()
            AppDatabase.getDatabase(context).timeTableDao().clearAllTimeTables()
        }
        val activity = Robolectric.buildActivity(ScheduleActivity::class.java).setup().get()

        assertTrue("空状态必须先出现导入引导（前提不成立，后面断言无意义）", awaitImportPrompt())

        // 覆盖原先全部裸读 lateinit 的可达入口：加号、顶栏日期行、分享、底部面板四项。
        val targets = listOf(
                R.id.anko_ib_add to "加号加课",
                R.id.anko_tv_date to "顶栏日期行（周数面板）",
                R.id.anko_ib_share to "分享导出",
                R.id.bottom_sheet_change_week_btn to "底部面板·当前周",
                R.id.bottom_sheet_modify_time_btn to "底部面板·上课时间",
                R.id.bottom_sheet_bg_btn to "底部面板·背景",
                R.id.bottom_sheet_check_course_btn to "底部面板·课程"
        )
        for ((id, name) in targets) {
            val view = activity.findViewById<View>(id)
                    ?: throw IllegalStateException("$name 的控件不存在（id 变了？测试需要跟上）")
            // 守卫失效时 performClick 会把 UninitializedPropertyAccessException 直接抛出来。
            view.performClick()
            idle()
        }

        // 全部点击之后不许有任何页面被拉起（守卫应当全部拦下）。
        assertTrue("空状态下守卫没拦住：有页面被拉起了 ${drainStartedActivities()}",
                drainStartedActivities() == null)
        // 而且用户要能看到「为什么没反应」：引导文字必须真的出现在屏幕上。
        assertTrue("点击后没有给出「先导入或新建」的引导提示",
                textVisible(activity, "还没有课表"))
    }

    @Test
    fun 有课表时加号照常放行() {
        context.getPrefer().edit().clear().putBoolean(Const.KEY_HAS_INTRO, true).commit()
        runBlocking {
            val db = AppDatabase.getDatabase(context)
            db.tableDao().clearAllTables()
            db.timeTableDao().clearAllTimeTables()
            // 课表对时间表有外键（SET_DEFAULT）：先播种默认时间表 id=1，与其它测试的播种口径一致。
            db.timeTableDao().insertTimeTable(TimeTableBean(id = 1, name = "守卫用例"))
            db.tableDao().insertTableAsDefaultIfNone(TableBean(id = 0, tableName = "测试表"))
        }
        val activity = Robolectric.buildActivity(ScheduleActivity::class.java).setup().get()
        drainStartedActivities()

        // initView 读库是异步的：轮询点 + 号，等闸门放行；只许成功放行，不许崩、不许永远拦。
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idle()
            activity.findViewById<View>(R.id.anko_ib_add).performClick()
            val started = drainStartedActivities()
            if (started?.component?.className == "courseclock.timetable.course_add.AddCourseActivity") return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("有课表时 + 号迟迟没有拉起加课页（最后拉起的是 $started）—— 闸门把正常路径也拦住了")
            }
            Thread.sleep(20)
        }
    }
}
