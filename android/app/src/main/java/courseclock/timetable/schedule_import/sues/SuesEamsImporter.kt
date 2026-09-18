package courseclock.timetable.schedule_import.sues

import courseclock.timetable.bean.CourseBaseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.utils.CourseTimes
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 一次导入的完整结果。
 *
 * 时间表、课表、课程基表与安排表一次性给出，是因为前两者之外的字段（配色、外键、
 * 节次上限）在解析阶段就已经定型，拆成多个入口只会让调用方重复推导同一批常量。
 */
data class SuesImportData(
        /** 时间表名称。本校所有排课共用同一张时间表，分组在时间表内部表达。 */
        val timeTableName: String,
        /** 已按 (节次, 分组) 展开的作息行；分组键与 [CourseDetailBean.timeGroup] 对应。 */
        val timeDetails: List<TimeDetailBean>,
        val tableName: String,
        val startDate: String,
        val maxWeek: Int,
        val nodes: Int,
        val courseBaseList: List<CourseBaseBean>,
        val courseDetailList: List<CourseDetailBean>,
        /**
         * **教务系统里存在、但一条排课时间都没有**的课程名（按出现顺序去重）。
         *
         * 真实数据里确实有这种课：7 个学期 86 门课里有 4 门（军训、军事理论、
         * 大学物理实验A 上下）的 `scheduleText` 四个字段全是 null —— 学校还没给它们排时间。
         * 它们不会出现在课表里，这是**数据现状而不是解析失败**，但用户会以为"少了课"，
         * 所以导入完必须主动说清楚少了哪几门（见 WebViewLoginFragment 与 ScheduleActivity 的提示）。
         */
        val coursesWithoutSchedule: List<String>
)

/**
 * 上海工程技术大学教学服务中心（EAMS）课表接口的解析器。
 *
 * 接口返回的排课文本形如「1~3,5~8周 星期二 1~2节 松江校区 B210多 张三」，
 * 一条文本只描述一个周次集合下的一个时间槽位；同一门课会有多条，同一个槽位
 * 也可能因为教师不同而重复出现，因此解析结果必须先去重再落库。
 *
 * 这里不依赖反射式的宽松映射：EAMS 的字段名与项目 bean 的字段名无关，且嵌套
 * 层级里有多个同义字段（textZh/text、nameZh/name），显式读取才能让缺字段时的
 * 行为可预期，而不是静默变成空串。
 */
object SuesEamsImporter {

    /** 时间表名称。分组键由导入器定义，App 不解释它，所以名称只需可读。 */
    private const val DEFAULT_TIME_TABLE_NAME = "默认"

    /** 课表名称，与时间表名区分：前者是「这张课表」，后者是「这套作息」。 */
    private const val DEFAULT_TABLE_NAME = "上海工程技术大学"

    /** 周次上限缺省值：weekIndices 缺失或为空时，按一个完整学期推算。 */
    private const val FALLBACK_MAX_WEEK = 21

    /** 节次上限的下界，保证 3~5 节的错峰时段总能落在课表内。 */
    private const val MIN_NODES = 15

    /** 教室未指明楼宇时按作息表「其他楼宇、教学场所」处理，即 B 方案。 */
    private const val FALLBACK_GROUP = "B"

    /**
     * 课表配色，取值与 colors.xml 的 customizedColors 一致。
     *
     * 解析阶段拿不到 Context，而颜色只是按课程序号轮转的常量，直接内联比为了
     * 取色把 Context 传进解析器更省事，也避免了解析结果依赖资源加载时机。
     */
    private val CUSTOMIZED_COLORS = intArrayOf(
            0xFFFF1744.toInt(), 0xFFFA6278.toInt(), 0xFF2979FF.toInt(), 0xFF1DE9B6.toInt(),
            0xFFA375FF.toInt(), 0xFFFF9100.toInt(), 0xFFFF3D00.toInt(), 0xFF2196F3.toInt(),
            0xFF005CAF.toInt()
    )

    /** 时段：(起始节, 结束节) 到 (起时, 起分) 与 (止时, 止分)。 */
    private data class Block(
            val startNode: Int,
            val endNode: Int,
            val startHour: Int,
            val startMinute: Int,
            val endHour: Int,
            val endMinute: Int
    )

    /** 三套作息共有的时段；上午 3~5 节之外的课在任何楼宇都同一时间。 */
    private val COMMON_BLOCKS = listOf(
            Block(1, 2, 8, 15, 9, 35),
            Block(6, 7, 13, 20, 14, 40),
            Block(8, 9, 15, 0, 16, 20),
            Block(10, 11, 16, 35, 17, 55),
            Block(12, 13, 18, 10, 19, 30),
            Block(14, 14, 19, 35, 20, 15),
            Block(15, 15, 20, 20, 21, 0)
    )

    /**
     * 上午第 3~5 节按教学楼错峰。
     *
     * 三套时间互相交叠且互不包含（例如第 4 节在 A/B/C 下分别是 10:40-12:00、
     * 09:55-11:15、10:15-11:35），没有任何一个区间能代表另外两个，所以必须
     * 按楼宇分组，而不是取某一套当全校时间。
     */
    private val MORNING_BLOCKS = mapOf(
            "A" to listOf(
                    Block(3, 3, 9, 55, 10, 35),
                    Block(4, 5, 10, 40, 12, 0)
            ),
            "B" to listOf(
                    Block(3, 4, 9, 55, 11, 15),
                    Block(5, 5, 11, 20, 12, 0)
            ),
            "C" to listOf(
                    Block(3, 4, 10, 15, 11, 35),
                    Block(5, 5, 11, 40, 12, 20)
            )
    )

    /** 楼栋字母到作息分组。J30x 教室单独判定，不走这张表。 */
    private val LETTER_SCHEME = mapOf(
            "A" to "A", "F" to "A",
            "B" to "B", "C" to "B",
            "D" to "C", "E" to "C"
    )

    private val WEEKDAY_TEXT = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7
    )

    /** 归一化时统一成半角连字符的符号：波浪线、破折号、制表横线，以及「至」。 */
    private const val DASH_LIKE = "~\u301c\u2014\u2013\u2500至"

    /**
     * 一个周次片段：「5」「1~3」「2~16(双)」。
     *
     * 括号里的单双周标注是教务系统给的，**必须当成周次语法的一部分**：真实数据里
     * 「2~16(双)周」「1~3(单),4~16周」这样的写法在过去 7 个学期里出现了 8 条，
     * 不认它就会整份导入失败（见 [expandWeeks]）。
     */
    private const val WEEK_PART = "\\d+(?:\\s*-\\s*\\d+)?(?:\\s*\\([单双]\\))?"

    /** 形如「1~3,5~8周 星期二 1~2节 松江校区 B210多 张三」；周次可带「(单)」「(双)」标注。 */
    private val P_ENTRY = Regex(
            "^($WEEK_PART(?:\\s*,\\s*$WEEK_PART)*)\\s*周\\s*" +
                    "星期([一二三四五六日天])\\s*" +
                    "(\\d+)(?:\\s*-\\s*(\\d+))?\\s*节" +
                    "(?:\\s+(.*))?$"
    )

    /** 周次片段末尾的「(单)」「(双)」标注；取消尾随空白后再取，避免「2~16 (双)」漏判。 */
    private val P_WEEK_MARK = Regex("^(.*?)\\s*\\(([单双])\\)$")

    /** 「松江校区」这类只有校区、没有楼栋的片段。 */
    private val P_CAMPUS = Regex("^.*校区$")

    /**
     * J30x 教室单独占一套作息。
     *
     * 前后排除数字与字母，是为了不把「交8J302」这类非教学楼编号误判成 J302。
     */
    private val P_ROOM_J = Regex("(?<![0-9A-Z])J30([123])(?![0-9])")

    /** 「教学楼A」这类明确写出楼栋的说法。 */
    private val P_TEACHING_BUILDING = Regex("教学楼\\s*([A-F])")

    /**
     * 房号：A~F 中一个字母后紧跟 2~4 位数字。
     *
     * 禁止字母前出现数字或字母，否则「交8B323」「现代交通工程中心8B317-319」会被
     * 当成 B 楼而分到错的作息；这类编号应当落到兜底方案。
     */
    private val P_ROOM_CODE = Regex("(?<![0-9A-Z])([A-F])(?:楼|栋|座|区|室)?\\d{2,4}")

    /** 一条解析好的排课文本。 */
    private data class Entry(
            val weeks: List<Int>,
            val day: Int,
            val startNode: Int,
            val endNode: Int,
            val room: String,
            val teacher: String
    )

    /** 一门课的一条排课文本；课程名是分组进基表的键之一，排课文本给出周次与槽位。 */
    private data class LessonEntry(
            val lessonId: Int,
            val name: String,
            val notAttend: Boolean,
            val retake: Boolean,
            val entry: Entry
    )

    /** 详情行：教师要去重合并，所以用可变对象承载中间状态，最后再转成 bean。 */
    private class DetailRow(
            val id: Int,
            val day: Int,
            val room: String,
            val startNode: Int,
            val step: Int,
            val startWeek: Int,
            val endWeek: Int,
            val type: Int,
            val timeGroup: String
    ) {
        private val teachers = LinkedHashSet<String>()

        fun addTeacher(teacher: String) {
            if (teacher.isNotEmpty()) teachers.add(teacher)
        }

        /** 同一槽位只差教师的排课合并成一行，教师按出现顺序用斜杠连接。 */
        fun teacherText(): String = teachers.joinToString("/")

        fun toBean(): CourseDetailBean = CourseDetailBean(
                id = id, day = day, room = room, teacher = teacherText(),
                startNode = startNode, step = step, startWeek = startWeek, endWeek = endWeek,
                type = type, tableId = 1, timeGroup = timeGroup
        )
    }

    /**
     * 解析教学服务中心课程表接口返回的 JSON 原文。
     *
     * @throws Exception JSON 结构不是 get-data 的返回、或没有任何可用的排课记录时抛出，
     * 消息面向使用者，直接可以显示在导入失败的提示里。
     */
    fun parse(rawJson: String): SuesImportData {
        val root = try {
            JSONObject(rawJson)
        } catch (e: JSONException) {
            throw Exception("课程表数据不是有效的 JSON，请确认获取到的是教学服务中心的课表接口返回。", e)
        }

        val lessons = root.optJSONArray("lessons")
        if (lessons == null || lessons.length() == 0) {
            throw Exception("课程表数据里没有课程列表，请确认是教学服务中心的课表接口返回。")
        }

        val maxWeek = resolveMaxWeek(root)
        val startDate = resolveStartDate(root)
        val notAttendIds = parseNotAttendIds(root)
        val retakeIds = parseRetakeIds(root)
        // 课程名先按接口顺序收全（含**没有任何排课文本**的那些）：后面要靠它算"哪几门课
        // 一条安排都没有"，而 parseAllEntries 只会返回有排课文本的课。
        val allNames = lessonNames(lessons)
        val parsed = parseAllEntries(lessons, notAttendIds, retakeIds)
        if (parsed.isEmpty()) {
            throw Exception("没有解析出任何排课记录，请确认本学期已经排课。")
        }

        return build(parsed, maxWeek, startDate, allNames)
    }

    /**
     * 顶层 `notAttendLessonIds`：免听 lesson id 集合。
     *
     * 与 `lessonId2Retake`、每门课的 `noAttendCount` 两两交叉核对过（7 个学期）：
     * 顶层列表是权威来源，缺字段时按空集处理，而不是猜。
     */
    private fun parseNotAttendIds(root: JSONObject): Set<Int> {
        val arr = root.optJSONArray("notAttendLessonIds") ?: return emptySet()
        val ids = LinkedHashSet<Int>()
        for (i in 0 until arr.length()) {
            val id = arr.optInt(i, Int.MIN_VALUE)
            if (id != Int.MIN_VALUE) ids.add(id)
        }
        return ids
    }

    /** 顶层 `lessonId2Retake`：id → 是否重修。真实数据里免听与重修 5/5 重合。 */
    private fun parseRetakeIds(root: JSONObject): Set<Int> {
        val obj = root.optJSONObject("lessonId2Retake") ?: return emptySet()
        val ids = LinkedHashSet<Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toIntOrNull() ?: continue
            if (obj.optBoolean(key, false)) ids.add(id)
        }
        return ids
    }

    /** 接口里全部课程的中文名，按出现顺序去重；空名跳过（它们归不到任何基表）。 */
    private fun lessonNames(lessons: JSONArray): List<String> {
        val names = LinkedHashSet<String>()
        for (i in 0 until lessons.length()) {
            val lesson = lessons.optJSONObject(i) ?: continue
            val name = lessonName(lesson)
            if (name.isNotEmpty()) names.add(name)
        }
        return names.toList()
    }

    // ----------------------------------------------------------------------
    // 周次上限
    // ----------------------------------------------------------------------

    /**
     * 周次上限取接口给出的周次列表的最大值。
     *
     * 列表缺失或为空时退回一个完整学期的周数——上限只决定课表能显示到第几周，
     * 猜小了会让后面的课被裁掉，猜大只是多几列空周，所以宁可取大。
     */
    private fun resolveMaxWeek(root: JSONObject): Int {
        val weekIndices = root.optJSONArray("weekIndices") ?: return FALLBACK_MAX_WEEK
        var maxWeek = 0
        for (i in 0 until weekIndices.length()) {
            val week = weekIndices.optInt(i, 0)
            if (week > maxWeek) maxWeek = week
        }
        return if (maxWeek > 0) maxWeek else FALLBACK_MAX_WEEK
    }

    /**
     * 学期起始日，即第 1 周的星期一。
     *
     * get-data 的返回里没有任何日期字段，但它给出了 `currentWeek` —— 那正是不必猜的东西：
     * 教务系统已经把「今天是第几周」算好了，把它和手机上的今天一起用，就能反推出第 1 周的
     * 星期一。这样开学日期每个学期都由教务系统的数据决定，而不是由代码里的常量决定。
     *
     * 拿不到 currentWeek（缺失、为 0 或负数）时返回空串：界面上会显示「还没设置开学日期」，
     * 让用户去设置里手动指定，也好过编一个日期出来骗人。
     */
    private fun resolveStartDate(root: JSONObject): String {
        val currentWeek = root.optInt("currentWeek", 0)
        if (currentWeek <= 0) return ""
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        // 按固定 7 天回退，不用 add(WEEK_OF_YEAR)：后者在跨年时会按 locale 的周规则
        // （minimalDaysInFirstWeek）重排，极端情况下反推出的第 1 周会偏几天。
        calendar.add(Calendar.DATE, -7 * (currentWeek - 1))
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(calendar.time)
    }

    // ----------------------------------------------------------------------
    // 排课文本
    // ----------------------------------------------------------------------

    private fun parseAllEntries(
            lessons: JSONArray,
            notAttendIds: Set<Int>,
            retakeIds: Set<Int>
    ): List<LessonEntry> {
        val parsed = ArrayList<LessonEntry>()
        for (i in 0 until lessons.length()) {
            val lesson = lessons.optJSONObject(i) ?: continue
            val name = lessonName(lesson)
            // 课程名为空的条目无法归到任何课程基表，也无法在界面上辨识，只能跳过。
            if (name.isEmpty()) continue
            val lessonId = lesson.optInt("id", -1)
            val notAttend = lessonId in notAttendIds
            val retake = lessonId in retakeIds
            for (text in lessonEntries(lesson)) {
                parsed.add(LessonEntry(lessonId, name, notAttend, retake, parseEntry(text)))
            }
        }
        return parsed
    }

    /** 课程名按接口的字段优先级取，先中文名再代码名。 */
    private fun lessonName(lesson: JSONObject): String {
        val course = lesson.optJSONObject("course")
        val name = firstNonBlank(
                course?.jsonText("nameZh"),
                course?.jsonText("name"),
                lesson.jsonText("nameZh")
        )
        return name.trim()
    }

    /**
     * 读一个可能缺失、也可能被**显式写成 JSON `null`** 的字符串字段。
     *
     * org.json 的 `optString` 对显式 null 返回的是字面量 `"null"`，不是空串。真实数据里
     * 就有这种字段：军训、军事理论、大学物理实验A（上/下）这四门课的
     * `scheduleText.dateTimePlacePersonText.textZh` 全是 `null` —— 按 `optString` 读会得到
     * 一条"排课文本「null」"，`parseEntry` 抛「无法解析排课文本：null」，**整份导入跟着失败**。
     * 这里把 JSON null 与字段缺失一视同仁地当成空串，再交给原有的"取第一个非空"逻辑。
     */
    private fun JSONObject.jsonText(key: String): String {
        val value = opt(key) ?: return ""
        if (value == JSONObject.NULL) return ""
        return value.toString().trim()
    }

    /**
     * 排课文本可能藏在三个同义字段里，取第一个非空的。
     *
     * 一条字段里用分号分隔多个时段，分号前后的换行与空格都不携带信息，直接丢掉。
     * 字面量 `"null"` 也当没有：接口把 null 序列化成字符串的版本是存在的。
     */
    private fun lessonEntries(lesson: JSONObject): List<String> {
        val scheduleText = lesson.optJSONObject("scheduleText") ?: return emptyList()
        val keys = listOf("dateTimePlacePersonText", "dateTimePlaceText", "dateTimeText")
        for (key in keys) {
            val node = scheduleText.optJSONObject(key) ?: continue
            val text = firstNonBlank(node.jsonText("textZh"), node.jsonText("text"))
            if (text.isBlank() || text.equals("null", ignoreCase = true)) continue
            return text.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private fun parseEntry(text: String): Entry {
        // 不间断空格先换成普通空格，否则 split 之后的首个词会带上它，教室名就脏了。
        val raw = text.replace('\u00a0', ' ')
        val normalized = normalize(raw)

        // 用归一化后的文本匹配、却从原文切片取教室与教师：归一化是逐字符替换，
        // 下标一一对应，因此原文的全角括号（如「A501（机房）」）不会被改写。
        val match = P_ENTRY.matchEntire(normalized)
                ?: throw Exception("无法解析排课文本：$text")

        val restIndex = match.groups[5]?.range?.first ?: -1
        val rest = if (restIndex >= 0 && restIndex <= raw.length) raw.substring(restIndex).trim() else ""
        val tokens = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }

        // 校区在校内是「松江校区」，对同校学生没有区分作用，占着教室名只会让
        // 同一次上课在不同学期显示成两个地点，所以丢掉。只有在它后面真的还有
        // 教室时才丢，否则「松江校区」单独一项会被当成教室名。
        val body = if (tokens.size > 1 && P_CAMPUS.matches(tokens[0])) tokens.drop(1) else tokens

        // 教师是教室之后的剩余词按空格拼回，接口偶有多位教师并列的情况。
        val room = body.firstOrNull() ?: ""
        val teacher = body.drop(1).joinToString(" ").trim()

        var startNode = parseNode(match.groups[3]!!.value, text)
        var endNode = match.groups[4]?.value?.let { parseNode(it, text) } ?: startNode
        // 接口偶尔会把区间写反，起止交换后才是真实节次范围。
        if (startNode > endNode) {
            val swap = startNode
            startNode = endNode
            endNode = swap
        }

        val dayChar = match.groups[2]!!.value[0]
        val day = WEEKDAY_TEXT[dayChar] ?: throw Exception("无法识别的星期：$text")

        return Entry(
                weeks = expandWeeks(match.groups[1]!!.value, text),
                day = day, startNode = startNode, endNode = endNode,
                room = room, teacher = teacher
        )
    }

    private fun parseNode(value: String, text: String): Int =
            value.toIntOrNull() ?: throw Exception("节次不是数字：$text")

    /**
     * 「1~3,5~8」-> [1,2,3,5,6,7,8]；区间写反时自动纠正。
     *
     * 「(单)」「(双)」标注按奇偶过滤：**丢标注、只留区间是错的** ——
     * 「2~16(双)」会被展开成第 2~16 周的每一周，整份课表都排错。标注是教务系统
     * 对单双周的唯一权威表述，`(双)` 即偶数周、`(单)` 即奇数周。
     *
     * 展开后的周次列表与 App 自己的 `type`（0 每周 / 1 单周 / 2 双周）语义一致，
     * 由 [weekSegments] 从相邻周次的步长再推一遍，两条路不会分叉。
     */
    private fun expandWeeks(expr: String, text: String): List<Int> {
        val weeks = sortedSetOf<Int>()
        for (part in expr.split(",")) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            val mark = P_WEEK_MARK.find(piece)
            val span = (mark?.groupValues?.get(1) ?: piece).trim()
            val parity = mark?.groupValues?.get(2)?.firstOrNull()
            val range: IntRange = if (span.contains("-")) {
                val bounds = span.split("-", limit = 2)
                var lo = bounds[0].trim().toIntOrNull() ?: throw Exception("周次不是数字：$text")
                var hi = bounds[1].trim().toIntOrNull() ?: throw Exception("周次不是数字：$text")
                if (lo > hi) {
                    val swap = lo
                    lo = hi
                    hi = swap
                }
                lo..hi
            } else {
                val week = span.toIntOrNull() ?: throw Exception("周次不是数字：$text")
                week..week
            }
            for (week in range) {
                if (parity == '单' && week % 2 == 0) continue
                if (parity == '双' && week % 2 == 1) continue
                weeks.add(week)
            }
        }
        return weeks.toList()
    }

    /**
     * 全角转半角、区间符号统一成连字符、转大写。
     *
     * 逐字符映射而非整体替换，是为了让输出与输入的**长度与下标严格对应**：
     * 调用方要靠匹配位置回到原文切片，一旦长度变化，教室名就会被错位截断。
     */
    private fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            var out = ch
            val code = ch.code
            if (code in 0xFF01..0xFF5E) {
                out = (code - 0xFEE0).toChar()
            } else if (code == 0x3000) {
                out = ' '
            }
            if (DASH_LIKE.indexOf(out) >= 0) out = '-'
            builder.append(if (out.isLowerCase()) out.uppercaseChar() else out)
        }
        return builder.toString()
    }

    // ----------------------------------------------------------------------
    // 作息分组
    // ----------------------------------------------------------------------

    /**
     * 由教室名判定作息分组。
     *
     * 三种写法的可信度依次下降：J30x 教室最明确，其次「教学楼X」，最后是
     * 「A501」这样的房号；都识别不出时按作息表里「其他楼宇、教学场所」处理。
     */
    private fun groupForRoom(room: String): String {
        val text = normalize(room)
        P_ROOM_J.find(text)?.let { match ->
            when (match.groupValues[1]) {
                "1" -> return "A"
                "2" -> return "B"
                "3" -> return "C"
                else -> Unit
            }
        }
        P_TEACHING_BUILDING.find(text)?.let { match ->
            LETTER_SCHEME[match.groupValues[1]]?.let { return it }
        }
        P_ROOM_CODE.find(text)?.let { match ->
            LETTER_SCHEME[match.groupValues[1]]?.let { return it }
        }
        return FALLBACK_GROUP
    }

    /**
     * 并列时优先哪一套作息（也是"这次导入一套安排都没有"时的兜底）。
     *
     * 它是学校口径里的「其他楼宇、教学场所」，覆盖面最广，所以并列时用它最不意外。
     * 真正的规则在 [CourseTimes.autoAxisGroup]：**只有落在上午第 3~5 节的课才投票**
     * （三套作息只在这几节不同）。用户还可以在设置里强制指定某一套。
     */
    private const val DEFAULT_SCHEME = CourseTimes.AUTO_AXIS_FALLBACK

    /** 三套上午错峰作息；导入时**全部**写进时间表，时间栏才能随时切换而不用重新导入。 */
    private val SCHEMES = listOf("A", "B", "C")

    /**
     * 默认分组（左侧时间栏 / 没有分组的课程）用哪一套作息。
     *
     * 规则本身在 [CourseTimes.autoAxisGroup]（App 运行时按同一份规则重算，两处不会分叉）：
     * 累加所有课块在各候选作息下的偏移量，取最小的那套。这里只把三套候选的作息与本次导入的
     * 安排交过去。**"哪些课有资格投票"这类判断不需要了** —— 对轴无所谓的课自然贡献 0。
     */
    private fun axisSchemeOf(rows: Collection<DetailRow>): String {
        val schemeTimes = SCHEMES.associateWith { nodeTimes(it) }
        return CourseTimes.autoAxisGroup(
                candidates = SCHEMES,
                rowOf = { group, node -> schemeTimes[group]?.get(node) },
                placements = rows.map { it.toBean() })
    }

    // ----------------------------------------------------------------------
    // 作息行
    // ----------------------------------------------------------------------

    /**
     * 某个分组的「节次 -> (起时, 止时)」。
     *
     * 作息表只精确到格：同一格里的每个节次都填整格的起止时间。App 取课的首节
     * 开始时间与末节结束时间，填整格才能让一次跨两节的课恰好显示整格区间。
     */
    private fun nodeTimes(group: String): Map<Int, Pair<String, String>> {
        val blocks = (MORNING_BLOCKS[group] ?: emptyList()) + COMMON_BLOCKS
        val times = LinkedHashMap<Int, Pair<String, String>>()
        for (block in blocks.sortedBy { it.startNode }) {
            val start = formatTime(block.startHour, block.startMinute)
            val end = formatTime(block.endHour, block.endMinute)
            for (node in block.startNode..block.endNode) {
                times[node] = start to end
            }
        }
        return times
    }

    private fun formatTime(hour: Int, minute: Int): String =
            String.format(Locale.US, "%02d:%02d", hour, minute)

    /**
     * 时间表的全部作息行：默认分组 + **A/B/C 三套全写**（每节恰好一行）。
     *
     * 默认分组只能有一套时间：左侧时间栏、以及没有分组的课程都只看它；以前把三套上午时段
     * 全部并进默认分组，于是第 3~5 节各出现两到三行、时间互不相同，界面上同一格压着两行字。
     * 现在它的内容由 [defaultGroup] 决定（导入时按占比选出来的那一套）。
     *
     * **带分组的三套一律写全，而不是只写"这次用到的"**：设置页允许把时间栏切到任意一套，
     * 而切换不该要求用户重新导入（重新导入会换一张课表、丢掉手工改动）。代价是时间表里
     * 最多 4×15 行，反正只按 (节次, 分组) 索引，多出来的行不参与渲染。
     *
     * 跨节次的课（step > 1）由 [nodeTimes] 的整格时间保证：首节到末节每一节都能查到时间，
     * 与学校公布的作息表一致（例如 A 方案第 4、5 节同属 10:40-12:00 一格）。
     */
    private fun buildTimeDetails(defaultGroup: String): List<TimeDetailBean> {
        val details = ArrayList<TimeDetailBean>()
        for (group in listOf(CourseTimes.DEFAULT_GROUP) + SCHEMES) {
            val times = if (group.isEmpty()) nodeTimes(defaultGroup) else nodeTimes(group)
            for (node in times.keys.sorted()) {
                val time = times.getValue(node)
                details.add(TimeDetailBean(
                        node = node, startTime = time.first, endTime = time.second,
                        timeTable = 1, timeGroup = group
                ))
            }
        }
        return details
    }

    // ----------------------------------------------------------------------
    // 组装
    // ----------------------------------------------------------------------

    private fun build(parsed: List<LessonEntry>, maxWeek: Int, startDate: String,
                      allLessonNames: List<String>): SuesImportData {
        val bases = ArrayList<CourseBaseBean>()
        // 键是 (课名, 是否免听)：同名课若免听状态不同必须拆成两条基表，
        // 否则后到的那条会把免听标记悄悄丢掉（或错误地盖到正常课上）。
        val baseIdByName = LinkedHashMap<String, Int>()
        val baseIdByCourseName = LinkedHashMap<String, Int>()
        val rows = LinkedHashMap<String, DetailRow>()

        for (item in parsed) {
            // 每条安排按它自己所在楼宇取作息：同一节次在不同楼宇时间不同，
            // 只有一个分组键能把这些差异同时表达清楚。
            val group = groupForRoom(item.entry.room)

            // 同名同免听状态复用同一条基表记录，配色按基表序号轮转，同门课各次安排同色。
            val baseKey = item.name + "|" + item.notAttend
            val baseId = baseIdByName.getOrPut(baseKey) {
                val id = bases.size
                bases.add(CourseBaseBean(
                        id = id, courseName = item.name,
                        color = colorFor(id), tableId = 1,
                        notAttend = item.notAttend, retake = item.retake
                ))
                // 显式返回下标：add 的返回值是 Boolean，块尾求值会得到错误的类型。
                id
            }
            if (!baseIdByCourseName.containsKey(item.name)) {
                baseIdByCourseName[item.name] = baseId
            }

            for (segment in weekSegments(item.entry.weeks)) {
                val startWeek = segment[0]
                val endWeek = segment[1]
                val type = segment[2]
                // 一次安排只能表达 startWeek~endWeek 一个区间，所以以「课程 + 星期 +
                // 起始节次 + 区间 + 单双周 + 教室」为主键去重：只有教师不同的重复
                // 排课会落到同一行，教师在此合并，其余情况都不会互相覆盖。
                val key = listOf(
                        baseId.toString(), item.entry.day.toString(), item.entry.startNode.toString(),
                        startWeek.toString(), endWeek.toString(), type.toString(), item.entry.room
                ).joinToString("\u0000")
                val row = rows.getOrPut(key) {
                    DetailRow(
                            id = baseId, day = item.entry.day, room = item.entry.room,
                            startNode = item.entry.startNode,
                            step = item.entry.endNode - item.entry.startNode + 1,
                            startWeek = startWeek, endWeek = endWeek, type = type, timeGroup = group
                    )
                }
                row.addTeacher(item.entry.teacher)
            }
        }

        // 默认分组（左侧时间栏）取本次分布里占比最大的那套；A/B/C 三套一律写全，
        // 设置页切到任意一套都不用重新导入（见 buildTimeDetails 与 axisSchemeOf）。
        val timeDetails = buildTimeDetails(axisSchemeOf(rows.values))
        // 节次数要覆盖所有分组的最大节次，否则跨到晚间的课会被课表的行数截断。
        val nodes = maxOf(timeDetails.maxOf { it.node }, MIN_NODES)

        return SuesImportData(
                timeTableName = DEFAULT_TIME_TABLE_NAME,
                timeDetails = timeDetails,
                tableName = DEFAULT_TABLE_NAME,
                startDate = startDate,
                maxWeek = maxWeek,
                nodes = nodes,
                courseBaseList = bases,
                courseDetailList = rows.values.map { it.toBean() },
                // "一条安排都没有"按**最终有没有落到详情行**判断，而不是"有没有排课文本"：
                // 「3~3(双)周」这类文本解析得出来、但展开后一周都不剩，同样不会出现在课表里。
                // 判据收在最后一步，两种情形一条不漏。同名课程只要有一次安排就算排上了。
                coursesWithoutSchedule = run {
                    val scheduled = rows.values.map { it.id }.toSet()
                    allLessonNames.filter { name ->
                        val baseId = baseIdByCourseName[name]
                        baseId == null || baseId !in scheduled
                    }
                }
        )
    }

    /**
     * 把周次列表切成 (起始周, 结束周, 类型) 段。
     *
     * 类型与 App 的 Common.weekIntList2WeekBeanList 一致：0 = 每周，1 = 单周，
     * 2 = 双周。步长由每段的头两个周次决定——差 1 是每周，差 2 是单双周，其余
     * 视为孤立的单周；随后只延伸与首步长相同的周次。非连续周次必须拆段，因为
     * 一次安排只能表达一个区间。
     */
    private fun weekSegments(weeks: List<Int>): List<IntArray> {
        val items = weeks.toSortedSet().toList()
        val segments = ArrayList<IntArray>()
        var index = 0
        while (index < items.size) {
            val start = items[index]
            if (index == items.size - 1) {
                segments.add(intArrayOf(start, start, 0))
                break
            }
            val step = items[index + 1] - start
            val kind: Int
            when (step) {
                1 -> kind = 0
                2 -> kind = if (start % 2 == 1) 1 else 2
                else -> {
                    segments.add(intArrayOf(start, start, 0))
                    index++
                    continue
                }
            }
            var end = items[index + 1]
            var cursor = index + 1
            while (cursor + 1 < items.size && items[cursor + 1] - items[cursor] == step) {
                cursor++
                end = items[cursor]
            }
            segments.add(intArrayOf(start, end, kind))
            index = cursor + 1
        }
        return segments
    }

    /** 与 Parser.kt 的配色写法对齐：ARGB 转十六进制串，带 # 前缀。 */
    private fun colorFor(index: Int): String =
            "#" + Integer.toHexString(CUSTOMIZED_COLORS[index % CUSTOMIZED_COLORS.size])

    private fun firstNonBlank(vararg values: String?): String =
            values.firstOrNull { !it.isNullOrBlank() } ?: ""
}
