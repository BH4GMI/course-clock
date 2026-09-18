package courseclock.timetable.schedule_appwidget

import android.graphics.drawable.ColorDrawable
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import courseclock.timetable.R
import courseclock.timetable.bean.TableSelectBean
import courseclock.timetable.utils.BackgroundImageLoader

class WidgetTableListAdapter(layoutResId: Int, data: MutableList<TableSelectBean>) :
        BaseQuickAdapter<TableSelectBean, BaseViewHolder>(layoutResId, data) {

    override fun convert(helper: BaseViewHolder, item: TableSelectBean?) {
        if (item == null) return
        helper.setVisible(R.id.ib_share, false)
        helper.setVisible(R.id.ib_edit, false)
        helper.setVisible(R.id.ib_delete, false)

        if (item.tableName != "") {
            helper.setText(R.id.tv_table_name, item.tableName)
        } else {
            helper.setText(R.id.tv_table_name, "我的课表")
        }
        val imageView = helper.getView<AppCompatImageView>(R.id.iv_pic)
        if (item.background != "") {
            // 与 TableNameAdapter 同一套：上游那个 Glide 依赖已被移除（见 app/build.gradle 里的说明），
            // 课表背景图是本 App 私有目录里的本机文件，采样 / EXIF 旋转 / 裁切 / 缓存统一交给
            // utils/BackgroundImageLoader。centerCrop 开着，免得图不预裁时留白边。
            BackgroundImageLoader.loadInto(
                    view = imageView,
                    spec = item.background,
                    reqWidth = 400,
                    reqHeight = 600,
                    centerCrop = true,
                    cornerRadiusPx = 0)
        } else {
            // 没有自定义背景图时**不再加载任何图片**：默认背景是纯色。
            // cancel 不能省：这个 View 上一条数据可能是有背景的，那次解码也许还在路上 ——
            // 不清占位 key 的话，回调回来时 key 仍然匹配，就会把上一条课表的图贴到这条纯色条目上。
            BackgroundImageLoader.cancel(imageView)
            imageView.setImageDrawable(
                    ColorDrawable(ContextCompat.getColor(context, R.color.main_background)))
        }
    }
}