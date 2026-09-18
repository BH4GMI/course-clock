package courseclock.timetable.base_view

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.setPadding
import courseclock.timetable.R
import splitties.dimensions.dip
import splitties.resources.styledColor

abstract class BaseTitleActivity : BaseActivity() {

    @get:LayoutRes
    protected abstract val layoutId: Int

    open fun onSetupSubButton(tvButton: AppCompatTextView): AppCompatTextView? {
        return null
    }

    lateinit var mainTitle: AppCompatTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutId)
        findViewById<LinearLayoutCompat>(R.id.ll_root).addView(createTitleBar(), 0)
    }

    open fun createTitleBar() = LinearLayoutCompat(this).apply {
        setBackgroundColor(styledColor(R.attr.colorSurface))
        setPadding(0, getStatusBarHeight(), 0, 0)
        val outValue = TypedValue()
        theme.resolveAttribute(R.attr.selectableItemBackgroundBorderless, outValue, true)

        addView(AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(outValue.resourceId)
            setPadding(dip(8))
            setColorFilter(styledColor(R.attr.colorOnBackground))
            setOnClickListener {
                // 返回箭头和行/按钮一样该有触感回执；先前只有设置列表的行接了 Haptics，
                // 于是"关于 / 设置 / 课表管理"这些页面的左上角返回按下去是没反应的。
                courseclock.timetable.utils.Haptics.tap(this)
                onBackPressed()
            }
        }, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(48)))

        mainTitle = AppCompatTextView(context).apply {
            text = title
            gravity = Gravity.CENTER_VERTICAL
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        addView(mainTitle, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(48)).apply {
            weight = 1f
        })

        onSetupSubButton(AppCompatTextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(outValue.resourceId)
            setPadding(dip(24), 0, dip(24), 0)
        })?.let {
            addView(it, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(48)))
        }

    }


}