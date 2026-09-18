package courseclock.timetable

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.settings.ArrowView
import courseclock.timetable.settings.items.HorizontalItem
import courseclock.timetable.settings.items.SettingRowArrow
import courseclock.timetable.settings.provider.HorizontalItemProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页行内绑定的**结构不变量**：绑定阶段不允许增删子视图。
 *
 * 这条不是洁癖 —— 真机上崩过（`docs/BUGFIX-MANUAL.md` §21 记档）：
 *
 * ```
 * java.lang.NullPointerException: 'void android.view.View.unFocus(android.view.View)' on a null object reference
 *   at android.view.ViewGroup.removeViewInternal(ViewGroup.java:5652)
 *   at courseclock.timetable.settings.provider.HorizontalItemProvider.convert(HorizontalItemProvider.kt:110)
 *   at androidx.recyclerview.widget.GapWorker.prefetch(GapWorker.java:368)   ← RecyclerView 预取时的绑定
 * ```
 *
 * 原来是"箭头种类变了就 `removeViewAt` + `addView` 换一个视图"，而预取绑定发生在
 * RecyclerView 自己的时机上，容器的焦点簿记（`mFocused`）此刻可能还没值 → 空指针。
 * 根因修法是**换箭头不换视图**（[ArrowView.arrow] 可变）。这里就钉住那个修法：
 * 同一份视图连续绑两次、两次箭头种类不同，子视图数量与箭头实例都必须不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsBindingStabilityTest {

    private val context: Context =
            ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)

    @Test
    fun 换箭头种类时绑定阶段不增删子视图() {
        val provider = HorizontalItemProvider()
        val parent = FrameLayout(context)
        val holder = provider.onCreateViewHolder(parent, provider.itemViewType)
        val row: ViewGroup = holder.itemView as ViewGroup
        val arrowBefore = row.findViewById<ArrowView>(R.id.anko_iv_arrow)
        val childCountBefore = row.childCount
        // childCount 相等抓不到"原地 remove 再 add" —— 而真机崩溃正是 removeViewAt 引发的，
        // 所以这里直接盯层级变更事件本身。
        val events = mutableListOf<String>()
        row.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parentView: View, child: View) {
                events += "add:${child.javaClass.simpleName}"
            }

            override fun onChildViewRemoved(parentView: View, child: View) {
                events += "remove:${child.javaClass.simpleName}"
            }
        })

        provider.convert(holder, row(item = "arrow_select", arrow = SettingRowArrow.SELECT))
        provider.convert(holder, row(item = "arrow_navigate", arrow = SettingRowArrow.NAVIGATE))
        provider.convert(holder, row(item = "arrow_select", arrow = SettingRowArrow.SELECT))

        assertEquals("绑定阶段不得增删子视图（真机那条崩溃就是 removeViewAt 触发的）",
                emptyList<String>(), events)
        assertEquals("子视图数量也不该变", childCountBefore, row.childCount)
        assertSame("箭头必须始终是同一个实例",
                arrowBefore, row.findViewById<ArrowView>(R.id.anko_iv_arrow))
        assertEquals("三次绑定后应当停在最后一次给的种类",
                SettingRowArrow.SELECT, row.findViewById<ArrowView>(R.id.anko_iv_arrow).arrow)
    }

    /** 箭头为 null 的纯展示行：只改可见性，同样不许换视图。 */
    @Test
    fun 纯展示行的箭头只看可见性也不换视图() {
        val provider = HorizontalItemProvider()
        val parent = FrameLayout(context)
        val holder = provider.onCreateViewHolder(parent, provider.itemViewType)
        val row: ViewGroup = holder.itemView as ViewGroup
        val arrow = row.findViewById<ArrowView>(R.id.anko_iv_arrow)
        val childCountBefore = row.childCount
        val events = mutableListOf<String>()
        row.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parentView: View, child: View) {
                events += "add"
            }

            override fun onChildViewRemoved(parentView: View, child: View) {
                events += "remove"
            }
        })

        provider.convert(holder, row(item = "no_arrow", arrow = null))

        assertSame("箭头实例不该被换掉", arrow, row.findViewById<ArrowView>(R.id.anko_iv_arrow))
        assertEquals("绑定阶段不得增删子视图", emptyList<String>(), events)
        assertEquals("子视图数量也不该变", childCountBefore, row.childCount)
    }

    private fun row(item: String, arrow: SettingRowArrow?) = HorizontalItem(
            id = item,
            name = "标题",
            value = "值",
            desc = "解释",
            arrow = arrow)
}
