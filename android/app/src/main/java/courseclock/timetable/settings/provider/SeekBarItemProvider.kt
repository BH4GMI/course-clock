package courseclock.timetable.settings.provider

import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import com.chad.library.adapter.base.provider.BaseItemProvider
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import courseclock.timetable.R
import courseclock.timetable.settings.RowCardDecoration
import courseclock.timetable.settings.SettingRowStyle
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.SeekBarItem
import courseclock.timetable.settings.items.SettingRowArrow
import courseclock.timetable.settings.items.SettingType
import splitties.dimensions.dip

/**
 * 数值行：标题在左，右侧是"当前数值 + 单位 + ⌃⌄"。
 *
 * 这一行**不是**真的 SeekBar（点一下弹输入框改数值），所以箭头用 [SettingRowArrow.SELECT]：
 * 就地改，不会跳页。按卡片样式重排之后就只剩"给数值一个和别的行一致的落点"这件事了。
 */
class SeekBarItemProvider : BaseItemProvider<BaseSettingItem>() {

    override val itemViewType: Int
        get() = SettingType.SEEKBAR

    override val layoutId: Int
        get() = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val context = parent.context
        val root = SettingRowStyle.cardRoot(context, PlaceholderRow.item,
                LinearLayoutCompat.HORIZONTAL).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.resources.getDimensionPixelSize(R.dimen.setting_row_min_height)
        }

        root.addView(AppCompatTextView(context).apply {
            id = R.id.anko_text_view
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(SettingRowStyle.titleColor(context))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayoutCompat.LayoutParams(0,
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
            marginEnd = context.dip(12)
        })

        root.addView(SettingRowStyle.secondaryText(context, R.id.anko_tv_prefix, 14f))
        root.addView(SettingRowStyle.valueText(context))
        root.addView(SettingRowStyle.secondaryText(context, R.id.anko_tv_unit, 14f).apply {
            layoutParams = LinearLayoutCompat.LayoutParams(
                    LinearLayoutCompat.LayoutParams.WRAP_CONTENT,
                    LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dip(2) }
        })
        root.addView(SettingRowStyle.arrowView(context, SettingRowArrow.SELECT).apply {
            layoutParams = LinearLayoutCompat.LayoutParams(context.dip(20), context.dip(20)).apply {
                marginStart = context.dip(6)
            }
        })
        return BaseViewHolder(root)
    }

    override fun convert(helper: BaseViewHolder, data: BaseSettingItem?) {
        if (data == null) return
        val item = data as SeekBarItem
        val context = helper.itemView.context
        helper.setText(R.id.anko_text_view, item.title)
        helper.itemView.background = RowCardDecoration.background(context, item)
        SettingRowStyle.applyRowAlpha(helper.itemView, item)

        // 越界的存量值不显示成一个假数字，明确说"无效值"（和改动前同一套判据）。
        if (item.valueInt > item.max || item.valueInt < item.min) {
            helper.setText(R.id.anko_tv_value, "无效值")
            helper.setGone(R.id.anko_tv_unit, true)
            helper.setGone(R.id.anko_tv_prefix, true)
            return
        }
        helper.setText(R.id.anko_tv_value, "${item.valueInt}")
        helper.setGone(R.id.anko_tv_unit, false)
        helper.setText(R.id.anko_tv_unit, item.unit)
        if (item.prefix.isEmpty()) {
            helper.setGone(R.id.anko_tv_prefix, true)
        } else {
            helper.setGone(R.id.anko_tv_prefix, false)
            helper.setText(R.id.anko_tv_prefix, item.prefix)
        }
    }

    private object PlaceholderRow {
        val item = SeekBarItem("placeholder", "", 0, 0, 0, "")
    }
}
