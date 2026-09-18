package courseclock.timetable.intro

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseBlurTitleActivity
import courseclock.timetable.databinding.ActivityAboutBinding
import courseclock.timetable.utils.UpdateUtils

class AboutActivity : BaseBlurTitleActivity() {
    override val layoutId: Int
        get() = R.layout.activity_about

    /** 页面不提供副标题按钮：这里只介绍本软件本身。 */
    override fun onSetupSubButton(tvButton: AppCompatTextView): AppCompatTextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这一页是"大标题页"：标题 30sp 在内容里，顶栏只留返回箭头（设计稿 11）。
        mainTitle.visibility = View.GONE

        // 整页用设计稿的灰底，卡片才是白的。基类把根视图和顶栏都设成 colorSurface（白），
        // 白卡放在白底上就看不出卡片了 —— 只改这一页，小部件配置页也继承基类，不动基类。
        val pageColor = ContextCompat.getColor(this, R.color.page_background)
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0)?.setBackgroundColor(pageColor)
        findViewById<View>(R.id.anko_layout)?.setBackgroundColor(pageColor)

        val binding = ActivityAboutBinding.bind(llContent.getChildAt(0))
        try {
            binding.tvVersion.text = getString(R.string.about_version, UpdateUtils.getVersionName(this))
        } catch (e: Exception) {
            // 取不到版本号不是致命问题，但不该静默：留一条日志，页面宁可不显示也不显示假信息。
            Log.w("AboutActivity", "无法取得版本号，关于页将不显示版本", e)
        }

    }
}
