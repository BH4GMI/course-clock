package courseclock.timetable.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import courseclock.timetable.R
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.CategoryItem

/**
 * 一行在它所属分组里的位置。它决定**这张卡片哪两个角落是圆的**：
 * 第一行上方两角、最后一行下方两角、中间行直角 —— 多行拼起来在视觉上就是一张整卡。
 */
enum class RowCardPosition {
    /** 分组里唯一的一行：四角都是圆的。 */
    SINGLE,
    FIRST,
    MIDDLE,
    LAST
}

/**
 * 按下高亮的淡入／淡出时长。
 *
 * 「快速」是刻意的：按下必须**立刻**看出"这一行接收到了"，超过 100ms 就会被读成
 * "点下去没反应"。
 *
 * 放在文件级：只有 [PressHighlightCard] 用它，而那个类是文件级的（见它的类文档）。
 */
private const val HIGHLIGHT_FADE_MS = 100L

/**
 * 卡片式列表的行装饰。
 *
 * 为什么把它做成一件独立的东西，而不是每个 provider 各画各的：一张卡片哪两个角是圆的，
 * 取决于这一行在组里排第几、以及它下面还是不是同一组 —— 那是**列表级**的事实。
 * RecyclerView 是扁平的（组靠 [CategoryItem] 起头），一个 provider 只看得到自己那一行，
 * 判断必须发生在"能看到整份列表"的地方。这里在数据上标注一次，5 个 provider 只照着画，
 * 判断就只有一份。
 *
 * 顺带把"组与组之间留多大空"也一起算掉：分组标题是一个 item，它的位置同样只有列表级能看到。
 */
object RowCardDecoration {

    /**
     * 把 [items] 整份看一遍，给每一行标注它在组里的位置、给每个分组标题标注上方间距。
     *
     * 幂等：列表内容变了（比如搜索过滤、总闸开关刷新）再调一次即可，不用重建 item。
     */
    fun apply(context: Context, items: List<BaseSettingItem>) {
        val gap = context.resources.getDimensionPixelSize(R.dimen.setting_group_gap)
        var indexInGroup = 0
        for (index in items.indices) {
            val item = items[index]
            if (item is CategoryItem) {
                // 第一组的标题顶上不留空：它下面紧挨着顶栏，再加 24dp 就成了双份留白。
                item.topGap = if (index == 0) 0 else gap
                indexInGroup = 0
                continue
            }
            val hasNextRowInGroup =
                    index + 1 < items.size && items[index + 1] !is CategoryItem
            // 没被任何分组标题领过的行（调用方压根没加 CategoryItem）也算 SINGLE：
            // 否则第一行会拿到 FIRST，卡片下沿永远不圆。
            item.rowPosition = when {
                indexInGroup == 0 && !hasNextRowInGroup -> RowCardPosition.SINGLE
                indexInGroup == 0 -> RowCardPosition.FIRST
                hasNextRowInGroup -> RowCardPosition.MIDDLE
                else -> RowCardPosition.LAST
            }
            indexInGroup++
        }
    }

    /**
     * 一行的完整背景：**卡片底 + 水波纹**，按位置切圆角。
     *
     * 卡片左右各缩进 `page_inset` —— 但那个缩进**不在这里画**，而是行的 `marginStart/marginEnd`
     * （见 [SettingRowStyle.cardRoot]）。原因是踩过的一个坑：这里原来返回
     * `InsetDrawable(card, inset, 0, inset, 0)`，而 `View.setBackgroundDrawable()` 会把背景的
     * `getPadding()` **写回视图的内边距** —— 于是"卡片缩进 16dp"顺手把行根的内边距也改成 16dp，
     * 内容正好压在卡片边上（真机上就是"文字/开关贴着边框"）。
     * 现在两者各归各位：**缩进是布局（margin），内容留白是内边距（padding）**，互不干扰。
     */
    fun background(context: Context, item: BaseSettingItem): Drawable {
        val radius = context.resources.getDimensionPixelSize(R.dimen.group_radius).toFloat()
        val cardColor = ContextCompat.getColor(context, R.color.card_background)
        val radii = cornerRadii(item.rowPosition, radius)

        // 不可点的行不给高亮：点上去毫无反应，才和"不可点"这件事一致。
        if (!item.isRowEnabled) return plainCard(cardColor, radii)

        // 按下高亮是**整块卡片**换色 —— 一按下去整行一起亮，没有从触点向外扩散的圆形水波纹
        // （那正是原来 RippleDrawable 的行为，已换掉）。变化过程由 [PressHighlightCard] 插值，
        // 所以是淡入淡出而不是瞬时跳变。
        //
        // 高亮色是"卡片底叠一层次要文字色 0x1F"，必须**先算成不透明结果**：卡片底就是画出来的
        // 那一块、没有下层可叠，直接给半透明会透出页面背景而不是压在卡片上。
        val highlight = ColorUtils.setAlphaComponent(SettingRowStyle.secondaryColor(context), 0x1F)
        return PressHighlightCard(cardColor, ColorUtils.compositeColors(highlight, cardColor), radii)
    }

    private fun plainCard(color: Int, radii: FloatArray): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // 组内相邻两行之间**不画分隔线**（设计说明明确要求）：这里只有底色和圆角，
                // 没有任何描边 —— 想加线的人会先看到这行注释。
                setColor(color)
                cornerRadii = radii
            }

    /** 圆角只出现在"组首的上两角"和"组尾的下两角"，其余一律直角。 */
    private fun cornerRadii(position: RowCardPosition, radius: Float): FloatArray {
        val none = 0f
        // GradientDrawable 的八个值顺序是 左上、右上、右下、左下（每角 x/y 各一）。
        return when (position) {
            RowCardPosition.SINGLE -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            RowCardPosition.FIRST -> floatArrayOf(radius, radius, radius, radius, none, none, none, none)
            RowCardPosition.LAST -> floatArrayOf(none, none, none, none, radius, radius, radius, radius)
            RowCardPosition.MIDDLE -> floatArrayOf(none, none, none, none, none, none, none, none)
        }
    }

}

/**
 * 卡片底 + **会渐变**的按下高亮。
 *
 * ## 为什么不用 ColorStateList
 *
 * `GradientDrawable.setColor(ColorStateList)` 只能得到**瞬时**跳变：状态一变就换色重画。
 * 按下高亮要有一下快速的淡入淡出，所以这里自己做一个**可状态化**的 Drawable：
 * [onStateChange] 里不直接换色，而是启动一次插值，把"常态色 → 按下色"在
 * [HIGHLIGHT_FADE_MS] 内走完，每帧重画。
 *
 * ## 整块一起亮、不扩散
 *
 * 画的始终是**同一个圆角矩形**，只是颜色整体插值 —— 不是水波纹那种以触点为圆心向外扩散的
 * 圆形。圆角仍然按这一行在组里的位置（首／中／尾）切，所以拼起来的卡片形状不变。
 *
 * 不覆盖 `getPadding()`：默认返回 false，`View.setBackgroundDrawable()` 就不会把内边距
 * 写回视图（那个坑见 [RowCardDecoration.background] 的说明）。
 *
 * ## 为什么是 internal 而不是 private
 *
 * "这一行哪两个角是圆的"最终**画在这层 Drawable 上**，不在 item 上 —— 设置页的两条渲染
 * 用例正是要量它（`SettingsRenderTest` / `SettingsSystemActionsTest`）。把它留在 private，
 * 那两条就只能退回去断言一张常量表，等于不再验证"真的画成了这样"。所以类与 [cornerRadii]
 * 都开放给同模块。
 */
internal class PressHighlightCard(
        private val normalColor: Int,
        private val pressedColor: Int,
        val cornerRadii: FloatArray) : Drawable() {

    private val card = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(normalColor)
        // 必须写全限定：`apply` 里的隐式接收者自己就有一个 `cornerRadii`
        // （GradientDrawable.getCornerRadii），裸写会被它遮住，读到的是一份还没设过的
        // null 数组，构造这一层就 NPE。
        this.cornerRadii = this@PressHighlightCard.cornerRadii
    }

    /** 0 = 常态，1 = 按下。动画只改这个值，颜色由它插出来。 */
    private var fraction = 0f

    private var animator: ValueAnimator? = null

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        animateTo(if (state.contains(android.R.attr.state_pressed)) 1f else 0f)
        return true
    }

    private fun animateTo(target: Float) {
        if (fraction == target) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = HIGHLIGHT_FADE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                card.setColor(ColorUtils.blendARGB(normalColor, pressedColor, fraction))
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        card.bounds = bounds
        card.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        card.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        card.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
