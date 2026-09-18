package courseclock.timetable.settings

import androidx.appcompat.widget.AppCompatImageView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import courseclock.timetable.R
import courseclock.timetable.bean.TimeTableBean
import splitties.resources.styledColor

/**
 * 选择时间表的一行（设计稿 10）：名字 + 「第 1-N 节 · 每节约 X 分钟」摘要，
 * 当前用的一行打勾，其他行一枚箭头。
 *
 * [summaries] 来自 TimeSettingsViewModel.timeTableSummaries()：缺某张表的摘要
 * （还没设置作息）就显示占位文案，不阻塞列表渲染。
 */
class TimeTableAdapter(layoutResId: Int, data: MutableList<TimeTableBean>, var selectedId: Int,
                       var summaries: Map<Int, Pair<Int, Int>> = emptyMap()) :
        BaseQuickAdapter<TimeTableBean, BaseViewHolder>(layoutResId, data) {

    override fun convert(helper: BaseViewHolder, item: TimeTableBean?) {
        if (item == null) return
        val isSelected = item.id == selectedId
        helper.setText(R.id.tv_time_name, item.name)
        helper.setTextColor(R.id.tv_time_name, if (isSelected) context.styledColor(R.attr.colorPrimary)
        else context.styledColor(R.attr.colorOnBackground))
        val summary = summaries[item.id]
        helper.setText(R.id.tv_time_summary, if (summary != null) {
            context.getString(R.string.time_table_summary, summary.first, summary.second)
        } else {
            context.getString(R.string.time_table_summary_unset)
        })
        helper.setVisible(R.id.tv_time_check, isSelected)
        helper.setVisible(R.id.iv_time_chevron, !isSelected)
        helper.getView<AppCompatImageView>(R.id.iv_time_chevron).setColorFilter(
                context.styledColor(R.attr.colorOnBackground))
    }
}
