package courseclock.timetable.settings.items

/**
 * 分组标题（卡片上方那行小字）。
 *
 * 它不再带 `hasMarginTop` 这种"要不要留白"的开关：组与组之间的间距由
 * [courseclock.timetable.settings.RowCardDecoration] 在整份列表上算一次
 * （只有列表第一组顶上不留），把"要不要留"交给调用点判断，就迟早会出现
 * 某一组多留或少留 24dp 的情况。
 */
data class CategoryItem(val name: String) : BaseSettingItem(name, null) {
    override fun getType() = SettingType.CATEGORY
}
