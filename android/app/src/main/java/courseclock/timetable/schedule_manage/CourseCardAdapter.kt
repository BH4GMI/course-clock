package courseclock.timetable.schedule_manage

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.card.MaterialCardView
import courseclock.timetable.R
import courseclock.timetable.utils.CourseUtils
import courseclock.timetable.utils.ViewUtils

/** 课程管理页一张卡片的展示数据：一门基础课 + 它最早的一个时间段。 */
data class CourseCardItem(
        val id: Int,
        val tableId: Int,
        val name: String,
        val color: String,
        val teacher: String?,
        val room: String?,
        val day: Int,
        val startNode: Int,
        val step: Int
) {
    /** 周几第几节，与添加课程页同一口径（getDayStr + 起止节）。 */
    fun timeText(): String = "${CourseUtils.getDayStr(day)} 第$startNode - ${startNode + step - 1}节"

    /** 教师 · 教室；两个都空就说明还没填。 */
    fun metaText(): String {
        val parts = listOfNotNull(teacher?.takeIf { it.isNotBlank() },
                room?.takeIf { it.isNotBlank() })
        return if (parts.isEmpty()) "未填写教师和地点" else parts.joinToString(" · ")
    }
}

/**
 * 课程管理两列卡片（设计稿 8）。
 *
 * 数据末尾**永远**跟一个「添加课程」虚线格：视图类型 viewType 加一档，而不是往
 * BaseQuickAdapter 上塞 header/footer——网格里的 footer 不吃 span，会把虚线格挤成半格。
 * 点击回调三个：点课程（编辑）、长按课程（删除）、点虚线格或加号（新增）。
 */
class CourseCardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_COURSE = 0
        private const val TYPE_ADD = 1
    }

    private val items = mutableListOf<CourseCardItem>()

    var onCourseClick: ((CourseCardItem) -> Unit)? = null
    var onCourseLongClick: ((CourseCardItem) -> Unit)? = null
    var onAddClick: (() -> Unit)? = null

    fun submit(list: List<CourseCardItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size + 1 // 末尾一格固定是「添加课程」

    override fun getItemViewType(position: Int): Int =
            if (position == items.size) TYPE_ADD else TYPE_COURSE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ADD) {
            AddHolder(inflater.inflate(R.layout.item_course_add_cell, parent, false))
        } else {
            CourseHolder(inflater.inflate(R.layout.item_course_list, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AddHolder -> holder.cell.setOnClickListener { onAddClick?.invoke() }
            is CourseHolder -> {
                val item = items[position]
                val context = holder.itemView.context
                // 卡片底色 = 这门课的颜色铺 12%（设计稿 fill-opacity .1），和课表色块同源：
                // 取色走全工程唯一的课程颜色出口 parseCourseColor，畸形值退回调色板同位色。
                val palette = context.resources.getIntArray(R.array.customizedColors)
                val courseColor = ViewUtils.parseCourseColor(item.color, palette[item.id % palette.size])
                holder.card.setCardBackgroundColor(ColorUtils.blendARGB(
                        ContextCompat.getColor(context, R.color.card_background), courseColor, 0.12f))
                holder.tvName.text = item.name
                holder.tvMeta.text = item.metaText()
                holder.tvTime.text = item.timeText()
                holder.tvTime.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                holder.itemView.setOnClickListener { onCourseClick?.invoke(item) }
                holder.itemView.setOnLongClickListener {
                    onCourseLongClick?.invoke(item)
                    true
                }
            }
        }
    }

    class CourseHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cv_course)
        val tvName: AppCompatTextView = view.findViewById(R.id.tv_course_name)
        val tvMeta: AppCompatTextView = view.findViewById(R.id.tv_course_meta)
        val tvTime: AppCompatTextView = view.findViewById(R.id.tv_course_time)
    }

    class AddHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cell: View = view.findViewById(R.id.cell_add)
    }
}
