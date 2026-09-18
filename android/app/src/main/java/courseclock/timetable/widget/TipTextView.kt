package courseclock.timetable.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.core.content.ContextCompat
import courseclock.timetable.R
import splitties.dimensions.dp

@SuppressLint("ViewConstructor")
class TipTextView(context: Context) : View(context) {

    var tipVisibility = 0
        set(value) {
            field = value
            invalidate()
        }

    /** 免听课整卡淡化，与「非本周」同一档透明度。 */
    var notAttendCard = false
        set(value) {
            field = value
            invalidate()
        }

    /** 同槽位还有别的课（被完全遮挡）：右下角三角，与 TIP_OTHER_WEEK 可叠加。 */
    var showMultiTip = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 这一格的描边改用强调色 —— 小部件用它标出"此刻正在上"的那一节，
     * 让它在满屏同色块里被一眼认出来（设计稿的"当前节次"标记）。
     *
     * **默认关**：主课表（`ScheduleFragment`）与周视图小部件共用本类，主课表不开这个开关，
     * 所以改这里不会动到主课表的样子。描边颜色本身仍由用户的「小部件描边颜色」决定，
     * 这里只是给"当前这一节"换成强调色。
     */
    var accentStroke = false
        set(value) {
            field = value
            invalidate()
        }

    private var text = ""
    private var mStaticLayout: StaticLayout? = null
    private lateinit var mTextPaint: TextPaint
    private lateinit var mPaint: Paint
    private lateinit var bgPaint: Paint
    private lateinit var strokePaint: Paint
    private val path = Path()
    private val rect = RectF()
    private val dpUnit = dp(1)
    private var otherWeekTextAlpha = 255
    private var otherWeekBgAlpha = 255
    private var otherWeekStrokeAlpha = 255

    fun init(text: String, txtSize: Int, txtColor: Int, bgColor: Int, bgAlpha: Int, stroke: Int) {
        this.text = text
        mTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            // textSize = mSize * resources.displayMetrics.scaledDensity
            textSize = txtSize * dpUnit
            typeface = Typeface.DEFAULT_BOLD
            color = txtColor
        }
        mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = txtColor
            isDither = true
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2 * dpUnit
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            isDither = true
            style = Paint.Style.FILL
            alpha = bgAlpha
        }
        strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke
            isDither = true
            style = Paint.Style.STROKE
            strokeWidth = 2 * dpUnit
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        otherWeekTextAlpha = (mTextPaint.alpha * 0.3).toInt()
        otherWeekBgAlpha = (bgPaint.alpha * 0.3).toInt()
        otherWeekStrokeAlpha = (strokePaint.alpha * 0.3).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Tell the parent layout how big this view would like to be
        // but still respect any requirements (measure specs) that are passed down.

        // determine the width
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // determine the height
        val height = MeasureSpec.getSize(heightMeasureSpec)
        rect.left = dpUnit
        rect.right = width.toFloat() - dpUnit
        rect.top = dpUnit
        rect.bottom = height.toFloat() - dpUnit
        // Required call: set width and height
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 放在透明度那段之前：`strokePaint.color = ...` 会把 alpha 一起重置成 255，
        // 先换色再按"非本周/免听"降透明度，两者才能叠加。
        if (accentStroke) {
            strokePaint.color = ContextCompat.getColor(context, R.color.colorPrimary)
        }
        if (tipVisibility == TIP_OTHER_WEEK || notAttendCard) {
            mTextPaint.alpha = otherWeekTextAlpha
            mPaint.alpha = otherWeekTextAlpha
            strokePaint.alpha = otherWeekStrokeAlpha
            bgPaint.alpha = otherWeekBgAlpha
        }
        if (mStaticLayout == null) {
            mStaticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout
                        .Builder
                        .obtain(text, 0, text.length, mTextPaint, width - paddingRight - paddingLeft)
                        .setIncludePad(false)
                        .build()
            } else {
                StaticLayout(
                        text,
                        mTextPaint,
                        width - paddingRight - paddingLeft,
                        Layout.Alignment.ALIGN_NORMAL,
                        1.0f,
                        0f,
                        false
                )
            }
        }
        canvas.drawRoundRect(rect, 4 * dpUnit, 4 * dpUnit, bgPaint)
        canvas.drawRoundRect(rect, 4 * dpUnit, 4 * dpUnit, strokePaint)
        canvas.clipRect(rect)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        mStaticLayout!!.draw(canvas)
        canvas.restore()
        if (tipVisibility == 1 || showMultiTip) {
            path.reset()
            path.moveTo(width - 12 * dpUnit, height - 6 * dpUnit) // 此点为多边形的起点
            path.lineTo(width - 6 * dpUnit, height - 6 * dpUnit)
            path.lineTo(width - 6 * dpUnit, height - 12 * dpUnit)
            path.close() // 使这些点构成封闭的多边形
            canvas.drawPath(path, mPaint)
        } else if (tipVisibility == -1) {
            canvas.drawLine(width - 12 * dpUnit,
                    height - 6 * dpUnit,
                    width - 6 * dpUnit,
                    height - 12 * dpUnit, mPaint)
            canvas.drawLine(width - 6 * dpUnit,
                    height - 6 * dpUnit,
                    width - 12 * dpUnit,
                    height - 12 * dpUnit, mPaint)
        }
    }

    companion object {
        const val TIP_INVISIBLE = 0
        const val TIP_VISIBLE = 1
        const val TIP_ERROR = -1
        const val TIP_OTHER_WEEK = 2
    }
}
