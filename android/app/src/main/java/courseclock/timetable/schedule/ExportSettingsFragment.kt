package courseclock.timetable.schedule

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.BaseDialogFragment
import androidx.fragment.app.activityViewModels
import courseclock.timetable.R
import courseclock.timetable.databinding.FragmentExportSettingsBinding
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.Haptics
import es.dmoral.toasty.Toasty
import com.google.android.material.card.MaterialCardView

class ExportSettingsFragment : BaseDialogFragment() {

    override val layoutId: Int
        get() = R.layout.fragment_export_settings

    private var _binding: FragmentExportSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel by activityViewModels<ScheduleViewModel>()

    /**
     * 导出文件名的默认名。课表可能尚未就绪（见 ScheduleViewModel.tableOrNull）：
     * 拿不到名字就退回「我的课表」，不裸读 lateinit —— 这个 Fragment 的展示入口
     * 已经有 requireTable 把关，这里是对同一契约的容错兜底。
     */
    val tableName by lazy(LazyThreadSafetyMode.NONE) {
        viewModel.tableOrNull()?.tableName?.takeIf { it.isNotEmpty() } ?: "我的课表"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentExportSettingsBinding.bind(view.findViewById<MaterialCardView>(R.id.base_card_view).getChildAt(0))
        // 刻意**不**写 isCancelable = false：这是个从底部滑出的面板，用户想退出时最顺手的
        // 三个动作（返回手势/返回键、点面板外的遮罩、点「取消导出」）全都应该管用。
        // 之前锁成不可取消，返回手势按下去毫无反应，在全面屏手势的机器上就等于"退不出去"。
        isCancelable = true

        binding.tvExport.setOnClickListener {
            Haptics.tap(binding.tvExport)
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "$tableName.wakeup_schedule")
            }
            Toasty.info(requireActivity(), "请自行选择导出的地方\n不要修改文件的扩展名哦", Toasty.LENGTH_LONG).show()
            activity?.startActivityForResult(intent, Const.REQUEST_CODE_EXPORT)
            dismiss()
        }

        binding.tvExportIcs.setOnClickListener {
            Haptics.tap(binding.tvExportIcs)
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/calendar"
                // CREATE_DOCUMENT 不会自动补扩展名，缺了 .ics 大多数日历应用不认。
                putExtra(Intent.EXTRA_TITLE, "日历-$tableName.ics")
            }
            Toasty.info(requireActivity(), "请自行选择导出的地方\n不要修改文件的扩展名哦", Toasty.LENGTH_LONG).show()
            activity?.startActivityForResult(intent, Const.REQUEST_CODE_EXPORT_ICS)
            dismiss()
        }

        binding.tvCancel.setOnClickListener {
            Haptics.tap(binding.tvCancel)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
