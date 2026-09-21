package courseclock.timetable

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.schedule_manage.ScheduleManageActivity
import courseclock.timetable.schedule_appwidget.ScheduleAppWidget
import courseclock.timetable.utils.AppWidgetUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class TableManagementActionsTest {
    @Test fun currentAndLastTableHaveVisibleDeleteActionWithConfirmationAndCascade() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(context)
        db.tableDao().clearAllTables()
        db.timeTableDao().clearAllTimeTables()
        db.timeTableDao().insertTimeTable(TimeTableBean(1, "作息"))
        db.tableDao().insertTable(TableBean(1, "待删除课表", type = 1))
        db.courseDao().insertCourses(listOf(CourseBaseBean(1, "测试课程", "#2979ff", 1)),
                listOf(CourseDetailBean(1, 1, "B210", "教师", 1, 1, 1, 20, 0, 1)))
        val manager = AppWidgetManager.getInstance(context)
        val widgetId = shadowOf(manager).createWidget(ScheduleAppWidget::class.java, R.layout.schedule_app_widget)
        AppWidgetUtils.refreshScheduleWidgets(context)
        assertTrue(shadowOf(manager).getViewFor(widgetId).findViewById<TextView>(R.id.tv_week)
                .text.contains("待删除课表"))
        val controller = Robolectric.buildActivity(ScheduleManageActivity::class.java).setup()
        val activity = controller.get()
        val recycler = activity.findViewById<RecyclerView>(R.id.rv_list)
        fun awaitUi(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                shadowOf(Looper.getMainLooper()).idle()
                recycler.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
                recycler.layout(0, 0, 1080, 1800)
                if (condition()) return
                Thread.yield()
            } while (System.nanoTime() < deadline)
            fail("课表列表未达到预期状态")
        }
        awaitUi { recycler.findViewById<View>(R.id.btn_table_actions) != null }
        val actions = recycler.findViewById<View>(R.id.btn_table_actions)
        assertEquals(View.VISIBLE, actions.visibility)
        assertTrue(actions.contentDescription.contains("待删除课表"))
        actions.performClick()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        val entries = (0 until menu.listView.adapter.count).map { menu.listView.adapter.getItem(it).toString() }
        assertEquals(listOf("课表设置", "管理课程", "删除课表"), entries)
        menu.listView.performItemClick(null, entries.indexOf("删除课表"), 0)
        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(confirm.findViewById<TextView>(android.R.id.message)!!.text.contains("全部课程"))
        assertNotNull("仅打开确认框不能删除", db.tableDao().getTableById(1))
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertNotNull("取消不能删除", db.tableDao().getTableById(1))

        actions.performClick()
        val reopened = ShadowDialog.getLatestDialog() as AlertDialog
        reopened.listView.performItemClick(null, 2, 0)
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        awaitUi { db.tableDao().countTablesSync() == 0 && recycler.findViewById<View>(R.id.btn_table_actions) == null }
        assertNull(db.tableDao().getDefaultTable())
        assertTrue(db.courseDao().getCourseOfTable(1).isEmpty())
        assertNotNull("删除课表不删除可共享的作息表", db.timeTableDao().getTimeTable(1))
        AppWidgetUtils.refreshScheduleWidgets(context)
        val widget = shadowOf(manager).getViewFor(widgetId)
        assertEquals("删除最后一张课表后不应保留旧课表名称", "还没有课表",
                widget.findViewById<TextView>(R.id.tv_week).text.toString())
        assertEquals("删除最后一张课表后隐藏旧课程", View.GONE,
                widget.findViewById<View>(R.id.lv_schedule).visibility)
        AppWidgetUtils.refreshScheduleWidgetFor(context, widgetId)
        assertEquals("调整空小组件尺寸也应保持空状态", "还没有课表",
                shadowOf(manager).getViewFor(widgetId).findViewById<TextView>(R.id.tv_week).text.toString())
        db.tableDao().insertTableAsDefaultIfNone(TableBean(2, "新课表"))
        AppWidgetUtils.refreshScheduleWidgets(context)
        val restored = shadowOf(manager).getViewFor(widgetId)
        assertEquals(View.VISIBLE, restored.findViewById<View>(R.id.lv_schedule).visibility)
        assertTrue(restored.findViewById<TextView>(R.id.tv_week).text.contains("新课表"))
        controller.pause().stop().destroy()
        db.close()
    }
}
