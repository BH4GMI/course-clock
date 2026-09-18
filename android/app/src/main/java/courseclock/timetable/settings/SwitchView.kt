package courseclock.timetable.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import courseclock.timetable.R

/**
 * 设置页 / 课表设置页那一枚 46×28 的开关（自绘）。
 *
 * ## 为什么不用 `SwitchCompat`
 *
 * `SwitchCompat`（appcompat 1.1.0）在 `onMeasure` 里先算一个**自己的内部盒子**：
 * `mSwitchWidth = max(mSwitchMinWidth, 2 × mThumbWidth + …)`，`onLayout` 再把这个盒子在视图里
 * **居中**。设计稿要求开关恒定 46×28，于是视图宽 46dp，而内部盒子（由圆点内在尺寸推出来）
 * 比它大 —— 居中的结果是左偏移为负：轨道左圆角和圆点左弧一起被视图边界裁掉，真机上就是
 * "开关的左侧被截断"。`mSwitchMinWidth` 只能把这个内部盒子**变大**，没有公开 API 能把它压到
 * 46dp；另外它的 `textOn/textOff` 默认是 null，`makeLayout` 会拿它去造 `StaticLayout`（API 29+
 * 不接受 null）—— 同一个控件上我们已经踩了两次，都是"把控件当画布用"时的内部约束。
 *
 * 这一枚开关需要的东西很少且都是确定的：恒定尺寸、两个颜色、圆点内缩、随行一起压暗。
 * 自己画（与本工程 `ArrowView` 同一条路子）比迁就上面那套内部布局更小、更可控。
 *
 * ## 谁负责点击
 *
 * 它自己 `isClickable = false`：整行才是点击目标（见 `SwitchItemProvider` 与分发处），
 * 否则点一下会触发两次、一开一关。因此这里只提供状态与画法。
 */
class SwitchView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null) : View(context, attrs) {

    private val trackWidth = context.resources.getDimensionPixelSize(R.dimen.switch_width)
    private val trackHeight = context.resources.getDimensionPixelSize(R.dimen.switch_height)
    private val thumbSize = context.resources.getDimensionPixelSize(R.dimen.switch_thumb)

    /** 圆点离轨道两端各 2dp（设计稿的内缩）。 */
    private val thumbInset = (trackHeight - thumbSize) / 2

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 打开时的轨道色：用户的主题色（由 provider 传进来，见 `SwitchItemProvider`）。 */
    var onColor: Int = ContextCompat.getColor(context, R.color.colorAccent)
        set(value) {
            field = value
            invalidate()
        }

    /** 「关」的轨道色：三级文字色压到约 60% 不透明度，深浅两套主题由同一套公式给出。 */
    private val offColor: Int = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.text_secondary), 0x99)

    /** 圆点位置：0 = 关（贴左），1 = 开（贴右）。动画只改这个值。 */
    private var position = 0f

    private var animator: ValueAnimator? = null

    private var checked = false

    /**
     * 逻辑状态。**同步用**（列表绑定、复用换行）：直接吸附到端点，不播动画 ——
     * 复用中的开关从上一行的位置滑过来，看起来会像"这一行的开关自己动了一下"。
     * 用户操作请走 [animateTo]。
     */
    var isChecked: Boolean
        get() = checked
        set(value) {
            if (checked == value) return
            checked = value
            cancelAnimation()
            position = if (value) 1f else 0f
            invalidate()
        }

    /**
     * 用户操作：从**当前位置**滑到 [checked]，并同步逻辑状态。
     *
     * 与 [isChecked] 分开是有意的：状态同步（列表绑定）不该有动画，用户操作该有。
     *
     * 调用方必须在任何 `notifyItemChanged` / `notifyDataSetChanged` **之前**调它。
     * 重绑会走 `convert()` 里的 `isChecked = …`，而那个 setter 对"值已经相同"提前返回
     * （见上），所以滑到一半的动画不会被掐掉；顺序反了先刷表，值就先被吸附成终值，
     * 这里再调会因为"值已相同"直接返回 —— 结果还是没有动画。
     */
    fun animateTo(checked: Boolean) {
        if (this.checked == checked) return
        this.checked = checked
        val target = if (checked) 1f else 0f
        cancelAnimation()
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = TOGGLE_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    /** 被上游开关关掉时整枚开关压暗，与行文字的变灰一致。 */
    fun setRowEnabled(enabled: Boolean) {
        alpha = if (enabled) 1f else SettingRowStyle.ROW_DISABLED_ALPHA / 255f
    }

    /**
     * 尺寸恒定为两个 token：不参与父布局的测量协商。
     *
     * 这样"46×28"就是控件本身的事实，而不是某一份 LayoutParams 的巧合 —— 换一个 provider 用它、
     * 或者放进 XML，尺寸都不会变。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackWidth, trackHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = trackHeight / 2f
        rect.set(0f, 0f, trackWidth.toFloat(), trackHeight.toFloat())
        paint.color = if (isChecked || position > 0.5f) onColor else offColor
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 圆点的行程 = 轨道宽 − 圆点直径 − 两侧内缩，所以开到端点时圆点仍然完整在轨道里。
        val travelStart = thumbInset + thumbSize / 2f
        val travelEnd = trackWidth - thumbInset - thumbSize / 2f
        val centerX = travelStart + (travelEnd - travelStart) * position
        paint.color = Color.WHITE
        canvas.drawCircle(centerX, trackHeight / 2f, thumbSize / 2f, paint)
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    /**
     * 给无障碍报出"这是一枚开关、现在是开还是关"。
     *
     * 节点本身不设成可聚焦：整行才是点击目标，多一个焦点节点会让朗读顺序变成两遍
     * （行的标题 + 一个没有上下文的开关）。
     */
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = isChecked
    }

    companion object {
        /**
         * 「尽可能快」：再短（约 80ms 以下）就只剩一下跳变、读不出方向了，
         * 所以取刚好还能看出是滑动的最短时长。圆点行程只有 46dp 轨道里的十几个 dp，
         * 这个时长下速度足够快，不会让人等。
         */
        const val TOGGLE_ANIMATION_MS = 100L
    }
}
