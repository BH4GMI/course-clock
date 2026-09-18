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
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.SettingType
import courseclock.timetable.settings.items.VerticalItem
import courseclock.timetable.utils.ViewUtils
import splitties.dimensions.dip

/**
 * 长说明行：标题 + 一段可以换行的解释，整行都能点（例如「主题颜色」点开取色器）。
 *
 * 它和开关行的区别只在"右侧没有控件"，所以排版口径（15sp 标题 / 12sp 解释 / 卡片内 16dp）
 * 完全共用，不再各写一套。
 */
class VerticalItemProvider : BaseItemProvider<BaseSettingItem>() {

    override val itemViewType: Int
        get() = SettingType.VERTICAL

    override val layoutId: Int
        get() = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val context = parent.context
        val root = SettingRowStyle.cardRoot(context, PlaceholderRow.item).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(SettingRowStyle.cardInset(context), context.dip(12),
                    SettingRowStyle.cardInset(context), context.dip(12))
        }

        root.addView(AppCompatTextView(context).apply {
            id = R.id.anko_text_view
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(SettingRowStyle.titleColor(context))
        }, LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT))

        root.addView(AppCompatTextView(context).apply {
            id = R.id.anko_tv_description
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(SettingRowStyle.secondaryColor(context))
        }, LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dip(4) })

        return BaseViewHolder(root)
    }

    override fun convert(helper: BaseViewHolder, data: BaseSettingItem?) {
        if (data == null) return
        val item = data as VerticalItem
        val context = helper.itemView.context
        val root = helper.itemView as LinearLayoutCompat

        // 空标题的行（列表末尾的留白占位）不该留下一块空标题的高度。
        val title = helper.getView<AppCompatTextView>(R.id.anko_text_view)
        title.visibility = if (item.title.isEmpty()) View.GONE else View.VISIBLE
        title.text = item.title

        val desc = helper.getView<AppCompatTextView>(R.id.anko_tv_description)
        if (item.description.isEmpty()) {
            desc.visibility = View.GONE
        } else {
            desc.visibility = View.VISIBLE
            desc.text = if (item.isSpanned) ViewUtils.getHtmlSpannedString(item.description)
            else item.description
        }
        root.background = RowCardDecoration.background(context, item)
        SettingRowStyle.applyRowAlpha(root, item)
        // 行高下限：与其它 provider 同一套口径（设计稿单行 56dp、带解释 64dp）。
        // 这种"标题 + 一段说明"的行，说明常常只占一行，不设下限就会缩到 40dp 上下 ——
        // 一整列里那一行会明显矮一截。空说明的占位行不设下限：它本来就是留白用的。
        root.minimumHeight = if (item.description.isEmpty()) 0
        else context.resources.getDimensionPixelSize(R.dimen.setting_row_two_line_height)
    }

    private object PlaceholderRow {
        val item = VerticalItem("placeholder", "")
    }
}
