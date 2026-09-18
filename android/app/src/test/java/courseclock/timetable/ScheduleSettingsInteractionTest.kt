package courseclock.timetable

import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TableBean
import courseclock.timetable.schedule_settings.ScheduleRowId
import courseclock.timetable.schedule_settings.ScheduleSettingsActivity
import courseclock.timetable.schedule_settings.ScheduleSettingsViewModel
import courseclock.timetable.settings.SettingItemAdapter
import courseclock.timetable.settings.SwitchView
import courseclock.timetable.settings.items.SwitchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScheduleSettingsInteractionTest {
    @Test
    fun allSwitchesUpdateTheirVisibleStateAndModelIncludingSearchResults() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ScheduleSettingsActivity::class.java)
                .putExtra("tableData", TableBean(id = 1, tableName = "Test", startDate = "2026-09-14"))
        val activity = Robolectric.buildActivity(ScheduleSettingsActivity::class.java, intent).setup().get()
        val recycler = activity.rootView.getChildAt(0) as RecyclerView
        val adapter = recycler.adapter as SettingItemAdapter
        val table = ViewModelProvider(activity).get(ScheduleSettingsViewModel::class.java).table
        recycler.itemAnimator = null

        fun layout() {
            shadowOf(Looper.getMainLooper()).idle()
            recycler.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            recycler.layout(0, 0, 1080, 2400)
        }

        fun modelValue(id: String) = when (id) {
            ScheduleRowId.SUNDAY_FIRST -> table.sundayFirst
            ScheduleRowId.SHOW_SAT -> table.showSat
            ScheduleRowId.SHOW_SUN -> table.showSun
            ScheduleRowId.SHOW_TIME_IN_CELL -> table.showTime
            ScheduleRowId.SHOW_OTHER_WEEK -> table.showOtherWeekCourse
            else -> error(id)
        }

        fun toggle(item: SwitchItem, touchSwitch: Boolean) {
            val position = adapter.data.indexOf(item)
            recycler.scrollToPosition(position)
            layout()
            val row = recycler.findViewHolderForAdapterPosition(position)!!.itemView
            val switch = row.findViewById<SwitchView>(R.id.anko_switch)
            val before = item.checked
            assertEquals(before, switch.isChecked)
            if (touchSwitch) {
                val x = switch.x + switch.width / 2f
                val y = switch.y + switch.height / 2f
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(0, 10, action, x, y, 0)
                    row.dispatchTouchEvent(event)
                    event.recycle()
                }
            } else {
                assertTrue(row.performClick())
            }
            layout()
            assertEquals(item.title, !before, item.checked)
            assertEquals(item.title, item.checked, modelValue(item.id))
            val rebound = recycler.findViewHolderForAdapterPosition(position)!!.itemView
            assertEquals("Visible switch: ${item.title}", item.checked,
                    rebound.findViewById<SwitchView>(R.id.anko_switch).isChecked)
            assertTrue(rebound.contentDescription.contains(if (item.checked) "已开启" else "已关闭"))
        }

        val switches = adapter.data.filterIsInstance<SwitchItem>().toList()
        assertEquals(5, switches.size)
        switches.forEach { toggle(it, false); toggle(it, true) }
        activity.searchView.setText("周六")
        layout()
        val saturday = adapter.data.filterIsInstance<SwitchItem>().single()
        toggle(saturday, true)
        toggle(saturday, false)

        // 搜索只留下日期行时，不能再按 position + 1 刷新不存在的“当前周”。
        activity.searchView.setText("开始")
        layout()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                assertTrue(positionStart >= 0 && positionStart + itemCount <= adapter.itemCount)
            }
        })
        assertTrue(recycler.findViewHolderForAdapterPosition(1)!!.itemView.performClick())
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.DatePickerDialog
        dialog.updateDate(2026, 8, 7)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        layout()
        assertEquals("2026-9-7", table.startDate)
    }
}
