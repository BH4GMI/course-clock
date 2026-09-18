package courseclock.timetable.settings.provider

import android.view.ViewGroup
import androidx.appcompat.widget.LinearLayoutCompat
import com.chad.library.adapter.base.provider.BaseItemProvider
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import courseclock.timetable.R
import courseclock.timetable.settings.SettingRowStyle
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.CategoryItem
import courseclock.timetable.settings.items.SettingType
import splitties.dimensions.dip

/**
 * 分组标题：卡片**上方**的一行小字（12.5sp、次文字色）。
 *
 * 曾经的实现是一条品牌色药丸标签。设计稿把它改成"卡片上方的组标题"，跟着改的原因是
 * 药丸会把分组标题变成一块比正文还抢眼的高饱和色块，而它承担的信息只是"下面这堆属于哪一类"。
 * 它同时承担了组与组之间的 [courseclock.timetable.settings.items.BaseSettingItem.topGap] 间距，
 * 不再需要额外的占位行。
 */
class CategoryItemProvider : BaseItemProvider<BaseSettingItem>() {

    override val itemViewType: Int
        get() = SettingType.CATEGORY

    override val layoutId: Int
        get() = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val view = LinearLayoutCompat(parent.context).apply {
            // 不设 id：`anko_layout` 是标题栏容器的 id，行根复用它会造成同一棵树里的重复 id
            // （见 SettingRowStyle.cardRoot 的说明）。
            orientation = LinearLayoutCompat.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            // 组标题在这里建**一次**。
            //
            // 原来是在 convert() 里 `removeAllViews()` + `addView(新建的 label)`：绑定阶段
            // （含 RecyclerView 预取）增删子视图会让容器的焦点簿记空指针 —— removeViewAt 内部
            // 对 mFocused 调 unFocus，而预取时它可能还没有值（同类崩溃见 HorizontalItemProvider）。
            // 而且每绑定一次就新建一个 View 也是在白扔对象。标题比卡片多缩进 8dp（设计稿：
            // 标题 x=24、卡片 x=16），这个缩进是固定的，跟着 View 一起建就行。
            addView(SettingRowStyle.categoryLabel(context).apply {
                id = R.id.anko_text_view
                layoutParams = LinearLayoutCompat.LayoutParams(
                        LinearLayoutCompat.LayoutParams.MATCH_PARENT, dip(20)).apply {
                    marginStart = dip(24)
                    marginEnd = dip(16)
                    topMargin = dip(8)
                    bottomMargin = dip(6)
                }
            })
        }
        return BaseViewHolder(view)
    }

    override fun convert(helper: BaseViewHolder, data: BaseSettingItem?) {
        if (data == null) return
        val item = data as CategoryItem
        val container = helper.itemView as LinearLayoutCompat
        container.setPadding(0, item.topGap, 0, 0)
        helper.setText(R.id.anko_text_view, item.name)
    }
}
