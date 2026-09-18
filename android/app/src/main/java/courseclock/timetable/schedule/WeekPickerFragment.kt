package courseclock.timetable.schedule

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.BaseDialogFragment
import courseclock.timetable.R
import courseclock.timetable.databinding.FragmentWeekPickerBinding
import com.google.android.material.card.MaterialCardView
import splitties.dimensions.dip
import splitties.resources.styledColor

/**
 * 周数选择面板（设计稿 2）：点顶栏那一行日期/周次时从底部滑出，选一周即切换。
 *
 * 为什么单独一个面板、而不是继续用「更多」那个控制中心面板：设计稿 2 的面板里只有周次
 * （「周数 / 共 N 周」+ 1..N 的格子），它是一个**一次只做一件事**的选择器；把周次塞在
 * 新建课表、更换背景、捷径那一堆按钮中间，用户得先在一屏里找它。控制中心仍然留在
 * 「更多」里（那是它的位置），这一屏只管"看第几周 / 换到第几周"。
 *
 * 结果用 [setFragmentResult] 回传：调用方（ScheduleActivity）注册监听器即可，
 * 面板不需要认识宿主是谁，也不需要回调字段（fragment 重建后回调会丢，结果不会）。
 */
class WeekPickerFragment : BaseDialogFragment() {

    override val layoutId: Int
        get() = R.layout.fragment_week_picker

    private var _binding: FragmentWeekPickerBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWeekPickerBinding.bind(
                view.findViewById<MaterialCardView>(R.id.base_card_view).getChildAt(0))

        val maxWeek = requireArguments().getInt(ARG_MAX_WEEK)
        val currentWeek = requireArguments().getInt(ARG_CURRENT_WEEK)
        binding.tvWeekTotal.text = getString(R.string.week_picker_total, maxWeek)
        fillWeekChips(maxWeek, currentWeek)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 铺 1..[maxWeek] 的周次格子，一行 7 个（设计稿 2）。
     *
     * 当前周是**实心强调色 + 白字**，其余是卡片色：用户扫一眼图就知道自己在第几周，
     * 而不用去找哪一个是选中的（描边式的选中态在这一屏会跟"未来的周"糊在一起）。
     */
    private fun fillWeekChips(maxWeek: Int, currentWeek: Int) {
        val grid = binding.gridWeek
        val ctx = requireContext()
        val size = ctx.dip(40) to ctx.dip(36)
        val gap = ctx.dip(3)
        for (week in 1..maxWeek) {
            val selected = week == currentWeek
            grid.addView(AppCompatTextView(ctx).apply {
                text = week.toString()
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                // 文字色一律走主题属性：深浅两套主题都不用再写第二份（选中项压在强调色上，
                // 取的是"强调色之上的前景色"，不是写死白字）。
                setTextColor(ctx.styledColor(
                        if (selected) R.attr.colorOnPrimary else R.attr.colorOnBackground))
                if (selected) typeface = Typeface.DEFAULT_BOLD
                background = chipBackground(ctx, selected)
                // 不要给 GridLayout 的 spec 加权重：带权重的 spec 在 GridLayout 里是"扩展填满
                // 剩余空间"，格子会被撑开（设计稿的格子是固定 40dp，一行排完在右边留一点点空）。
                layoutParams = GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED),
                        GridLayout.spec(GridLayout.UNDEFINED)).apply {
                    width = size.first
                    height = size.second
                    setMargins(gap, gap, gap, gap)
                }
                setOnClickListener {
                    // 选周 = 状态切换，用轻一档的 tick（与课表页周数按钮同一个档位）。
                    courseclock.timetable.utils.Haptics.tick(this)
                    parentFragmentManager.setFragmentResult(
                            REQUEST_KEY, Bundle().apply { putInt(RESULT_WEEK, week) })
                    dismiss()
                }
            })
        }
    }

    /** 格子底：当前周用强调色，其余用卡片色（设计稿里其余格子是"卡片"那一档的底色）。 */
    private fun chipBackground(context: android.content.Context,
                               selected: Boolean): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dip(12).toFloat()
                setColor(ContextCompat.getColor(context,
                        if (selected) R.color.colorPrimary else R.color.card_background))
            }

    companion object {

        const val REQUEST_KEY = "week_picker"
        const val RESULT_WEEK = "week"

        private const val ARG_MAX_WEEK = "maxWeek"
        private const val ARG_CURRENT_WEEK = "currentWeek"

        /**
         * @param maxWeek 这张课表一共有多少周（决定格子数量，也就是「共 N 周」）
         * @param currentWeek 当前选中的周；0 表示"还没开学/没设置"，此时没有格子是实心的
         */
        fun newInstance(maxWeek: Int, currentWeek: Int) = WeekPickerFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_MAX_WEEK, maxWeek)
                putInt(ARG_CURRENT_WEEK, currentWeek)
            }
        }
    }
}
