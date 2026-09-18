package courseclock.timetable.settings.items

/**
 * 行右侧箭头的两种意思（设计说明明确要求，不让用户点进去才知道）：
 * 「>」= 进新页面，[SELECT] = 就地选择（弹选择器，页面不跳）。
 */
enum class SettingRowArrow {
    /** 「>」：点了会进入一个新的页面。 */
    NAVIGATE,

    /** 「⌃⌄」：点了在当前页弹选择器。 */
    SELECT
}
