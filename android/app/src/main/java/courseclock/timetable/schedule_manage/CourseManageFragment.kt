package courseclock.timetable.schedule_manage

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseFragment
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.TableSelectBean
import courseclock.timetable.course_add.AddCourseActivity
import courseclock.timetable.databinding.FragmentCourseManageBinding
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.Const
import es.dmoral.toasty.Toasty
import splitties.dimensions.dip

/**
 * 课程管理（设计稿 8）：顶部搜索、课程数一行、两列课程卡片（底色 = 课程色）、
 * 末尾一格「添加课程」虚线格，右下角加号保留，标题栏右上角「清空」带二次确认。
 *
 * 卡片三行：课程名 / 教师 · 教室 / 周几第几节——数据来自「基础课 join 时间段」，
 * 一门课多个时间段时取最早的一个展示，卡片里只负责「扫一眼认出这门课」。
 * 点卡片进编辑、长按删课（二次确认）；两处入口都用 startActivityForResult，
 * 回来重拉整表——以前「编辑后列表不刷新」就是编辑入口用普通 start 导致结果没人接收。
 */
class CourseManageFragment : BaseFragment() {

    private val viewModel by activityViewModels<ScheduleManageViewModel>()
    private val table by lazy(LazyThreadSafetyMode.NONE) {
        arguments?.getParcelable<TableSelectBean>("selectedTable")
    }
    private lateinit var adapter: CourseCardAdapter
    private var _binding: FragmentCourseManageBinding? = null
    private val binding get() = _binding!!

    /** 全量卡片（未过滤）；搜索只改 [adapter] 提交的子集。 */
    private var allItems: List<CourseCardItem> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        _binding = FragmentCourseManageBinding.inflate(inflater, container, false)
        if (table == null) {
            return binding.root
        }
        initRecyclerView()
        launch { loadCourses() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (table == null) return
        binding.fabAdd.setOnClickListener { startAddCourse(-1) }
        binding.etSearch.doAfterTextChanged { text -> applyFilter(text?.toString().orEmpty()) }
        val act = activity as ScheduleManageActivity
        act.subButton?.setOnClickListener {
            MaterialAlertDialogBuilder(requireActivity())
                    .setTitle("提示")
                    .setMessage("真的要清空课表吗？这将无法恢复。")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.sure) { _, _ ->
                        launch {
                            try {
                                viewModel.clearTable(table!!.id)
                                Toasty.success(requireActivity(), "操作成功~").show()
                                // 清空改变了课表数据，桌面小部件要跟着重画（与单课删除同一路径）。
                                AppWidgetUtils.refreshAllWidgets(requireActivity().applicationContext)
                                loadCourses()
                            } catch (e: Exception) {
                                Toasty.error(requireActivity(), "操作失败>_<${e.message}").show()
                            }
                        }
                    }
                    .show()
        }
    }

    private fun initRecyclerView() {
        val context = context ?: return
        adapter = CourseCardAdapter()
        adapter.onAddClick = { startAddCourse(-1) }
        adapter.onCourseClick = { item -> startAddCourse(item.id) }
        adapter.onCourseLongClick = { item -> confirmDelete(item) }
        binding.rvList.layoutManager = GridLayoutManager(context, 2, RecyclerView.VERTICAL, false)
        val space = context.dip(8)
        binding.rvList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View,
                                        parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(space, space, space, space)
            }
        })
        binding.rvList.adapter = adapter
    }

    /** 拉整表课程并按基础课归组：展示字段取最早的一个时间段（卡片只负责「认出这门课」）。 */
    private suspend fun loadCourses() {
        val context = context ?: return
        if (table == null) return
        val courses = viewModel.getCourseListOfTable(table!!.id)
        allItems = courses.groupBy { it.id }.map { (_, rows) ->
            val first = rows.minByOrNull { it.day * 100 + it.startNode } ?: rows.first()
            CourseCardItem(
                    id = first.id,
                    tableId = first.tableId,
                    name = first.courseName,
                    color = first.color,
                    teacher = first.teacher,
                    room = first.room,
                    day = first.day,
                    startNode = first.startNode,
                    step = first.step
            )
        }
        applyFilter(binding.etSearch.text?.toString().orEmpty())
    }

    /** 搜索：课程名 / 教师 / 教室包含即命中（不区分大小写）；空查询回到全量。 */
    private fun applyFilter(query: String) {
        val context = context ?: return
        val q = query.trim()
        val filtered = if (q.isEmpty()) allItems else allItems.filter { item ->
            item.name.contains(q, true) ||
                    (item.teacher ?: "").contains(q, true) ||
                    (item.room ?: "").contains(q, true)
        }
        adapter.submit(filtered)
        binding.tvCount.text = if (q.isEmpty()) {
            // 无过滤时顺带告诉用户「轻触编辑、长按删除」——删除没有可见入口，这句就是说明。
            context.getString(R.string.course_manage_count_hint, allItems.size)
        } else {
            context.getString(R.string.course_manage_found, filtered.size)
        }
        val empty = allItems.isEmpty()
        val noResult = !empty && filtered.isEmpty()
        binding.llEmpty.visibility = if (empty || noResult) View.VISIBLE else View.GONE
        binding.tvEmptyText.text = if (noResult) context.getString(R.string.course_manage_no_result, q)
        else context.getString(R.string.course_manage_empty)
        // 空态盖在网格中间说清楚原因；虚线格仍在末尾，随时可以加课。
    }

    private fun startAddCourse(courseId: Int) {
        val intent = Intent(activity, AddCourseActivity::class.java).apply {
            putExtra("id", courseId)
            putExtra("tableId", table!!.id)
            putExtra("maxWeek", table!!.maxWeek)
            putExtra("nodes", table!!.nodes)
        }
        startActivityForResult(intent, Const.REQUEST_CODE_ADD_COURSE)
    }

    private fun confirmDelete(item: CourseCardItem) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle("提示")
                .setMessage("确定要删除「${item.name}」吗？它的所有时间段都将会被删除。")
                .setPositiveButton(R.string.sure) { _, _ ->
                    launch {
                        viewModel.deleteCourse(CourseBaseBean(
                                id = item.id, courseName = item.name,
                                color = item.color, tableId = item.tableId))
                        Toasty.success(requireContext(), "删除成功~").show()
                        // 小部件刷新一律以平台登记的实例为准（见 AppWidgetUtils.refreshAllWidgets）：
                        // 自建的 AppWidgetBean 表可能一行都没有（配置页只在有多张课表时才写入），
                        // 拿它当实例清单会漏刷新。
                        AppWidgetUtils.refreshAllWidgets(requireActivity().applicationContext)
                        loadCourses()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == android.app.Activity.RESULT_OK && requestCode == Const.REQUEST_CODE_ADD_COURSE) {
            // 新增和编辑都可能改数据（新增回传 course，编辑只回 RESULT_OK），统一重拉整表。
            launch { loadCourses() }
        }
    }
}
