package courseclock.timetable.schedule_import

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.Type
import courseclock.timetable.App
import courseclock.timetable.AppDatabase
import courseclock.timetable.bean.*
import courseclock.timetable.schedule_import.sues.SuesEamsImporter
import courseclock.timetable.utils.CourseTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次教务系统导入的结果。
 *
 * @property courseCount 真正进了课表的课程门数
 * @property coursesWithoutSchedule **教务系统里存在、但一条排课时间都没有**的课程名：
 * 它们不会出现在课表里，必须主动告诉用户少了哪几门，而不是让人自己发现"怎么少了一门课"。
 * 真实样本里这种课是存在的（军训、军事理论、大学物理实验A 上下四个学期各有一门）。
 */
data class SuesImportResult(
        val courseCount: Int,
        val coursesWithoutSchedule: List<String>
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    var school: String? = null
    var importType: String? = null

    private val dataBase = AppDatabase.getDatabase(application)
    private val tableDao = dataBase.tableDao()
    private val courseDao = dataBase.courseDao()
    private val timeTableDao = dataBase.timeTableDao()
    private val timeDetailDao = dataBase.timeDetailDao()

    /**
     * 新建课表，并立刻让它接管「默认表」标记。
     *
     * 默认表由 TableBean.type == 1 表示（见 TableDao.getDefaultTable），全库必须恰好一行。
     * 导入完成后若不切换默认表，首页仍然渲染旧表，用户会以为导入没成功。
     * 所有「新建课表」的导入路径都必须走这里，不要在别处直接 insertTable。
     */
    private suspend fun insertTableAsDefault(table: TableBean) {
        val oldDefaultId = tableDao.getDefaultTableId() ?: 0
        table.type = 0
        tableDao.insertTable(table)
        tableDao.changeDefaultTable(oldDefaultId, table.id)
    }

    /**
     * 从教学服务中心（EAMS）课表接口返回的 JSON 原文导入课表。
     *
     * 这是本 fork 的主导入路径：作息表（含教学楼分组）、课表、课程与安排由解析器一次性给出，
     * 学期起始日与周次上限也都来自教务系统，代码里不留任何学期相关的常量。
     * 新表直接接管默认表，导入完就能在首页看到课表。
     *
     * @return 入库门数 + 没有排课时间、因此没进课表的课程名
     * @throws Exception 数据不是课表接口的返回、或解析不出任何排课记录时抛出，消息可直接展示
     */
    suspend fun importFromSues(rawJson: String): SuesImportResult {
        val data = SuesEamsImporter.parse(rawJson)

        val timeTableId = timeTableDao.getMaxId() + 1
        val timeDetails = data.timeDetails
        timeDetails.forEach { it.timeTable = timeTableId }
        val tableId = getNewId()
        val courseBaseList = data.courseBaseList
        val courseDetailList = data.courseDetailList
        courseBaseList.forEach { it.tableId = tableId }
        courseDetailList.forEach { it.tableId = tableId }

        timeTableDao.insertTimeTable(TimeTableBean(id = timeTableId, name = data.timeTableName))
        timeDetailDao.insertTimeList(timeDetails)
        insertTableAsDefault(TableBean(
                id = tableId,
                tableName = data.tableName,
                nodes = data.nodes,
                timeTable = timeTableId,
                startDate = data.startDate,
                maxWeek = data.maxWeek
        ))
        courseDao.insertCourses(courseBaseList, courseDetailList)
        return SuesImportResult(
                courseCount = courseBaseList.size,
                coursesWithoutSchedule = data.coursesWithoutSchedule)
    }

    suspend fun getNewId(): Int {
        val lastId = tableDao.getLastId()
        return if (lastId != null) lastId + 1 else 1
    }

    suspend fun importFromFile(uri: Uri?) {
        if (uri == null) throw Exception("读取文件失败")
        if (!looksLikeWakeupSchedule(uri)) throw Exception("请确保文件类型正确")
        val gson = Gson()
        val list = withContext(Dispatchers.IO) {
            // openInputStream 对"文件不存在 / 读不到"是**抛**异常而不是返回 null，所以原来那句
            // "文件打不开或已被移动"几乎永远走不到：用户看到的是 `open failed: ENOENT
            // (No such file or directory)` 这种系统原文。两种失败在这里收敛成同一句人话。
            val input = try {
                getApplication<App>().contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                throw Exception("读取文件失败：文件打不开或已被移动", e)
            } ?: throw Exception("读取文件失败：文件打不开或已被移动")
            readScheduleLines(input)
        }
        if (list.size < 5) throw Exception("文件格式不对：应为 Wakeup 导出的 .wakeup_schedule 文件（5 行）")
        // 分享文件是外部输入：行内容是字面 "null"、或被其他工具截断时，Gson 会返回 null 而不是抛
        // 异常。逐行判空，把崩溃换成面向用户的一句话。
        fun <T> decode(line: String, type: Type, what: String): T =
                gson.fromJson<T>(line, type) ?: throw Exception("文件不完整或已损坏（$what 无法解析）")
        val timeTable = decode<TimeTableBean>(list[0], object : TypeToken<TimeTableBean>() {}.type, "时间表")
        val timeDetails = decode<List<TimeDetailBean>>(list[1], object : TypeToken<List<TimeDetailBean>>() {}.type, "作息表")
        val table = decode<TableBean>(list[2], object : TypeToken<TableBean>() {}.type, "课表")
        val courseBaseList = decode<List<CourseBaseBean>>(list[3], object : TypeToken<List<CourseBaseBean>>() {}.type, "课程列表")
        val courseDetailList = decode<List<CourseDetailBean>>(list[4], object : TypeToken<List<CourseDetailBean>>() {}.type, "课程安排列表")

        // Gson 反序列化缺失字段时会绕过构造器把 null 写进非空 String 字段，
        // Kotlin 的非空声明拦不住。渲染端处处假设这些字段非空（`c.room != ""`、
        // `c.color.isEmpty()`、`times.startOfNode(...)` 拼 SQL 等），所以 null 只允许存在到
        // 这一步为止：入库前统一收敛成空串/默认分组，之后整条链路都只见到非空值。
        // 经可空局部量读一次再判断——直接对非空字段判空会被编译器当成恒假。
        timeDetails.forEach { detail ->
            val group: String? = detail.timeGroup
            if (group == null) detail.timeGroup = CourseTimes.DEFAULT_GROUP
        }
        courseDetailList.forEach { detail ->
            val group: String? = detail.timeGroup
            if (group == null) detail.timeGroup = CourseTimes.DEFAULT_GROUP
            val room: String? = detail.room
            if (room == null) detail.room = ""
            val teacher: String? = detail.teacher
            if (teacher == null) detail.teacher = ""
        }
        courseBaseList.forEach { base ->
            val name: String? = base.courseName
            if (name == null) base.courseName = ""
            val color: String? = base.color
            if (color == null) base.color = ""
        }
        val timeTableId = timeTableDao.getMaxId() + 1
        timeTable.id = timeTableId
        timeTable.name = "分享_" + timeTable.name
        // 老版本导出的文件里，默认分组是把三套上午时段合并写成的，同一节次会带好几行时间。
        // 时间轴按节次查时间，多行会让它不知道取哪一个；先收拾成每节一行再入库。
        val cleanedTimeDetails = normalizeDefaultTimeDetails(timeDetails)
        cleanedTimeDetails.forEach {
            it.timeTable = timeTableId
        }
        val tableId = getNewId()
        table.background = ""
        table.id = tableId
        table.timeTable = timeTableId
        table.type = 0
        courseBaseList.forEach {
            it.tableId = tableId
        }
        courseDetailList.forEach {
            it.tableId = tableId
        }
        timeTableDao.insertTimeTable(timeTable)
        timeDetailDao.insertTimeList(cleanedTimeDetails)
        insertTableAsDefault(table)
        courseDao.insertCourses(courseBaseList, courseDetailList)
    }

    /**
     * 是不是本应用认的分享文件：URI 的 path 或显示名（DISPLAY_NAME）里带 wakeup_schedule。
     *
     * 只看 path 会误拒——部分 SAF 提供方（如下载提供方）的 path 是
     * `content://.../document/123`，文件名只在显示名里。显示名也拿不到时不再硬拒：
     * 放行到解析阶段，那里对坏数据本来就有明确的错误信息，比在这里错杀合法文件好。
     *
     * **入口（LoginWebActivity）直接复用这一个函数**：这条判定以前在 Activity 里被抄了
     * 一份"只看 path"的版本，于是同一个文件在入口被拒、导入器却认为它是合法的。
     */
    internal fun looksLikeWakeupSchedule(uri: Uri): Boolean {
        val path = uri.path ?: ""
        if (path.contains("wakeup_schedule")) return true
        // `file://` 的 path 就是**真实文件名**，所以它不含 wakeup_schedule 时可以直接断定
        // "这不是课表备份"——不能退到"拿不到就放行"，否则任何一个未知类型文件（外部会以
        // `application/octet-stream` 把这里列进候选）都会被放进导入流程，最后给用户的是一句
        // 系统原文（`open failed: ENOENT`）。
        // 只有 SAF 的 content:// 才需要退回显示名：它的 path 长这样 `/document/1234`，
        // 文件名不在里面。
        if (uri.scheme == "file") return false
        val displayName = try {
            getApplication<App>().contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                    }
        } catch (e: Exception) {
            null
        }
        // 显示名也拿不到时放宽（返回 true），交给解析阶段报错——那边对坏数据有明确文案，
        // 比在这里错杀合法文件好。
        return displayName?.contains("wakeup_schedule", ignoreCase = true) ?: true
    }

    /**
     * 把分享文件读成行，并给这份外部输入一个字节上限。
     *
     * 为什么不再用 `readLines()`：它是"先把整个文件读进内存、再判断"，而文件类型只看
     * 文件名/显示名，一个几百 MB 的同名文件足矣把 App 读爆（"行数不足 5"更是读完才判）。
     * [MAX_SCHEDULE_BYTES] 相对真实导出文件（仓库夹具 9 KB）有百倍余量，不会误杀合法文件。
     *
     * 手写读循环的代价是自己关流：`readLines()` 自带关闭（实测 `closed == true`），换成
     * 这里之后 `use` 必须显式写，否则才会真的泄漏文件描述符。
     */
    private fun readScheduleLines(input: InputStream): List<String> {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (bytes.size() + read > MAX_SCHEDULE_BYTES) {
                    throw Exception("文件过大：只认 App 导出的 .wakeup_schedule 文件" +
                            "（不超过 ${MAX_SCHEDULE_BYTES / 1024 / 1024} MB）")
                }
                bytes.write(buffer, 0, read)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).lineSequence().toList()
    }

    /**
     * 把默认分组的作息收拾成「每节恰好一行」。
     *
     * 默认分组是左侧时间栏和所有无分组课程唯一认的那一套时间，同一节次出现多行时没人能
     * 判断该用哪一行——老版本导出的文件正是这种形状。这里保留每个节次的第一行（文件里按
     * 节次有序，第一行是最早写入的那一套），带分组的行原样保留。
     */
    private fun normalizeDefaultTimeDetails(list: List<TimeDetailBean>): List<TimeDetailBean> {
        val seenNodes = HashSet<Int>()
        val result = ArrayList<TimeDetailBean>(list.size)
        for (detail in list) {
            if (detail.timeGroup == CourseTimes.DEFAULT_GROUP && !seenNodes.add(detail.node)) continue
            result.add(detail)
        }
        return result
    }

    private companion object {
        /** 分享文件的字节上限：仓库里的真实导出夹具约 9 KB，这里留了约 110 倍余量。 */
        const val MAX_SCHEDULE_BYTES = 1024 * 1024
    }

}
