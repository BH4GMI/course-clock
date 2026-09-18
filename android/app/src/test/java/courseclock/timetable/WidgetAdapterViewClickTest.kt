package courseclock.timetable

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 集合型 RemoteViews 的硬约束：**不许对 AdapterView 调 setOnClickPendingIntent。**
 *
 * RemoteViews$SetOnClickResponse.apply() 内部会调 `AdapterView.setOnClickListener`，那里直接抛
 * `RuntimeException("Don't call setOnClickListener for an AdapterView. You probably want
 * setOnItemClickListener instead")`。要命的是这个异常抛在**宿主**（桌面）的 RemoteViews.apply 阶段：
 *
 * ```
 * W AppWidgetHostView: Error inflating RemoteViews
 * W AppWidgetHostView: java.lang.RuntimeException: Don't call setOnClickListener for an AdapterView...
 *     at android.widget.AdapterView.setOnClickListener(AdapterView.java:820)
 *     at android.widget.RemoteViews$SetOnClickResponse.apply(RemoteViews.java:1952)
 * E Launcher.Widget: widget load error: ...TodayCourseAppWidget
 * ```
 *
 * provider 侧什么都看不到（没有 ActionException），桌面上的表现永远是「载入窗口小部件时出现问题」，
 * 而且每次刷新都会重新抛一遍 —— 只靠日志很难定位（就是这次踩的坑）。
 *
 * 正确做法是集合型的原生机制：容器上 `setPendingIntentTemplate`，列表项根布局上
 * `setOnClickFillInIntent`。这条测试直接按 layout 里的实际控件类型来判，不写死控件 id。
 */
class WidgetAdapterViewClickTest {

    private val moduleDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return@lazy dir
            dir = dir.parentFile
        }
        throw AssertionError("从 ${File("").absolutePath} 向上找不到模块目录（src/main/AndroidManifest.xml）")
    }

    /** layout 里所有属于 AdapterView 家族的控件 id（简单名匹配，够用且不依赖 SDK 反射）。 */
    private fun adapterViewIds(): Map<String, String> {
        val adapterSimpleNames = listOf(
                "ListView", "GridView", "Spinner", "Gallery", "AbsListView", "ExpandableListView",
                "AdapterViewFlipper", "AdapterViewAnimator", "StackView"
        )
        val result = LinkedHashMap<String, String>()
        val tagRegex = Regex("<([A-Za-z_][\\w.]*)\\b([^>]*)>")
        val idRegex = Regex("android:id=\"@\\+id/(\\w+)\"")
        File(moduleDir, "src/main/res/layout").listFiles { f -> f.extension == "xml" }?.forEach { file ->
            val text = file.readText()
            tagRegex.findAll(text).forEach { m ->
                val simpleName = m.groupValues[1].substringAfterLast('.')
                if (adapterSimpleNames.any { it.equals(simpleName, ignoreCase = true) }) {
                    val id = idRegex.find(m.groupValues[2])?.groupValues?.get(1) ?: return@forEach
                    result[id] = "${file.name} 的 <$simpleName>"
                }
            }
        }
        return result
    }

    @Test
    fun `AdapterView 上没有 setOnClickPendingIntent`() {
        val adapterIds = adapterViewIds()
        assertTrue("一个 AdapterView 都没扫到，说明扫描逻辑失效了，这条测试等于没跑", adapterIds.isNotEmpty())

        val offenders = mutableListOf<String>()
        File(moduleDir, "src/main/java").walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        adapterIds.forEach { (id, where) ->
                            if (line.contains("setOnClickPendingIntent(R.id.$id")) {
                                offenders += "${file.name}:${index + 1} 对 $where（id=$id）设了点击 PendingIntent"
                            }
                        }
                    }
                }
        if (offenders.isNotEmpty()) {
            fail("AdapterView 的点击必须用 setPendingIntentTemplate + setOnClickFillInIntent，" +
                    "否则宿主 apply RemoteViews 时会抛异常、桌面显示「载入窗口小部件时出现问题」：\n" +
                    offenders.joinToString("\n"))
        }
    }

    /**
     * 反过来的一半：日视图的列表上**不许挂任何点击入口**。
     *
     * 上一版这条测试是反向的 —— 它要求列表必须挂 `setPendingIntentTemplate` +
     * `setOnClickFillInIntent`。那套写法本身没错（集合型 RemoteViews 的原生机制，也躲开了上面那个
     * `AdapterView.setOnClickListener` 崩溃），但它有一个当时没看到的代价：**它让整个小部件变成了
     * 可点的**。用户在「添加小部件」选择器里点一下，选择器渲染的是一份真实的 RemoteViews，
     * 点击被小部件自己的 PendingIntent 吃掉 —— 结果是打开 App，而不是把这个小部件放上去。
     *
     * 而我们没有任何办法区分"点击来自桌面"还是"点击来自选择器"：RemoteViews 不知道自己在哪。
     * 取舍见 `AppWidgetUtils.refreshTodayWidget` 的注释：添加路径是硬需求，点击进 App 只是顺手的
     * 便利（桌面图标、抽屉入口都能进）。所以整个小部件改成纯展示。
     *
     * 断言写成"这两个文件里不许出现这些调用"，并且先钉住扫描目标没跑偏（文件里必须还有
     * `setRemoteAdapter(R.id.lv_course`）—— 否则文件被改名/搬走之后，这条"不许出现"会变成
     * 永远为真的空断言。
     */
    @Test
    fun `日视图列表不挂任何点击入口`() {
        val utilsFile = File(moduleDir, "src/main/java/courseclock/timetable/utils/AppWidgetUtils.kt")
        val utils = stripComments(utilsFile.readText())
        assertTrue("扫描目标跑偏了：${utilsFile.name} 里找不到日视图的 setRemoteAdapter(R.id.lv_course",
                utils.contains("setRemoteAdapter(R.id.lv_course"))

        for (forbidden in listOf(
                "setPendingIntentTemplate(R.id.lv_course",
                "setOnClickPendingIntent(R.id.tv_date",
                "setOnClickPendingIntent(R.id.lv_schedule")) {
            assertTrue("日视图/周视图不该挂点击入口：$forbidden（会在选择器里被吃掉，导致点一下不进添加流程）",
                    !utils.contains(forbidden))
        }

        val serviceFile = File(moduleDir,
                "src/main/java/courseclock/timetable/today_appwidget/TodayColorfulService.kt")
        val service = stripComments(serviceFile.readText())
        assertTrue("扫描目标跑偏了：${serviceFile.name} 里找不到 item_schedule_widget",
                service.contains("R.layout.item_schedule_widget"))
        assertTrue("没有模板可填时不该留 setOnClickFillInIntent（死动作）",
                !service.contains("setOnClickFillInIntent"))
    }

    /**
     * 剥掉行注释与块注释再比对。
     *
     * 必须剥：生产代码的注释里**故意**写着这些反面写法（"以前这里挂了两处 setOnClickPendingIntent…"），
     * 不剥就会拿说明文字把自己绊倒 —— 同一个坑 `ReminderTimelinessGuardTest` 已经踩过一次，
     * 那里也是先剥注释再比对。
     */
    private fun stripComments(source: String): String =
            source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
                    .replace(Regex("//[^\n]*"), "")
}
