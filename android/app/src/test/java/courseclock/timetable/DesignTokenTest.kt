package courseclock.timetable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设计 token 的**值**。
 *
 * 为什么需要这一份：现有渲染测试断言的是"布局用了 token"（测试与实现读**同一个**资源），
 * 例如
 * ```
 * val inset = px(R.dimen.page_inset)
 * assertEquals("卡片没有左右各缩进 page_inset", inset, row.marginStart)
 * ```
 * 它证明接线正确，却证明不了 token 的值对不对。实测：把 `page_inset` 16→24、
 * `setting_row_min_height` 56→40、`setting_row_two_line_height` 64→48、
 * `widget_corner_radius` 18→2、`course_detail_inset` 24→8 之后，**全量 234 项仍然全绿** ——
 * 换句话说，设计走样可以完全无声地发生。
 *
 * 所以这一份刻意把设计稿的数值钉住：改设计时**必须**连它一起改，那点摩擦正是目的
 * （数值来源：`values/dimens.xml` 的注释与 `docs/preview-hyperos.html`）。
 * 未列出的 token（如遗留的 [R.dimen.weekItemMarTop]）没有设计稿依据，不在这里钉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DesignTokenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 资源取出来的是像素；除以密度换成 dp，这样断言与屏幕密度无关。 */
    private fun dp(id: Int): Float =
            context.resources.getDimension(id) / context.resources.displayMetrics.density

    @Test
    fun 分组卡片的尺寸token与设计稿一致() {
        assertEquals("卡片离屏幕左右的距离", 16f, dp(R.dimen.page_inset), TOLERANCE)
        assertEquals("卡片内左右留白", 16f, dp(R.dimen.setting_row_inset), TOLERANCE)
        assertEquals("分组卡片圆角", 8f, dp(R.dimen.group_radius), TOLERANCE)
        assertEquals("一组与一组之间", 24f, dp(R.dimen.setting_group_gap), TOLERANCE)
    }

    @Test
    fun 设置行高与开关尺寸与设计稿一致() {
        assertEquals("单行行高", 56f, dp(R.dimen.setting_row_min_height), TOLERANCE)
        assertEquals("带解释的行高", 64f, dp(R.dimen.setting_row_two_line_height), TOLERANCE)
        assertEquals("开关胶囊宽", 46f, dp(R.dimen.switch_width), TOLERANCE)
        assertEquals("开关胶囊高", 28f, dp(R.dimen.switch_height), TOLERANCE)
        assertEquals("开关圆点", 24f, dp(R.dimen.switch_thumb), TOLERANCE)
    }

    @Test
    fun 小部件与详情弹窗的尺寸token与设计稿一致() {
        assertEquals("小部件圆角（1080p 档：55px @3x）",
                18f, dp(R.dimen.widget_corner_radius), TOLERANCE)
        assertEquals("小部件内容安全区", 16f, dp(R.dimen.widget_edge_padding), TOLERANCE)
        assertEquals("课程详情弹窗内容边距", 24f, dp(R.dimen.course_detail_inset), TOLERANCE)
    }

    @Test
    fun 跨token的顺序不变量成立() {
        assertTrue("带解释的行必须比单行高：${dp(R.dimen.setting_row_two_line_height)}dp vs " +
                "${dp(R.dimen.setting_row_min_height)}dp",
                dp(R.dimen.setting_row_two_line_height) > dp(R.dimen.setting_row_min_height))
        assertTrue("开关是横胶囊：宽必须大于高",
                dp(R.dimen.switch_width) > dp(R.dimen.switch_height))
        assertTrue("圆点必须小于胶囊，否则画出来是溢出的一团",
                dp(R.dimen.switch_height) > dp(R.dimen.switch_thumb))
        assertTrue("组间距不该小于卡内留白，否则分组分不开",
                dp(R.dimen.setting_group_gap) >= dp(R.dimen.setting_row_inset))
    }

    private companion object {
        /** dp 上的浮点容差：资源是 px 整数，除以密度后允许半像素级的误差。 */
        const val TOLERANCE = 0.01f
    }
}
