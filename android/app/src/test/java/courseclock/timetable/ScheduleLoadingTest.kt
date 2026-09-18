package courseclock.timetable

import android.app.Application
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.schedule.ScheduleViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ScheduleLoadingTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var model: ScheduleViewModel

    @Before
    fun prepare() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        AppDatabase.getDatabase(application).close()
        application.deleteDatabase("wakeup")
        database = AppDatabase.getDatabase(application)
        runBlocking {
            database.timeTableDao().insertTimeTable(TimeTableBean(1, "作息"))
            database.tableDao().insertTable(TableBean(1, "课表一"))
            database.tableDao().insertTable(TableBean(2, "课表二"))
            database.courseDao().insertSingleCourse(CourseBaseBean(1, "单周课", "", 1), listOf(
                    CourseDetailBean(1, 1, null, null, 1, 2, 1, 8, 1, 1)))
            database.courseDao().insertSingleCourse(CourseBaseBean(2, "未来课", "", 1), listOf(
                    CourseDetailBean(2, 2, null, null, 1, 2, 5, 8, 0, 1)))
        }
        model = ScheduleViewModel(application)
        model.table = TableBean(1, "课表一", showOtherWeekCourse = false)
    }

    @After
    fun cleanup() {
        database.close()
        application.deleteDatabase("wakeup")
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun <T> LiveData<T>.awaitValue(predicate: (T) -> Boolean): T {
        val ready = CountDownLatch(1)
        var result: T? = null
        val observer = Observer<T> { value ->
            if (predicate(value)) {
                result = value
                ready.countDown()
            }
        }
        observeForever(observer)
        try {
            assertTrue("课程查询未在期限内返回预期数据", ready.await(5, TimeUnit.SECONDS))
            return requireNotNull(result)
        } finally {
            removeObserver(observer)
        }
    }

    @Test
    fun 共享课程数据保持单双周和未来课程的空状态规则() {
        assertEquals(1, model.getRawCourseByDay(1, 1).awaitValue { it.size == 1 }.size)
        assertEquals(1, model.getShowCourseNumber(1).awaitValue { it == 1 })
        assertEquals(0, model.getShowCourseNumber(2).awaitValue { it == 0 })
        model.table.showOtherWeekCourse = true
        assertEquals(2, model.getShowCourseNumber(2).awaitValue { it == 2 })
        assertEquals(0, model.getShowCourseNumber(9).awaitValue { it == 0 })
    }

    @Test
    fun 切换空课表不会复用上一张课表数据() {
        model.getRawCourseByDay(1, 1).awaitValue { it.size == 1 }
        model.table = TableBean(2, "课表二")
        assertTrue(model.getRawCourseByDay(1, 2).awaitValue { it.isEmpty() }.isEmpty())
        assertEquals(0, model.getShowCourseNumber(1).awaitValue { it == 0 })
    }

    @Test
    fun 删除课程后同一数据源会刷新课程和空状态() {
        val day = model.getRawCourseByDay(1, 1)
        val count = model.getShowCourseNumber(1)
        day.awaitValue { it.size == 1 }
        count.awaitValue { it == 1 }
        runBlocking { database.courseDao().deleteDetailByIdOfTable(1, 1) }
        assertTrue(day.awaitValue { it.isEmpty() }.isEmpty())
        assertEquals(0, count.awaitValue { it == 0 })
    }
}
