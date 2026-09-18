package courseclock.timetable.bean

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index

/**
 * 一次上课安排。
 *
 * [timeGroup] 指向 [TimeDetailBean.timeGroup]：同一次安排落在哪个作息分组，
 * 就按哪一组的时间显示起止。空串是默认分组。
 *
 * 声明为非空，且只允许 '' 这一种"没有分组"的表示：Gson 反序列化缺失字段
 * 会绕过构造器写 null，所以入库前由 ImportViewModel 统一归一化成空串。
 */
@Entity(foreignKeys = [(
        ForeignKey(entity = CourseBaseBean::class,
                parentColumns = ["id", "tableId"],
                childColumns = ["id", "tableId"],
                onUpdate = CASCADE,
                onDelete = CASCADE
        ))],
        primaryKeys = ["day", "startNode", "startWeek", "type", "tableId", "id"],
        indices = [Index(value = ["id", "tableId"], unique = false)])

data class CourseDetailBean(
        var id: Int,
        var day: Int,
        var room: String?,
        var teacher: String?,
        var startNode: Int,
        var step: Int,
        var startWeek: Int,
        var endWeek: Int,
        var type: Int,
        var tableId: Int,
        var timeGroup: String = ""
)
