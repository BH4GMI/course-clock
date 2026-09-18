package courseclock.timetable.base_view

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.OVER_SCROLL_NEVER
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import courseclock.timetable.R
import splitties.dimensions.dip
import splitties.resources.styledColor

abstract class BaseListActivity : BaseActivity() {

    /** 标题栏右侧的附加按钮，返回 null 表示不需要。 */
    open fun onSetupSubButton(tvButton: AppCompatTextView): AppCompatTextView? = null
    lateinit var mainTitle: AppCompatTextView
    lateinit var searchView: AppCompatEditText
    protected var showSearch = false
    protected var textWatcher: TextWatcher? = null
    protected lateinit var mRecyclerView: RecyclerView
    lateinit var rootView: ConstraintLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootView = createView()
        setContentView(rootView)
    }

    private fun createView() = ConstraintLayout(this).apply {

        // 列表类页面统一用设计稿的三层底色：灰底 +（列表里的）白卡片。
        // 主课表那一屏不走这里 —— 它的底色是用户可以换背景的 ui.bg（main_background）。
        setBackgroundColor(ContextCompat.getColor(context, R.color.page_background))

        val outValue = TypedValue()
        theme.resolveAttribute(R.attr.selectableItemBackgroundBorderless, outValue, true)

        mRecyclerView = RecyclerView(context, null, R.attr.verticalRecyclerViewStyle).apply {
            overScrollMode = OVER_SCROLL_NEVER
        }

        mainTitle = AppCompatTextView(context).apply {
            text = title
            gravity = Gravity.CENTER_VERTICAL
            // 设计稿的页面标题是 18sp / 800；原来 16sp 在"设置"这类两字标题上偏小。
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }

        searchView = AppCompatEditText(context).apply {
            hint = "请输入……"
            textSize = 16f
            background = null
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setLines(1)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(textWatcher)
        }

        addView(mRecyclerView, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT).apply {
            // 列表挂在**标题栏下面**，不是铺满整窗。
            //
            // 原来这里是 topToTop = PARENT_ID，列表从窗口最顶上开始，而标题栏是后加进来、
            // 画在列表**之上**的一层不透明 View：结果是第一组的分组标题和第一行被标题栏压住，
            // 真机上打开设置页看到的第一行就是第二行（"设置当前课表"整行点不到也看不见）。
            // 设计稿里标题栏下面紧接分组标题（设置 y=62、分组标题 y=100），所以正确的关系就是
            // "列表的顶边 = 标题栏的底边"。
            topToBottom = R.id.anko_layout
            bottomToBottom = ConstraintSet.PARENT_ID
            startToStart = ConstraintSet.PARENT_ID
            endToEnd = ConstraintSet.PARENT_ID
        })

        addView(LinearLayoutCompat(context).apply {
            id = R.id.anko_layout
            setPadding(0, getStatusBarHeight(), 0, 0)
            setBackgroundColor(styledColor(R.attr.colorSurface))

            addView(AppCompatImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                setBackgroundResource(outValue.resourceId)
                setPadding(dip(8))
                setColorFilter(styledColor(R.attr.colorOnBackground))
                setOnClickListener {
                    // 设置页/课表管理页的左上角返回，同样要有触感回执。
                    courseclock.timetable.utils.Haptics.tap(this)
                    onBackPressed()
                }
            }, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(48)))

            addView(mainTitle,
                    LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(48)).apply {
                        weight = 1f
                    })

            addView(searchView, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(48)).apply {
                weight = 1f
            })

            if (showSearch) {
                val iconFont = ResourcesCompat.getFont(context, R.font.iconfont)
                addView(AppCompatTextView(context).apply {
                    textSize = 20f
                    typeface = iconFont
                    text = "\uE6D4"
                    gravity = Gravity.CENTER
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener {
                        when (searchView.visibility) {
                            View.GONE -> {
                                mainTitle.visibility = View.GONE
                                searchView.visibility = View.VISIBLE
                                setTextColor(ContextCompat.getColor(context, R.color.colorAccent))
                                searchView.isFocusable = true
                                searchView.isFocusableInTouchMode = true
                                searchView.requestFocus()
                                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                        .showSoftInput(searchView, 0)
                            }
                        }
                    }
                }, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(48)).apply {
                    marginEnd = dip(24)
                })
            }

            onSetupSubButton(AppCompatTextView(context).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(outValue.resourceId)
            })?.let {
                addView(it, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dip(48)).apply {
                    marginEnd = dip(24)
                })
            }

        }, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            topToTop = ConstraintSet.PARENT_ID
            startToStart = ConstraintSet.PARENT_ID
            endToEnd = ConstraintSet.PARENT_ID
        })
    }

    override fun onDestroy() {
        searchView.removeTextChangedListener(textWatcher)
        textWatcher = null
        super.onDestroy()
    }
}
