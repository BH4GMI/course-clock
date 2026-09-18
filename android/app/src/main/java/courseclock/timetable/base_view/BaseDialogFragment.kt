package androidx.fragment.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import courseclock.timetable.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 全 App 的"从底部滑出的圆角面板"。
 *
 * ## 为什么继承 [BottomSheetDialogFragment] 而不是自己调窗口
 *
 * 这里原本是 `DialogFragment` + `onStart` 里手设窗口几何（MATCH_PARENT × WRAP_CONTENT、
 * `Gravity.BOTTOM`、透明底、32% 遮罩）。几何是对的，**交互是错的**：那样得到的只是"贴在屏幕
 * 下边的一个对话框"，没有底部面板真正的那套效果 —— 上滑入场、按住往下拖就能关、拖到一半松手
 * 会回弹，遮罩也不跟着手指走。首页右上角「…」那个面板用的是 `BottomSheetBehavior`，
 * 于是同一屏上三个按钮弹出三种观感（这正是要统一掉的那件事）。
 *
 * [BottomSheetDialogFragment] 是 Material 为这件事提供的**原生**入口：它自带
 * `BottomSheetDialog` + `BottomSheetBehavior`，上滑/下拖/回弹/遮罩联动全部由框架负责，
 * 各面板不必再各自实现一遍。所以统一动作发生在**这一层**：改一处，7 个面板一起变成真面板。
 *
 * 面板自己的外观（把手 + `page_background` 圆角卡片）仍由 [R.layout.fragment_base_dialog] 提供；
 * `design_bottom_sheet` 那层自带的白色圆角底要抹成透明，否则会叠出两层圆角、两个把手。
 */
abstract class BaseDialogFragment : BottomSheetDialogFragment() {

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = lifecycleScope.launch {
        lifecycle.whenStarted(block)
    }

    @get:LayoutRes
    protected abstract val layoutId: Int

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_base_dialog, container, false)
        val cardView = root.findViewById<MaterialCardView>(R.id.base_card_view)
        LayoutInflater.from(context).inflate(layoutId, cardView, true)
        return root
    }

    /**
     * 面板一打开就是完全展开态。
     *
     * 为什么还要显式设 `STATE_EXPANDED`：[BottomSheetDialog] 默认按 `peekHeight=AUTO` 先落在
     * 折叠态，面板会从"只露一条"开始，和首页「…」的直接展开不一样。
     *
     * 这里**不**去改 `design_bottom_sheet` 的背景（那是徒劳的，见
     * [R.drawable.bg_bottom_sheet_panel] 的说明）：面板自己的底由 fragment_base_dialog 画。
     */
    override fun onResume() {
        super.onResume()
        val sheet = dialog as? BottomSheetDialog ?: return
        sheet.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            isHideable = true
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        // fragment 1.6 起 mDismissed/mShownByMe 已私有化，无法再手动复位；
        // 保留"允许状态丢失时显示"的原始行为
        val ft = manager.beginTransaction()
        ft.add(this, tag)
        ft.commitAllowingStateLoss()
    }

}
