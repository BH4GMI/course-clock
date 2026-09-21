package courseclock.timetable

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户最在意的那条体验 —— **「小组件下课提醒保持每分钟更新，通知提醒要准时」** —— 的守卫。
 *
 * ## 为什么是"读源码"而不是"跑行为"
 *
 * "提醒准不准"这件事在单元测试里**测不到**：它取决于 `AlarmManager` 的调用方式，而不是
 * 返回值的算术。真机实证（`CourseReminderScheduler.setExact` 的 dumpsys 记录）表明这台
 * HyperOS 设备会把**非精确**闹钟一律改写成 `requester + 3 天`，精确闹钟的 `power_pending`
 * 恒为 `--`。所以"精确"不是性能取舍，是这条提醒能不能响的前提，值得一条会红的守卫。
 *
 * 行为侧的覆盖在别处：每分钟一个刷新点由
 * `CourseReminderSchedulerTest.countdownInstantsCoverEveryMinuteOfTheWindowIncludingTheEnd`
 * 与 `countdownMinutesLeftIsOneAtTheEdgeAndCoversTheWholeWindow` 钉住；这里只钉"用什么 API 排"。
 *
 * ## 为什么要先剥注释
 *
 * 这个文件的注释里**故意**写着各种反面写法（`setInexactRepeating(ELAPSED_REALTIME_WAKEUP, …)`、
 * `setAlarmClock`、`AlarmManager.set`、`WorkManager`）。不剥注释直接扫全文，守卫会被自己的
 * 说明文字绊倒。
 */
class ReminderTimelinessGuardTest {

    private val moduleDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return@lazy dir
            dir = dir.parentFile
        }
        throw AssertionError("从 ${File("").absolutePath} 向上找不到模块目录（src/main/AndroidManifest.xml）")
    }

    /** 调度器的源码，**注释已剥离**（保留字符串字面量）。 */
    private val schedulerCode: String by lazy {
        stripComments(File(moduleDir,
                "src/main/java/courseclock/timetable/utils/CourseReminderScheduler.kt").readText())
    }

    private val nativeClockCode: String by lazy {
        stripComments(File(moduleDir, "src/main/java/courseclock/timetable/utils/CourseTime.kt").readText())
    }

    /**
     * 去掉 `//` 行注释与 `/* */` 块注释，保留字符串字面量里的内容。
     *
     * 处理字符串是为了不把 `"http://…"` 这类字面量当成注释的开头。
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        var rawString = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append(c) }
                    i++
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; i += 2 } else {
                        if (c == '\n') out.append(c)
                        i++
                    }
                }
                rawString -> {
                    if (c == '"' && next == '"' && source.getOrNull(i + 2) == '"') {
                        rawString = false; i += 3
                    } else i++
                }
                inString -> {
                    if (c == '\\') i += 2 else { if (c == '"') inString = false; i++ }
                }
                c == '/' && next == '/' -> { inLine = true; i += 2 }
                c == '/' && next == '*' -> { inBlock = true; i += 2 }
                c == '"' && next == '"' && source.getOrNull(i + 2) == '"' -> { rawString = true; i += 3 }
                c == '"' -> { inString = true; i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    @Test
    fun 调度器只使用精确闹钟() {
        val scheduled = Regex("""(?:alarmManager|manager)\.(set\w*)\(""")
                .findAll(schedulerCode + nativeClockCode)
                .map { it.groupValues[1] }
                .toSet()

        assertEquals(
                "本类排出的每一枚闹钟都必须是精确的：非精确闹钟（window > 0）会被 HyperOS 的 " +
                        "power_pending 策略推成 requester + 3 天，提醒等于不响。" +
                        "允许的只有 setExactAndAllowWhileIdle（提醒）与 setExact（倒计时刷新链）；" +
                        "set / setWindow / setRepeating / setInexactRepeating / setAlarmClock 一律不得出现。",
                setOf("setExactAndAllowWhileIdle", "setExact"),
                scheduled)
    }

    @Test
    fun 课程边界唤醒而纯显示刷新不唤醒() {
        assertFalse("RTC_WAKEUP 之外的类型必须在决定前重新论证（ELAPSED_REALTIME 用开机时长，关机/重启后就错位）",
                (schedulerCode + nativeClockCode).contains("ELAPSED_REALTIME"))

        assertTrue(nativeClockCode.contains("setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP"))
        assertTrue(nativeClockCode.contains("setExact(if (idle) AlarmManager.RTC_WAKEUP else AlarmManager.RTC"))
    }

    @Test
    fun 不使用会被系统延迟的调度框架() {
        val lowered = (schedulerCode + nativeClockCode).lowercase()
        for (forbidden in listOf("workmanager", "jobscheduler", "setalarmclock")) {
            assertFalse("提醒不得改走 $forbidden：它们在 Doze/待机下会被延迟，" +
                    "而本 App 的 7 天滚动窗口依赖闹钟真的在那一刻响",
                    lowered.contains(forbidden))
        }
    }

    @Test
    fun 倒计时刷新链也仍然是精确闹钟() {
        val display = Regex("""private fun setExactDisplay[\s\S]*?\n    }""")
                .find(schedulerCode)?.value
                ?: throw AssertionError("找不到 setExactDisplay：倒计时刷新链的排法变了，请更新这条守卫")

        assertTrue("倒计时刷新必须走 setExact（window = 0）。改成非精确的 set()/setWindow() 之后，" +
                "这一枚同样会被 power_pending 推后，每分钟的数字就不再准时跳",
                display.contains("CourseClock.schedule(") && display.contains("idle = false") &&
                        nativeClockCode.contains("else manager.setExact("))
        assertFalse("倒计时刷新链不得使用非精确排法",
                display.contains("setWindow(") || Regex("""\bset\(""").containsMatchIn(display))
    }
}
