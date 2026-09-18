package courseclock.timetable.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.annotation.ColorInt
import courseclock.timetable.settings.items.SettingRowArrow

/**
 * 行右侧的箭头 View，两种语义两种画法（见 [SettingRowArrow]）。
 *
 * 为什么直接画而不是上图标：本仓库的 iconfont 是上游裁剪过的子集字体，里面没有 ^ / v 的
 * 配对字形，去猜一个私有区码位的结果是真机上画出一个豆腐块；为两条折线引入一个图标库更不值。
 * `View.onDraw` 是平台原生机制，也不需要把 SVG 塞进代码里。
 *
 * 尺寸 = [sizeDp] 见方的透明位；箭头的墨迹只占中间一部分，所以换行高不用重算坐标。
 */
class ArrowView(
        context: Context,
        arrow: SettingRowArrow,
        @ColorInt color: Int,
        private val sizeDp: Int = 20) : View(context) {

    /**
     * 箭头种类，**可以改**。
     *
     * 行是复用的：同一个 ViewHolder 上一轮可能画的是「>」，这一轮要画「⌃⌄」。原来调用方为此
     * 把整个 View 换掉（removeViewAt + addView），真机崩在 `ViewGroup.removeViewInternal`：
     * RecyclerView 预取（GapWorker）绑定时容器的焦点簿记可能还没有值，removeViewAt 内部对
     * mFocused 调 unFocus → 空指针。换画法本来就只是"重画一次"，不该换视图。
     */
    var arrow: SettingRowArrow = arrow
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val sizePx = (sizeDp * density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 1.8dp：参考 HyperOS 的折线粗细；1.6dp 在 xxhdpi 的放大图里偏细。
        strokeWidth = STROKE_WIDTH_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        when (arrow) {
            // 「>」：一条尖朝右的折线，和系统设置页一致。
            SettingRowArrow.NAVIGATE -> {
                val halfWidth = sizePx * 0.16f
                val halfHeight = sizePx * 0.30f
                canvas.drawLine(centerX - halfWidth, centerY - halfHeight,
                        centerX + halfWidth, centerY, paint)
                canvas.drawLine(centerX + halfWidth, centerY,
                        centerX - halfWidth, centerY + halfHeight, paint)
            }
            // 「⌃⌄」：上下两个尖，表示"就在本页选一个值"，不用离开。
            //
            // 两个尖要**分得开、且够陡**：它们原来是扁的（高 2.4dp）而且贴在一起，真机上糊成
            // 一个"<>"，看不出是"上下选择"。参考 HyperOS 的设置页：两个折线各自完整、中间留缝，
            // 斜率接近 45°。
            //
            // 间距要按**肉眼看到的缝**算，不能只算两条折线的坐标差：两尖分别是中心上下 [gap]，
            // 各自腿长 [height]，于是两条"底边"的坐标差 = 2 × (gap − height)；再各让开半个
            // [strokeWidth]，剩下的才是缝。
            //
            // 几何按 45° 定：`height == half`（顶角 90°，和系统设置页的折线一个样子）。
            // 上一版 height 只有 half 的 0.63（斜率 32°），仍然是"扁"的 —— 用户第二次反馈
            // "太扁了"说的就是它。要同时满足"更陡"和"中间留缝"，只能把 [gap] 一起放大：
            // 20dp 上 half = height = 3.8dp、gap = 6dp → 墨迹高 13.8dp（不裁切），
            // 两条底边差 4.4dp，扣掉 1.8dp 线宽后肉眼可见缝约 2.6dp。
            SettingRowArrow.SELECT -> {
                val half = sizePx * SELECT_HALF
                val height = sizePx * SELECT_HEIGHT
                val gap = sizePx * SELECT_GAP
                drawChevron(canvas, centerX, centerY - gap, half, height, up = true)
                drawChevron(canvas, centerX, centerY + gap, half, height, up = false)
            }
        }
    }

    /** 一条以 ([cx], [tipY]) 为尖的折线，两腿各展开 [half]、向下/向上 [height]。 */
    private fun drawChevron(canvas: Canvas, cx: Float, tipY: Float, half: Float, height: Float,
                            up: Boolean) {
        val baseY = if (up) tipY + height else tipY - height
        canvas.drawLine(cx - half, baseY, cx, tipY, paint)
        canvas.drawLine(cx, tipY, cx + half, baseY, paint)
    }

    companion object {
        /**
         * 「⌃⌄」的几何，全部是边长的比例。**抽出来是为了能被测**（见 `ArrowViewGeometryTest`）：
         * 它被用户反馈"太扁"过两次，光靠看代码守不住 —— 折线的斜率与两尖之间的可见缝都得有断言。
         *
         * - `HEIGHT == HALF` → 顶角 90°（斜率 45°），和系统设置页一个样子；只要有人把它调小，
         *   斜率就退回去，"扁"会重演。
         * - `GAP` 决定两个尖的中心距。缝 = 2 × (GAP − HEIGHT) × 边长 − 线宽，必须留得下。
         */
        const val SELECT_HALF = 0.19f
        const val SELECT_HEIGHT = 0.19f
        const val SELECT_GAP = 0.30f

        /** 折线粗细（dp）。缝的换算要用到它，测试里也按同一个值算。 */
        const val STROKE_WIDTH_DP = 1.8f
    }
}
