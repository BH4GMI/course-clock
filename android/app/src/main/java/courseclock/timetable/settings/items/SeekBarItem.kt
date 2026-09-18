package courseclock.timetable.settings.items

/** 数值行：[id] 是身份、[name] 是文案，理由见 [SwitchItem]。 */
data class SeekBarItem(
        val id: String,
        val name: String,
        var valueInt: Int,
        val min: Int,
        var max: Int,
        val unit: String,
        val prefix: String = "",
        val keys: List<String>? = null) : BaseSettingItem(name, keys) {
    override fun getType(): Int {
        return SettingType.SEEKBAR
    }
}
