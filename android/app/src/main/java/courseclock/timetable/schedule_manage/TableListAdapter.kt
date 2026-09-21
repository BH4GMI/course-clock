package courseclock.timetable.schedule_manage

import android.graphics.Color
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.TooltipCompat
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import courseclock.timetable.R
import courseclock.timetable.bean.TableSelectBean
import splitties.resources.styledColor

/**
 * 多课表卡片（设计稿 7）的绑定：色块写「表名首字 + 周数区间」，右侧「N 门课程 · 正在使用」，
 * 当前表一枚对勾、其他表一枚箭头。
 *
 * 色块底色的口径：当前表 = 实心主题色（文字 colorOnPrimary）；其他表 = 课程调色板按位置取色、
 * 20% 透明度打底 + 同色实色文字——库里没有「课表颜色」这一列，靠位置取色和课表格子的
 * 调色板同源（getCustomizedColor 那套），视觉上能和「正在用这套课表」对得上。
 */
class TableListAdapter(layoutResId: Int, data: MutableList<TableSelectBean>,
                       private val courseCounts: Map<Int, Int>,
                       private val onActions: (TableSelectBean) -> Unit) :
        BaseQuickAdapter<TableSelectBean, BaseViewHolder>(layoutResId, data) {

    override fun convert(helper: BaseViewHolder, item: TableSelectBean?) {
        if (item == null) return
        val name = item.tableName.ifEmpty { context.getString(R.string.table_default_name) }
        val isCurrent = item.type == 1

        val palette = context.resources.getIntArray(R.array.customizedColors)
        val accentColor = context.styledColor(R.attr.colorPrimary)
        val onAccentColor = context.styledColor(R.attr.colorOnPrimary)
        val paletteColor = palette[Math.floorMod(helper.layoutPosition, palette.size)]
        // 20% 透明度打底（设计稿 fill-opacity .2）：底色淡、字用同色实色，比白字对比度稳。
        val avatarColor = if (isCurrent) accentColor
        else Color.argb(51, Color.red(paletteColor), Color.green(paletteColor), Color.blue(paletteColor))

        val avatar = helper.getView<LinearLayoutCompat>(R.id.ll_avatar)
        avatar.background.mutate().setTint(avatarColor)
        val charText = helper.getView<AppCompatTextView>(R.id.tv_avatar_char)
        val weekText = helper.getView<AppCompatTextView>(R.id.tv_avatar_week)
        charText.text = name.firstOrNull()?.toString() ?: "表"
        weekText.text = "1-${item.maxWeek} 周"
        val charColor = if (isCurrent) onAccentColor else paletteColor
        charText.setTextColor(charColor)
        weekText.setTextColor(charColor)

        helper.setText(R.id.tv_table_name, name)
        val count = courseCounts[item.id] ?: 0
        helper.setText(R.id.tv_table_subtitle, when {
            isCurrent -> context.getString(R.string.table_current_subtitle, count)
            count == 0 -> context.getString(R.string.table_empty_subtitle)
            else -> context.getString(R.string.table_courses_subtitle, count)
        })
        helper.getView<androidx.appcompat.widget.AppCompatImageButton>(R.id.btn_table_actions).apply {
            contentDescription = context.getString(R.string.table_actions_description, name)
            TooltipCompat.setTooltipText(this, context.getString(R.string.table_actions))
            setOnClickListener { onActions(item) }
        }
    }
}
