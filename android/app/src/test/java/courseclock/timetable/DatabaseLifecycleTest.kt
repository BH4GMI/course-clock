package courseclock.timetable

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DatabaseLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun closeDatabase() {
        AppDatabase.getDatabase(context).close()
    }

    @Test
    fun 未执行查询时也复用同一个数据库实例() {
        val first = AppDatabase.getDatabase(context)
        assertFalse("Room 尚未打开连接", first.isOpen)
        assertSame("启动时各组件必须共享尚未打开的实例", first, AppDatabase.getDatabase(context))
    }

    @Test
    fun 关闭后重建实例且旧引用再次关闭不影响新实例() {
        val first = AppDatabase.getDatabase(context)
        first.openHelper.writableDatabase
        first.close()
        val second = AppDatabase.getDatabase(context)
        assertNotSame(first, second)
        second.openHelper.writableDatabase
        assertTrue(second.isOpen)
        first.close()
        assertSame(second, AppDatabase.getDatabase(context))
        assertTrue(second.isOpen)
    }
}
