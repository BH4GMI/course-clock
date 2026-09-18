package courseclock.timetable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TableBean
import courseclock.timetable.schedule.ScheduleUI
import courseclock.timetable.utils.ViewUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 课表视图**量得出来尺寸** —— 这条测试是为一次真实崩溃写的。
 *
 * ## 那次崩溃
 *
 * 虚线网格一开始被做成 `content` 的一个 `MATCH_PARENT × MATCH_PARENT` 子视图。`content` 由
 * ScrollView 以"高度不限"测量，这样一个子视图让 ConstraintLayout 的高度**塌成 0**：
 *
 * - 主课表：整张空白（用户看到的就是"还是没有"）；
 * - 周视图小部件：`ViewUtils.getViewBitmap` 把子视图高度加起来当位图高度，得到 0，
 *   `Bitmap.createBitmap(宽, 0)` 抛 `IllegalArgumentException: width and height must be > 0`，
 *   在 `AppWidgetManager` 的线程池里把进程反复打死（真机日志里连着崩了四次）。
 *
 * ## 为什么是"量尺寸"而不是"截图比对"
 *
 * 崩溃的判据就是尺寸为 0，判据本身就是这个断言，不需要像素级对比。而课表视图里
 * 权重的分配、隐藏周六周日带来的列数变化、留白区加不加……全都会影响测量结果，
 * 正好也是这条测试顺带守住的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScheduleUIMeasureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun table() = TableBean(
            id = 1,
            tableName = "量尺寸用例",
            nodes = 15,
            timeTable = 1,
            startDate = "2026-09-14",
            itemHeight = 56,
            widgetItemHeight = 56)

    /** 主课表：ScrollView 里的 content 必须有高度。 */
    @Test
    fun mainTimetableMeasuresToANonZeroSize() {
        val ui = ScheduleUI(context, table(), 1)
        ViewUtils.layoutView(ui.scrollView, 1220, 2656)
        assertSized(ui.content.measuredWidth, ui.content.measuredHeight, "主课表")
    }

    /**
     * 周视图小部件：`initData` 会 `getViewBitmap(scrollView)`，位图高度是**子视图高度之和**。
     * 子视图高度为 0 就是那次崩溃，所以这里连位图也真的生成一次。
     */
    @Test
    fun weekWidgetRendersANonEmptyBitmap() {
        val ui = ScheduleUI(context, table(), 1, forWidget = true)
        ViewUtils.layoutView(ui.scrollView, 1220, 2656)
        assertSized(ui.content.measuredWidth, ui.content.measuredHeight, "周视图小部件")

        // 这一步就是崩溃现场；量出 0 高时它会抛 IllegalArgumentException。
        val bitmap = ViewUtils.getViewBitmap(ui.scrollView)
        assertTrue("出图尺寸不该是 0：${bitmap.width}x${bitmap.height}",
                bitmap.width > 0 && bitmap.height > 0)
    }

    private fun assertSized(width: Int, height: Int, what: String) {
        assertTrue("$what 的内容宽度量成了 $width", width > 0)
        assertTrue("$what 的内容高度量成了 $height（高度塌成 0 会让课表整张空白、" +
                "小部件出图时抛 width and height must be > 0）", height > 0)
    }

    /**
     * 周视图小部件那张出图的**体积预算**。
     *
     * ## 为什么需要这条
     *
     * `ScheduleAppWidgetService.initData` 会把整张滚动视图画成一张位图交给 RemoteViews。
     * 位图高度是「子视图高度之和」（见 [ViewUtils.getViewBitmap]），所以它随
     * `TableBean.nodes` 与 `itemHeight` 线性长大 —— 而这两个值用户都能改
     * （自定义时间表可以加节次、行高可以拖）。这条测试把"正常配置下该多大"钉下来，
     * 一旦有人把默认行高或节次上限调大一个量级，这里会先炸，而不是让用户机器上
     * 每次刷小部件都分配几十 MB。
     *
     * ## 实测值（本机 Robolectric，2026-09-18）
     *
     * ```
     * nodes=15, 屏幕 1220×2656 → 位图 1220×870  = 4.05 MB
     * nodes=20, 屏幕 1220×2656 → 位图 1220×1160 = 5.40 MB
     * nodes=15, 屏幕 1080×2400 → 位图 1080×870  = 3.58 MB
     * ```
     *
     * 上限取 12 MB（实测的约 2.2 倍）：留出改行高、加节次的余地，但拦得住量级失控。
     *
     * ## 顺带澄清一件事
     *
     * 这张位图**不会**触发 binder 的 TransactionTooLargeException：`Bitmap.writeToParcel`
     * 对较大的位图走共享内存（ashmem）传 fd，不占事务缓冲区。所以这里守的是内存占用，
     * 不是"传不过去"。
     */
    @Test
    fun weekWidgetBitmapStaysWithinBudget() {
        for (nodes in listOf(15, 20)) {
            val ui = ScheduleUI(context, table().apply { this.nodes = nodes }, 1, forWidget = true)
            ViewUtils.layoutView(ui.scrollView, 1220, 2656)
            val bitmap = ViewUtils.getViewBitmap(ui.scrollView)
            val mb = bitmap.byteCount / 1048576.0
            assertTrue("nodes=$nodes 时周视图小部件出图 %.2f MB，超出 12 MB 预算".format(mb), mb < 12.0)
        }
    }
}
