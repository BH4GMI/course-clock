package courseclock.timetable.bean

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.parcelize.Parcelize

/**
 * 时间表里的一格：某个节次在某个分组下的上下课时间。
 *
 * [timeGroup] 是不透明的分组键，含义由导入器决定，App 不解释它。它存在的意义是
 * 让「同一节次的时间可以随场景不同」成为模型的一部分——例如本校不同教学楼
 * 上午第 3~5 节错峰，三个区间互不相同且互相交叠。
 *
 * 默认分组是空串：手工设置的时间表、以及没有分组概念的课程都用它。
 *
 * 放在同一张时间表内部（而不是拆成多张时间表），是为了让导出/导入格式继续
 * 只承载一张时间表——见 ScheduleViewModel.exportData 与 ImportViewModel.importFromFile。
 */
@Parcelize
@Entity(foreignKeys = [(
        ForeignKey(entity = TimeTableBean::class,
                parentColumns = ["id"],
                childColumns = ["timeTable"],
                onUpdate = ForeignKey.CASCADE,
                onDelete = ForeignKey.CASCADE
        ))], primaryKeys = ["node", "timeTable", "timeGroup"],
        indices = [Index(value = ["timeTable"], unique = false)])
data class TimeDetailBean(
        val node: Int,
        var startTime: String,
        var endTime: String,
        var timeTable: Int = 1,
        var timeGroup: String = ""
) : Parcelable
