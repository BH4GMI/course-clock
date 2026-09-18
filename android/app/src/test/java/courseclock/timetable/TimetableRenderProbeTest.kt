package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.schedule.ScheduleUI
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseTimes
import courseclock.timetable.utils.getPrefer
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 把课表**真正画出来**存成 PNG（诊断与预览用，不是断言型测试）。
 *
 * 主课表的观感只能在真机上看，于是每次改视觉都得装到手机上让人看一眼，看不对再猜一轮。
 * Robolectric 的 NATIVE 图形模式下 Canvas 是真的在画，所以在单测里就能出图：同一个 ScheduleUI、
 * 同一个 blockBox、同一个密度 —— 写出来就是手机会画的样子。改视觉前先在这里看一眼，别再拿真机当画板。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimetableRenderProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 与测试课表一致的作息：默认分组 B，另有 A、C 两套上午错峰。 */
    private fun timeDetails(): List<TimeDetailBean> {
        fun row(node: Int, start: String, end: String, group: String) =
                TimeDetailBean(node = node, startTime = start, endTime = end, timeTable = 1, timeGroup = group)
        return listOf(
                row(1, "08:15", "09:35", ""), row(2, "08:15", "09:35", ""),
                row(3, "09:55", "11:15", ""), row(4, "09:55", "11:15", ""), row(5, "11:20", "12:00", ""),
                row(6, "13:20", "14:40", ""),
                row(3, "09:55", "10:35", "A"), row(4, "10:40", "12:00", "A"), row(5, "10:40", "12:00", "A"),
                row(3, "09:55", "11:15", "B"), row(4, "09:55", "11:15", "B"), row(5, "11:20", "12:00", "B"),
                row(3, "10:15", "11:35", "C"), row(4, "10:15", "11:35", "C"), row(5, "11:40", "12:20", "C"))
    }

    private fun dip(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun render(textColor: Int, backdrop: Int, gridOn: Boolean, outName: String) {
        context.getPrefer().edit().putBoolean(Const.KEY_SCHEDULE_GRID, gridOn).commit()
        context.getPrefer().edit().putBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, true).commit()

        val table = TableBean(id = 1, tableName = "渲染", nodes = 8, timeTable = 1,
                startDate = "2026-09-14", itemHeight = 56, textColor = textColor)
        val times = CourseTimes.of(timeDetails())
        val ui = ScheduleUI(context, table, 1, times = times)
        val content = ui.content

        // 左侧时间栏文字（ScheduleFragment.fillTimeColumn 做的事）
        for (i in 0 until table.nodes) {
            val timeRow = times.defaultTimeForNode(i + 1) ?: continue
            val frame = content.findViewById<FrameLayout>(R.id.anko_tv_node1 + i)
            frame.findViewById<TextView>(R.id.tv_start).text = timeRow.startTime
            frame.findViewById<TextView>(R.id.tv_end).text = timeRow.endTime
        }

        // 四个课块：默认方案两节、C 楼两节、A 楼两节、默认方案跨两节 —— 正好覆盖"正常课"与"被偏移的课"
        data class Block(val day: Int, val node: Int, val step: Int, val group: String, val name: String)
        listOf(
                Block(0, 3, 2, "B", "汽车理论\n@B210"),
                Block(1, 3, 2, "C", "机械设计基础\n@D401"),
                Block(2, 4, 2, "A", "汽车电控技术\n@A201"),
                Block(3, 1, 2, "B", "高等数学\n@F101")).forEach { block ->
            val column = content.findViewById<FrameLayout>(R.id.anko_ll_week_panel_0 + block.day)
            val view = TextView(context).apply {
                text = block.name
                setTextColor(0xffffffff.toInt())
                textSize = 12f
                setPadding(8, 8, 8, 8)
                background = GradientDrawable().apply {
                    setColor(0xff2979ff.toInt())
                    cornerRadius = 12f
                }
            }
            val box = times.blockBox(block.node, block.step, block.group, ui.itemHeight, ui.rowGap)
            column.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, box.height).apply {
                gravity = Gravity.TOP
                topMargin = box.top
            })
        }

        // 真的量一遍再 layout：ViewUtils.layoutView 先 layout 后 measure，会把刚加进来的块跳过。
        val width = 1220
        val scroll = ui.scrollView
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2656, View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, width, scroll.measuredHeight)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(content.measuredHeight, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)

        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 课表底板颜色来自 Activity 主题，不在 ScheduleUI 里；这里先铺上，深色模式才看得出线的强弱。
        canvas.drawColor(backdrop)
        content.draw(canvas)
        val file = File("../../_crop/$outName")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun renderLight() = render(0xff000000.toInt(), 0xffffffff.toInt(), gridOn = true, outName = "render_light.png")

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun renderDark() = render(0xffffffff.toInt(), 0xff2A2F3A.toInt(), gridOn = true, outName = "render_dark.png")

    // ------------------------------------------------------------------
    // 整张测试课表（数据就是手机里那份 .wakeup_schedule）
    // ------------------------------------------------------------------

    /**
     * 与 [SuesConflictTableTest] 共用同一份夹具：随仓库提交的测试资源，不是运行时生成物，
     * 这样全新 clone 上直接跑 `./gradlew test` 就能出图。
     */
    private val fixtureLines: List<String> by lazy {
        val stream = javaClass.getResourceAsStream("/sues_345_conflict_test.wakeup_schedule")
                ?: throw AssertionError("测试夹具缺失：android/app/src/test/resources/" +
                        "sues_345_conflict_test.wakeup_schedule")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .lines().filter { it.isNotBlank() }
    }

    private fun renderWholeTable(textColor: Int, backdrop: Int, outName: String,
                                 backgroundUri: String = ""): Bitmap {
        context.getPrefer().edit().putBoolean(Const.KEY_SCHEDULE_GRID, true).commit()
        context.getPrefer().edit().putBoolean(Const.KEY_SCHEDULE_DETAIL_TIME, true).commit()

        val gson = com.google.gson.Gson()
        val lines = fixtureLines
        val timeDetails: List<TimeDetailBean> = gson.fromJson(lines[1],
                object : com.google.gson.reflect.TypeToken<List<TimeDetailBean>>() {}.type)
        val table: TableBean = gson.fromJson(lines[2], TableBean::class.java)
        val bases: List<courseclock.timetable.bean.CourseBaseBean> = gson.fromJson(lines[3],
                object : com.google.gson.reflect.TypeToken<List<courseclock.timetable.bean.CourseBaseBean>>() {}.type)
        val details: List<courseclock.timetable.bean.CourseDetailBean> = gson.fromJson(lines[4],
                object : com.google.gson.reflect.TypeToken<List<courseclock.timetable.bean.CourseDetailBean>>() {}.type)

        table.id = 1
        table.textColor = textColor
        // 自定义背景图：ScheduleUI 的取色规则要看它（原来"有背景图就用 table.textColor"，
        // 而默认 textColor 是纯黑 → 深色模式下时间栏、节次、虚线全黑）。
        table.background = backgroundUri
        val times = CourseTimes.of(timeDetails)
        val ui = ScheduleUI(context, table, 1, times = times)
        val content = ui.content

        for (i in 0 until table.nodes) {
            val timeRow = times.defaultTimeForNode(i + 1) ?: continue
            val frame = content.findViewById<FrameLayout>(R.id.anko_tv_node1 + i)
            frame.findViewById<TextView>(R.id.tv_start).text = timeRow.startTime
            frame.findViewById<TextView>(R.id.tv_end).text = timeRow.endTime
        }

        val colorOf = bases.associate { it.id to it.color }
        val nameOf = bases.associate { it.id to it.courseName }
        details.forEach { detail ->
            val column = content.findViewById<FrameLayout>(R.id.anko_ll_week_panel_0 + detail.day - 1)
            val view = TextView(context).apply {
                text = "${times.startOfNode(detail.startNode, detail.timeGroup)}\n" +
                        nameOf[detail.id] + "\n@" + (detail.room ?: "")
                setTextColor(0xffffffff.toInt())
                textSize = 10f
                setPadding(6, 6, 6, 6)
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor(colorOf[detail.id] ?: "#ff2979ff"))
                    cornerRadius = 10f
                }
            }
            val box = times.blockBox(detail.startNode, detail.step, detail.timeGroup, ui.itemHeight, ui.rowGap)
            column.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, box.height).apply {
                gravity = Gravity.TOP
                topMargin = box.top
            })
        }

        val width = 1220
        val scroll = ui.scrollView
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2656, View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, width, scroll.measuredHeight)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(content.measuredHeight, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)

        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backdrop)
        content.draw(canvas)
        val file = File("../../_crop/$outName")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /**
     * 深色模式 + 自定义背景图时，时间栏/节次/虚线必须仍然可见（浅色）。
     *
     * 这条曾经是"设了背景图就整屏纯黑"：`table.textColor` 默认纯黑，而取色规则一看到
     * background 非空就无条件用它。这里真的渲染出来数像素 —— 修复前时间栏区域一个亮像素都没有。
     */
    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 深色模式加自定义背景图时时间栏不是纯黑() {
        val bitmap = renderWholeTable(0xff000000.toInt(), 0xff2A2F3A.toInt(),
                "render_night_custombg.png", backgroundUri = "content://com.android.fileexplorer.myprovider/x.jpg")
        var black = 0
        var drawn = 0
        for (y in 0 until bitmap.height) {
            // 只数时间栏那一条（宽度约 78px，第 1 天从 x=81 开始）：
            // 越过 81 就会数到课程色块，那跟文字颜色无关。
            for (x in 0 until minOf(72, bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(c) + android.graphics.Color.green(c) +
                        android.graphics.Color.blue(c) < 90) black++
                // 与底板 #2A2F3A 差得够远 = 真的画上了文字/虚线
                if (Math.abs(android.graphics.Color.red(c) - 0x2A) +
                        Math.abs(android.graphics.Color.green(c) - 0x2F) +
                        Math.abs(android.graphics.Color.blue(c) - 0x3A) > 30) drawn++
            }
        }
        // 不写死具体颜色（灰/白都算对），只钉住"深色模式下不许纯黑"这个用户要求。
        // 修复前实测：时间栏里纯黑 12500 个像素、一个非底板的像素都没有（整条文字都没了）。
        org.junit.Assert.assertTrue("时间栏区域纯黑像素 $black 个——深色模式下又变成纯黑了", black < 100)
        org.junit.Assert.assertTrue("时间栏没画出任何文字/虚线（drawn=$drawn）", drawn > 500)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun renderWholeTableLight() {
        renderWholeTable(0xff000000.toInt(), 0xffffffff.toInt(), "render_table_light.png")
    }

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun renderWholeTableDark() {
        renderWholeTable(0xffffffff.toInt(), 0xff2A2F3A.toInt(), "render_table_dark.png")
    }
}
