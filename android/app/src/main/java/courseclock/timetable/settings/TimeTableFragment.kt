package courseclock.timetable.settings

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseFragment
import es.dmoral.toasty.Toasty
import splitties.dimensions.dip
import splitties.snackbar.longSnack

/**
 * 选择时间表（设计稿 10）：一张卡装全部作息方案，每行写清「几节 · 每节多长」，
 * 当前用的一行打勾；下面一条「+ 新建时间表」浅强调按钮和一张「什么是分楼错峰」解释卡。
 *
 * 行交互：点 = 选中（保存仍是右上角「保存」按钮的职责，宿主 [TimeSettingsActivity]）；
 * 长按 = 编辑作息 / 删除——旧版把「编辑」「删除」做成两枚 40dp 图标按钮、把删除确认
 * 藏在「长按删除按钮」后面，现在收进一个长按菜单，默认时间表（id = 1）不给删。
 */
class TimeTableFragment : BaseFragment() {

    private val viewModel by activityViewModels<TimeSettingsViewModel>()
    private lateinit var adapter: TimeTableAdapter
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arguments = arguments
        viewModel.selectedId = arguments!!.getInt("selectedId")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.time_table_fragment, container, false)
        recyclerView = view.findViewById(R.id.rv_time_table)
        initRecyclerView()

        // viewLifecycleOwner：本页是导航起点，视图会随往返销毁重建而实例保留——
        // 挂 fragment 生命周期的话每往返一次就多叠一个观察者，同一批数据会被处理 N 遍。
        viewModel.getTimeTableList().observe(viewLifecycleOwner, Observer {
            if (it == null) return@Observer
            viewModel.timeTableList.clear()
            viewModel.timeTableList.addAll(it)
            reloadSummariesThenNotify()
        })

        view.findViewById<View>(R.id.tv_new_time_table).setOnClickListener { showCreateTableDialog() }
        return view
    }

    /** 摘要是库里算出来的（ suspend ）：列表到位后再补一轮，算完只刷新一次。 */
    private fun reloadSummariesThenNotify() {
        launch {
            try {
                adapter.summaries = viewModel.timeTableSummaries()
            } catch (e: Exception) {
                // 摘要算不出来（库被改坏等）不挡列表：行上显示「还没有设置作息」。
                adapter.summaries = emptyMap()
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun initRecyclerView() {
        val context = context ?: return
        adapter = TimeTableAdapter(R.layout.item_time_table, viewModel.timeTableList, viewModel.selectedId)
        adapter.setOnItemClickListener { _, _, position ->
            adapter.selectedId = viewModel.timeTableList[position].id
            viewModel.selectedId = viewModel.timeTableList[position].id
            adapter.notifyDataSetChanged()
        }
        adapter.setOnItemLongClickListener { _, _, position ->
            showRowActions(position)
            true
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val paint = Paint().apply {
                color = ContextCompat.getColor(context, R.color.list_divider)
                strokeWidth = context.dip(1).toFloat()
            }

            override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                val inset = context.dip(16).toFloat()
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    val position = parent.getChildAdapterPosition(child)
                    if (position == RecyclerView.NO_POSITION || position == 0) continue
                    c.drawLine(inset, child.top.toFloat(), (parent.width - inset), child.top.toFloat(), paint)
                }
            }
        })
        recyclerView.adapter = adapter
    }

    /** 长按一行：编辑作息 / 删除。默认表（id = 1）不可删（界面上连入口都不给）。 */
    private fun showRowActions(position: Int) {
        val table = viewModel.timeTableList[position]
        val items = mutableListOf("编辑作息")
        if (table.id != 1) items.add("删除时间表")
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(table.name)
                .setItems(items.toTypedArray()) { _, which ->
                    when (items[which]) {
                        "编辑作息" -> {
                            viewModel.entryPosition = position
                            val bundle = Bundle()
                            bundle.putInt("position", position)
                            Navigation.findNavController(recyclerView).navigate(R.id.timeTableFragment_to_timeSettingsFragment, bundle)
                        }
                        "删除时间表" -> {
                            if (table.id == viewModel.selectedId) {
                                view?.longSnack("不能删除已选中的时间表哦>_<")
                            } else {
                                launch {
                                    try {
                                        // 不弹"删除成功"：那一行从列表里消失本身就是回执。
                                        viewModel.deleteTimeTable(table)
                                    } catch (e: Exception) {
                                        // 现在这个分支真的会走到了：被课表引用的时间表在入口被拒绝删除。
                                        view?.longSnack("删除失败：${e.message ?: "未知错误"}")
                                    }
                                }
                            }
                        }
                    }
                }
                .show()
    }

    private fun showCreateTableDialog() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("时间表名字")
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
                        viewModel.addNewTimeTable(editText.text.toString())
                        Toasty.success(requireActivity().applicationContext, "新建成功~").show()
                    } catch (e: Exception) {
                        Toasty.error(requireActivity().applicationContext, "发生异常>_<${e.message}").show()
                    }
                    dialog.dismiss()
                }
            }
        }
    }

}
