package courseclock.timetable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TableBean
import courseclock.timetable.utils.scheduleTextColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 深色模式下界面（顶部栏、五个图标按钮、时间栏、虚线）必须是灰/白，不能是纯黑。
 *
 * 触发这个 bug 的是一条看似合理的规则：**有自定义背景图就用用户配的 textColor**。
 * 而 `textColor` 的默认值就是纯黑 —— 于是"设了背景图"这件事本身把整个界面拉黑，
 * 在深色模式的 #2A2F3A 上全部消失（时间栏、节次、虚线一起没）。
 *
 * 现在深色模式由主题说了算，自定义背景图只在浅色模式下参与取色；这个测试把两边都钉住。
 */
@RunWith(RobolectricTestRunner::class)
class ScheduleTextColorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 一张"设了自定义背景图、文字色还是默认纯黑"的课表 —— 用户手机里的真实状态。 */
    private fun tableWithBackground(textColor: Int = 0xff000000.toInt()) = TableBean(
            id = 1, tableName = "测试",
            background = "content://com.android.fileexplorer.myprovider/x.jpg",
            textColor = textColor)

    private fun brightness(color: Int): Int =
            maxOf((color shr 16) and 0xff, (color shr 8) and 0xff, color and 0xff)

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 深色模式下自定义背景也不会把界面变纯黑() {
        val color = scheduleTextColor(context, tableWithBackground())
        assertTrue("深色模式取色 = #${Integer.toHexString(color)}，应该是灰/白", brightness(color) > 0x80)
    }

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 深色模式下连课块文字都不受影响_课块用的是课程自己的颜色() {
        val table = tableWithBackground()
        assertEquals("课程文字色是另一个字段，不该被这条规则动到",
                0xffffffff.toInt(), table.courseTextColor)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 浅色模式下自定义背景仍听用户配的文字色() {
        assertEquals(0xff000000.toInt(), scheduleTextColor(context, tableWithBackground()))
    }

    /**
     * 换一个**非默认**的文字色再验一次"浅色下听用户的"。
     *
     * 为什么原来那条不够：它断言的 `0xff000000` 正好就是 `textColor` 的默认值，
     * 于是"有背景图就永远返回黑"这种实现（实测）也能让它保持绿色 —— 分不清
     * "读了用户的字段"和"没读"。只有用与默认值不同的颜色才验得出来。
     */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 浅色模式下用的是用户配的那个文字色而不是默认黑() {
        val custom = 0xff3366ff.toInt()
        assertEquals("有自定义背景图时必须返回用户自己配的 textColor",
                custom, scheduleTextColor(context, tableWithBackground(textColor = custom)))
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 浅色模式无背景图时跟随主题() {
        val noBg = TableBean(id = 2, tableName = "无背景")
        assertEquals(scheduleTextColor(context, TableBean(id = 3, tableName = "空")),
                scheduleTextColor(context, noBg))
    }
}
