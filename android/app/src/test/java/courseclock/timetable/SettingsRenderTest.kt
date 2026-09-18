package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.settings.ArrowView
import courseclock.timetable.settings.PressHighlightCard
import courseclock.timetable.settings.RowCardDecoration
import courseclock.timetable.settings.RowCardPosition
import courseclock.timetable.settings.SettingItemAdapter
import courseclock.timetable.settings.SettingRowId
import courseclock.timetable.settings.SettingsList
import courseclock.timetable.settings.SwitchView
import courseclock.timetable.settings.items.BaseSettingItem
import courseclock.timetable.settings.items.CategoryItem
import courseclock.timetable.settings.items.HorizontalItem
import courseclock.timetable.settings.items.SeekBarItem
import courseclock.timetable.settings.items.SettingRowArrow
import courseclock.timetable.settings.items.SettingRowState
import courseclock.timetable.settings.items.SwitchItem
import courseclock.timetable.settings.items.VerticalItem
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.CourseReminderScheduler
import courseclock.timetable.utils.getPrefer
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置页列表的几何与出图。
 *
 * 这一页的排版契约（分组卡片左右各 16dp、单行 56dp / 两行 64dp、开关 46×28 且右沿距卡片
 * 右沿 16dp、组内不画分隔线、总闸关了依赖行不可点）没有一条是"看着差不多"能验的：
 * 它们全是像素级的相等关系，而设计稿把每一条都写死了。所以这里既真的量一遍，也整页画成
 * PNG（[出图_浅色] / [出图_深色]）：改完先看图，不用装到手机上。
 *
 * 被测对象是**生产代码**：列表内容来自 [SettingsList]，行的视图来自
 * [courseclock.timetable.settings.SettingItemAdapter] 里那 5 个 provider。
 * 测试只负责把它摆成 360dp 宽、再量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density

    private fun dip(value: Int): Int = (value * density).toInt()

    private fun px(dimen: Int): Int = context.resources.getDimensionPixelSize(dimen)

    /** 一次渲染的产物：列表、recycler，以及"这一份列表里的行"（顺序与 recycler 一致）。 */
    private class Rendered(
            val list: SettingsList,
            val items: List<BaseSettingItem>,
            val recycler: RecyclerView) {

        fun rowAt(position: Int): View = recycler.getChildAt(position)!!

        fun switchAt(position: Int): SwitchView? =
                recycler.getChildAt(position)?.findViewById(R.id.anko_switch)

        /**
         * 行的卡片左右各离屏幕多远 —— 卡片缩进是行的**布局 margin**（不是背景 drawable 的内缩：
         * 背景 drawable 的 padding 会被 setBackgroundDrawable 写回视图内边距，两者会打架，
         * 见 RowCardDecoration.background）。
         */
        fun cardInsetAt(position: Int): Int? {
            val params = recycler.getChildAt(position)!!.layoutParams
            if (params !is android.view.ViewGroup.MarginLayoutParams) return null
            return if (params.marginStart == params.marginEnd) params.marginStart else null
        }

        /**
         * 这一行背景的圆角。
         *
         * 背景现在有两种形态：可点的行是 [PressHighlightCard]（整块卡片换色的按下高亮，
         * 取代了早先的水波纹），不可点的行是裸 `GradientDrawable`。两者都持有同一份
         * `cornerRadii` —— 沿真实的背景 drawable 读，而不是另抄一张常量表。
         */
        fun cardCornersAt(position: Int): FloatArray? {
            var current: Drawable? = recycler.getChildAt(position)!!.background
            while (current != null) {
                when (current) {
                    is GradientDrawable -> return current.cornerRadii
                    is PressHighlightCard -> return current.cornerRadii
                    is InsetDrawable -> current = current.drawable
                    is LayerDrawable -> current = current.getDrawable(0)
                    else -> return null
                }
            }
            return null
        }
    }

    /** 把生产代码那一份列表真的摆成 360dp 宽的 RecyclerView。 */
    private fun render(): Rendered {
        val list = SettingsList(context)
        val items = list.build()
        val recycler = RecyclerView(context)
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = SettingItemAdapter().apply { data = items }
        recycler.measure(
                View.MeasureSpec.makeMeasureSpec(dip(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dip(1400), View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, recycler.measuredWidth, recycler.measuredHeight)
        return Rendered(list, items, recycler)
    }

    /**
     * 一行的身份。
     *
     * `id` 是各子类自己声明的（它们是互不相干的 data class），基类上没有 —— 所以这里统一取一次，
     * 免得"按 id 找行"的写法每处都要写一遍 `when` 类型。
     */
    private fun BaseSettingItem.rowId(): String? = when (this) {
        is SwitchItem -> id
        is HorizontalItem -> id
        is SeekBarItem -> id
        is VerticalItem -> id
        else -> null
    }

    private fun positionOf(items: List<BaseSettingItem>, id: String): Int {
        // 按 id 找，不限定类型：同一个 id 在不同版本里可能是开关、也可能是"值 + 箭头"那一类行
        // （例如「后台运行不受限制」），限定类型的写法会在换类型时变成"列表里找不到这一行"。
        val position = items.indexOfFirst { it.rowId() == id }
        assertTrue("列表里找不到 $id 这一行", position >= 0)
        return position
    }

    /**
     * [view] 相对祖先 [ancestor] 的左边距。
     *
     * 直接用 `view.left` 只到**父容器**那一层（provider 常把标题包在自己的列里），
     * 逐层累加到行这一层，量到的才是"离卡片左边多远"。
     */
    private fun leftWithin(view: View, ancestor: View): Int {
        var x = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            x += current.left
            current = current.parent as? View
        }
        return x
    }

    private fun seekBarPositionOf(items: List<BaseSettingItem>, id: String): Int {
        val position = items.indexOfFirst {
            it is SeekBarItem && it.id == id
        }
        assertTrue("列表里找不到 $id 这一行", position >= 0)
        return position
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 卡片缩进与内容留白各归各位() {
        val rendered = render()
        val items = rendered.items
        // 逐行验，不是抽一行验：一整列里只有一行对，等于没对。
        val rows = items.indices.filter { items[it] !is CategoryItem }
        assertTrue("列表里没有任何内容行，测试本身失去意义", rows.isNotEmpty())
        var titled = 0
        var switches = 0
        for (position in rows) {
            val row = rendered.rowAt(position)
            val pageInset = px(R.dimen.page_inset)
            val rowInset = px(R.dimen.setting_row_inset)
            assertEquals("第 $position 行的卡片没有左右各缩进 page_inset",
                    pageInset, rendered.cardInsetAt(position))

            // 内容再往卡片里缩 16dp：**这一条是用户指着真机说"贴着边框"的那一处**。
            // 行根的内边距必须等于 setting_row_inset（不是 cardInset，也不是两者相加 ——
            // 卡片的缩进已经在 margin 上了，不再占内边距）。
            assertEquals("第 $position 行的内容没有缩到卡片里侧 16dp", rowInset, row.paddingLeft)
            assertEquals("第 $position 行的内容右侧没有缩到卡片里侧 16dp", rowInset, row.paddingRight)

            // 标题相对**卡片左边** 16dp（行的 left 就是卡片左沿，卡片缩进在 margin 上）。
            // 这一条就是用户指着真机说"文字贴着边框"的那一处。
            // 标题为空的说明行（列表末尾那一段）标题是 GONE 的，没量过就没有 left，
            // 这种行只看内边距那两条断言。
            val title = row.findViewById<View>(R.id.anko_text_view)
            if (title != null && title.visibility == View.VISIBLE) {
                assertEquals("第 $position 行的标题没有落在卡片里侧 16dp",
                        rowInset, leftWithin(title, row))
                titled++
            }

            val switch = rendered.switchAt(position)
            if (switch != null) {
                // 开关右沿离卡片右沿 16dp（HyperOS 的设置页就是这个内缩：控件不贴卡片边）。
                assertEquals("第 $position 行：开关右沿距卡片右沿不是 16dp",
                        rowInset, row.width - (switch.left + switch.width))
                switches++
            }
        }
        assertTrue("一条带标题的行都没量到，测试本身失去意义", titled > 0)
        assertTrue("一枚开关都没量到，测试本身失去意义", switches > 0)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 单行56dp带解释的行64dp() {
        val rendered = render()
        val items = rendered.items
        var single = 0
        var twoLine = 0
        for (position in items.indices) {
            val item = items[position]
            if (item is CategoryItem) continue
            val row = rendered.rowAt(position)
            when (item) {
                is SwitchItem -> {
                    if (item.desc.isEmpty()) {
                        assertTrue("单行开关行高不足 56dp：${row.height / density}dp",
                                row.height >= px(R.dimen.setting_row_min_height))
                        single++
                    } else {
                        assertTrue("带解释的开关行高不足 64dp：${row.height / density}dp",
                                row.height >= px(R.dimen.setting_row_two_line_height))
                        twoLine++
                    }
                }
                is HorizontalItem -> {
                    val wanted = if (item.desc.isEmpty()) R.dimen.setting_row_min_height
                    else R.dimen.setting_row_two_line_height
                    assertTrue("第 $position 行（${item.title}）行高不足：${row.height / density}dp",
                            row.height >= px(wanted))
                    if (item.desc.isEmpty()) single++ else twoLine++
                }
                else -> assertTrue("第 $position 行高不足 56dp：${row.height / density}dp",
                        row.height >= px(R.dimen.setting_row_min_height))
            }
        }
        assertTrue("列表里没有单行行，覆盖不到 56dp 那条契约", single > 0)
        assertTrue("列表里没有带解释的行，覆盖不到 64dp 那条契约", twoLine > 0)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 开关是46乘28的胶囊() {
        val rendered = render()
        val items = rendered.items
        var checked = 0
        for (position in items.indices) {
            val switch = rendered.switchAt(position) ?: continue
            assertEquals("开关宽度不是 46dp", px(R.dimen.switch_width), switch.width)
            assertEquals("开关高度不是 28dp", px(R.dimen.switch_height), switch.height)
            // 圆点直径由 SwitchView 读 `switch_thumb` 一处决定，画出来的实际直径在
            // 「开关真的画出来了」里量（读 token 只能证明 token 没改，量像素才证明画对了）。
            if (switch.isChecked) checked++
        }
        assertTrue("一个打开的开关都没有，验不到「开」的样子", checked > 0)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 组内不画分隔线圆角只在组首组尾() {
        val rendered = render()
        val items = rendered.items
        val radius = px(R.dimen.group_radius).toFloat()
        var firstChecked = 0
        var lastChecked = 0
        var middleChecked = 0
        for (position in items.indices) {
            val item = items[position]
            if (item is CategoryItem) continue
            val raw = rendered.cardCornersAt(position)
            assertNotNull("第 $position 行的背景不是圆角矩形，卡片没了", raw)
            val corners = raw!!
            // GradientDrawable 的顺序是 左上、右上、右下、左下（每角 x/y 各一）。
            val topLeft = corners[0]
            val topRight = corners[2]
            val bottomRight = corners[4]
            val bottomLeft = corners[6]
            when (item.rowPosition) {
                RowCardPosition.FIRST -> {
                    assertEquals("组首的上两角应该是 20dp 圆角", radius, topLeft, 0.5f)
                    assertEquals("组首的上两角应该是 20dp 圆角", radius, topRight, 0.5f)
                    assertEquals("组首的下两角必须是直角（组内不画线，靠圆角拼成一张卡）", 0f, bottomLeft, 0.5f)
                    assertEquals("组首的下两角必须是直角（组内不画线，靠圆角拼成一张卡）", 0f, bottomRight, 0.5f)
                    firstChecked++
                }
                RowCardPosition.MIDDLE -> {
                    for (corner in listOf(topLeft, topRight, bottomRight, bottomLeft)) {
                        assertEquals("中间行四角都必须是直角", 0f, corner, 0.5f)
                    }
                    middleChecked++
                }
                RowCardPosition.LAST -> {
                    assertEquals("组尾的下两角应该是 20dp 圆角", radius, bottomRight, 0.5f)
                    assertEquals("组尾的下两角应该是 20dp 圆角", radius, bottomLeft, 0.5f)
                    assertEquals("组尾的上两角必须是直角", 0f, topLeft, 0.5f)
                    assertEquals("组尾的上两角必须是直角", 0f, topRight, 0.5f)
                    lastChecked++
                }
                RowCardPosition.SINGLE -> {
                    for (corner in listOf(topLeft, topRight, bottomRight, bottomLeft)) {
                        assertEquals("独立一行的四角都应该是 20dp 圆角", radius, corner, 0.5f)
                    }
                }
            }
        }
        // 三条分支都要被走到：只验组首等于没验"中间行没有圆角"。
        assertTrue("没有组首行", firstChecked > 0)
        assertTrue("没有组中间行", middleChecked > 0)
        assertTrue("没有组尾行", lastChecked > 0)
    }

    /**
     * 设计说明那条行为：关掉的开关，它下面依赖它的行变灰且不可点。
     *
     * 三种依赖行的处置不同，都要在这里钉住：总闸控制的行跟着灰；
     * 「后台运行不受限制」是前提，**刻意不灰**（理由见 [SettingsList.refreshAvailability]）；
     * 两个提前量是课前偏好，也不灰。
     */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 总闸关闭时依赖行变灰不可点而前提行仍可用() {
        context.getPrefer().edit().putBoolean(Const.KEY_COURSE_REMIND, true).commit()
        val on = render()
        for (id in listOf(SettingRowId.REMINDER_START, SettingRowId.REMINDER_END,
                SettingRowId.REMINDER_MERGE, SettingRowId.REMINDER_ON_GOING)) {
            val position = positionOf(on.items, id)
            assertTrue("总闸开着时 $id 应该可用", on.items[position].isRowEnabled)
            assertEquals("总闸开着时 $id 不该变灰", 1f, on.rowAt(position).alpha, 0.01f)
        }
        context.getPrefer().edit().putBoolean(Const.KEY_COURSE_REMIND, false).commit()
        val off = render()
        for (id in listOf(SettingRowId.REMINDER_START, SettingRowId.REMINDER_END,
                SettingRowId.REMINDER_MERGE, SettingRowId.REMINDER_ON_GOING)) {
            val position = positionOf(off.items, id)
            assertEquals("总闸关掉后 $id 应该变成不可用", SettingRowState.DISABLED_BY_DEPENDENCY,
                    off.items[position].rowState)
            assertTrue("总闸关掉后 $id 应该变灰", off.rowAt(position).alpha < 1f)
        }

        // 「后台运行不受限制」是提醒能不能响的前提，用户可能想提前授权，所以不跟着灰。
        val battery = positionOf(off.items, SettingRowId.BATTERY_UNRESTRICTED)
        assertTrue("前提行不该跟着总闸变灰", off.items[battery].isRowEnabled)
        assertEquals(1f, off.rowAt(battery).alpha, 0.01f)

        // 提前量是课前偏好，也不灰。
        for (id in listOf(SettingRowId.REMINDER_BEFORE_START, SettingRowId.REMINDER_BEFORE_END)) {
            val position = seekBarPositionOf(off.items, id)
            assertTrue("提前量不该跟着总闸变灰", off.items[position].isRowEnabled)
        }
    }

    /**
     * 不可点不是"看起来灰"：分发处那条 `if (!item.isRowEnabled) return` 必须真的挡住。
     *
     * 这里不启动 Activity（那需要 Room 与整个 App），而是把**同一份列表**交给
     * [RowCardDecoration] 重算一遍状态，直接验"点了会不会改到数据"。
     */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 总闸关闭时点依赖行不会改到数据() {
        context.getPrefer().edit()
                .putBoolean(Const.KEY_COURSE_REMIND, false)
                .putBoolean(CourseReminderScheduler.KEY_REMINDER_START_ENABLED, true)
                .commit()
        val rendered = render()
        val items = rendered.items
        val start = items.first { it is SwitchItem && it.id == SettingRowId.REMINDER_START } as SwitchItem
        assertFalse("总闸关着时子开关不该可用", start.isRowEnabled)

        // 与 SettingsActivity 的分发同一套判据：不可点的行直接不进入点击处理。
        val before = start.checked
        if (start.isRowEnabled) start.checked = !start.checked
        assertEquals("不可点的行被点动了", before, start.checked)

        // 总闸打开后同一行必须恢复可点。
        context.getPrefer().edit().putBoolean(Const.KEY_COURSE_REMIND, true).commit()
        rendered.list.refreshAvailability()
        assertTrue("总闸打开后子开关应该恢复可用", start.isRowEnabled)
        if (start.isRowEnabled) start.checked = !start.checked
        assertEquals("可点的行应该被点动了", !before, start.checked)
    }

    /**
     * 同一个 ViewHolder 反复绑定（含 RecyclerView 预取）不许崩、也不许往子视图里长东西。
     *
     * 这是一条**回归测试**，复现的是真机上抓到的那次崩溃：
     * `HorizontalItemProvider.convert()` 里曾经 `removeViewAt` + `addView` 换箭头视图，
     * 而 `ViewGroup.removeViewInternal` 会对容器的焦点簿记 `mFocused` 调 `unFocus` ——
     * RecyclerView 预取（GapWorker）绑定时它可能还没有值，于是空指针。
     *
     * 根因是"绑定阶段增删子视图"这件事本身，所以这里钉的不是"某一行不崩"，而是三条不变式：
     * 1. 连续绑定不抛异常（预取导致同一条数据被反复绑定是常态）；
     * 2. 子视图数量不随绑定次数增长（涨了就是每绑一次新建一个 View）；
     * 3. 换箭头种类之后还是**同一个**箭头 View（换种类只改属性、重画一次）。
     * 这三条同时成立，才说明"换形态靠属性"而不是"换形态靠换视图"。
     */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 同一个ViewHolder反复绑定不增删子视图也不崩() {
        val adapter = SettingItemAdapter()
        // 几行刻意挑成"同一 provider、形态来回变"：带解释/不带解释、换箭头种类、空标题。
        // 分类行也放进来交替绑定：组标题的 provider 同样不能在绑定期增删子视图。
        val variants = mutableListOf<BaseSettingItem>(
                HorizontalItem("probe", "进新页面", "值一", desc = "带解释", arrow = SettingRowArrow.NAVIGATE),
                CategoryItem("分组一"),
                HorizontalItem("probe", "就地选择", "值二", arrow = SettingRowArrow.SELECT),
                HorizontalItem("probe", "纯展示", "值三", arrow = null),
                CategoryItem("分组二"),
                SwitchItem("probe", "开关一", true, "带解释"),
                SwitchItem("probe", "开关二", false),
                VerticalItem("probe", "长说明", "一段说明"),
                VerticalItem("probe", "", ""),
                SeekBarItem("probe", "数值", 10, 0, 90, "分钟"))
        adapter.data = variants

        val parent = android.widget.FrameLayout(context)
        val holders = HashMap<Int, com.chad.library.adapter.base.viewholder.BaseViewHolder>()
        val childCounts = HashMap<Int, Int>()
        val arrowViews = HashMap<Int, Any>()
        var binds = 0
        // 绑三轮：第一轮建 ViewHolder，后两轮全是在复用它们（预取就是这个形态）。
        repeat(3) {
            for (position in variants.indices) {
                val viewType = adapter.getItemViewType(position)
                // 走 Adapter 公开的 createViewHolder：它内部按 viewType 找 provider，
                // 与 RecyclerView 真正复用时是同一条路径。
                val holder = holders.getOrPut(viewType) {
                    adapter.createViewHolder(parent, viewType) as
                            com.chad.library.adapter.base.viewholder.BaseViewHolder
                }
                // 不该抛异常：抛了就是又把"绑定期增删子视图"写回来了。
                adapter.onBindViewHolder(holder, position)
                binds++
                val count = (holder.itemView as android.view.ViewGroup).childCount
                val previous = childCounts.put(viewType, count)
                if (previous != null) {
                    assertEquals("viewType=$viewType 的行每绑定一次就多/少一个子视图", previous, count)
                }
                val arrow = holder.itemView.findViewById<ArrowView>(R.id.anko_iv_arrow)
                if (arrow != null) {
                    val previousArrow = arrowViews.put(viewType, arrow)
                    if (previousArrow != null) {
                        assertSame("换箭头种类不该换视图（那正是真机崩溃的原因）", previousArrow, arrow)
                    }
                }
            }
        }
        assertTrue("没有发生任何绑定，测试本身失去意义", binds >= variants.size * 3)
        assertTrue("箭头的复用没有被验到，测试本身失去意义", arrowViews.isNotEmpty())
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 开关真的画出来了() {
        // 这条盯的不是几何（尺寸/位置另有断言），而是"控件存在却一个像素都不落"这类问题：
        // 自绘的开关只要画法或状态接错（例如把位置算到视图外、或颜色取成透明），
        // 尺寸仍然可以是 46×28、位置仍然对，用户在设置页却什么都看不到。
        val rendered = render()
        val position = positionOf(rendered.items, SettingRowId.SCHEDULE_DETAIL_TIME)
        val row = rendered.rowAt(position)
        val sw = rendered.switchAt(position)!!
        val bmp = android.graphics.Bitmap.createBitmap(row.width, row.height,
                android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(0xFFFFFFFF.toInt())
        row.draw(canvas)
        var painted = 0
        var brandPixels = 0
        val brand = 0xFF3482FF.toInt()
        for (x in sw.left until minOf(sw.left + sw.width, bmp.width)) {
            for (y in sw.top until minOf(sw.top + sw.height, bmp.height)) {
                val c = bmp.getPixel(x, y)
                if (c != 0xFFFFFFFF.toInt()) painted++
                if (Math.abs(android.graphics.Color.red(c) - android.graphics.Color.red(brand)) < 24 &&
                        Math.abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(brand)) < 24) {
                    brandPixels++
                }
            }
        }
        assertTrue("开关一块里一个像素都没画出来（几何对但不可见）", painted > 0)
        assertTrue("开关画出来了但不是品牌色（轨道色没跟上'开'的状态）", brandPixels > 0)

        // 左端的圆角必须真的在：整块内容被左移再被视图边界裁掉时，左上角会变成直角实心块 ——
        // 真机上用户看到的就是"开关的左侧被截断"（SwitchCompat 时代实测命中过）。
        assertEquals("轨道左上角不是圆角：开关左端被裁掉了",
                0xFFFFFFFF.toInt(), bmp.getPixel(sw.left + 1, sw.top + 1))

        // 圆点直径：在开关的水平中线上量那一截连续的白。
        // 圆点直径 24dp、轨道高 28dp，所以中点这一条线上量到的就是圆的直径。
        val midY = sw.top + sw.height / 2
        var runStart = -1
        var runEnd = -1
        for (x in sw.left until sw.left + sw.width) {
            val isThumb = bmp.getPixel(x, midY) == 0xFFFFFFFF.toInt()
            if (isThumb) {
                if (runStart < 0) runStart = x - sw.left
                runEnd = x - sw.left
            } else if (runStart >= 0) {
                break
            }
        }
        val measuredThumb = runEnd - runStart + 1
        val wantedThumb = px(R.dimen.switch_thumb)
        // 允许 2px：圆的边缘是抗锯齿的，"纯白像素"比几何直径少一圈（每一侧约 1px）。
        assertTrue("圆点直径不是 24dp：量到 ${measuredThumb}px，token 是 ${wantedThumb}px",
                Math.abs(measuredThumb - wantedThumb) <= 2)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 出图_设置页浅色() = renderToPng("settings_light.png")

    @Test
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun 出图_设置页深色() = renderToPng("settings_dark.png")

    /**
     * 整页画成 PNG。
     *
     * 深色那份用 `zh-rCN-night-xxhdpi` 限定符取深色资源，所以 Context 必须在这一份
     * 限定符下重新取 —— 用类字段里那个浅色 Context 渲染，出来的图永远不可能是深色的。
     */
    private fun renderToPng(outName: String) {
        val nightContext: Context = ApplicationProvider.getApplicationContext()
        val list = SettingsList(nightContext)
        val items = list.build()
        val recycler = RecyclerView(nightContext)
        recycler.layoutManager = LinearLayoutManager(nightContext)
        recycler.adapter = SettingItemAdapter().apply { data = items }
        val width = dip(360)
        val height = dip(900)
        recycler.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, recycler.measuredWidth, recycler.measuredHeight)
        recycler.setBackgroundColor(nightContext.getColor(R.color.page_background))

        val bitmap = Bitmap.createBitmap(recycler.width, recycler.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        recycler.draw(canvas)
        val file = File("../../_crop/$outName")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("出图失败：$outName 没写出来", file.length() > 0)
    }

    /** 保住"渲染必须在主线程"这条前提：RecyclerView 的绑定会在别的线程上报错。 */
    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 测试本身跑在主线程上() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
    }
}
