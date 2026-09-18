package courseclock.timetable.bean

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TableSelectBean(
        var id: Int,
        var tableName: String,
        var background: String = "",
        var maxWeek: Int = 30,
        // 与 TableBean.nodes 的默认值保持一致：两个默认值分叉时，任何“部分列查询 + 默认值补齐”的
        // 写法都会静默拿到另一个节数
        var nodes: Int = 20,
        var type: Int = 0
) : Parcelable