package courseclock.timetable.schedule_manage

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseFragment
import courseclock.timetable.bean.TableSelectBean
import courseclock.timetable.databinding.FragmentTableManageBinding
import courseclock.timetable.schedule_settings.ScheduleSettingsActivity
import courseclock.timetable.utils.AppWidgetUtils
import es.dmoral.toasty.Toasty
import splitties.dimensions.dip
import splitties.resources.color
import kotlinx.coroutines.CancellationException
import android.util.Log

/**
 * 多课表管理（设计稿 7）：每张课表一张卡片。
 *
 * 交互按设计稿副标题的口径：
 * - **点卡片 = 切换**到这张课表（换掉默认表后刷新小部件；主页回来时经
 *   `onActivityResult → initView()` 重载）。
 * - 卡片右侧操作按钮与长按共用课表设置、管理课程、删除课表菜单；删除须二次确认。
 * - 「+ 新建课表」是一条明确的按钮，不再用右下角加号。
 */
class ScheduleManageFragment : BaseFragment() {

    private val viewModel by activityViewModels<ScheduleManageViewModel>()
    private lateinit var adapter: TableListAdapter

    companion object {
        /** 「编辑课表信息」的请求码：返回时重拉列表（改名/改周数后卡片不能停在旧数据）。 */
        private const val REQUEST_EDIT_TABLE = 102
    }
    private var _binding: FragmentTableManageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        _binding = FragmentTableManageBinding.inflate(inflater, container, false)
        launch { reloadList() }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EDIT_TABLE && resultCode == android.app.Activity.RESULT_OK) {
            launch { reloadList() }
        }
    }

    /** 拉一次课表列表 + 课程数，绑到 RecyclerView 上（切换/删除后整表重拉，口径只有一条）。 */
    private suspend fun reloadList() {
        val context = context ?: return
        val data = viewModel.initTableSelectList()
        val counts = viewModel.getCourseCounts()
        val view = _binding?.root ?: return
        adapter = TableListAdapter(R.layout.item_table_card, data, counts, ::showTableActions)
        adapter.setOnItemClickListener { _, _, position ->
            switchTo(data[position])
        }
        adapter.setOnItemLongClickListener { _, _, position ->
            showTableActions(data[position])
            true
        }
        adapter.addHeaderView(AppCompatTextView(context).apply {
            text = context.getString(R.string.table_manage_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(context.dip(20), context.dip(12), context.dip(20), 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        adapter.addFooterView(initFooterView())
        view.findViewById<RecyclerView>(R.id.rv_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ScheduleManageFragment.adapter
        }
    }

    /** 点卡片：已是当前表就不动；否则换默认表、刷新小部件、整表重拉。 */
    private fun switchTo(target: TableSelectBean) {
        if (target.type == 1) return
        launch {
            try {
                val currentId = viewModel.initTableSelectList().firstOrNull { it.type == 1 }?.id ?: target.id
                viewModel.switchDefaultTable(currentId, target.id)
                AppWidgetUtils.refreshAllWidgets(requireContext().applicationContext)
                Toasty.success(requireContext(), "已切换到「${target.tableName.ifEmpty { "我的课表" }}」").show()
                reloadList()
            } catch (e: Exception) {
                Toasty.error(requireContext().applicationContext, "切换失败>_${e.message}").show()
            }
        }
    }

    /** 可见操作按钮与长按共用菜单；当前表也可删除，默认表转移由 DAO 事务维护。 */
    private fun showTableActions(table: TableSelectBean) {
        val name = table.tableName.ifEmpty { "我的课表" }
        val items = listOf("课表设置", "管理课程", "删除课表")
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(name)
                .setItems(items.toTypedArray()) { _, which ->
                    when (items[which]) {
                        "课表设置" -> launch {
                            val task = viewModel.getTableById(table.id)
                            if (task != null) {
                                // 带结果启动：课表设置页自己保存后返回，本页刷新卡片
                                // （改名/改周数后卡片不能再停在旧数据上）。
                                startActivityForResult(Intent(activity,
                                        ScheduleSettingsActivity::class.java).apply {
                                    putExtra("tableData", task)
                                }, REQUEST_EDIT_TABLE)
                            } else {
                                Toasty.error(requireContext().applicationContext, "课表不存在，请返回刷新").show()
                            }
                        }
                        "管理课程" -> {
                            val bundle = Bundle()
                            bundle.putParcelable("selectedTable", table)
                            Navigation.findNavController(binding.root)
                                    .navigate(R.id.scheduleManageFragment_to_courseManageFragment, bundle)
                        }
                        "删除课表" -> confirmDelete(table)
                    }
                }
                .show()
    }

    /** 删除整表连同课程 CASCADE 清掉，不可恢复——必须确认（同页单课删除、清空课表都确认）。 */
    private fun confirmDelete(table: TableSelectBean) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle("删除课表")
                .setMessage("删除「${table.tableName.ifEmpty { "我的课表" }}」及其中全部课程？此操作无法撤销。" +
                        if (table.type == 1) "\n删除后使用剩余课表；没有其他课表时显示无课表页面。" else "")
                .setPositiveButton("删除") { _, _ ->
                    launch {
                        val appContext = requireContext().applicationContext
                        try {
                            viewModel.deleteTable(table.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("ScheduleManage", "删除课表失败", e)
                            Toasty.error(appContext, "删除失败，课表未删除，请重试").show()
                            return@launch
                        }
                        Toasty.success(appContext, "课表已删除").show()
                        try {
                            reloadList()
                            AppWidgetUtils.refreshAllWidgets(appContext)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("ScheduleManage", "课表已删除，视图刷新失败", e)
                            Toasty.error(appContext, "课表已删除，显示未更新，请重新打开应用").show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    /** 「+ 新建课表」浅强调按钮 + 页脚说明，都挂在卡片流末尾（设计稿 7 的收尾）。 */
    private fun initFooterView(): View {
        val context = requireActivity()
        return LinearLayoutCompat(context).apply {
            orientation = LinearLayoutCompat.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(AppCompatTextView(context).apply {
                text = context.getString(R.string.table_manage_new)
                textSize = 15.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.colorAccent))
                gravity = android.view.Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_soft_button)
                layoutParams = LinearLayoutCompat.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, context.dip(48)).apply {
                    marginStart = context.dip(16)
                    marginEnd = context.dip(16)
                    topMargin = context.dip(20)
                }
                setOnClickListener { showCreateTableDialog() }
            })
            addView(AppCompatTextView(context).apply {
                text = context.getString(R.string.table_manage_footnote)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                layoutParams = LinearLayoutCompat.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = context.dip(20)
                    topMargin = context.dip(24)
                    bottomMargin = context.dip(24)
                }
            })
        }
    }

    private fun showCreateTableDialog() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_schedule_name)
                .setView(R.layout.dialog_edit_text)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sure, null)
                .create()
        dialog.show()
        val inputLayout = dialog.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialog.findViewById<TextInputEditText>(R.id.edit_text)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = editText?.text
            if (value.isNullOrBlank()) {
                inputLayout?.error = "名称不能为空哦>_<"
            } else {
                launch {
                    try {
                        viewModel.addBlankTable(editText.text.toString())
                        Toasty.success(requireContext(), "新建成功~").show()
                        reloadList()
                    } catch (e: Exception) {
                        Toasty.error(requireContext(), "操作失败>_<").show()
                    }
                    dialog.dismiss()
                }
            }
        }
    }

}
