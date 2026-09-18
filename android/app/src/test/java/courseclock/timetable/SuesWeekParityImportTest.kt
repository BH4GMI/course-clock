package courseclock.timetable

import courseclock.timetable.schedule_import.sues.SuesEamsImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 教务系统的「(单)」「(双)」周次标注（真实样本回归）。
 *
 * 2023-2024 到 2026-2027 共 7 个学期的课表接口返回里，有 4 个学期、8 条排课文本长这样：
 * 「2~16(双)周 星期五 3~4节 …」「1~3(单),4~16周 星期一 6~7节 …」。旧实现的正则只认
 * 纯数字与区间，这 8 条会走到 `throw Exception("无法解析排课文本：…")`，而
 * [SuesEamsImporter.parse] 里那条异常会**掀掉整份导入** —— 用户看到的是"导入失败"，
 * 一门课都进不来（当前学期恰好没有单双周，所以这个坑一直没被踩到）。
 *
 * 这里钉两件事：
 * 1. 标注必须被解析成正确的周次集合（`(双)` = 偶数周、`(单)` = 奇数周），
 *    **不是**把标注当噪声删掉 —— 删掉会把「2~16(双)」展开成第 2~16 周的每一周。
 * 2. 展开后的周次列表要能落回 App 的单双周语义：`type` 1 = 单周、2 = 双周、0 = 每周，
 *    区间端点也要对得上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SuesWeekParityImportTest {

    private val rawJson = """
        {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
         {"id":1,"course":{"nameZh":"双周课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"2~16(双)周 星期一 1~2节 松江校区 B210多 张三"}}},
         {"id":2,"course":{"nameZh":"单周课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"3~17(单)周 星期二 3~4节 松江校区 A501 李四"}}},
         {"id":3,"course":{"nameZh":"混合周课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~3(单),4~16周 星期三 6~7节 松江校区 C210多 王五"}}},
         {"id":4,"course":{"nameZh":"散列双周课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"2,7,14~16(双)周 星期四 8~9节 松江校区 D210多 赵六"}}}
        ]}
    """.trimIndent()

    /** (课程, 起始周, 结束周, 类型) —— 类型 0 每周 / 1 单周 / 2 双周。 */
    private fun rowsOf(json: String): List<List<Int>> =
            SuesEamsImporter.parse(json).let { data ->
                val nameById = data.courseBaseList.associate { it.id to it.courseName }
                data.courseDetailList.map { detail ->
                    val name = nameById.getValue(detail.id)
                    val index = listOf("双周课", "单周课", "混合周课", "散列双周课").indexOf(name)
                    listOf(index, detail.startWeek, detail.endWeek, detail.type)
                }
            }

    @Test
    fun `even week marker expands to even weeks only`() {
        val rows = rowsOf(rawJson)
        // 「2~16(双)」= 第 2、4、…、16 周：落成一行双周，区间仍是 2~16
        assertEquals(listOf(0, 2, 16, 2), rows.first { it[0] == 0 })
    }

    @Test
    fun `odd week marker expands to odd weeks only`() {
        val rows = rowsOf(rawJson)
        assertEquals(listOf(1, 3, 17, 1), rows.first { it[0] == 1 })
    }

    @Test
    fun `marker applies to its own comma part only`() {
        val rows = rowsOf(rawJson)
        // 「1~3(单),4~16周」：前一段单周、后一段每周，不能互相污染
        assertEquals(listOf(listOf(2, 1, 3, 1), listOf(2, 4, 16, 0)), rows.filter { it[0] == 2 })
    }

    @Test
    fun `scattered weeks with a marked range split into three segments`() {
        val rows = rowsOf(rawJson)
        // {2} 每周、{7} 每周、{14,16} 双周 —— 非连续周次必须拆段
        assertEquals(listOf(listOf(3, 2, 2, 0), listOf(3, 7, 7, 0), listOf(3, 14, 16, 2)),
                rows.filter { it[0] == 3 })
    }

    @Test
    fun `a marked range that matches no week of that parity imports nothing instead of failing`() {
        // 「3~3(双)」在双周里没有可取的第 3 周：该条不产出安排，但整份导入不能失败
        val json = """
            {"currentWeek":1,"weekIndices":[1,2,3],"lessons":[
             {"id":1,"course":{"nameZh":"正常课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1~8周 星期五 1~2节 松江校区 B210多 张三"}}},
             {"id":2,"course":{"nameZh":"空集课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"3~3(双)周 星期一 3~4节 松江校区 A501 李四"}}}
            ]}
        """.trimIndent()
        val data = SuesEamsImporter.parse(json)
        assertTrue("正常课必须照旧入库", data.courseDetailList.isNotEmpty())
        assertEquals("空集课不应产出任何安排", 0,
                data.courseDetailList.count { it.id == data.courseBaseList.first { b -> b.courseName == "空集课" }.id })
    }
}
