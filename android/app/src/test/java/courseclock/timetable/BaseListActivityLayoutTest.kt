package courseclock.timetable

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import courseclock.timetable.base_view.BaseListActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 列表类页面的骨架约束：**列表挂在标题栏下面**。
 *
 * 为什么值得单独立一条测试：这一条不成立时页面照样"能开、能滚、不报错"，只是第一组的分组标题
 * 与第一行被标题栏压住 —— 真机上打开设置页看到的第一行是第二行，"设置当前课表"整行既看不见
 * 也点不到。这种问题靠肉眼看列表内容发现不了（内容本身都是对的），只能靠这条几何关系锁住。
 *
 * 用 [ProbeActivity] 而不是真的 SettingsActivity：这条契约属于 [BaseListActivity]，
 * 与某一页的列表内容无关；用最小子类测，任何一页改坏都会在这里先亮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BaseListActivityLayoutTest {

    /** 只为拿到 [BaseListActivity] 的骨架；没有标题、没有数据。 */
    class ProbeActivity : BaseListActivity() {
        fun titleBarOf(): View = rootView.findViewById(R.id.anko_layout)
        fun listOf(): RecyclerView = mRecyclerView
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 列表的顶边接着标题栏的底边() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup().get()
        val root = activity.rootView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        val titleBar = activity.titleBarOf()
        val list = activity.listOf()
        assertTrue("标题栏没量出高度，测试本身失去意义", titleBar.bottom > 0)
        assertEquals("列表没有接在标题栏下面（列表会被标题栏压住）：" +
                "标题栏底 ${titleBar.bottom}px，列表顶 ${list.top}px",
                titleBar.bottom, list.top)
    }
}
