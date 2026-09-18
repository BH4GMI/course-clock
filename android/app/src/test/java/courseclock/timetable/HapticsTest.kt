package courseclock.timetable

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.utils.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 触感反馈的三个档位必须各自对应一个**平台语义常量**。
 *
 * 这一条值得钉：用户反馈"震动太少了"，很容易顺手改成"到处都震"，而真正要守的是
 * "按语义选常量"——它决定了系统「触感」开关与强弱档位还管不管用（见 [Haptics] 的说明）。
 * 只要有人把某一档换成普通 `VIRTUAL_KEY`，或者干脆去掉，这里就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HapticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val view = View(context)

    @Test
    fun `tap is a context click`() {
        Haptics.tap(view)
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK,
                shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test
    fun `tick is lighter than tap`() {
        Haptics.tick(view)
        assertEquals("切换类操作要用更轻的一档，否则连翻几周会发麻",
                HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test
    fun `long press is reported as a long press`() {
        Haptics.longPress(view)
        assertEquals(HapticFeedbackConstants.LONG_PRESS,
                shadowOf(view).lastHapticFeedbackPerformed())
    }

    /**
     * 三档互不相同：同一屏里轻点、切换、长按必须能被手指区分出来，
     * 否则"分级"只是写在注释里。
     */
    @Test
    fun `the three levels are actually different`() {
        val views = List(3) { View(context) }
        Haptics.tap(views[0])
        Haptics.tick(views[1])
        Haptics.longPress(views[2])
        val constants = views.map { shadowOf(it).lastHapticFeedbackPerformed() }
        assertEquals("三个档位必须互不相同", 3, constants.toSet().size)
        assertTrue(constants.none { it == HapticFeedbackConstants.NO_HAPTICS })
    }
}
