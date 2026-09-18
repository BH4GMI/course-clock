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
import courseclock.timetable.settings.RowCardDecoration
import courseclock.timetable.settings.SettingRowStyle
import courseclock.timetable.settings.SwitchView
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.SettingType
import courseclock.timetable.settings.items.SwitchItem
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.getPrefer
import splitties.resources.color

/**
 * 开关行：标题（+可选解释）在左，右侧一枚 46×28 的开关（[SwitchView] 自绘）。
 *
 * 开关从"染色 CheckBox"换成胶囊开关，根因是 CheckBox 的勾选框无论怎么染色都是一个小方块，
 * 与设计稿的胶囊开关既不同形也不同义（勾选框读起来是"多选"）。中途用过 `SwitchCompat`，
 * 但它会把内部盒子在视图里居中、把 46dp 的视图算成负偏移而裁掉左边 —— 原因与取舍见
 * [SwitchView] 的类文档。尺寸由 `switch_width` / `switch_height` / `switch_thumb` 三个 token
 * 决定，开关的几何从此是可以断言的常量，不再随主题的 switchMinWidth 漂移。
 */
class SwitchItemProvider : BaseItemProvider<BaseSettingItem>() {

    override val itemViewType: Int
        get() = SettingType.SWITCH

    override val layoutId: Int
        get() = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val context = parent.context
        val width = context.resources.getDimensionPixelSize(R.dimen.switch_width)
        val height = context.resources.getDimensionPixelSize(R.dimen.switch_height)

        // 外框（卡片 + 水波纹 + 左右 16dp）由统一口径给出，这里只定行高。
        // 高度用 minHeight 表达：系统字号调大时行应该长高，而不是把解释裁掉。
        val root = SettingRowStyle.cardRoot(context, PlaceholderRow.item,
                LinearLayoutCompat.HORIZONTAL).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.resources.getDimensionPixelSize(R.dimen.setting_row_two_line_height)
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
                    LinearLayoutCompat.LayoutParams.WRAP_CONTENT))
        }
        root.addView(textColumn, LinearLayoutCompat.LayoutParams(0,
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
            marginEnd = context.resources.getDimensionPixelSize(R.dimen.setting_row_inset)
        })

        root.addView(SwitchView(context).apply {
            id = R.id.anko_switch
            // 主题色由用户选（「主题颜色」那一行），开关的轨道跟着它走。
            onColor = context.getPrefer().getInt(Const.KEY_THEME_COLOR, color(R.color.colorAccent))
            // 点击由整行接收（见 convert 的说明）：自己可点会让一次点击触发两遍。
            isClickable = false
            isFocusable = false
        }, LinearLayoutCompat.LayoutParams(width, height))

        return BaseViewHolder(root)
    }

    override fun convert(helper: BaseViewHolder, data: BaseSettingItem?) {
        if (data == null) return
        val item = data as SwitchItem
        val context = helper.itemView.context
        helper.setText(R.id.anko_text_view, item.title)
        if (item.desc.isEmpty()) {
            helper.setGone(R.id.anko_tv_description, true)
        } else {
            helper.setText(R.id.anko_tv_description, item.desc)
            helper.setGone(R.id.anko_tv_description, false)
        }
        // 单行 56dp、带解释 64dp：行高在这里按"有没有第二行"选 token。
        helper.itemView.minimumHeight = context.resources.getDimensionPixelSize(
                if (item.desc.isEmpty()) R.dimen.setting_row_min_height
                else R.dimen.setting_row_two_line_height)
        // 卡片哪两个角是圆的取决于这一行在组里的位置 —— 列表级的事实，由装饰器标注在 item 上。
        helper.itemView.background = RowCardDecoration.background(context, item)
        SettingRowStyle.applyRowAlpha(helper.itemView, item)

        helper.getView<SwitchView>(R.id.anko_switch).apply {
            // 状态同步不播动画：列表复用换行时，动画会让开关短暂停在上一行的位置。
            isChecked = item.checked
            setRowEnabled(item.isRowEnabled)
        }
        // 整行才是点击目标，所以"这一行现在是什么状态"也得挂在行上，否则读屏只会念一遍标题、
        // 读不出开关是开是关（开关自己不可聚焦，见 SwitchView）。
        helper.itemView.contentDescription = item.rowContentDescription
    }

    /**
     * [onCreateViewHolder] 拿不到具体 item，但外框（背景 drawable）需要一个 item 才能建。
     * 用一枚默认状态的占位行即可 —— 真正的背景在 [convert] 里会按真实 item 重设。
     */
    private object PlaceholderRow {
        val item = SwitchItem("placeholder", "", false)
    }
}
