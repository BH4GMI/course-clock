package courseclock.timetable.settings.items

/**
 * 带一个具体值的行（标题 + 当前值 + 箭头）。
 *
 * [id] 是身份、[name] 是文案，理由见 [SwitchItem]。一个坑值得记住：这枚 `data class` 的
 * 自动 `equals` 会把 [value] 算进去，而列表刷新时要按身份找行 —— 所以找行请用 [id]，
 * 不要用 `==` 比两个 item。
 */
data class HorizontalItem(
        val id: String,
        val name: String,
        var value: String,
        val desc: String = "",
        val keys: List<String>? = null,
        /** 右侧箭头的种类；给 null 表示这一行纯展示、点下去没有任何跳转。 */
        val arrow: SettingRowArrow? = SettingRowArrow.NAVIGATE) : BaseSettingItem(name, keys) {
    override fun getType(): Int {
        return SettingType.HORIZON
    }
}
