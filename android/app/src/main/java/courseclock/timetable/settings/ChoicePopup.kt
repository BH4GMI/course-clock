package courseclock.timetable.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.widget.BaseAdapter
import android.widget.ListView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import courseclock.timetable.R
import splitties.dimensions.dip

/**
 * 「值 + 箭头」那一行的**就地选择列表**：锚在行上弹出，点一项立刻生效、当前项打勾。
 *
 * ## 为什么不是 AlertDialog
 *
 * 这一行的语义是"就地换一个值"（设计稿给它的箭头是「⌃⌄」，不是「>」）。模态框会把它变成
 * 另一件事：弹出居中对话框、选完还要按"确定"，值多一步才生效，视觉上也离开了那一行
 * —— 用户看不出"我改的是这一行"。锚定列表让选项贴在行边上、当前值打勾，改完即生效。
 *
 * ## 为什么用 [ListPopupWindow]
 *
 * 它就是"锚在一个 View 上的列表弹窗"的一等 API：定位、避让屏幕边缘、点外部关闭、返回键关闭
 * 都由它处理（自己用 PopupWindow 拼这些要写一堆）。行本身是自己搭的 —— 只有"每行一个标题 +
 * 选中打勾"这点内容，用框架的 `simple_list_item_single_item` 反而拿不到本工程的配色与间距。
 *
 * ## 三处必须显式接管的地方
 *
 * 1. **宽度**：`ListPopupWindow` 的 `mDropDownWidth` 默认是 `WRAP_CONTENT`，但它把这个值
 *    解释成"**和锚点一样宽**"（`show()` 里 `widthSpec = getAnchorView().getWidth()`），
 *    而我们的锚点是**整行**，于是弹窗被撑成整屏宽 —— 一个"小窗口"变成了一整块面板。
 *    所以这里按内容量出像素宽度交给 `setContentWidth`。
 * 2. **出现的方向**：窗口动画这条路不受本工程控制（见 [animateIn]），所以进入动画自己驱动，
 *    缩放的支点定在**右缘** —— 与那一行行尾的「值 + ⌃⌄」对齐。窗口只保留退出动画。
 * 3. **背景压暗**：`android.widget.PopupWindow` **没有 dim API**（整个类没有 `setDimAmount`，
 *    也没有任何 dim 成员；dim 是 `Window`/`WindowManager.LayoutParams` 的能力，只有 Dialog
 *    那条路拿得到），`ListPopupWindow` 又完全不转发内部那个 PopupWindow。所以"背景略微变暗"
 *    只能在应用层画一层（见 [attachScrim]），并且**自己淡入淡出** —— 系统 dim 也是渐变的，
 *    直接切换会像"灯一下子关了"。
 */
internal object ChoicePopup {

    /**
     * 弹窗背后那层压暗的不透明度（"略微变暗"，不是模态对话框那种重度遮罩）。
     *
     * 取值参照系统自带的列表弹窗：能让人一眼看出"现在在选一个值、别处先放下"，
     * 又不至于把底下的文字压到读不出来。
     */
    private const val SCRIM_COLOR = 0x3D000000

    /**
     * 展开的起点缩放。再小就不像"从那一处展开"，而像整个窗口翻出来。
     */
    private const val SCALE_FROM = 0.85f

    /**
     * 圆角卡片离窗口边各 4dp。
     *
     * 两个用途，必须是同一个数：[panelBackground] 的内缩，以及 [show] 里给
     * `setContentWidth` 补回的那段（窗口底是全透明的，它不会替我们加内边距）。
     * 让行的水波纹不至于压到卡片的圆角上。
     */
    private const val CARD_INSET_DP = 4

    /**
     * 压暗层的淡入／淡出时长。
     *
     * 读的是**弹窗自己那套进出动画用的同一个值**（`R.integer.choice_popup_duration`）：
     * 两者一起出现／一起消失才像一件事，各写一个数迟早分叉。**直接出现是不行的** ——
     * 那就是"灯一下子关了"。
     */
    private fun fadeDuration(context: Context): Long =
            context.resources.getInteger(R.integer.choice_popup_duration).toLong()

    /**
     * 弹出选项列表。
     *
     * @param anchor 锚点（传被点的那一行），列表贴着它的下沿弹。
     * @param labels 选项文案。
     * @param current 当前项下标，它会带一枚勾。
     * @param onPick 选中回调，**先关闭列表再回调**（回调里通常会刷新那一行）。
     */
    fun show(anchor: View, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        val context = anchor.context
        val popup = ListPopupWindow(context).apply {
            anchorView = anchor
            isModal = true
            verticalOffset = context.dip(4)
            // 横向贴**右边**：这一行的"值 + ⌃⌄"在行尾，弹窗挂在左边会离用户看的那一处很远。
            // 框架的 PopupWindow.findDropDownPosition 对 hgrav == RIGHT 会做
            // `x -= width - anchorWidth`，也就是**弹窗右缘对齐锚点右缘**。用 END 而不是 RIGHT：
            // 它按 anchor.getLayoutDirection() 解析，RTL 下同样正确。
            setDropDownGravity(Gravity.END)
            // 只留**退出**动画：进入动画由我们自己驱动（见 [animateIn]），方向才确定。
            setAnimationStyle(R.style.ChoicePopupExitAnimation)
            // 窗口底全透明，圆角卡片改由**内容视图**自己画（见 [animateIn]）—— 窗口底不参与内容的
            // 缩放，留在那里就会出现"卡片先整个出现、只有文字在缩放"。透明底仍然满足
            // PopupWindow"有 background 才响应点外部关闭"的前提。
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 必须给**像素**宽度：WRAP_CONTENT 在 ListPopupWindow 里不等于"按内容"，而是"按锚点"。
            // 窗口底已透明、没有内边距可加，所以这里自己把卡片那点内缩算进去。
            setContentWidth(measureContentWidth(context, labels, current) + 2 * context.dip(CARD_INSET_DP))
        }
        popup.setAdapter(RowAdapter(context, labels, current))
        popup.setOnItemClickListener { _, _, which, _ ->
            anchor.settingRowTapFeedback()
            popup.dismiss()
            onPick(which)
        }
        val scrim = attachScrim(anchor)
        // 关闭路径不止一条（点选项、点外部、返回键），统一在这里撤掉压暗层。
        popup.setOnDismissListener { detachScrim(scrim) }
        popup.show()
        animateIn(popup.listView, anchor, context)
    }

    /**
     * 弹窗的出现：**从自己的右缘**展开（缩放 + 淡入）。
     *
     * ## 为什么自己驱动，而不是交给窗口动画
     *
     * 窗口动画那条路在真机上不可靠：appcompat 的 `Base.Widget.AppCompat.ListPopupWindow` 没有
     * `android:popupAnimationStyle`，`PopupWindow.computeAnimationResource()` 于是因为"这是
     * dropdown"退回框架的 `Animation.DropDownDown/Up`；我们虽然用 `setAnimationStyle` 覆盖过，
     * 真机上表现的支点却仍在**窗口左缘**（"从左边展开"）。方向是这里唯一要保证的东西，所以自己拿住。
     *
     * 支点定在右缘：弹窗用 `Gravity.END` 右对齐到了行尾，而那一行的「值 + ⌃⌄」就在行尾，
     * 从自己的右缘长出来才像"从标志那一侧展开"。
     *
     * 卡片画在**内容视图**上（见 [panelBackground] 的说明），所以它会跟着一起缩放。
     */
    private fun animateIn(listView: ListView?, anchor: View, context: Context) {
        if (listView == null) return
        // 卡片画在**内容视图**上，才会跟着一起缩放（窗口底不参与内容变换）。
        listView.background = panelBackground(context)
        listView.doOnLayout { view ->
            // 支点交给 Animation 自己解析，**不要**在这里读 view.width 当 pivotX：
            // ViewPropertyAnimator 的 pivotX 是像素值，而 show() 刚回来时内容还没排版，
            // 读到 0 就等于把支点放在**左缘** —— 那正是"从左边展开"的来源。
            // RELATIVE_TO_SELF 是由 Animation.initialize() 在开始绘制时按视图真实尺寸算的。
            view.startAnimation(contentEnterAnimation(context, isAboveAnchor(view, anchor)))
        }
    }

    /**
     * 内容的展开动画：从**右缘**长出来 + 淡入。
     *
     * 弹窗用 `Gravity.END` 右对齐到了行尾，而那一行的「值 + ⌃⌄」就在行尾，所以右缘支点
     * 看起来就是"从标志那一侧展开"。
     *
     * @param fromBottom 弹窗落在锚点**上方**时为 true —— 支点跟着挪到下缘，与原来
     *   `Animation.DropDownUp/Down` 那对"上下各自贴边"的方向一致（上下这一点用户是认可的）。
     */
    private fun contentEnterAnimation(context: Context, fromBottom: Boolean): Animation {
        val duration = fadeDuration(context)
        val scale = ScaleAnimation(
                SCALE_FROM, 1f, SCALE_FROM, 1f,
                Animation.RELATIVE_TO_SELF, 1f,                            // 支点 X = 右缘
                Animation.RELATIVE_TO_SELF, if (fromBottom) 1f else 0f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
        }
        return AnimationSet(false).apply {
            addAnimation(scale)
            addAnimation(AlphaAnimation(0f, 1f).apply { this.duration = duration })
        }
    }

    /** 弹窗落在锚点上方还是下方：决定支点贴哪条边。 */
    private fun isAboveAnchor(content: View, anchor: View): Boolean {
        val contentLocation = IntArray(2)
        val anchorLocation = IntArray(2)
        content.getLocationOnScreen(contentLocation)
        anchor.getLocationOnScreen(anchorLocation)
        return contentLocation[1] < anchorLocation[1]
    }

    /**
     * 弹窗内容（最宽的那一行）的像素宽度。
     *
     * 用**我们自己的行**去量，而不是自己算文字宽度：行里有内边距、标题、勾，以及它们之间的
     * 间距，这些都在 [RowAdapter.rowView] 里，抄一份到这里就一定会两处不同步。
     */
    private fun measureContentWidth(context: Context, labels: List<String>, current: Int): Int {
        val adapter = RowAdapter(context, labels, current)
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var widest = 0
        for (position in labels.indices) {
            val row = adapter.getView(position, null, null)
            row.measure(spec, spec)
            widest = maxOf(widest, row.measuredWidth)
        }
        return widest
    }

    /**
     * 在 Activity 内容之上压一层暗色，作为弹窗的"背景变暗"。
     *
     * 加在 `android.R.id.content` 上：弹窗是**独立窗口**，永远在这层之上，所以压暗只作用于
     * 底下的内容，弹窗自己保持全亮 —— 这正是 dim 的观感。
     *
     * 这一层**不可点、不可聚焦、不参与无障碍**：绝不抢触摸。外面点击的关闭仍旧由
     * `ListPopupWindow` 的 outside-touch 处理，加这层前后行为完全一致。
     *
     * @return 加进去的那一层；拿不到内容容器时返回 null（此时只是没有压暗，功能不受影响）。
     */
    private fun attachScrim(anchor: View): View? {
        val content = anchor.rootView.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return View(anchor.context).apply {
            setBackgroundColor(SCRIM_COLOR)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // 从全透明淡入，与弹窗的出现同时进行。
            alpha = 0f
            content.addView(this, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            animate().alpha(1f).setDuration(fadeDuration(context)).start()
        }
    }

    private fun detachScrim(scrim: View?) {
        if (scrim == null) return
        // 淡出**之后**再摘掉：立刻 removeView 会看到一次亮度突变，正是要修的那个观感。
        // withEndAction 在动画正常跑完时执行；动画没跑起来（视图已经被摘下）时那层也随
        // 视图树一起没了，不会在内容上留一层永久压暗。
        scrim.animate().alpha(0f).setDuration(fadeDuration(scrim.context)).withEndAction {
            (scrim.parent as? ViewGroup)?.removeView(scrim)
        }.start()
    }

    /**
     * 弹窗的底：圆角卡片 + 一点内边距，圆角才不会被行切成直角。
     *
     * 内边距用 [InsetDrawable] 而不是 `GradientDrawable.setPadding` —— 后者 API 29 才有
     * （`api-versions.xml` 记 `since=29`），而本工程 `minSdk 21`。Android 5–9 上直接调用
     * 会在点「显示主题」/作息时间表那行时 `NoSuchMethodError` 崩掉。[InsetDrawable] 自 API 1
     * 就存在，且 `ListPopupWindow` 同样会向它要 padding。
     */
    @VisibleForTesting
    internal fun panelBackground(context: Context): Drawable {
        val inset = context.dip(CARD_INSET_DP)
        return InsetDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = context.resources.getDimensionPixelSize(R.dimen.group_radius).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.card_background))
                },
                inset, inset, inset, inset)
    }

    /** 每行：标题在左，**当前项**右侧一枚勾、且标题用强调色（其它项不画标记）。 */
    private class RowAdapter(
            private val context: Context,
            private val labels: List<String>,
            private val current: Int) : BaseAdapter() {

        private val accent = ContextCompat.getColor(context, R.color.colorPrimary)

        override fun getCount(): Int = labels.size

        override fun getItem(position: Int): Any = labels[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayoutCompat ?: rowView()
            val label = row.getChildAt(0) as AppCompatTextView
            val tick = row.getChildAt(1) as AppCompatTextView
            val selected = position == current
            label.text = labels[position]
            // 选中项：强调色文字 + 一枚勾；未选中项不画任何标记 —— 参考图里就是这样，
            // 每一项都画一个空方框反而让人以为"多选"。
            label.setTextColor(if (selected) accent else SettingRowStyle.titleColor(context))
            tick.visibility = if (selected) View.VISIBLE else View.GONE
            return row
        }

        private fun rowView(): LinearLayoutCompat {
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                    R.attr.selectableItemBackground, outValue, true)
            return LinearLayoutCompat(context).apply {
                orientation = LinearLayoutCompat.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dip(48)
                setPadding(context.dip(16), 0, context.dip(16), 0)
                setBackgroundResource(outValue.resourceId)
                // 标题不用 weight：整行按文字宽度量，弹窗宽度就跟着内容（参考图的弹窗比行窄）。
                addView(AppCompatTextView(context).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    maxLines = 1
                }, LinearLayoutCompat.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(AppCompatTextView(context).apply {
                    text = "✓"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(accent)
                    includeFontPadding = false
                }, LinearLayoutCompat.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = context.dip(16)
                })
            }
        }
    }
}
