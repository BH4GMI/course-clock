package courseclock.timetable

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.schedule_import.ImportViewModel
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 分享文件的**脏输入**行为。允许的结果只有两种：
 *
 * 1. 成功导入；或
 * 2. 抛出一个**带可读文案的异常**（调用方直接把 `message` 弹给用户）。
 *
 * 不允许：抛 `Error`（OOM / StackOverflow 之类，那就是崩溃而不是"可处理的失败"）、或者抛一个
 * `message` 为空的异常（界面会显示一句空原因，比报错更糟）。
 *
 * 这一类的价值在于它不需要人脑枚举每一种畸形：把形状各异的坏文件一次喂进去，只看上面这条
 * 性质成立不成立。文件类型判断只看文件名，所以这里的 URI 一律带 `wakeup_schedule`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImportMalformedInputTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val viewModel = ImportViewModel(application)
    private val db = AppDatabase.getDatabase(application)

    /** `AppDatabase` 是单例，同一测试类里各方法共享同一个库：先清空再断言。 */
    @Before
    fun clearDatabase() = runBlocking {
        db.tableDao().clearAllTables()
        db.timeTableDao().clearAllTimeTables()
    }

    @Test
    fun 畸形文件只会给出可读原因不会崩() = runBlocking {
        for ((name, bytes) in malformedPayloads()) {
            val thrown = importRaw(name, bytes)
            assertFalse("$name 抛出的是 Error（${thrown?.javaClass?.name}）—— 那属于崩溃，不是可处理的失败",
                    thrown is Error)
            assertTrue("$name 失败时必须带可读原因，实际 message=${thrown?.message}",
                    thrown == null || !thrown.message.isNullOrBlank())
        }
    }

    @Test
    fun 正好等于上限不被当成超限而走格式判断() = runBlocking {
        // 1 MB 全是 'x'：没有换行 → 1 行 → 应当以"文件格式不对"结束，而不是"文件过大"。
        val thrown = importRaw("exactly_at_cap", ByteArray(1 shl 20) { 'x'.code.toByte() })

        assertNotNull("1 MB 的垃圾内容不可能导入成功", thrown)
        assertFalse("恰好等于上限不算超限，实际：${thrown?.message}",
                thrown?.message?.contains("文件过大") == true)
        assertTrue("仍然要给可读原因，实际：${thrown?.message}", !thrown?.message.isNullOrBlank())
    }

    private suspend fun importRaw(name: String, bytes: ByteArray): Throwable? {
        val uri = Uri.parse("content://courseclock.test/$name.wakeup_schedule")
        shadowOf(application.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return try {
            viewModel.importFromFile(uri)
            null
        } catch (t: Throwable) {
            t
        }
    }

    /** 形状各异的坏文件。`.wakeup_schedule` 是"每行一个 JSON 文档"，共 5 行。 */
    private fun malformedPayloads(): List<Pair<String, ByteArray>> {
        fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)
        return listOf(
                "empty" to ByteArray(0),
                "four_lines" to utf8("1\n2\n3\n4\n"),
                "nulls" to utf8("null\nnull\nnull\nnull\nnull\n"),
                "truncated_json" to utf8("{\n{\n{\n{\n{\n"),
                "empty_objects" to utf8("{}\n{}\n{}\n{}\n{}\n"),
                "empty_arrays" to utf8("[]\n[]\n[]\n[]\n[]\n"),
                "numbers_instead_of_objects" to utf8("1\n1\n1\n1\n1\n"),
                "strings_instead_of_objects" to utf8("\"a\"\n\"a\"\n\"a\"\n\"a\"\n\"a\"\n"),
                "valid_but_wrong_shape" to utf8("{\"a\":1}\n{\"a\":1}\n{\"a\":1}\n{\"a\":1}\n{\"a\":1}\n"),
                "one_huge_line" to utf8("x".repeat(200_000) + "\n1\n2\n3\n4\n"),
                "non_utf8_garbage" to ByteArray(2048) { (it % 256).toByte() },
                "blank_lines" to utf8("\n\n\n\n\n"))
    }
}
