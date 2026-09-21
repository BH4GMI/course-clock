package courseclock.timetable.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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
    private var widgetContent: List<String>? = null
    private var preferredTextSize = 0f
    internal var widgetOverlapCount = 0
        set(value) {
            field = value
            mStaticLayout = null
            invalidate()
        }

    internal fun setWidgetContent(title: String, location: String, time: String, metadata: String) {
        widgetContent = listOf(title, location, time, metadata)
        contentDescription = widgetContent?.filter { it.isNotBlank() }?.joinToString("，")
        mStaticLayout = null
    }

    fun init(text: String, txtSize: Int, txtColor: Int, bgColor: Int, bgAlpha: Int, stroke: Int) {
        this.text = text
        mStaticLayout = null
        mTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            // textSize = mSize * resources.displayMetrics.scaledDensity
            textSize = txtSize * dpUnit
            typeface = Typeface.DEFAULT_BOLD
            color = txtColor
        }
        preferredTextSize = mTextPaint.textSize
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mStaticLayout = null
    }

    private fun layoutText(value: CharSequence, availableWidth: Int): StaticLayout =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(value, 0, value.length, mTextPaint, availableWidth)
                        .setIncludePad(false).build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(value, mTextPaint, availableWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
            }

    internal fun widgetTextLayout(): StaticLayout {
        val parts = requireNotNull(widgetContent)
        val availableWidth = (width - paddingRight - paddingLeft).coerceAtLeast(1)
        mTextPaint.textSize = preferredTextSize
        val twoCharacters = mTextPaint.measureText("课程")
        if (twoCharacters > availableWidth) {
            mTextPaint.textSize = minOf(preferredTextSize,
                    maxOf(10 * dpUnit, preferredTextSize * availableWidth / twoCharacters))
        }
        val full = layoutText(parts.joinToString("\n"), availableWidth)
        val lineHeight = (0 until full.lineCount).maxOf { full.getLineBottom(it) - full.getLineTop(it) }.coerceAtLeast(1)
        val lines = (height - paddingTop - paddingBottom) / lineHeight
        if (lines <= 0) return layoutText("", availableWidth)
        val (title, location, time, metadata) = parts
        val titleMinimum = minOf(2, layoutText(title, availableWidth).lineCount)
        val roomLines = if (location.isBlank() || lines <= titleMinimum) 0 else
            minOf(2, layoutText(location, availableWidth).lineCount, lines - titleMinimum)
        val meta = if (widgetOverlapCount > 0) "另有${widgetOverlapCount}门" else metadata
        val titleWanted = minOf(3, layoutText(title, availableWidth).lineCount)
        val spare = (lines - roomLines - titleWanted).coerceAtLeast(0)
        val showTime = time.isNotEmpty() && spare > 0
        val showMeta = meta.isNotEmpty() && spare > (if (showTime) 1 else 0)
        val titleLines = lines - roomLines - (if (showTime) 1 else 0) - (if (showMeta) 1 else 0)
        fun fit(value: String, count: Int): CharSequence {
            val layout = layoutText(value, availableWidth)
            if (layout.lineCount <= count) return value
            val start = layout.getLineStart(count - 1)
            val tail = TextUtils.ellipsize(value.substring(start).replace('\n', ' '), mTextPaint,
                    availableWidth.toFloat(), if (value == location) TextUtils.TruncateAt.START else TextUtils.TruncateAt.MIDDLE)
            return if (start == 0) tail else value.substring(0, start).trimEnd() + "\n" + tail
        }
        val values = ArrayList<CharSequence>()
        if (showTime) values.add(fit(time, 1))
        values.add(fit(title, titleLines))
        if (roomLines > 0) values.add(fit(location, roomLines))
        if (showMeta) values.add(fit(meta, 1))
        return layoutText(values.joinToString("\n"), availableWidth)
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
            val availableWidth = (width - paddingRight - paddingLeft).coerceAtLeast(1)
            mStaticLayout = if (widgetContent != null) widgetTextLayout() else layoutText(text, availableWidth)
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
