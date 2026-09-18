package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.schedule.CourseDetailFragment
import courseclock.timetable.schedule.ScheduleViewModel
import courseclock.timetable.utils.CourseDetailText
import courseclock.timetable.utils.CourseTimes
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 课程详情底部弹窗的几何与出图。
 *
 * 这一页的每一条要求都是**可量的关系**，不是"看着差不多"：弹窗铺满屏幕宽度、内容左右各留
 * [R.dimen.course_detail_inset]、所有可点目标不小于 48dp、以及设计说明点名的"摘要里出现过的
 * 信息明细里不再写一遍"。所以这里既真的量一遍，也把弹窗画成 PNG
 * （[出图_课程详情] / [出图_课程详情_深色]）：改完先看图，不用装到手机上。
 *
 * ## 这一版跟着布局一起换过口径
 *
 * 详情页曾经是"标题 + 一串 56dp 的明细行（上课时间 / 授课教师 / 上课地点 / 上课提醒）"，
 * 布局重做成整块大字卡片之后，那套 `row_time` / `row_teacher` / `row_room` 行已经不存在了：
 * 时刻变成一行 25sp 的大字，地点和教师合成一组（没填时分别写作「地点未设置」与整行隐藏），
 * 「提醒设置」只剩一行通往全局设置的入口。原来那几条断言量的是已经不存在的控件，
 * 会让整个单测源码集编译不过 —— 所以这里跟着新结构重写，**不是**为了让它变绿而放宽：
 *
 * * 「明细行 ≥56dp」→ 换成新结构里真正存在的量：可点目标（关闭 / 编辑 / 删除 / 提醒设置）
 *   一律 ≥48dp，地点行的最小高度 ≥28dp；
 * * 「编辑比删除宽」这条被**删掉**了：新设计里两枚按钮都是 48dp 的图标按钮，不再分主次宽度，
 *   这是设计变更而不是放宽验证（`ib_edit` / `ib_delete_course` 各自 ≥48dp 仍然被量）；
 * * 「提醒行显示当前设置值」→ 新结构不再把课前分钟数印在详情里，「提醒设置」是一行入口，
 *   所以改成验"它是可点的入口、且不冒充课程字段"。
 *
 * 被测对象是**生产代码的三层**：弹窗的形状（贴底、只圆上面两角）来自 `fragment_base_dialog`
 * + `BaseDialogFragment`（这里把生成的根视图挂进测试 Activity），内容来自
 * `fragment_course_detail` 与 [CourseDetailFragment]，文案来自 [CourseDetailText]。
 * 测试只负责喂一条课程、把它摆成 360dp 宽再量 —— 拼法与排版都不在这里复制一份，
 * 改生产代码这一页就会跟着变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseDetailRenderTest {

    /** 一台普通手机：360dp 宽、780dp 高、xxhdpi（与 ScheduleWeekAxisTest 同一口径）。 */
    private val screenWidthDp = 360
    private val screenHeightDp = 780

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density

    private fun dip(value: Int): Int = (value * density).toInt()

    private fun px(dimen: Int): Int = context.resources.getDimensionPixelSize(dimen)

    /** 学校公布的那套作息（第 1~4 节），够这门 1-2 节的课查。 */
    private fun timeDetails(): List<TimeDetailBean> {
        fun row(node: Int, start: String, end: String) =
                TimeDetailBean(node = node, startTime = start, endTime = end, timeTable = 1, timeGroup = "")
        return listOf(row(1, "08:15", "09:35"), row(2, "08:15", "09:35"),
                row(3, "09:55", "11:15"), row(4, "09:55", "11:15"))
    }

    /** 设计稿里的那门课：高等数学 / 张伟 / A-201 / 周一 1-2 节 / 第 1-16 周。 */
    private fun course(notAttend: Boolean = false, retake: Boolean = false) = CourseBean(
            id = 1, courseName = "高等数学", day = 1, room = "A-201", teacher = "张伟",
            startNode = 1, step = 2, startWeek = 1, endWeek = 16, type = 0,
            color = "#3482FF", tableId = 1, timeGroup = "", notAttend = notAttend, retake = retake)

    /**
     * 摘要文案的**格式**必须钉在字面量上。
     *
     * 为什么需要这一条：同文件里「摘要左 = [CourseDetailText.whenText] 的结果」那种断言是
     * **自指**的（渲染侧与期望侧读同一个函数，两边一起变），把节次算成后一节
     * （「第 1-2 节」变成「第 1-3 节」）之后实测整套 241 项仍然全绿。
     * 这里把格式钉死在字面量上，格式一改就红。
     */
    @Test
    fun 摘要文案的格式必须钉在字面量上() {
        val course = course()

        assertEquals("摘要左", "周一 第 1-2 节", CourseDetailText.whenText(course))
        assertEquals("摘要右", "第 1-16 周", CourseDetailText.weekText(course))
        assertEquals("单周课要缀上「单周」", "第 1-16 周 单周",
                CourseDetailText.weekText(course.copy(type = 1)))
        assertEquals("双周课要缀上「双周」", "第 1-16 周 双周",
                CourseDetailText.weekText(course.copy(type = 2)))
    }

    /**
     * 真的把这一页渲染一遍：走 [CourseDetailFragment.onCreateView]，量成一台手机的尺寸再返回。
     *
     * 量的是**弹窗的根视图**（`fragment_course_detail` 那一层）：它本来就是 wrap_content，
     * 生产环境里由窗口给它屏幕宽度、把它贴到底边；测试里窗口不进视图树，所以这里按屏幕宽度量一次、
     * 把底边对齐到屏幕底边 —— 量出来的就是手机上那一眼。
     */
    private fun render(course: CourseBean): View {
        // setup() = create → start → resume。必须是 setup：fragment 的视图在父级进入 STARTED 之后
        // 才会创建（VIEW_CREATED 是 CREATED 的子状态），只 .create() 的话 fragment.view 恒为 null，
        // 量到的是"fragment 还没建视图"这件事，不是这一页的排版。
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()

        // activity 本来就有一个 android.R.id.content 容器，直接把 fragment 挂进去：
        // 再套一个自己 setContentView 的容器，测的就不是生产环境里那个视图树了。
        val container = activity.findViewById<ViewGroup>(android.R.id.content)

        val fragment = CourseDetailFragment.newInstance(course)
        // onViewCreated 会去 activity 的 ViewModel 拿作息表；在 inflate 之前把它准备好，
        // 出图里才有真实的上课时刻（默认是空作息，会显示"时间未设置"）。
        ViewModelProvider(activity).get(ScheduleViewModel::class.java).courseTimes =
                CourseTimes.of(timeDetails())

        activity.supportFragmentManager.beginTransaction()
                .add(container.id, fragment)
                .commitNow()

        val root = fragment.view!!
        root.measure(View.MeasureSpec.makeMeasureSpec(dip(screenWidthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dip(screenHeightDp), View.MeasureSpec.AT_MOST))
        assertEquals("弹窗放不进屏幕高度，出图会被裁", true, root.measuredHeight <= dip(screenHeightDp))
        root.layout(0, dip(screenHeightDp) - root.measuredHeight, dip(screenWidthDp), dip(screenHeightDp))
        return root
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 弹窗铺满宽度且内容左右各留_inset() {
        val root = render(course())
        assertEquals("弹窗没有铺满屏幕宽度", dip(screenWidthDp), root.width)

        // 课程名是那一列内容里最宽的一行（match_parent），所以它到卡片左右沿的距离就是内容边距。
        val inset = px(R.dimen.course_detail_inset)
        val name = root.findViewById<TextView>(R.id.tv_course_name)
        assertEquals("课程名左边没有留出内容边距", inset, name.left)
        assertEquals("课程名右边没有留出内容边距", inset, root.width - name.right)
    }

    /**
     * 可点目标一律不小于 48dp。
     *
     * 这条盯的是"手指点不准"：图标按钮、整行可点的入口都是 48dp 起，是 Material 与无障碍的
     * 共同下限。详情页原来还量"明细行 ≥56dp"，那套行在新结构里已经没有了，换成了这里的 48dp
     * 下限 —— 新结构里没有一行是"必须 56dp 高"的：时刻是大字、地点/教师是紧排的两行文字。
     */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 可点目标都不小于_48dp() {
        val root = render(course())
        val minimum = dip(48)
        listOf(R.id.close_detail to "关闭", R.id.ib_edit to "编辑课程",
                R.id.ib_delete_course to "删除", R.id.row_reminder to "提醒设置")
                .forEach { (id, name) ->
                    val target = root.findViewById<View>(id)
                    assertEquals("「$name」没有显示出来", View.VISIBLE, target.visibility)
                    assertTrue("「$name」高度只有 ${target.height}px（${target.height / density}dp），" +
                            "小于 48dp，点起来太窄", target.height >= minimum)
                    assertTrue("「$name」宽度只有 ${target.width}px（${target.width / density}dp），" +
                            "小于 48dp，点起来太窄", target.width >= minimum)
                }
    }

    /** 地点那一行的最小高度：它是图标 + 文字的一行，图标本身 28dp，文字行不该比它更矮。 */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 地点行不小于图标高度() {
        val root = render(course())
        val room = root.findViewById<View>(R.id.tv_room_value)
        assertEquals("地点没填时也要显示出来（写「地点未设置」）", View.VISIBLE, room.visibility)
        assertTrue("地点行只有 ${room.height}px（${room.height / density}dp），低于 28dp 的图标高度",
                room.height >= dip(28))
    }

    /** 设计说明点名的那个问题：摘要里写过的信息，明细里不再写第二遍。 */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 摘要与明细不重复同一条信息() {
        val root = render(course())
        val whenText = root.findViewById<TextView>(R.id.tv_when).text.toString()
        val weekText = root.findViewById<TextView>(R.id.tv_weeks).text.toString()
        val timeText = root.findViewById<TextView>(R.id.tv_time_value).text.toString()

        assertEquals("摘要左应该是「周几 + 节次」", CourseDetailText.whenText(course()), whenText)
        assertEquals("摘要右应该是周次", CourseDetailText.weekText(course()), weekText)
        assertEquals("明细里的时间应该是时刻", "08:15 - 09:35", timeText)
        // 节次只出现在摘要：明细里再出现一遍"第 1-2 节"，用户会以为是两组不同的数据。
        assertFalse("明细里又写了一遍节次：$timeText", timeText.contains("节"))
        assertFalse("摘要里又写了一遍时刻：$whenText", whenText.contains("08:15"))
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 免听课带状态标签普通课没有() {
        val normal = render(course())
        assertEquals("普通课不该有状态标签", View.GONE,
                normal.findViewById<View>(R.id.tv_status).visibility)

        val notAttend = render(course(notAttend = true, retake = true))
        val tag = notAttend.findViewById<TextView>(R.id.tv_status)
        assertEquals("免听 + 重修应该并成一枚标签", "免听 · 重修", tag.text.toString())
        assertEquals(View.VISIBLE, tag.visibility)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 地点与教师没填时各自处置() {
        val root = render(course().apply { teacher = "  "; room = null })
        // 地点是这一页的主信息，没填也要明说，免得用户以为"这课没地点"和"没读到地点"是一回事。
        assertEquals("地点没填时要写出来", "地点未设置",
                root.findViewById<TextView>(R.id.tv_room_value).text.toString())
        // 教师是次要信息，没填就整行不占位：留一行空白比不写更让人困惑。
        assertEquals("教师没填时整行不该占位", View.GONE,
                root.findViewById<View>(R.id.tv_teacher_value).visibility)
    }

    /**
     * 提醒那一行是**进入全局提醒设置的入口**，不是课程自己的字段。
     *
     * 这里原来量的是"上课前 20 分钟"这类全局设置值，新结构把这一格去掉、只留一行入口：
     * 详情面板会长高、会被挤，把全局值印在这里既占地方又容易过期。所以现在钉的是
     * 「它写着什么、能不能点、有没有冒充课程字段」。
     */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 提醒行是进入全局提醒设置的入口() {
        val root = render(course())
        val row = root.findViewById<TextView>(R.id.row_reminder)
        assertEquals("提醒设置", row.text.toString())
        assertTrue("提醒行必须可点：它是从详情进全局提醒设置的唯一入口", row.isClickable)
        assertFalse("提醒行不该冒充课程字段（例如印出课程自己的时间）",
                row.text.contains("分钟") || row.text.contains("08:15"))
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 出图_课程详情() = renderToFile("course_detail_light.png")

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 出图_课程详情_深色() = renderToFile("course_detail_dark.png")

    /**
     * 把整屏画出来：底板先按 page_background 铺满（弹窗只圆上面两角，其余是遮罩下的一屏），
     * 再把弹窗画在上面对齐底边 —— 出图就是手机上看到的样子。
     */
    private fun renderToFile(outName: String) {
        val root = render(course())
        val screen = Bitmap.createBitmap(dip(screenWidthDp), dip(screenHeightDp), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        val backdrop = ContextCompat.getColor(context, R.color.page_background)
        canvas.drawColor(backdrop)
        canvas.save()
        canvas.translate(0f, (dip(screenHeightDp) - root.height).toFloat())
        root.draw(canvas)
        canvas.restore()

        // 出图不是断言，但它必须真的画上了东西：弹窗区域一个非底板像素都没有，说明这一页是空的。
        var painted = 0
        for (y in (dip(screenHeightDp) - root.height) until dip(screenHeightDp) step 3) {
            for (x in 0 until screen.width step 3) if (screen.getPixel(x, y) != backdrop) painted++
        }
        assertTrue("弹窗区域什么都没画出来（出图是空的）", painted > 500)

        val file = File("../../_crop/$outName")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
