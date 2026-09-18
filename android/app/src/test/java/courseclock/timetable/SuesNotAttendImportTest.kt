package courseclock.timetable

import courseclock.timetable.schedule_import.sues.SuesEamsImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 免听 / 重修字段的导入链路。
 *
 * 顶层 `notAttendLessonIds` 是权威来源；`lessonId2Retake` 只决定角标文案。
 * 同名课若免听状态不同，必须拆成两条基表，否则标记会静默丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SuesNotAttendImportTest {

    @Test
    fun `notAttendLessonIds and lessonId2Retake land on the right base rows`() {
        val raw = """
            {"currentWeek":1,"weekIndices":[1,2,3],
             "notAttendLessonIds":[2],
             "lessonId2Retake":{"2":true},
             "lessons":[
              {"id":1,"course":{"nameZh":"正常课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-3周 星期一 1-2节 B210多 张三"}}},
              {"id":2,"course":{"nameZh":"重修免听课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-3周 星期一 1-2节 E510多 李四"}}}
             ]}
        """.trimIndent()

        val data = SuesEamsImporter.parse(raw)
        val normal = data.courseBaseList.single { it.courseName == "正常课" }
        val na = data.courseBaseList.single { it.courseName == "重修免听课" }

        assertFalse(normal.notAttend)
        assertFalse(normal.retake)
        assertTrue(na.notAttend)
        assertTrue(na.retake)
    }

    @Test
    fun `same course name with different notAttend status becomes two base rows`() {
        val raw = """
            {"currentWeek":1,"weekIndices":[1,2],
             "notAttendLessonIds":[11],
             "lessonId2Retake":{"11":true},
             "lessons":[
              {"id":10,"course":{"nameZh":"同名课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-2周 星期二 1-2节 B210多 张三"}}},
              {"id":11,"course":{"nameZh":"同名课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1-2周 星期三 3-4节 E510多 李四"}}}
             ]}
        """.trimIndent()

        val data = SuesEamsImporter.parse(raw)
        val sameName = data.courseBaseList.filter { it.courseName == "同名课" }
        assertEquals(2, sameName.size)
        assertEquals(1, sameName.count { it.notAttend })
        assertEquals(1, sameName.count { !it.notAttend })
    }

    @Test
    fun `missing notAttend fields default to false`() {
        val raw = """
            {"currentWeek":1,"weekIndices":[1],
             "lessons":[
              {"id":1,"course":{"nameZh":"普通课"},"scheduleText":{"dateTimePlacePersonText":{"textZh":"1周 星期一 1-2节 B210多 张三"}}}
             ]}
        """.trimIndent()

        val data = SuesEamsImporter.parse(raw)
        val base = data.courseBaseList.single()
        assertFalse(base.notAttend)
        assertFalse(base.retake)
    }
}
