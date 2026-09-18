package courseclock.timetable.utils

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.os.Build
import android.text.Html
import android.text.Spanned
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.text.HtmlCompat
import courseclock.timetable.R
import courseclock.timetable.bean.TableBean
import splitties.resources.styledColor


/**
 * 当前是否处于深色模式（AppCompatDelegate 的 night 模式）。
 *
 * 用 configuration.uiMode 而不是 SharedPreferences 里的开关：跟随系统/省电模式这些分支下，
 * 只有 uiMode 是真正生效的那个结果。
 */
fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

/**
 * 课表这一屏（含顶部栏、五个图标按钮、时间栏与虚线）的文字/线取色。
 *
 * 深色模式下**一律用主题的浅色**：深色模式要求"UI 和按钮是灰或白，不是纯黑"，
 * 而 `table.textColor` 是「压在自定义背景图上的文字颜色」、默认值就是纯黑 ——
 * 原来只要设了背景图就无条件用它，于是深色模式下整个界面变纯黑，在 #2A2F3A 上等于看不见
 * （时间栏、节次、虚线一起消失，用户报的"深色虚线不明显"是同一个根）。
 * 自定义背景图的配色只在**浅色模式**下参与取色。
 */
fun scheduleTextColor(context: Context, table: TableBean, forWidget: Boolean = false): Int = when {
    !forWidget && isNightMode(context) -> context.styledColor(R.attr.colorOnBackground)
    !forWidget && table.background.isNotEmpty() -> table.textColor
    else -> context.styledColor(R.attr.colorOnBackground)
}

object ViewUtils {

    fun judgeColorIsLight(color: Int): Boolean {
        val red = color and 0xff0000 shr 16
        val green = color and 0x00ff00 shr 8
        val blue = color and 0x0000ff
        return (0.213 * red + 0.715 * green + 0.072 * blue > 255 / 2)
    }

    fun getScreenInfo(context: Context): Array<Int> {
        val displayMetrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getMetrics(displayMetrics)
        val height = displayMetrics.heightPixels
        val width = displayMetrics.widthPixels
        return arrayOf(width, height)
    }

    fun getHtmlSpannedString(str: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(str, HtmlCompat.FROM_HTML_MODE_COMPACT)
        } else {
            Html.fromHtml(str)
        }
    }

    fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    fun resizeStatusBar(context: Context, view: View) {
        val layoutParams = view.layoutParams
        layoutParams.height = getStatusBarHeight(context.applicationContext)
        view.layoutParams = layoutParams
    }

    /**
     * 获取虚拟功能键高度
     */
    fun getVirtualBarHeight(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val dm = DisplayMetrics()
        // minSdk 21 > API 17，getRealMetrics 是公开 API，不需要反射。
        display.getRealMetrics(dm)
        return dm.heightPixels - display.height
    }

    fun createColorStateList(color: Int): ColorStateList {
        val colors = intArrayOf(color, color, color, color, color, color)
        val states = arrayOfNulls<IntArray>(6)
        states[0] = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
        states[1] = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
        states[2] = intArrayOf(android.R.attr.state_enabled)
        states[3] = intArrayOf(android.R.attr.state_focused)
        states[4] = intArrayOf(android.R.attr.state_window_focused)
        states[5] = intArrayOf()
        return ColorStateList(states, colors)
    }

    fun createColorStateList(normal: Int, pressed: Int, focused: Int, unable: Int): ColorStateList {
        val colors = intArrayOf(pressed, focused, normal, focused, unable, normal)
        val states = arrayOfNulls<IntArray>(6)
        states[0] = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
        states[1] = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
        states[2] = intArrayOf(android.R.attr.state_enabled)
        states[3] = intArrayOf(android.R.attr.state_focused)
        states[4] = intArrayOf(android.R.attr.state_window_focused)
        states[5] = intArrayOf()
        return ColorStateList(states, colors)
    }

    fun getRealSize(activity: Activity): Point {
        val size = Point()
        activity.windowManager.defaultDisplay.getRealSize(size)
        return size
    }

    fun getViewBitmap(viewGroup: ViewGroup, low: Boolean = false, marginBottom: Int = 0): Bitmap {
        var h = 0
        val bitmap: Bitmap
        // 位图高度必须包含**底板自己的上下 padding** 和**子 View 的 margin**：
        // 背景画在整个 viewGroup 上，只累加 child.height 会把 margin 占位连同压在它上面的
        // 底部 padding 一起裁掉（4×2「下一节」色卡第二行 topMargin=3dp，蓝框下方被轻微截断）。
        h += viewGroup.paddingTop + viewGroup.paddingBottom
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            val mTop = lp?.topMargin ?: 0
            val mBottom = lp?.bottomMargin ?: 0
            h += mTop + child.height + mBottom + marginBottom
        }
        // 创建对应大小的bitmap
        bitmap = Bitmap.createBitmap(viewGroup.width, h,
                if (low) Bitmap.Config.ARGB_4444 else Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        viewGroup.draw(canvas)
        return bitmap
    }


    fun layoutView(v: View, width: Int, height: Int) {
        // validate view.width and view.height
        v.layout(0, 0, width, height)
        val measuredWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val measuredHeight = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)

        // validate view.measurewidth and view.measureheight
        v.measure(measuredWidth, measuredHeight)
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    fun getCustomizedColor(context: Context, index: Int): Int {
        val customizedColors = context.resources.getIntArray(R.array.customizedColors)
        return customizedColors[index]
    }

    /**
     * 课程颜色字符串的安全解析，全工程唯一的课程颜色出口。
     *
     * 课程颜色在库里是任意字符串：历史导入、其他 fork 的分享文件都可能写进畸形值，而渲染端
     * 曾经各自 `substring`/`Color.parseColor`，一处畸形就崩掉整块课表或整个小部件（工厂里
     * 抛异常 = 桌面上「载入出现问题」，且每次刷新复现）。这里只认 `#RRGGBB` 与 `#AARRGGBB`
     * 两种形态，其余一律退回 [fallback]，绝不抛异常。不依赖 `Color.parseColor`，
     * 纯 JVM 可测。
     */
    fun parseCourseColor(color: String?, fallback: Int): Int {
        if (color.isNullOrEmpty()) return fallback
        val body = color.removePrefix("#")
        val value = body.toLongOrNull(16) ?: return fallback
        return when (body.length) {
            6 -> (value or 0xFF000000L).toInt()
            8 -> value.toInt()
            else -> fallback
        }
    }
}
