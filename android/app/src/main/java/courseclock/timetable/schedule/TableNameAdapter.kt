package courseclock.timetable.schedule

import android.graphics.drawable.ColorDrawable
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import courseclock.timetable.R
import courseclock.timetable.bean.TableSelectBean
import courseclock.timetable.utils.BackgroundImageLoader
import splitties.dimensions.dip

class TableNameAdapter(layoutResId: Int, data: MutableList<TableSelectBean>) :
        BaseQuickAdapter<TableSelectBean, BaseViewHolder>(layoutResId, data) {

    override fun convert(helper: BaseViewHolder, item: TableSelectBean?) {
        if (item == null) return
        helper.setGone(R.id.menu_setting, item.type != 1)

        if (item.tableName != "") {
            helper.setText(R.id.tv_table_name, item.tableName)
        } else {
            helper.setText(R.id.tv_table_name, "我的课表")
        }
        val imageView = helper.getView<AppCompatImageView>(R.id.iv_table_bg)
        if (item.background != "") {
            // centerCrop 必须开：这个 ImageView 在 XML 里没给 scaleType（默认 FIT_CENTER），
            // 原先靠 Glide 的 CenterCrop() 把图裁满 48×72dp 再叠 4dp 圆角，不预裁就会留白边。
            BackgroundImageLoader.loadInto(
                    view = imageView,
                    spec = item.background,
                    reqWidth = 200,
                    reqHeight = 300,
                    centerCrop = true,
                    cornerRadiusPx = context.dip(4))
        } else {
            // 没有自定义背景图时**不再加载任何图片**：默认背景是纯色
            // （@color/main_background，浅色纯白 / 深色纯黑）。上游那张来源不明的默认照片已删除，
            // 而"默认背景是纯色"本来就不该走图片加载器。
            //
            // cancel 不能省：这个 View 上一条数据可能是有背景的，那次解码也许还在路上。
            // 不清掉占位 key 的话，它的回调回来时 key 仍然匹配，就会把上一条课表的图贴到
            // 这条纯色条目上 —— 滑一下列表就能看见的串图。
            BackgroundImageLoader.cancel(imageView)
            imageView.setImageDrawable(
                    ColorDrawable(ContextCompat.getColor(context, R.color.main_background)))
        }
    }

}