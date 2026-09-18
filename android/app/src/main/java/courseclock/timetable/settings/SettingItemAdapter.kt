package courseclock.timetable.settings

import com.chad.library.adapter.base.BaseProviderMultiAdapter
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.provider.*

class SettingItemAdapter : BaseProviderMultiAdapter<BaseSettingItem>() {

    init {
        addItemProvider(CategoryItemProvider())
        addItemProvider(HorizontalItemProvider())
        addItemProvider(SeekBarItemProvider())
        addItemProvider(SwitchItemProvider())
        addItemProvider(VerticalItemProvider())
    }

    override fun getItemType(data: List<BaseSettingItem>, position: Int): Int {
        return data[position].getType()
    }

}