package courseclock.timetable

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Toast 背景色必须真的是半透明的。
 *
 * 这些颜色定义在 App 的 `res/values/toasty_colors.xml` 里，靠"App 资源覆盖库资源"
 * 生效——Toasty 1.4.2 的 `Config` 没有颜色 API（已反编译确认），资源层是它唯一的入口。
 * 这里特意用**库自己的 R** 去读，走库代码同一条解析路径：只要合并规则、库版本、颜色名
 * 任何一环变化让覆盖失效，读到的不透明库默认值就会让这里变红。
 *
 * 钉住的是两件事：alpha 恰为 0xD9（85%，"不要完全不透明"的量化落点），RGB 与库默认
 * 完全一致（我们只改透明度，不顺手改配色）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ToastyBackgroundOverrideTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `all five Toasty backgrounds are 85 percent translucent`() {
        val expected = mapOf(
                es.dmoral.toasty.R.color.normalColor to 0xD9353A3E.toInt(),
                es.dmoral.toasty.R.color.errorColor to 0xD9D50000.toInt(),
                es.dmoral.toasty.R.color.successColor to 0xD9388E3C.toInt(),
                es.dmoral.toasty.R.color.infoColor to 0xD93F51B5.toInt(),
                es.dmoral.toasty.R.color.warningColor to 0xD9FFA900.toInt())
        expected.forEach { (id, want) ->
            assertEquals("颜色资源 $id 应被 App 覆盖为 85% 半透明，实际读到库的不透明默认值",
                    want, context.getColor(id))
            assertEquals("只允许改 alpha，RGB 不能动", 0xD9, Color.alpha(context.getColor(id)))
        }
    }

    @Test
    fun `toast text stays fully opaque for readability`() {
        assertEquals("文字色不跟随背景变透明，否则半透明底上白字会糊",
                0xFFFFFFFF.toInt(), context.getColor(es.dmoral.toasty.R.color.defaultTextColor))
    }
}
