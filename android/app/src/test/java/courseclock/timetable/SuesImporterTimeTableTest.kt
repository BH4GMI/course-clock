package courseclock.timetable

import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.schedule_import.sues.SuesEamsImporter
import courseclock.timetable.utils.CourseTimes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 作息表**双源对账**（防漂移）：
 *
 * 同一套学校作息目前存在两份实现——CLI 侧 `coursetime` 模块（由 `coursetime/verify.ps1`
 * 的 52 项自测钉住）与 App 侧 `SuesEamsImporter` 的 COMMON/MORNING_BLOCKS。两处物理隔离，
 * 改一处忘另一处不会有任何报错。本测试把 App 侧经 `parse()` 全链路产出的 (节次, 分组) ->
 * 时刻**逐格**钉在学校公布的作息上（与 docs/COURSETIME.md、coursetime 模块同源核对过），
 * 任何一侧的数值漂移都会在这里炸出来。
 *
 * `parse()` 走 org.json，需要 Robolectric 提供真实的 android 实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SuesImporterTimeTableTest {

    /** 「1-15节」一整天的排课文本：五天各落一个代表教室，凑齐默认 + A/B/C 四个分组。 */
    private val rawJson = """
        {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
         {"id":1,"course":{"nameZh":"A楼课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-15周 星期一 1-15节 松江校区 A501 张三"}}},
         {"id":2,"course":{"nameZh":"B楼课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-15周 星期二 1-15节 松江校区 B210多 李四"}}},
         {"id":3,"course":{"nameZh":"C楼课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-15周 星期三 1-15节 松江校区 E406多 王五"}}},
         {"id":4,"course":{"nameZh":"J室课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-15周 星期四 1-15节 松江校区 J302 赵六"}}},
         {"id":5,"course":{"nameZh":"兜底课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-15周 星期五 1-15节 校外 孙七"}}}
        ]}
    """.trimIndent()

    /** 三套方案共有的时段（上午 3~5 节之外都一样）。 */
    private val common = mapOf(
            1 to ("08:15" to "09:35"), 2 to ("08:15" to "09:35"),
            6 to ("13:20" to "14:40"), 7 to ("13:20" to "14:40"),
            8 to ("15:00" to "16:20"), 9 to ("15:00" to "16:20"),
            10 to ("16:35" to "17:55"), 11 to ("16:35" to "17:55"),
            12 to ("18:10" to "19:30"), 13 to ("18:10" to "19:30"),
            14 to ("19:35" to "20:15"), 15 to ("20:20" to "21:00"))

    /** 在共有时段上叠加该方案的上午 3~5 节（连格形状各方案不同：A 是 3 与 4-5，B/C 是 3-4 与 5）。 */
    private fun expected(morning: Map<Int, Pair<String, String>>): Map<Int, Pair<String, String>> =
            common + morning

    private val morningA = mapOf(3 to ("09:55" to "10:35"), 4 to ("10:40" to "12:00"), 5 to ("10:40" to "12:00"))
    private val morningB = mapOf(3 to ("09:55" to "11:15"), 4 to ("09:55" to "11:15"), 5 to ("11:20" to "12:00"))
    private val morningC = mapOf(3 to ("10:15" to "11:35"), 4 to ("10:15" to "11:35"), 5 to ("11:40" to "12:20"))

    private fun rowsOf(times: CourseTimes, group: String): Map<Int, Pair<String, String>> =
            times.rowsFor(group).associate { it.node to (it.startTime to it.endTime) }

    @Test
    fun `every group matches the published schedule node by node`() {
        val data = SuesEamsImporter.parse(rawJson)
        val times = CourseTimes.of(data.timeDetails)

        // 默认分组固定 B 方案（DEFAULT_SCHEME），左侧时间栏显示的就是它
        assertEquals(expected(morningB), rowsOf(times, CourseTimes.DEFAULT_GROUP))
        // A 方案（A、F 楼，J301）
        assertEquals(expected(morningA), rowsOf(times, "A"))
        // B 方案（B、C 楼，J302），教室认不出楼栋时也按它兜底
        assertEquals(expected(morningB), rowsOf(times, "B"))
        // C 方案（D、E 楼，J303）
        assertEquals(expected(morningC), rowsOf(times, "C"))
    }

    @Test
    fun `groups are exactly default plus the three schemes`() {
        val data = SuesEamsImporter.parse(rawJson)
        val groups = data.timeDetails.map { it.timeGroup }.toSet()
        assertEquals(setOf(CourseTimes.DEFAULT_GROUP, "A", "B", "C"), groups)
    }

    /**
     * 「星期X」的字 → 数字必须逐个钉住。
     *
     * 为什么单独要有这一条：把 `三` 映射成 `5`（也就是周三的课**全部排到周五**）之后，
     * 整套 238 项测试**全绿** —— 既有夹具虽然写着「星期三」，但断言的是作息表与教室分组的
     * 推导，没有一条断言"星期三的课落在第 3 天"。这条补上这个缺口。
     */
    @Test
    fun `every weekday word maps to its own day number`() {
        val words = listOf("一" to 1, "二" to 2, "三" to 3, "四" to 4,
                "五" to 5, "六" to 6, "日" to 7, "天" to 7)
        val lessons = words.mapIndexed { index, (word, _) ->
            "{\"id\":" + (index + 1) + ",\"course\":{\"nameZh\":\"课$index\"}," +
                    "\"scheduleText\":{\"dateTimePlacePersonText\":{\"textZh\":" +
                    "\"1-15周 星期$word 3-4节 松江校区 A501 老师\"}}}"
        }.joinToString(",")
        val json = "{\"currentWeek\":1,\"weekIndices\":[1,2,3],\"lessons\":[$lessons]}"

        val data = SuesEamsImporter.parse(json)

        assertEquals("字与数字必须一一对应；错一个，那一天的课会整批排到别的日子",
                words.map { it.second }.sorted(),
                data.courseDetailList.map { it.day }.sorted())
    }

    @Test
    fun `every node of every group resolves to a usable time`() {
        val data = SuesEamsImporter.parse(rawJson)
        val times = CourseTimes.of(data.timeDetails)
        for (group in listOf(CourseTimes.DEFAULT_GROUP, "A", "B", "C")) {
            for (node in 1..15) {
                val row: TimeDetailBean? = times.rowsFor(group).firstOrNull { it.node == node }
                assertEquals("分组 $group 第 $node 节应有且仅有一行", 1,
                        times.rowsFor(group).count { it.node == node })
                assertEquals("分组 $group 第 $node 节起止时间应成对",
                        true, row!!.startTime.isNotBlank() && row.endTime.isNotBlank())
            }
        }
    }

    /**
     * 默认分组（左侧时间栏）取**本次导入里安排最多的那套**：三套作息一个竖排轴只表示得了一套，
     * 用占比最大的那套，轴与屏幕上大多数课块就是对齐的。设置页可以再手动改（见 `ofPreferred`）。
     */
    @Test
    fun `default group follows the scheme with the most arrangements`() {
        // D 楼（C 方案）3 条、B 楼（B 方案）1 条 ⇒ 轴应当是 C
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"D楼课一"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期一 3~4节 松江校区 D210多 张三"}}},
             {"id":2,"course":{"nameZh":"D楼课二"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期二 3~4节 松江校区 D211多 李四"}}},
             {"id":3,"course":{"nameZh":"D楼课三"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期三 3~4节 松江校区 E406多 王五"}}},
             {"id":4,"course":{"nameZh":"B楼课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期五 3~4节 松江校区 B210多 赵六"}}}
            ]}
        """.trimIndent()
        val times = CourseTimes.of(SuesEamsImporter.parse(json).timeDetails)
        assertEquals("轴应取占比最大的 C 方案（D、E 楼）", expected(morningC), rowsOf(times, CourseTimes.DEFAULT_GROUP))
    }

    /** 并列时取 B（学校口径里的"其他楼宇、教学场所"），保证同一份数据每次导入得到同一个轴。 */
    @Test
    fun `a tie falls back to scheme B`() {
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"D楼课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期一 3~4节 松江校区 D210多 张三"}}},
             {"id":2,"course":{"nameZh":"B楼课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期二 3~4节 松江校区 B210多 李四"}}}
            ]}
        """.trimIndent()
        val times = CourseTimes.of(SuesEamsImporter.parse(json).timeDetails)
        assertEquals(expected(morningB), rowsOf(times, CourseTimes.DEFAULT_GROUP))
    }

    /**
     * **不在上午 3~5 节的课没有投票权**。
     *
     * 三套作息只在这几节不同：一门第 6~7 节的课，无论它在 D 楼（C 方案）还是 B 楼（B 方案），
     * 时间都是 13:20~14:40 —— 拿它投票，等于让与上午那几节无关的课决定上午显示什么。
     * 这份数据里 C 的方案有三条 6~7 节的课、B 只有一条 3~4 节的课，轴必须是 **B**。
     */
    @Test
    fun `courses outside the morning 3-5 periods do not vote`() {
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"D楼晚间一"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期一 6~7节 松江校区 D210多 张三"}}},
             {"id":2,"course":{"nameZh":"D楼晚间二"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期二 6~7节 松江校区 D211多 李四"}}},
             {"id":3,"course":{"nameZh":"D楼晚间三"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期三 8~9节 松江校区 E406多 王五"}}},
             {"id":4,"course":{"nameZh":"B楼上午"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期五 3~4节 松江校区 B210多 赵六"}}}
            ]}
        """.trimIndent()
        val times = CourseTimes.of(SuesEamsImporter.parse(json).timeDetails)
        assertEquals("只有 3~4 节那门课有权投票，轴应是 B", expected(morningB),
                rowsOf(times, CourseTimes.DEFAULT_GROUP))
    }

    /**
     * **长实验课不投票**：第 1~10 节只是"经过" 3~5 节，但它的上边锚在第 1 行、下边锚在第 10 行，
     * 而这两行在三套作息下逐格相同 —— 任何轴下这个块都画在同一个位置。
     *
     * 真实样本里当前学期正是这种情形：新能源汽车技术实验、智能汽车控制实验各在周一/周二/周四
     * 上 1~10 节（A501），共 6 条安排。若让它们投票，轴会从 C 被拉到 A，而学生**每天**坐在
     * 3~4 节里的是 E406、E210 那几门 C 楼课。
     */
    @Test
    fun `long lab courses crossing the morning periods do not vote`() {
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"A楼长实验"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期一 1~10节 松江校区 A501（机房） 张三"}}},
             {"id":2,"course":{"nameZh":"A楼长实验"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期二 1~10节 松江校区 A501（机房） 张三"}}},
             {"id":3,"course":{"nameZh":"A楼长实验"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期四 1~10节 松江校区 A501（机房） 张三"}}},
             {"id":4,"course":{"nameZh":"E楼上午课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期三 3~4节 松江校区 E406多 李四"}}}
            ]}
        """.trimIndent()
        val times = CourseTimes.of(SuesEamsImporter.parse(json).timeDetails)
        assertEquals("只有 E 楼那门 3~4 节的课有权投票，轴应是 C", expected(morningC),
                rowsOf(times, CourseTimes.DEFAULT_GROUP))
    }

    /** 反过来：只要有一门课**从 3~5 节里起步或收尾**，它就投票。 */
    @Test
    fun `a placement starting inside the morning periods does vote`() {
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"A楼长实验"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期一 3~9节 松江校区 A501（机房） 张三"}}},
             {"id":2,"course":{"nameZh":"B楼长实验"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期二 3~9节 松江校区 B210多 李四"}}}
            ]}
        """.trimIndent()
        val times = CourseTimes.of(SuesEamsImporter.parse(json).timeDetails)
        assertEquals("两票并列 ⇒ 回退 B", expected(morningB), rowsOf(times, CourseTimes.DEFAULT_GROUP))
    }

    /**
     * 三套带分组的作息**一律写全**，不管这次用到没用到：设置页允许把时间栏切到任意一套，
     * 而切换不该要求重新导入（重新导入会换一张课表、丢掉手工改动）。
     */
    @Test
    fun `all three schemes are always written even when unused`() {
        // 这份数据只用到 B 楼（B 方案），A、C 两套照样要有行
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"B楼课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期一 3~4节 松江校区 B210多 张三"}}}
            ]}
        """.trimIndent()
        val times = CourseTimes.of(SuesEamsImporter.parse(json).timeDetails)
        assertEquals("A 方案的行必须写全", expected(morningA), rowsOf(times, "A"))
        assertEquals("C 方案的行必须写全", expected(morningC), rowsOf(times, "C"))
        // 切到 A 方案时，左侧时间栏与"没有分组的课程"就按 A 走
        val switched = CourseTimes.of(SuesEamsImporter.parse(json).timeDetails, "A")
        assertEquals(expected(morningA), switched.defaultList.associate { it.node to (it.startTime to it.endTime) })
    }

    /**
     * 教务系统里"有课、但一条排课时间都没排"的课程要能被点名报出来。
     *
     * 真实样本里这种课是存在的：军训、军事理论、大学物理实验A 上下，接口 `scheduleText`
     * 四个字段全是 null。它们不会出现在课表里，**这不是解析失败，但用户会以为少了课**。
     */
    @Test
    fun `courses with no schedule text at all are reported`() {
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"正常课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期一 1~2节 松江校区 B210多 张三"}}},
             {"id":2,"course":{"nameZh":"军事理论"},"scheduleText":{"dateTimePlacePersonText":{"textZh":null}}},
             {"id":3,"course":{"nameZh":"大学物理实验A（上）"},"scheduleText":{"dateTimePlacePersonText":{"textZh":""}}}
            ]}
        """.trimIndent()
        val data = SuesEamsImporter.parse(json)
        assertEquals(listOf("军事理论", "大学物理实验A（上）"), data.coursesWithoutSchedule)
        assertEquals("正常课必须照旧入库", 1, data.courseBaseList.size)
    }

    /** 排课文本解析得出来、但展开后一周都不剩的课（如「3~3(双)」）同样算"没排上课表"。 */
    @Test
    fun `a lesson whose weeks expand to nothing also counts as unscheduled`() {
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"正常课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期一 1~2节 松江校区 B210多 张三"}}},
             {"id":2,"course":{"nameZh":"空集课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"3~3(双)周 星期二 3~4节 松江校区 B210多 李四"}}}
            ]}
        """.trimIndent()
        assertEquals(listOf("空集课"), SuesEamsImporter.parse(json).coursesWithoutSchedule)
    }
}
