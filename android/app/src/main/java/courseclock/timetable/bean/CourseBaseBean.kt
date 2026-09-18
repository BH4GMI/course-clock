package courseclock.timetable.bean

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(foreignKeys = [(
        ForeignKey(entity = TableBean::class,
                parentColumns = ["id"],
                childColumns = ["tableId"],
                onUpdate = ForeignKey.CASCADE,
                onDelete = ForeignKey.CASCADE
        ))],
        primaryKeys = ["id", "tableId"],
        indices = [Index(value = ["tableId"], unique = false)])
data class CourseBaseBean(
        var id: Int,
        var courseName: String,
        var color: String,
        var tableId: Int,
        /** 免听（EAMS `notAttendLessonIds`）。免听课不排提醒，渲染时压在正常课下面。 */
        var notAttend: Boolean = false,
        /** 重修（EAMS `lessonId2Retake`）。只影响免听课角标文案，不改变免听语义。 */
        var retake: Boolean = false
) : Parcelable