package courseclock.timetable.settings.items

import courseclock.timetable.settings.RowCardPosition

/**
 * 一行的可用状态。
 *
 * 放在基类而不是各个 item 里：设计稿要求"关掉的开关，下面依赖它的行变灰且不可点"，
 * 而"谁控制谁"是**列表级**的事实（由构造列表的人一次算清，见 settings/SettingsList.kt），
 * 不是每一行自己知道的事。行只照着 [rowState] 画，不再各自判断。
 */
enum class SettingRowState {
    /** 正常：可以点，文字用正文色。 */
    ENABLED,

    /** 被上游开关关掉了：变灰、不可点。 */
    DISABLED_BY_DEPENDENCY
}

/**
 * 列表里的一行。
 *
 * [rowState] 与 [rowPosition] 都有默认值，所以只关心标题的调用点（例如「课表设置」页）
 * 一个字都不用改；分组卡片那套排版则由 [courseclock.timetable.settings.RowCardDecoration]
 * 在整份列表上标注一次。
 */
abstract class BaseSettingItem(
        val title: String,
        val keyWords: List<String>?,
        var rowState: SettingRowState = SettingRowState.ENABLED) {

    abstract fun getType(): Int

    /**
     * 这一行在所属分组里的位置（首/中/尾/独立），由 [courseclock.timetable.settings.RowCardDecoration]
     * 标注。放在基类而不是 provider 里算，原因见那个类的文档。
     *
     * **这是派生状态，不是行自己的属性**：data class 子类的 `copy()` 只带构造参数，用它替换列表里
     * 的一行会把这里悄悄重置成默认的 [RowCardPosition.SINGLE]（四角全圆角）—— 真机上表现为
     * "这一行变成了一张凭空冒出来的独立卡片"。要改某一行的字段请**原地改**，或者改完之后重跑一次
     * [courseclock.timetable.settings.RowCardDecoration.apply]。
     */
    var rowPosition: RowCardPosition = RowCardPosition.SINGLE

    /** 这一行上方要留出的组间距（只有分组标题 > 0）。同样由装饰器标注。 */
    var topGap: Int = 0

    /** 这一行现在能不能点。给点击分发和 provider 共用，避免两处各写一遍判断。 */
    val isRowEnabled: Boolean
        get() = rowState == SettingRowState.ENABLED
}

object SettingType {
    const val CATEGORY = 0
    const val HORIZON = 1
    const val SEEKBAR = 2
    const val SWITCH = 3
    const val VERTICAL = 4
}
