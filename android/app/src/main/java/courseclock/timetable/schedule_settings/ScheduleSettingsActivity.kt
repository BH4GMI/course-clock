package courseclock.timetable.schedule_settings

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseListActivity
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TableSelectBean
import courseclock.timetable.schedule_manage.ScheduleManageActivity
import courseclock.timetable.settings.SettingItemAdapter
import courseclock.timetable.settings.SwitchView
import courseclock.timetable.settings.TimeSettingsActivity
import courseclock.timetable.settings.settingRowTapFeedback
import courseclock.timetable.settings.items.*
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.BackgroundStore
import courseclock.timetable.widget.colorpicker.ColorPickerFragment
import es.dmoral.toasty.Toasty
import splitties.activities.start
import splitties.dimensions.dip
import splitties.snackbar.longSnack
import java.util.Calendar

private const val TITLE_COLOR = 1
private const val COURSE_TEXT_COLOR = 2
private const val STROKE_COLOR = 3
private const val WIDGET_COURSE_TEXT_COLOR = 5
private const val WIDGET_STROKE_COLOR = 6

class ScheduleSettingsActivity : BaseListActivity(), ColorPickerFragment.ColorPickerDialogListener {

    override fun onColorSelected(dialogId: Int, color: Int) {
        when (dialogId) {
            TITLE_COLOR -> viewModel.table.textColor = color
            COURSE_TEXT_COLOR -> viewModel.table.courseTextColor = color
            STROKE_COLOR -> viewModel.table.strokeColor = color
            WIDGET_COURSE_TEXT_COLOR -> viewModel.table.widgetCourseTextColor = color
            WIDGET_STROKE_COLOR -> viewModel.table.widgetStrokeColor = color
        }
    }

    private val viewModel by viewModels<ScheduleSettingsViewModel>()
    private val mAdapter = SettingItemAdapter()
    private val REQUEST_CODE_CHOOSE_BG = 23
    private val REQUEST_CODE_CHOOSE_TABLE = 21
    private val allItems = mutableListOf<BaseSettingItem>()
    private val showItems = mutableListOf<BaseSettingItem>()

    private val currentWeekItem by lazy(LazyThreadSafetyMode.NONE) {
        SeekBarItem(ScheduleRowId.CURRENT_WEEK, "当前周", viewModel.getCurrentWeek(), 1, viewModel.table.maxWeek, "周", "第", keys = listOf("学期", "周", "日期", "开学", "开始", "时间"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        showSearch = true
        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                showItems.clear()
                if (s.isNullOrBlank() || s.isEmpty()) {
                    showItems.addAll(allItems)
                } else {
                    showItems.add(CategoryItem("搜索结果"))
                    showItems.addAll(allItems.filter {
                        val k = it.keyWords
                        k?.contains(s.toString()) ?: false
                    })
                }
                mRecyclerView.adapter?.notifyDataSetChanged()
                if (showItems.size == 1) {
                    mRecyclerView.longSnack("未找到相关设置，请更换关键词")
                }
            }
        }
        super.onCreate(savedInstanceState)
        viewModel.table = intent.extras!!.getParcelable<TableBean>("tableData") as TableBean

        //onAdapterCreated(mAdapter)

        onItemsCreated(allItems)
        showItems.addAll(allItems)
        mAdapter.data = showItems
        mRecyclerView.layoutManager = LinearLayoutManager(this)
        mRecyclerView.itemAnimator?.changeDuration = 250
        mRecyclerView.adapter = mAdapter
        // 开关自己不可点（见 SwitchView）：点击整行即可。这里**不要**再注册子视图点击 ——
        // 注册了 BRVAH 会给它挂一个 clickListener，点开关就只走子视图那条路、整行的回调不触发，
        // 而"把开关当前的 isChecked 当成新状态"读到的还是旧值，表现是"点开关没反应"。
        mAdapter.setOnItemClickListener { _, view, position ->
            view.settingRowTapFeedback()
            when (val item = showItems[position]) {
                is HorizontalItem -> onHorizontalItemClick(item, position)
                is VerticalItem -> onVerticalItemClick(item)
                // 开关自己 isClickable=false（点击整行即可），所以这里直接把状态取反后走同一套处理，
                // 而不是去 performClick 一个不可点的控件。
                is SwitchItem -> onSwitchItemCheckChange(item, !item.checked, view)
                is SeekBarItem -> onSeekBarItemClick(item, position)
            }
        }
        mAdapter.setOnItemLongClickListener { _, _, position ->
            when (val item = showItems[position]) {
                is VerticalItem -> onVerticalItemLongClick(item)
            }
            true
        }
        // 尚未设置开学日期（空串）时，日期选择器先落在今天
        viewModel.termStartList = viewModel.table.startDate.split("-")
        val today = Calendar.getInstance().apply { timeInMillis = courseclock.timetable.utils.CourseClock.nowMillis() }
        viewModel.mYear = viewModel.termStartList.getOrNull(0)?.toIntOrNull() ?: today.get(Calendar.YEAR)
        viewModel.mMonth = viewModel.termStartList.getOrNull(1)?.toIntOrNull() ?: (today.get(Calendar.MONTH) + 1)
        viewModel.mDay = viewModel.termStartList.getOrNull(2)?.toIntOrNull() ?: today.get(Calendar.DATE)
        val settingItem = intent?.extras?.getString("settingItem")
        if (settingItem != null) {
            mRecyclerView.postDelayed({
                try {
                    val i = showItems.indexOfFirst {
                        it.title == settingItem
                    }
                    // 找不到目标行（deep-link 的标题和当前列表对不上）就只滚动不点击，
                    // 静默吞掉会让人以为「设置项没生效」。
                    (mRecyclerView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                            if (i >= 0) i else 0, dip(64))
                    if (i >= 0) {
                        when (showItems[i]) {
                            is HorizontalItem -> onHorizontalItemClick(showItems[i] as HorizontalItem, i)
                            is VerticalItem -> onVerticalItemClick(showItems[i] as VerticalItem)
                            is SeekBarItem -> onSeekBarItemClick(showItems[i] as SeekBarItem, i)
                        }
                    } else {
                        Log.w("ScheduleSettings", "deep-link 找不到设置项: $settingItem")
                    }
                } catch (e: Exception) {
                    Log.w("ScheduleSettings", "deep-link 打开失败: $settingItem", e)
                }
            }, 100)
        }
    }

    private fun onItemsCreated(items: MutableList<BaseSettingItem>) {
        items.add(CategoryItem("课程数据"))
        items.add(HorizontalItem(ScheduleRowId.TABLE_NAME, "课表名称", viewModel.table.tableName, keys = listOf("名称", "名字", "名", "课表")))
        items.add(HorizontalItem(ScheduleRowId.CLASS_TIME, "上课时间", "", desc = "每节课的起止时间", keys = listOf("作息", "时间")))
        items.add(HorizontalItem(ScheduleRowId.TERM_START, "学期开始日期", viewModel.table.startDate, keys = listOf("学期", "周", "日期", "开学", "开始", "时间")))
        items.add(currentWeekItem)
        items.add(HorizontalItem(ScheduleRowId.MANAGE_COURSE, "管理课程", "", keys = listOf("课程", "课")))
        items.add(SeekBarItem(ScheduleRowId.NODES, "每天显示节数", viewModel.table.nodes, 1, 30, "节", keys = listOf("节数", "数量", "数")))
        items.add(SeekBarItem(ScheduleRowId.MAX_WEEK, "学期周数", viewModel.table.maxWeek, 1, 30, "周", keys = listOf("学期", "周", "时间")))
        items.add(SwitchItem(ScheduleRowId.SUNDAY_FIRST, "周日为每周第一天", viewModel.table.sundayFirst, keys = listOf("周日", "第一天", "起始", "星期天", "天")))
        items.add(SwitchItem(ScheduleRowId.SHOW_SAT, "显示周六", viewModel.table.showSat, keys = listOf("周六", "显示", "星期六", "六")))
        items.add(SwitchItem(ScheduleRowId.SHOW_SUN, "显示周日", viewModel.table.showSun, keys = listOf("周日", "显示", "星期日", "日", "星期天", "周天")))

        items.add(CategoryItem("课表外观"))
        items.add(SwitchItem(ScheduleRowId.SHOW_TIME_IN_CELL, "课程格子内显示时间", viewModel.table.showTime, keys = listOf("时间", "显示", "格子", "上课时间")))
        items.add(VerticalItem(ScheduleRowId.TABLE_BACKGROUND, "课程表背景", "长按恢复默认背景", keys = listOf("背景", "显示", "图片")))
        items.add(VerticalItem(ScheduleRowId.UI_TEXT_COLOR, "课表标题与时间颜色", "支持透明度调整", keys = listOf("颜色", "显示", "文字", "文字颜色")))
        items.add(VerticalItem(ScheduleRowId.COURSE_TEXT_COLOR, "课程文字颜色", "课程格子内的文字", keys = listOf("颜色", "显示", "文字", "文字颜色")))
        items.add(VerticalItem(ScheduleRowId.STROKE_COLOR, "课程格子边框颜色", "完全透明时隐藏边框", keys = listOf("边框", "显示", "边框颜色", "格子", "边")))
        items.add(SeekBarItem(ScheduleRowId.ITEM_HEIGHT, "课程格子基准高度", viewModel.table.itemHeight, 32, 96, "dp", keys = listOf("格子", "高度", "格子高度", "显示")))
        items.add(SeekBarItem(ScheduleRowId.ITEM_ALPHA, "课程格子不透明度", viewModel.table.itemAlpha, 0, 100, "%", keys = listOf("格子", "透明", "格子高度", "显示")))
        items.add(SeekBarItem(ScheduleRowId.ITEM_TEXT_SIZE, "课程字号", viewModel.table.itemTextSize, 8, 16, "sp", keys = listOf("文字", "大小", "文字大小")))
        items.add(SwitchItem(ScheduleRowId.SHOW_OTHER_WEEK, "显示非本周课程", viewModel.table.showOtherWeekCourse, keys = listOf("非本周")))

        items.add(CategoryItem("桌面小部件外观"))
        items.add(SeekBarItem(ScheduleRowId.WIDGET_ITEM_HEIGHT, "周视图格子高度", viewModel.table.widgetItemHeight, 32, 96, "dp", keys = listOf("格子", "高度", "小部件", "桌面")))
        items.add(SeekBarItem(ScheduleRowId.WIDGET_ITEM_ALPHA, "周视图格子不透明度", viewModel.table.widgetItemAlpha, 0, 100, "%", keys = listOf("格子", "透明", "小部件", "桌面")))
        items.add(SeekBarItem(ScheduleRowId.WIDGET_ITEM_TEXT_SIZE, "小组件字号", viewModel.table.widgetItemTextSize, 8, 16, "sp", keys = listOf("文字", "大小", "小部件", "桌面")))
        items.add(VerticalItem(ScheduleRowId.WIDGET_COURSE_COLOR, "周视图课程文字颜色", "课程格子内的文字", keys = listOf("颜色", "文字", "小部件", "桌面")))
        items.add(VerticalItem(ScheduleRowId.WIDGET_STROKE_COLOR, "周视图课程边框颜色", "完全透明时隐藏边框", keys = listOf("边框", "颜色", "小部件", "桌面")))

        items.add(VerticalItem(ScheduleRowId.BOTTOM_SPACER, "", "\n\n\n"))
    }

    private fun onSwitchItemCheckChange(item: SwitchItem, isChecked: Boolean, rowView: View? = null) {
        // 用户点的那一行：让活着的那枚开关自己滑，不重绑（理由见 SettingsActivity.setSwitch）。
        rowView?.findViewById<SwitchView>(R.id.anko_switch)?.animateTo(isChecked)
        when (item.id) {
            ScheduleRowId.SUNDAY_FIRST -> viewModel.table.sundayFirst = isChecked
            ScheduleRowId.SHOW_SAT -> viewModel.table.showSat = isChecked
            ScheduleRowId.SHOW_SUN -> viewModel.table.showSun = isChecked
            ScheduleRowId.SHOW_TIME_IN_CELL -> viewModel.table.showTime = isChecked
            ScheduleRowId.SHOW_OTHER_WEEK -> viewModel.table.showOtherWeekCourse = isChecked
        }
        item.checked = isChecked
        // 只补读屏用的整行描述：不 notifyItemChanged（那会另造 ViewHolder，把正在滑动的
        // 开关和它的 itemView 一起换掉，动画白播、落在替换窗口里的点击还会丢）。
        rowView?.contentDescription = item.rowContentDescription
    }

    private fun onSeekBarItemClick(item: SeekBarItem, position: Int) {
        val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(item.title)
                .setView(R.layout.dialog_edit_text)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sure, null)
                .setCancelable(false)
                .create()
        dialog.show()
        val inputLayout = dialog.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialog.findViewById<TextInputEditText>(R.id.edit_text)
        inputLayout?.helperText = "范围 ${item.min} ~ ${item.max}"
        if (item.prefix.isNotEmpty()) {
            inputLayout?.prefixText = item.prefix
        }
        inputLayout?.suffixText = item.unit
        editText?.inputType = InputType.TYPE_CLASS_NUMBER
        if (item.valueInt < item.min) {
            item.valueInt = item.min
        }
        if (item.valueInt > item.max) {
            item.valueInt = item.max
        }
        val valueStr = item.valueInt.toString()
        editText?.setText(valueStr)
        editText?.setSelection(valueStr.length)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = editText?.text
            if (value.isNullOrBlank()) {
                inputLayout?.error = "请输入数值"
                return@setOnClickListener
            }
            val valueInt = try {
                value.toString().toInt()
            } catch (e: Exception) {
                inputLayout?.error = "请输入有效整数"
                return@setOnClickListener
            }
            if (valueInt < item.min || valueInt > item.max) {
                inputLayout?.error = "请输入 ${item.min} 至 ${item.max} 之间的整数"
                return@setOnClickListener
            }
            when (item.id) {
                ScheduleRowId.NODES -> viewModel.table.nodes = valueInt
                ScheduleRowId.MAX_WEEK -> {
                    currentWeekItem.max = valueInt
                    viewModel.table.maxWeek = valueInt
                }
                ScheduleRowId.CURRENT_WEEK -> {
                    viewModel.setCurrentWeek(valueInt)
                    item.valueInt = valueInt
                    // 学期开始日期那一行按身份找，不用 position-1：搜索过滤激活时，
                    // position-1 可能是「搜索结果」分类头，按下标强转会直接崩。
                    val dateItem = allItems.filterIsInstance<HorizontalItem>()
                            .firstOrNull { it.id == ScheduleRowId.TERM_START }
                    dateItem?.value = viewModel.table.startDate
                    val dateIndex = dateItem?.let { showItems.indexOf(it) } ?: -1
                    if (dateIndex >= 0) {
                        mAdapter.notifyItemChanged(dateIndex)
                    }
                    mAdapter.notifyItemChanged(position)
                    dialog.dismiss()
                }
                ScheduleRowId.ITEM_HEIGHT -> viewModel.table.itemHeight = valueInt
                ScheduleRowId.ITEM_ALPHA -> viewModel.table.itemAlpha = valueInt
                ScheduleRowId.ITEM_TEXT_SIZE -> viewModel.table.itemTextSize = valueInt
                ScheduleRowId.WIDGET_ITEM_HEIGHT -> viewModel.table.widgetItemHeight = valueInt
                ScheduleRowId.WIDGET_ITEM_ALPHA -> viewModel.table.widgetItemAlpha = valueInt
                ScheduleRowId.WIDGET_ITEM_TEXT_SIZE -> viewModel.table.widgetItemTextSize = valueInt
            }
            item.valueInt = valueInt
            mAdapter.notifyItemChanged(position)
            dialog.dismiss()
        }
    }

    private fun onHorizontalItemClick(item: HorizontalItem, position: Int) {
        when (item.id) {
            ScheduleRowId.TABLE_NAME -> {
                val dialog = MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.setting_schedule_name)
                        .setView(R.layout.dialog_edit_text)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.sure, null)
                        .create()
                dialog.show()
                val inputLayout = dialog.findViewById<TextInputLayout>(R.id.text_input_layout)
                val editText = dialog.findViewById<TextInputEditText>(R.id.edit_text)
                editText?.setText(item.value)
                editText?.setSelection(item.value.length)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = editText?.text
                    if (value.isNullOrBlank()) {
                        inputLayout?.error = "请输入课表名称"
                        return@setOnClickListener
                    }
                    viewModel.table.tableName = value.toString()
                    item.value = value.toString()
                    mAdapter.notifyItemChanged(position)
                    dialog.dismiss()
                }
            }
            ScheduleRowId.TERM_START -> {
                DatePickerDialog(this, DatePickerDialog.OnDateSetListener { _, year, monthOfYear, dayOfMonth ->
                    viewModel.mYear = year
                    viewModel.mMonth = monthOfYear + 1
                    viewModel.mDay = dayOfMonth
                    val mDate = "${viewModel.mYear}-${viewModel.mMonth}-${viewModel.mDay}"
                    item.value = mDate
                    viewModel.table.startDate = mDate
                    currentWeekItem.valueInt = viewModel.getCurrentWeek()
                    mAdapter.notifyItemChanged(position)
                    val weekIndex = showItems.indexOf(currentWeekItem)
                    if (weekIndex >= 0) mAdapter.notifyItemChanged(weekIndex)
                }, viewModel.mYear, viewModel.mMonth - 1, viewModel.mDay).show()
            }
            ScheduleRowId.CLASS_TIME -> {
                startActivityForResult(Intent(this, TimeSettingsActivity::class.java).apply {
                    putExtra("selectedId", viewModel.table.timeTable)
                }, REQUEST_CODE_CHOOSE_TABLE)
            }
            ScheduleRowId.MANAGE_COURSE -> {
                start<ScheduleManageActivity> {
                    putExtra("selectedTable", TableSelectBean(
                            id = viewModel.table.id,
                            background = viewModel.table.background,
                            tableName = viewModel.table.tableName,
                            maxWeek = viewModel.table.maxWeek,
                            nodes = viewModel.table.nodes,
                            type = viewModel.table.type
                    ))
                }
            }
        }
    }

    private fun onVerticalItemClick(item: VerticalItem) {
        when (item.id) {
            ScheduleRowId.TABLE_BACKGROUND -> {
                // 选完立刻复制进 App 私有目录，所以这里只需要"读一下就还"的临时授权：
                // 不再需要 FLAG_GRANT_PERSISTABLE_URI_PERMISSION / takePersistableUriPermission。
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivityForResult(intent, REQUEST_CODE_CHOOSE_BG)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                }
            }
            ScheduleRowId.UI_TEXT_COLOR -> {
                buildColorPickerDialogBuilder(viewModel.table.textColor, TITLE_COLOR)
            }
            ScheduleRowId.COURSE_TEXT_COLOR -> {
                buildColorPickerDialogBuilder(viewModel.table.courseTextColor, COURSE_TEXT_COLOR)
            }
            ScheduleRowId.STROKE_COLOR -> {
                buildColorPickerDialogBuilder(viewModel.table.strokeColor, STROKE_COLOR)
            }
            ScheduleRowId.WIDGET_COURSE_COLOR -> {
                buildColorPickerDialogBuilder(viewModel.table.widgetCourseTextColor, WIDGET_COURSE_TEXT_COLOR)
            }
            ScheduleRowId.WIDGET_STROKE_COLOR -> {
                buildColorPickerDialogBuilder(viewModel.table.widgetStrokeColor, WIDGET_STROKE_COLOR)
            }
        }
    }

    private fun onVerticalItemLongClick(item: VerticalItem): Boolean {
        return when (item.id) {
            ScheduleRowId.TABLE_BACKGROUND -> {
                // 文件随设置成功保存后释放，返回前仍能恢复原配置。
                viewModel.setBackground("")
                Toasty.success(applicationContext, "已恢复默认背景").show()
                true
            }
            else -> false
        }
    }

    private fun buildColorPickerDialogBuilder(color: Int, id: Int) {
        ColorPickerFragment.newBuilder()
                .setShowAlphaSlider(true)
                .setColor(color)
                .setDialogId(id)
                .show(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CHOOSE_BG && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                // 图片**复制进 App 私有目录**再存路径，不再存 content:// URI：存 URI 就得依赖
                // 那条读权限一直有效（临时授权会过期、对方 FilesProvider 会失效），真机表现就是
                // 每次进课表弹「无法检索背景图片」。复制进来之后只有 App 自己能删掉它。
                val stored = BackgroundStore.import(this, viewModel.table.id, uri)
                if (stored != null) {
                    viewModel.setBackground(stored)
                } else {
                    Toasty.error(this, "这张图片读不出来（可能已被移动或删除），背景未更改",
                            Toasty.LENGTH_LONG).show()
                }
            }
        }
        if (requestCode == REQUEST_CODE_CHOOSE_TABLE && resultCode == RESULT_OK) {
            viewModel.table.timeTable = data!!.getIntExtra("selectedId", 1)
        }
    }

    override fun onBackPressed() {
        launch {
            viewModel.saveSettings()
            // 设置先落库、再让两个小部件整块重画：刷新目标以平台登记的实例为准（见 AppWidgetUtils.refreshAllWidgets），
            // 而小部件渲染时是从数据库里读课表设置的，所以顺序不能反。
            AppWidgetUtils.refreshAllWidgets(applicationContext)
            setResult(RESULT_OK)
            finish()
        }
    }
}
