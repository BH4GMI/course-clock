package courseclock.timetable.schedule

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.BaseDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import courseclock.timetable.R
import courseclock.timetable.schedule_import.Common
import courseclock.timetable.schedule_import.LoginWebActivity
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.Haptics
import courseclock.timetable.databinding.FragmentImportChooseBinding
import com.google.android.material.card.MaterialCardView

class ImportChooseFragment : BaseDialogFragment() {

    override val layoutId: Int
        get() = R.layout.fragment_import_choose

    private var _binding: FragmentImportChooseBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentImportChooseBinding.bind(view.findViewById<MaterialCardView>(R.id.base_card_view).getChildAt(0))
        initEvent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initEvent() {
        // 取消：整块 48dp 高的中性按钮（设计稿 5 没有右上角的 ×，取消放在最下面、离手指近）。
        binding.tvCancel.setOnClickListener {
            Haptics.tap(binding.tvCancel)
            dismiss()
        }

        // 整块选项卡片可点（80dp 高），不是只有标题那一行能点。
        binding.tvSues.setOnClickListener {
            Haptics.tap(binding.tvSues)
            requireActivity().startActivityForResult(
                    Intent(activity, LoginWebActivity::class.java).apply {
                        putExtra("import_type", Common.TYPE_SUES)
                        putExtra("school_name", "上海工程技术大学")
                        putExtra("url", Common.SUES_WEBVPN_URL)
                    },
                    Const.REQUEST_CODE_IMPORT)
            dismiss()
        }

        binding.tvFile.setOnClickListener {
            Haptics.tap(binding.tvFile)
            showSAFTips {
                requireActivity().startActivityForResult(
                        Intent(activity, LoginWebActivity::class.java).apply {
                            putExtra("import_type", "file")
                        },
                        Const.REQUEST_CODE_IMPORT)
                this.dismiss()
            }
        }
    }

    private fun showSAFTips(block: () -> Unit) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle("提示")
                .setMessage("为了避免使用敏感的外部存储读写权限，本应用采用了系统级的文件选择器来选择文件。如果找不到路径，请点选择器右上角的三个点，选择「显示内部存储设备」，然后通过侧栏选择路径。")
                .setPositiveButton(R.string.sure) { _, _ ->
                    block.invoke()
                }
                // 这是一条"往下走之前先看一眼"的提示，不是必须作答的确认框：
                // 用户按返回手势/点遮罩就是"我不看了"，锁住只会让人以为界面卡死。
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

}
