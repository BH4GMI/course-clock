package courseclock.timetable.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 全应用唯一的触感反馈入口。
 *
 * ## 为什么走 `performHapticFeedback` 而不是自己去拿 `Vibrator`
 *
 * 在 MIUI / HyperOS 上，这一族常量会走用户在「声音与振动 → 触感」里选的那一档
 * （线性马达上就是那一下干脆的轻震）；用户把触感整体关掉时**一点都不震**。
 * 自己拿 `Vibrator` + `VibrationEffect` 就绕开了这个开关，用户关掉触感还会被震，
 * 那是最容易被骂的一类"优化"。所以这里只用平台的语义常量，并且**不加**
 * `FLAG_IGNORE_VIEW_SETTING` —— 用户对触感的偏好该被尊重。
 *
 * ## 分三级，不按"能加就加"来铺
 *
 * 触感是**回执**，不是装饰：只在"用户做了一件事、界面因此变了"时给一下。
 * 因此这里只有三种语义，且调用点由各界面明写（见各自的点击处理），不做全局自动注入 ——
 * 自动注入会把滚动、列表复用、程序化刷新也算成"用户操作"，那是震个不停的最短路径。
 *
 * - [tap]：一次普通点击（按钮、列表行、课表格子）。稍重，明确"这一下生效了"。
 * - [tick]：状态/视图切换（翻周、开合面板、选中项变化）。比 [tap] 轻，
 *   连续操作时不会发麻。
 * - [longPress]：长按进入可删除/可拖动的状态。给的是"长按被识别了"的确认。
 *
 * 同一件事只发一次：例如"点周数按钮"会同时触发按钮选中与 ViewPager 翻页，
 * 翻页那一侧因此改用**手势判定**（见 `ScheduleActivity` 的滚动状态监听），
 * 避免同一次操作震两下。
 */
object Haptics {

    /** 普通点击。 */
    fun tap(view: View) = feedback(view, HapticFeedbackConstants.CONTEXT_CLICK, Build.VERSION_CODES.M)

    /** 状态/视图切换：比 [tap] 轻一档。 */
    fun tick(view: View) = feedback(view, HapticFeedbackConstants.CLOCK_TICK, Build.VERSION_CODES.LOLLIPOP)

    /** 长按。 */
    fun longPress(view: View) = feedback(view, HapticFeedbackConstants.LONG_PRESS, Build.VERSION_CODES.CUPCAKE)

    /**
     * 常量低于当前系统版本时退回 [HapticFeedbackConstants.VIRTUAL_KEY] ——
     * 同样是"按键级"的一下轻震，不会因为机型老就完全不震。
     */
    private fun feedback(view: View, constant: Int, sinceApi: Int) {
        view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= sinceApi) constant else HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
