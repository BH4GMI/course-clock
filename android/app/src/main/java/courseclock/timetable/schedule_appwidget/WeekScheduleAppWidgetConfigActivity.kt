package courseclock.timetable.schedule_appwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.LinearLayoutManager
import courseclock.timetable.AppDatabase
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseBlurTitleActivity
import courseclock.timetable.bean.AppWidgetBean
import courseclock.timetable.bean.TableSelectBean
import courseclock.timetable.databinding.ActivityWeekScheduleAppWidgetConfigBinding
import courseclock.timetable.utils.AppWidgetUtils
import es.dmoral.toasty.Toasty
import splitties.snackbar.longSnack

/**
 * 周视图小部件的配置页：选择这个实例显示哪张课表。
 *
 * ## 只在"确实有得选"时才出现
 *
 * 见 [onCreate] 顶部那段**同步**判定：库里只有一张课表（或一张都没有）时直接结束自己，用户看不到
 * 这个页面。宿主（桌面）在放置完成后必然调用它，所以"跳过"必须在它内部完成，而不是靠不声明
 * `android:configure`——不声明就失去了"多课表时让用户挑"的能力。
 *
 * ## 为什么不处理日视图
 *
 * 日视图的 provider（`today_course_app_widget_info.xml`）**没有声明 `android:configure`**，所以
 * 这个页面永远不会为它被拉起。原先这里有一条按 `isTodayType` 分支的"我知道啦"说明页，
 * 既不可达、又停在早已变更的 `refreshTodayWidget` 签名上（第 4 个参数现在是 `Boolean`，不再是
 * 课表），已整体删除。日视图的说明由它的静态预览图承担。
 */
class WeekScheduleAppWidgetConfigActivity : BaseBlurTitleActivity() {

    override val layoutId: Int
        get() = R.layout.activity_week_schedule_app_widget_config

    private lateinit var binding: ActivityWeekScheduleAppWidgetConfigBinding

    override fun onSetupSubButton(tvButton: AppCompatTextView): AppCompatTextView? {
        return null
    }

    private val viewModel by viewModels<WeekScheduleAppWidgetConfigViewModel>()
    private var mAppWidgetId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWeekScheduleAppWidgetConfigBinding.bind(llContent.getChildAt(0))

        val extras = intent.extras
        if (extras != null) {
            mAppWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID)
        }

        // 只有一张课表（或一张都没有）时不弹这个页面。
        //
        // 一张：**库里恰好一行 `type = 1` 是全 App 依赖的不变量**（见 TableDao），所以那一张就是
        //      默认表；不写登记直接结束，小部件按默认表画，结果与"在这里选中它"完全一致 ——
        //      AppWidgetUtils.refreshScheduleWidgets 本来就有"没有登记就用默认课表"的回退。
        // 零张：没有可绑定的课表，与其留一个永远空着的小部件，不如取消这次添加并说明原因
        //      （不设 RESULT_OK 时宿主会把实例删掉）。
        //
        // **必须同步判定**：下面那些 `launch` 最早也要让出一帧，页面就会闪一下 —— 用户看到的
        // 就是"明明只有一张课表，还是弹了一下"。判定放在 super.onCreate 之后、绑定列表之前，
        // 且在同一次 onCreate 里 finish，所以页面不会被绘制出来。
        val tableCount = AppDatabase.getDatabase(applicationContext).tableDao().countTablesSync()
        if (tableCount <= 1) {
            if (tableCount == 1) {
                setResult(Activity.RESULT_OK,
                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId))
            } else {
                Toasty.error(applicationContext, "还没有课表，先在课钟里导入或新建一张吧").show()
            }
            finish()
            return
        }

        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        // 个别启动器在把配置页拉起来的瞬间还没登记实例：getAppWidgetInfo 返回 null，
        // 直接解引用就崩在配置页上，小部件留在「载入出现问题」。此时只能让用户重加一次。
        if (appWidgetManager.getAppWidgetInfo(mAppWidgetId) == null) {
            Toasty.error(applicationContext, "小部件信息读取失败，请删掉后重新添加").show()
            finish()
            return
        }

        val list = ArrayList<TableSelectBean>()
        val adapter = WidgetTableListAdapter(R.layout.item_table_list, list)
        adapter.setOnItemClickListener { _, _, position ->
            launch {
                viewModel.insertWeekAppWidgetData(AppWidgetBean(mAppWidgetId, 0, 0, "${list[position].id}"))
                val table = viewModel.getTableById(list[position].id)

                if (table == null) {
                    Toasty.error(applicationContext, "该课表读取错误>_<").show()
                    finish()
                } else {
                    AppWidgetUtils.refreshScheduleWidget(applicationContext, appWidgetManager, mAppWidgetId, table)
                    val resultValue = Intent()
                    resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId)
                    setResult(Activity.RESULT_OK, resultValue)
                    finish()
                }
            }
        }
        binding.rvList.adapter = adapter
        binding.rvList.layoutManager = LinearLayoutManager(this)
        launch {
            list.clear()
            list.addAll(viewModel.getTableList())
            adapter.notifyDataSetChanged()
        }
    }

    /**
     * 返回键**不**结束自己：这个页面唯一的出口是"选一张课表"。
     *
     * 直接 finish 等于取消添加（宿主会把刚放上去的实例删掉），而用户往往只是还没看清该选哪张。
     */
    override fun onBackPressed() {
        binding.llRoot.longSnack("请从列表中选择需要放置的课表")
    }
}
