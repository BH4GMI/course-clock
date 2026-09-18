package courseclock.timetable

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.settings.TimeSettingsFragment
import courseclock.timetable.settings.TimeSettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class TimeSettingsLifecycleTest {
    @Test
    fun 编辑视图重建后旧列表不再接收数据库更新() {
        val application: Application = ApplicationProvider.getApplicationContext()
        AppDatabase.getDatabase(application).close()
        application.deleteDatabase("wakeup")
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        val database = AppDatabase.getDatabase(application)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        try {
            val table = TimeTableBean(1, "作息")
            val detail = TimeDetailBean(1, "08:00", "08:50", 1)
            runBlocking {
                database.timeTableDao().insertTimeTable(table)
                database.timeDetailDao().insertTimeList(listOf(detail))
            }
            val activity = controller.setup().get()
            val model = ViewModelProvider(activity).get(TimeSettingsViewModel::class.java)
            model.timeTableList.add(table)
            val fragment = TimeSettingsFragment().apply {
                arguments = Bundle().apply { putInt("position", 0) }
            }
            val manager = activity.supportFragmentManager
            manager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val oldAdapter = fragment.requireView().findViewById<RecyclerView>(R.id.rv_time_detail).adapter!!
            var oldRefreshes = 0
            oldAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { oldRefreshes++ }
            })
            manager.beginTransaction().detach(fragment).commitNow()
            runBlocking {
                database.timeDetailDao().updateTimeDetailList(listOf(detail.copy(startTime = "09:00")))
            }
            manager.beginTransaction().attach(fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("已销毁视图的观察者必须移除", 0, oldRefreshes)
            assertEquals("新视图仍应加载更新后的作息", "09:00", model.timeList.single().startTime)
        } finally {
            controller.pause().stop().destroy()
            database.close()
            application.deleteDatabase("wakeup")
            ArchTaskExecutor.getInstance().setDelegate(null)
        }
    }
}
