package courseclock.timetable

import courseclock.timetable.settings.ArrowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「⌃⌄」箭头的几何。
 *
 * 为什么值得单独钉：用户为"太扁"反馈过**两次**。第一次把两条折线分开了一点，但斜率还是 32°，
 * 真机上仍旧是扁的；第二次才改成 45°。这种"看起来差不多"的东西，只有把
 * **斜率**与**两尖之间的可见缝**写成断言才守得住 —— 单看代码里几个小数是看不出扁的。
 */
class ArrowViewGeometryTest {

    /** 箭头在行里的实际边长（dp），与 `SettingRowStyle.arrowView` 一致。 */
    private val sizeDp = 20f

    @Test
    fun `select chevrons are drawn at 45 degrees, not flat`() {
        val slope = ArrowView.SELECT_HEIGHT / ArrowView.SELECT_HALF
        assertEquals("两条腿必须等长等高：顶角 90°（斜率 45°），否则又会变成扁的",
                1f, slope, 0.01f)
    }

    /**
     * 两个尖之间的**肉眼可见**缝。
     *
     * 缝 = 两条底边的坐标差 − 线宽。上一版底边差只有约 2.6dp，扣掉 1.8dp 线宽几乎为零，
     * 真机上两枚箭头糊成一个菱形，看不出是"上下选择"。
     */
    @Test
    fun `the two select chevrons keep a visible gap`() {
        val baseGapDp = 2f * (ArrowView.SELECT_GAP - ArrowView.SELECT_HEIGHT) * sizeDp
        val visibleGapDp = baseGapDp - ArrowView.STROKE_WIDTH_DP
        assertTrue("两尖的可见缝只有 ${visibleGapDp}dp：真机上会糊成一个菱形",
                visibleGapDp >= 2.5f)
    }

    /** 墨迹不能超出它自己那块见方的位：出了就是被裁掉的半个箭头。 */
    @Test
    fun `select ink fits inside the view box`() {
        val inkHeightDp = (2f * ArrowView.SELECT_GAP + ArrowView.SELECT_HEIGHT) * sizeDp
                + ArrowView.STROKE_WIDTH_DP
        assertTrue("墨迹高 ${inkHeightDp}dp 超过了 ${sizeDp}dp 的位", inkHeightDp <= sizeDp)

        val inkWidthDp = 2f * ArrowView.SELECT_HALF * sizeDp + ArrowView.STROKE_WIDTH_DP
        assertTrue("墨迹宽 ${inkWidthDp}dp 超过了 ${sizeDp}dp 的位", inkWidthDp <= sizeDp)
    }
}
