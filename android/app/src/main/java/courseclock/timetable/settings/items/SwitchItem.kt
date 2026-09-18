package courseclock.timetable.settings.items

/**
 * 开关类的行。
 *
 * [id] 是这一行的**身份**（取值见 `settings/SettingRowId.kt`），[name] 才是给用户看的文案。
 * 两者分开的原因见 `SettingRowId` 的说明：文案会为了说人话反复改，而 `when (item.title)`
 * 曾把它同时当成标识符，改一次措辞就得回去改一处 `when`，漏一处就是"点了没反应"。
 * 同一节里「上课提醒」既可能是分组标题、又是总闸、还是子开关，靠标题区分本来就已经勉强。
 */
data class SwitchItem(
        val id: String,
        val name: String,
        var checked: Boolean,
        var desc: String = "",
        val keys: List<String>? = null) : BaseSettingItem(name, keys) {
    override fun getType(): Int {
        return SettingType.SWITCH
    }

    /**
     * 整行的无障碍描述。
     *
     * 开关自己不可聚焦（见 `SwitchView` 的类文档），所以"现在是开还是关"必须念在**行**上。
     * 这里集中一处：绑定时（provider 的 `convert`）和用户点击时都要用 —— 点击那条路不再整行
     * 重绑（见 `SettingsActivity.setSwitch` 的说明），两处各写一遍就会在点完之后留下过期的朗读。
     */
    val rowContentDescription: String
        get() = "$title，${if (checked) "已开启" else "已关闭"}"
}
