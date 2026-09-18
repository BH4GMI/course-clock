package courseclock.timetable.settings

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import courseclock.timetable.R
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.SettingRowArrow
import splitties.resources.styledColor

/**
 * 设置列表五类行**共用**的排版口径。
 *
 * 为什么要有这一份：这些值（标题 15sp、解释 12sp、行高 56/64dp、卡片内左右 16dp）
 * 设计稿逐条定死了，散在 5 个 provider 里各写一遍的话，下一次改字号就会改出三种行高。
 * 汇总在这里之后，provider 只负责"这一行有哪几块、怎么排"，不再各自决定字号与颜色。
 *
 * 取色一律走主题属性（`?attr/colorOnBackground` / 固定的 `text_secondary`），
 * 和 `BaseListActivity` 顶部标题栏同一套口径，深色模式不用再写第二份。
 *
 * **字重**：行标题一律常规字重。设计稿的 `.ink` 只定颜色，凡是要加粗的地方都显式写了
 * `font-weight`（标题栏「设置」= 800、星期与日期 = 700），设置页的行标题没有 —— 之前
 * 四个 provider 各自写成 `DEFAULT_BOLD`，同一页里就成了"设计稿没有的粗体"。
 */
object SettingRowStyle {

    /** 卡片内左右内边距（设计稿 setting_row_inset）：文字、开关、箭头离**卡片边**这么远。 */
    fun cardInset(context: Context): Int =
            context.resources.getDimensionPixelSize(R.dimen.setting_row_inset)

    /** 卡片离屏幕左右的距离（设计稿 page_inset）。它是行的 margin，不是背景 drawable 的内缩。 */
    fun pageInset(context: Context): Int =
            context.resources.getDimensionPixelSize(R.dimen.page_inset)

    /** 行标题色 = 正文字色，跟主题走。 */
    fun titleColor(context: Context): Int = context.styledColor(R.attr.colorOnBackground)

    /** 解释、当前值、分组标题共用的次文字色。 */
    fun secondaryColor(context: Context): Int =
            ContextCompat.getColor(context, R.color.text_secondary)

    /** 不可用的行整行压暗，和开关的轨道/圆点用同一个系数（见 [ROW_DISABLED_ALPHA]）。 */
    fun applyRowAlpha(view: View, item: BaseSettingItem) {
        view.alpha = if (item.isRowEnabled) 1f else ROW_DISABLED_ALPHA / 255f
    }

    /**
     * 一行的外框（卡片 + 水波纹 + 左右内边距）。
     *
     * 行的**高度不在这里定**：高度由 provider 按"单行 56dp / 带解释 64dp"取 token，
     * 并且用 `minHeight` 表达 —— 用户把系统字号调大时行应该长高，而不是把文字裁掉。
     */
    fun cardRoot(context: Context, item: BaseSettingItem,
                 orientation: Int = LinearLayoutCompat.VERTICAL): LinearLayoutCompat =
            LinearLayoutCompat(context).apply {
                // 刻意不设 id：`anko_layout` 是**标题栏**那个容器的 id（见 BaseListActivity）。
                // 行根再挂同一个 id，同一棵视图树里就出现重复 id —— findViewById 会先命中外层
                // 列表里的行，"拿到标题栏"的代码从此拿错东西；列表挂到标题栏下面时，约束也会
                // 绑到列表自己身上。行不需要 id：外界一律用 ViewHolder 的 itemView 拿它。
                this.orientation = orientation
                background = RowCardDecoration.background(context, item)
                // 卡片离屏幕左右各 page_inset：用**行的 margin** 表达，不用背景 drawable 的内缩。
                // 背景 drawable 的 padding 会被 `setBackgroundDrawable` 写回视图内边距，
                // 两者挤在同一个属性上，内容就永远贴在卡片边上（见 RowCardDecoration.background）。
                layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = pageInset(context)
                    marginEnd = pageInset(context)
                }
                // 内容离卡片边各 setting_row_inset：这才是内边距该管的唯一一件事。
                setPadding(cardInset(context), 0, cardInset(context), 0)
            }

    /**
     * 当前值那行字（设计稿：14sp、次文字色、贴着卡片右侧）。
     *
     * 它是**可以被挤的那一个**：行里同时有标题、值和箭头时，标题是身份、值只是描述
     * （「上课时间、周数、格子样式」这种长串），放不下时该省略的是值 —— 所以这里定了右对齐 +
     * 末尾省略，宽度由调用方给 `weight = 1f`（见 HorizontalItemProvider）。
     */
    fun valueText(context: Context): AppCompatTextView =
            secondaryText(context, R.id.anko_tv_value, 14f).apply {
                setLines(1)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }

    /** 次文字色的普通说明/数值片段。 */
    fun secondaryText(context: Context, id: Int, sizeSp: Float): AppCompatTextView =
            AppCompatTextView(context).apply {
                this.id = id
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                setTextColor(secondaryColor(context))
                gravity = Gravity.CENTER_VERTICAL
            }

    /** 行右侧的箭头 View（20dp 见方，两种语义两种画法，见 [ArrowView]）。 */
    fun arrowView(context: Context, arrow: SettingRowArrow, id: Int = R.id.anko_iv_arrow): ArrowView =
            ArrowView(context, arrow, secondaryColor(context), 20).apply { this.id = id }

    /**
     * 不可用的行整行压到 38% 不透明度：够"看起来关着"，又还读得清写的是什么。
     *
     * 放在这里而不是某个控件文件里：行文字、开关、箭头都得用**同一个**系数，否则同一行里
     * 有的压暗有的没压，看起来像坏了。
     */
    internal const val ROW_DISABLED_ALPHA = 0x61

    /** 分组标题：卡片上方那行小字。 */
    fun categoryLabel(context: Context): AppCompatTextView =
            AppCompatTextView(context).apply {
                id = R.id.anko_text_view
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(secondaryColor(context))
                gravity = Gravity.CENTER_VERTICAL
                setLines(1)
            }
}

/**
 * 按下设置行时的触感反馈。语义与分级见 [courseclock.timetable.utils.Haptics] ——
 * 设置行是"一次普通点击"，所以是 [Haptics.tap]。
 *
 * 保留这个扩展而不是让调用方直接写 `Haptics.tap(view)`：设置列表有三处分发点
 * （设置页、课表设置页、就地选择弹窗），它们表达的是同一件事"这一行被按下了"，
 * 由这里统一口径，换档位时只改一处。
 */
internal fun View.settingRowTapFeedback() = courseclock.timetable.utils.Haptics.tap(this)
