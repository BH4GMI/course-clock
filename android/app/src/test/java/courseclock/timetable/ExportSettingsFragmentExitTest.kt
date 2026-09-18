package courseclock.timetable

import android.os.Looper
import androidx.fragment.app.FragmentActivity
import courseclock.timetable.schedule.ExportSettingsFragment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「分享/导出」面板必须**能退出去**。
 *
 * 它是个从底部滑出的面板（见 `BaseDialogFragment`），用户想退出时最顺手的三个动作是
 * 返回手势/返回键、点面板外的遮罩、点「取消导出」。之前这里锁着 `isCancelable = false`，
 * 前两个动作按下去毫无反应 —— 在全面屏手势的机器上就等于"退不出去"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExportSettingsFragmentExitTest {

    @Test
    fun `export panel can be dismissed by back gesture or scrim tap`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val fragment = ExportSettingsFragment()
        fragment.show(activity.supportFragmentManager, null)
        shadowOf(Looper.getMainLooper()).idle()

        // 真正决定"返回手势/点遮罩管不管用"的是对话框自己的 cancelable 标记，
        // 而它由 DialogFragment.isCancelable 同步下来。
        assertTrue("面板必须可取消，否则返回键与点遮罩都会被无视", fragment.isCancelable)
        assertTrue("面板得真的在屏幕上，不然这条用例什么都没验到",
                fragment.dialog?.isShowing == true)
    }
}
