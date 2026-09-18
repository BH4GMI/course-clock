package courseclock.timetable

import courseclock.timetable.utils.ViewUtils.parseCourseColor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseCourseColor] 是全工程唯一的课程颜色出口（主课表 / 周视图 / 日视图三处渲染都走它）。
 * 课程颜色在库里是任意字符串——历史导入、其他 fork 的分享文件都可能写进畸形值——旧实现
 * 在渲染端各自 `substring`/`Color.parseColor`，一个畸形值就崩掉整块课表或整个小部件。
 * 这里钉住它的契约：只认 `#RRGGBB` 与 `#AARRGGBB`，其余一律退回 fallback，绝不抛异常。
 */
class CourseColorParseTest {

    private val fallback = 0xFFFA6278L.toInt()

    @Test
    fun `null and empty fall back`() {
        assertEquals(fallback, parseCourseColor(null, fallback))
        assertEquals(fallback, parseCourseColor("", fallback))
    }

    @Test
    fun `six digit hex gets opaque alpha`() {
        assertEquals(0xFFFF1744L.toInt(), parseCourseColor("#FF1744", fallback))
        assertEquals(0xFF2979FFL.toInt(), parseCourseColor("#2979ff", fallback))
    }

    @Test
    fun `eight digit hex keeps its alpha`() {
        // SuesEamsImporter.colorFor 写出的形态：# + AARRGGBB
        assertEquals(0xFFFF1744L.toInt(), parseCourseColor("#FFFF1744", fallback))
        assertEquals(0x80FF1744L.toInt(), parseCourseColor("#80ff1744", fallback))
    }

    @Test
    fun `malformed values fall back instead of throwing`() {
        val bad = listOf("12345", "#12345", "#1234567", "#123456789", "garbage", "#GG1122",
                " #FF1744", "#FF1744 ", "##FF1744")
        for (value in bad) {
            assertEquals("应退回 fallback：$value", fallback, parseCourseColor(value, fallback))
        }
    }
}
