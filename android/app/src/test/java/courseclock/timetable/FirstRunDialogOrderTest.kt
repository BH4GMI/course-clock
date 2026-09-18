package courseclock.timetable

import android.app.Dialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.schedule.ScheduleActivity
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.getPrefer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * 首次启动的**弹窗顺序**。
 *
 * 用户原话：「首次打开软件为什么先弹出没有课表的提示再弹出"关于本软件"」。
 *
 * 根因不是"哪个弹窗文案写错了"，而是两者之间**压根没有顺序**：全新安装同时满足"没有课表"和
 * "没讲过声明"，声明走 `postDelayed(500)`、导入引导走 initView 里的协程，谁先到谁先弹 —— 那半秒
 * 常数把顺序固定成了"先提示、后声明"，而声明才是"使用之前就该知道"的那一条（它 `setCancelable(false)`）。
 *
 * 所以这里不断言"两个弹窗各自的文案对不对"，只断言**顺序**：第一屏必须是声明，点掉之后才轮到
 * 导入引导；已经讲过声明的老用户则只看到导入引导。谁把顺序交回给计时器，这些就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FirstRunDialogOrderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 把弹窗窗口里所有可见文字收出来：不依赖 Material 用的那个内部布局 id。 */
    private fun textsOf(dialog: Dialog): List<String> {
        val out = mutableListOf<String>()
        walk(dialog.window!!.decorView) { view ->
            if (view is TextView) view.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    private fun buttonsOf(dialog: Dialog): List<String> {
        val out = mutableListOf<String>()
        walk(dialog.window!!.decorView) { view ->
            if (view is Button) view.text?.toString()?.let { out += it }
        }
        return out
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * 等到屏幕上出现符合 [predicate] 的弹窗，最多等 [timeoutMs]。
     *
     * 为什么要轮询：导入引导出自 initView 的协程，而 `getDefaultTable()` 是 Room 的挂起查询 ——
     * 它跑在 Room 自己的查询线程池上，`shadowOf(mainLooper).idle()` 只排空主线程的消息队列，
     * **不等**那个线程。这里给一个有上限的等待：等到了就返回，等不到返回 null 让断言自己失败，
     * 不用 sleep 掩盖竞态。
     */
    private fun awaitDialog(timeoutMs: Long = 5_000, predicate: (Dialog) -> Boolean): Dialog? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            ShadowDialog.getShownDialogs().lastOrNull { it.isShowing && predicate(it) }?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(20)
        }
    }

    private fun isIntro(dialog: Dialog) = textsOf(dialog).any { it == "关于本软件" }

    private fun isImportPrompt(dialog: Dialog) =
            textsOf(dialog).any { it.contains("还没有课表") }

    @Test
    fun 声明的校名口径是适配而不是本校的应用() {
        val body = context.getString(R.string.first_run_body)

        assertTrue("校名口径必须是「适配」：$body", body.contains("适配上海工程技术大学"))
        for (misleading in listOf("面向上海工程技术大学", "只服务上海工程技术大学", "本校")) {
            assertTrue("这句读起来像「该校自己的应用」：$misleading", !body.contains(misleading))
        }

        // 一句"与校方无关"不够：无隶属、无合作、无背书、非校方出品，四条要能分别读出来。
        for (clause in listOf("无隶属", "合作", "背书", "不代表本软件由该校出品")) {
            assertTrue("缺少与校方无关的陈述「$clause」：$body", body.contains(clause))
        }
        assertTrue("许可与来源仍然必须写全：$body",
                body.contains("Apache License 2.0") && body.contains("Cerbur/WakeupSchedule_Kotlin"))
    }

    @Test
    fun 随包的NOTICE也要声明非校方出品() {
        val notice = context.assets.open("NOTICE").bufferedReader().use { it.readText() }
        assertTrue("NOTICE 仍在说「只服务该校」（serves ... only），像官方应用", !notice.contains("serves Shanghai University"))
        for (clause in listOf("adapted for", "independent", "neither produced", "not an official application")) {
            assertTrue("NOTICE 缺少「$clause」", notice.contains(clause))
        }
    }

    @Test
    fun 首次启动的第一个弹窗是许可与免费声明() {
        context.getPrefer().edit().clear().commit()
        Robolectric.buildActivity(ScheduleActivity::class.java).setup()

        val dialog = awaitDialog { isIntro(it) }
        assertTrue("首次启动没有弹出「关于本软件」的声明", dialog != null)

        val texts = textsOf(dialog!!)
        assertTrue("声明里必须写清授权与来源，实际文字：$texts",
                texts.any { it.contains("Apache License 2.0") && it.contains("Cerbur") })
        assertTrue("声明还在屏幕上时不该同时出现导入引导：$texts",
                texts.none { it.contains("还没有课表") })
        assertTrue("导入引导抢在声明之前弹了出来：${ShadowDialog.getShownDialogs().map { textsOf(it) }}",
                awaitDialog(200) { isImportPrompt(it) } == null)
    }

    @Test
    fun 声明讲完才轮到导入引导() {
        context.getPrefer().edit().clear().commit()
        Robolectric.buildActivity(ScheduleActivity::class.java).setup()

        val intro = awaitDialog { isIntro(it) }
        assertTrue("首次启动没有弹出声明", intro != null)
        (intro as AlertDialog).getButton(Dialog.BUTTON_POSITIVE).performClick()
        // AlertDialog 的按钮回调用 Handler 转一手（AlertController 的 mButtonHandler），
        // performClick 本身不会同步跑到监听器里，必须把主线程消息排空。
        idle()

        assertTrue("点掉声明之后必须记下已经讲过",
                context.getPrefer().getBoolean(Const.KEY_HAS_INTRO, false))

        val next = awaitDialog { isImportPrompt(it) }
        assertTrue("声明讲完后没有接着引导导入，屏幕上的弹窗：${ShadowDialog.getShownDialogs().map { textsOf(it) }}",
                next != null)
        assertTrue("导入引导必须给出「去导入」这个出口，实际按钮：${buttonsOf(next!!)}",
                buttonsOf(next).any { it == "去导入" })
    }

    @Test
    fun 已经讲过声明的老用户只看到导入引导() {
        context.getPrefer().edit().clear().putBoolean(Const.KEY_HAS_INTRO, true).commit()
        Robolectric.buildActivity(ScheduleActivity::class.java).setup()

        val dialog = awaitDialog { isImportPrompt(it) }
        assertTrue("没有课表时必须给出导入引导，屏幕上的弹窗：${ShadowDialog.getShownDialogs().map { textsOf(it) }}",
                dialog != null)
        assertTrue("声明只讲一次，老用户不该再看到它：${textsOf(dialog!!)}",
                textsOf(dialog).none { it == "关于本软件" })
        idle()
        assertEquals("导入引导只该弹一次（补弹不能变成弹两次）",
                1, ShadowDialog.getShownDialogs().count { it.isShowing && isImportPrompt(it) })
    }
}
