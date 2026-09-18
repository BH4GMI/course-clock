package courseclock.timetable.settings.provider

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import com.chad.library.adapter.base.provider.BaseItemProvider
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import courseclock.timetable.R
import courseclock.timetable.settings.ArrowView
import courseclock.timetable.settings.RowCardDecoration
import courseclock.timetable.settings.SettingRowStyle
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.HorizontalItem
import courseclock.timetable.settings.items.SettingRowArrow
import courseclock.timetable.settings.items.SettingType
import splitties.dimensions.dip

/**
 * 有具体值的一行：标题（+可选解释）在左，右侧「当前值 + 箭头」。
 *
 * 箭头分两种（见 [SettingRowArrow]）：「>」= 进新页面，「⌃⌄」= 就地选择。
 * 箭头不是装饰 —— 用户看一眼就知道点下去会不会离开当前页，所以它跟着 item 的
 * **真实行为**走，由调用点在构造列表时标注。
 */
class HorizontalItemProvider : BaseItemProvider<BaseSettingItem>() {

    override val itemViewType: Int
        get() = SettingType.HORIZON

    override val layoutId: Int
        get() = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val context = parent.context
        val gap = context.dip(4)
        // 外框（卡片 + 水波纹 + 左右 16dp）统一口径；行高在 convert 里按有没有解释选 token。
        val root = SettingRowStyle.cardRoot(context, PlaceholderRow.item,
                LinearLayoutCompat.HORIZONTAL).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        val textColumn = LinearLayoutCompat(context).apply {
            orientation = LinearLayoutCompat.VERTICAL
            addView(AppCompatTextView(context).apply {
                id = R.id.anko_text_view
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(SettingRowStyle.titleColor(context))
            }, LinearLayoutCompat.LayoutParams(
                    LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                    LinearLayoutCompat.LayoutParams.WRAP_CONTENT))
            addView(AppCompatTextView(context).apply {
                id = R.id.anko_tv_description
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(SettingRowStyle.secondaryColor(context))
                visibility = View.GONE
            }, LinearLayoutCompat.LayoutParams(
                    LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                    LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        }
        // 标题列按内容取宽（它是这一行的身份，不该被挤）；被挤的是右边的"值"。
        root.addView(textColumn, LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT,
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = context.dip(12)
        })

        // 值占剩下的宽度：右对齐、放不下就末尾省略（长值「上课时间、周数、格子样式」不该把标题
        // 挤成两行 —— 真机上出现过一个标题被挤断成「设置当前课 / 表」的排版）。
        root.addView(SettingRowStyle.valueText(context), LinearLayoutCompat.LayoutParams(0,
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
        })

        // 箭头始终占位、按需显示：可见性切换不会让复用中的行重新排一遍层级。
        root.addView(SettingRowStyle.arrowView(context, SettingRowArrow.NAVIGATE).apply {
            layoutParams = LinearLayoutCompat.LayoutParams(context.dip(20), context.dip(20)).apply {
                marginStart = context.dip(6)
            }
        })
        return BaseViewHolder(root)
    }

    override fun convert(helper: BaseViewHolder, data: BaseSettingItem?) {
        if (data == null) return
        val item = data as HorizontalItem
        val context = helper.itemView.context
        val root = helper.itemView as LinearLayoutCompat

        helper.setText(R.id.anko_text_view, item.title)
        helper.setText(R.id.anko_tv_value, item.value)
        if (item.desc.isEmpty()) {
            helper.setGone(R.id.anko_tv_description, true)
        } else {
            helper.setText(R.id.anko_tv_description, item.desc)
            helper.setGone(R.id.anko_tv_description, false)
        }
        // 单行 56dp、带解释 64dp。
        root.minimumHeight = context.resources.getDimensionPixelSize(
                if (item.desc.isEmpty()) R.dimen.setting_row_min_height
                else R.dimen.setting_row_two_line_height)
        // 卡片哪两个角是圆的取决于这一行在组里的位置 —— 列表级的事实，由装饰器标注在 item 上。
        root.background = RowCardDecoration.background(context, item)
        SettingRowStyle.applyRowAlpha(root, item)

        val arrow = helper.getView<ArrowView>(R.id.anko_iv_arrow)
        arrow.visibility = if (item.arrow == null) View.GONE else View.VISIBLE
        // 箭头种类变了只改属性、重画一次，**不换视图**。
        //
        // 原来这里写着 `root.removeViewAt(index)` + `addView(新的 ArrowView)`，真机上崩在
        // ViewGroup.removeViewInternal：RecyclerView 预取（GapWorker）绑定 ViewHolder 时，
        // 容器的焦点簿记（mFocused）可能还没有值，removeViewAt 内部对它调 unFocus → 空指针。
        // 绑定阶段增删子视图没有安全的写法，所以根因修法是让"换箭头"不再需要换视图，
        // 而不是在 removeViewAt 外面加判空/try-catch（那只是把崩溃藏起来）。
        if (item.arrow != null) {
            arrow.arrow = item.arrow
        }
    }

    private object PlaceholderRow {
        val item = HorizontalItem("placeholder", "", "")
    }
}
