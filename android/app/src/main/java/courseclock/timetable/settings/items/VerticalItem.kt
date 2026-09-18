package courseclock.timetable.settings.items

/** 长说明行：[id] 是身份、[name] 是文案，理由见 [SwitchItem]。 */
data class VerticalItem(
        val id: String,
        val name: String,
        var description: String = "",
        val isSpanned: Boolean = false,
        val keys: List<String>? = null) : BaseSettingItem(name, keys) {
    override fun getType(): Int {
        return SettingType.VERTICAL
    }
}
