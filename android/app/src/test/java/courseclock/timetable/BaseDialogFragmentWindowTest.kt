package courseclock.timetable

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.BaseDialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import courseclock.timetable.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 底部面板是不是**真的**底部面板。
 *
 * 为什么单独立一条、而不是并进 `CourseDetailRenderTest`：那些用例量的是 fragment 的**视图**
 * （自己 measure/layout 一遍），量不到窗口与行为。而这正是出过问题的地方，且只在真机上现形：
 * 早先 `DialogFragment` 在 `onCreateView` 里设的窗口尺寸会被 `onStart` 里的 `show()` 丢掉，
 * 窗口被量成 `wrap×wrap`，设计稿要的"贴底面板"变成屏幕中间一条窄长条（深色主题下卡片近黑，
 * 看着就是一条黑杠）—— 视图层断言全绿，真机还是错的。
 *
 * 现在这条用例钉住的是**机制**：面板必须由 `BottomSheetDialog`（`BottomSheetBehavior`）承载。
 * 只要这一点成立，"铺满宽度、贴底、能上滑入场、能下拖关闭"就都由框架保证，不再是每个面板
 * 各自手搓窗口属性 —— 手搓那套的失效模式（属性设在不生效的时机上）也就没有了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BaseDialogFragmentWindowTest {

    /** 只要一个最小面板：内容用导入课程那一份（同样属于底部面板家族）。 */
    class ProbeFragment : BaseDialogFragment() {
        override val layoutId: Int
            get() = R.layout.fragment_import_choose
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 面板是真正的底部面板而不是居中对话框() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val fragment = ProbeFragment()
        fragment.show(activity.supportFragmentManager, "probe")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val dialog = requireNotNull(fragment.dialog) { "面板没有窗口" }
        assertTrue("面板必须由 BottomSheetDialog 承载，否则就没有上滑入场与下拖关闭这一套效果：" +
                "实际是 ${dialog.javaClass.name}", dialog is BottomSheetDialog)
        val sheet = dialog as BottomSheetDialog

        val sheetView = requireNotNull(
                sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)) {
            "没有 design_bottom_sheet：底部面板的容器不在，几何与拖拽都不会生效"
        }
        // 面板自己的底必须由我们的根布局画出来：`design_bottom_sheet` 那层表面色是 Material 在
        // 内容挂载后套上的、代码里改不掉（见 bg_bottom_sheet_panel.xml），所以只能盖住它。
        // 根视图没有背景 = 把手位置会多一条框架色的横带。
        assertNotNull("面板根视图没有自己的底色，框架那层表面色会从把手位置露出来",
                requireNotNull(fragment.view) { "面板没有视图" }.background)
        assertEquals("面板根视图的宽必须铺满，否则两侧会露出框架那层底",
                ViewGroup.LayoutParams.MATCH_PARENT,
                fragment.view!!.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT)
        assertTrue("面板必须可以下拖关闭（与首页「…」那个面板一致）", sheet.behavior.isHideable)

        // 内容确实渲染出来了：面板本体而不是空壳。
        val card = sheet.findViewById<View>(R.id.base_card_view)
        assertTrue("面板里的卡片视图不在，底部面板是个空壳", card != null)
        assertTrue("design_bottom_sheet 里没有内容", (sheetView as ViewGroup).childCount > 0)
    }
}
