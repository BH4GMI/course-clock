package courseclock.timetable

import android.app.Application
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.schedule_import.ImportViewModel
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 导入**读文件**那一步的健壮性。
 *
 * **上限**（真问题）：文件类型只看文件名/显示名，原来没有字节上限，一个几百 MB 的同名文件
 * 会被整个读进内存，而"行数不足 5"是读完才判的。
 *
 * **关流**：这里断言"读完真的关流"，钉的是**手写读循环**这个实现 —— 注意 Kotlin 的
 * `bufferedReader().readLines()` **自带关闭**（探针实测 `closed == true`），所以从前那版
 * 并没有泄漏 fd；换成按字节预算读之后，`use` 必须自己写，这条断言就是那个保障。
 *
 * 夹具是**真的** `.wakeup_schedule`（`src/test/resources/sues_345_conflict_test.wakeup_schedule`，
 * 约 9 KB）：上限存在的意义只是防爆，不能误杀合法文件，所以第一条用例就是导入它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImportFileRobustnessTest {

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
    fun 合法文件照常导入且读完就关流() = runBlocking {
        val payload = javaClass.getResourceAsStream("/sues_345_conflict_test.wakeup_schedule")!!
                .use { it.readBytes() }
        val stream = CloseTrackingInputStream(payload)

        viewModel.importFromFile(uriOf("sues_345_conflict_test.wakeup_schedule", stream))

        assertNotNull("合法文件必须能导入，并接管默认课表", db.tableDao().getDefaultTableSync())
        assertTrue("读文件必须关流，否则每导入一次泄漏一个 fd", stream.closed)
    }

    @Test
    fun 超过上限的文件被拒且一个字节都不入库() {
        val oversized = ByteArrayInputStream(ByteArray(MAX_SCHEDULE_BYTES + 1) { 'x'.code.toByte() })
        val error = runCatching {
            runBlocking { viewModel.importFromFile(uriOf("huge.wakeup_schedule", oversized)) }
        }.exceptionOrNull()

        assertNotNull("超限文件必须报错，而不是把整个文件读进内存", error)
        assertTrue("错误文案要能直接给用户看，实际：${error?.message}",
                error?.message?.contains("文件过大") == true)
        assertNull("被拒的文件不允许留下半截数据", db.tableDao().getDefaultTableSync())
    }

    /**
     * 「这个 URI 是不是课表备份」的判定必须**入口与导入器同一份**。
     *
     * 从前 `LoginWebActivity` 自己抄了一份"只看 `uri.path`"的版本，而真实的「分享 / 从文件管理器
     * 打开」给的是 SAF 的 `content://…/document/1234` —— 文件名只在 DISPLAY_NAME 里。结果是：
     * 合法文件在入口就被判成"只能打开 .wakeup_schedule 文件哦"拒掉，而且那条分支还会
     * `CLEAR_TASK` 重启整个 App（用户看到的就是"闪退 + 自动重启"）。
     */
    @Test
    fun 路径里没有文件名但显示名是课表备份时入口必须放行() {
        val uri = Uri.parse("content://downloads/document/1234")
        registerDisplayName(uri, "我的课表.wakeup_schedule")

        assertTrue("显示名才是文件名时被误拒：这正是分享导入被拦下的原因",
                viewModel.looksLikeWakeupSchedule(uri))
    }

    /** 反向：显示名明显不是课表备份时，入口要拦住（不能一律放行到解析阶段）。 */
    @Test
    fun 显示名不是课表备份时入口要拦住() {
        val uri = Uri.parse("content://downloads/document/5678")
        registerDisplayName(uri, "简历.pdf")

        assertFalse("无关文件不该走进导入流程", viewModel.looksLikeWakeupSchedule(uri))
    }

    /** 给这个 authority 挂一个只回答 DISPLAY_NAME 的提供方，模拟 SAF 的下载提供方。 */
    private fun registerDisplayName(uri: Uri, displayName: String) {
        DisplayNameProvider.displayName = displayName
        Robolectric.buildContentProvider(DisplayNameProvider::class.java).create(uri.authority!!)
    }

    /** 只回答 DISPLAY_NAME 的极简提供方：SAF 的下载提供方就是这样给出文件名的。 */
    private class DisplayNameProvider : android.content.ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayName)) }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?): Int = 0

        companion object {
            var displayName: String = ""
        }
    }

    /**
     * `file://` 的 path 就是真实文件名，所以它不含 `wakeup_schedule` 时必须**直接判否**。
     *
     * 这是踩过的坑：判定放松成"拿不到显示名就放行"之后，任何一个未知类型文件（外部会以
     * `application/octet-stream` 把课钟列进候选）都会被放进导入流程，最后用户看到的是一句
     * 系统原文 —— `发生异常>_<\n/sdcard/Download/foo.txt: open failed:ENOENT`。
     */
    @Test
    fun file路径不含课表文件名时入口直接判否() {
        assertFalse("file:// 的文件名就是 path，不能因为拿不到显示名就放行",
                viewModel.looksLikeWakeupSchedule(Uri.parse("file:///sdcard/Download/foo.txt")))
    }

    /** 打不开的文件要给**人话**，不能把 `open failed: ENOENT` 这类系统原文甩给用户。 */
    @Test
    fun 打不开的文件给的是人话而不是系统原文() {
        val uri = Uri.parse("content://downloads/document/9999")
        // 显示名像课表备份（过得了入口），但流拿不到：模拟"文件已被删除/移动"。
        registerDisplayName(uri, "没了.wakeup_schedule")

        val error = runCatching { runBlocking { viewModel.importFromFile(uri) } }.exceptionOrNull()

        assertNotNull("打不开的文件必须报错", error)
        assertTrue("文案要能直接给用户看，实际：${error?.message}",
                error?.message?.contains("文件打不开") == true)
        assertFalse("不能把系统原文甩给用户，实际：${error?.message}",
                error?.message?.contains("ENOENT") == true)
    }

    private fun uriOf(name: String, stream: InputStream): Uri {
        val uri = Uri.parse("content://courseclock.test/$name")
        shadowOf(application.contentResolver).registerInputStream(uri, stream)
        return uri
    }

    /** 只用来确认"读完真的关了"，其余行为与普通输入流一致。 */
    private class CloseTrackingInputStream(private val payload: ByteArray) : InputStream() {

        private var offset = 0

        var closed = false
            private set

        override fun read(): Int =
                if (offset < payload.size) payload[offset++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (offset >= payload.size) return -1
            val count = minOf(len, payload.size - offset)
            System.arraycopy(payload, offset, b, off, count)
            offset += count
            return count
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        /** 与 [ImportViewModel] 里的上限一致：这里多写 1 个字节，确保真的越过界。 */
        const val MAX_SCHEDULE_BYTES = 1024 * 1024
    }
}
