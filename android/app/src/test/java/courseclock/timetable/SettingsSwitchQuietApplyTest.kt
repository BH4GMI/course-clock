package courseclock.timetable

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.settings.SettingItemAdapter
import courseclock.timetable.settings.SettingRowId
import courseclock.timetable.settings.SettingsActivity
import courseclock.timetable.settings.items.SwitchItem
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.getPrefer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * 「课表显示 / 外观」一族开关必须**静默生效**。
 *
 * 用户原话：「这一类的toast……要求少出现！占地少！」。历史行为是每拨一个开关就弹一条
 * 全宽 Snackbar（「回到课表就生效哦」「重启App后生效哦」……），价值近乎为零——开关自身的
 * 状态变化就是回执，生效时机改由 [courseclock.timetable.settings.SettingsList] 的行副标题
 * 常驻说明（只在"回来看不到效果"的行上写：重启/切页面/下一次提醒）。
 *
 * 这里在真的 Activity 上把四个开关各拨一次，钉两件事：偏好真的落盘（"静默"不等于"没保存"），
 * 以及全程不出现任何 toast。谁往回加弹窗，这条就红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsSwitchQuietApplyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun toggle(activity: SettingsActivity, id: String) {
        val recycler = activity.rootView.getChildAt(0) as RecyclerView
        val adapter = recycler.adapter as SettingItemAdapter
        val position = adapter.data.indexOfFirst { it is SwitchItem && it.id == id }
        assertTrue("列表里找不到开关行 $id", position >= 0)
        recycler.itemAnimator = null
        recycler.scrollToPosition(position)
        shadowOf(Looper.getMainLooper()).idle()
        recycler.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, 1080, 2400)
        assertTrue(recycler.findViewHolderForAdapterPosition(position)!!.itemView.performClick())
    }

    @Test
    fun displaySwitchesPersistQuietlyWithoutAnyToast() {
        context.getPrefer().edit().clear().commit()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        toggle(activity, SettingRowId.SCHEDULE_GRID)
        toggle(activity, SettingRowId.SCHEDULE_DETAIL_TIME)
        toggle(activity, SettingRowId.SCHEDULE_BLANK_AREA)
        toggle(activity, SettingRowId.SHOW_EMPTY_VIEW)

        assertFalse("开关状态必须落盘", context.getPrefer().getBoolean(Const.KEY_SCHEDULE_GRID, true))
        assertFalse(context.getPrefer().getBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, true))
        assertFalse(context.getPrefer().getBoolean(Const.KEY_SCHEDULE_BLANK_AREA, true))
        assertFalse(context.getPrefer().getBoolean(Const.KEY_SHOW_EMPTY_VIEW, true))

        shadowOf(Looper.getMainLooper()).idle()
        assertNull("拨开关不允许弹任何提示", ShadowToast.getLatestToast())
    }
}
