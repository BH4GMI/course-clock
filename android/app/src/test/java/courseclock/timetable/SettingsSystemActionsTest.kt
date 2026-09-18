package courseclock.timetable

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.settings.PressHighlightCard
import courseclock.timetable.settings.SettingItemAdapter
import courseclock.timetable.settings.RowCardPosition
import courseclock.timetable.settings.SettingRowId
import courseclock.timetable.settings.SettingsActivity
import courseclock.timetable.settings.SettingsList
import courseclock.timetable.settings.items.HorizontalItem
import courseclock.timetable.utils.BatteryOptimization
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.getPrefer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsSystemActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun register(intent: Intent, pkg: String, name: String) {
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                this.name = name
                exported = true
            }
        })
    }

    private fun click(activity: SettingsActivity, id: String) {
        val recycler = activity.rootView.getChildAt(0) as RecyclerView
        val adapter = recycler.adapter as SettingItemAdapter
        val position = adapter.data.indexOfFirst { it is HorizontalItem && it.id == id }
        recycler.itemAnimator = null
        recycler.scrollToPosition(position)
        shadowOf(Looper.getMainLooper()).idle()
        recycler.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, 1080, 2400)
        assertTrue(recycler.findViewHolderForAdapterPosition(position)!!.itemView.performClick())
    }

    @Test
    fun batterySetupKeepsHyperOsConfirmationSeparateFromActualAospExemption() {
        context.getPrefer().edit().clear().commit()
        register(BatteryOptimization.miuiIntent(context), "com.miui.securitycenter", "VendorBattery")
        register(BatteryOptimization.aospIntent(context), "com.android.settings", "AospBattery")
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        click(activity, SettingRowId.BATTERY_UNRESTRICTED)
        assertEquals("com.miui.securitycenter", shadowOf(activity).nextStartedActivityForResult.intent.`package`)
        controller.pause().resume()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val list = SettingsList(activity)
        // 行名固定，状态由值承担：厂商那一步确认过了，但系统的电池优化还没放行。
        // 行名要从**真的建出来的列表**里取（batteryItem 是 build() 填的，光 new 一个 SettingsList 还是 null）。
        val batteryRow = list.build().first {
            it is HorizontalItem && it.id == SettingRowId.BATTERY_UNRESTRICTED
        } as HorizontalItem
        assertEquals("后台运行不受限制", batteryRow.title)
        assertEquals("未设置", list.batteryStateText())
        assertFalse(list.batteryGranted())
        click(activity, SettingRowId.BATTERY_UNRESTRICTED)
        assertEquals("com.android.settings", shadowOf(activity).nextStartedActivityForResult.intent.`package`)
        context.getPrefer().edit().putBoolean(Const.KEY_BATTERY_WHITELIST_CONFIRMED, true).commit()
        assertFalse("旧结果码记录不能冒充白名单", list.batteryGranted())
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(list.batteryGranted())
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse("系统撤销后必须重新显示未加入", list.batteryGranted())
    }

    /**
     * 回前台刷新「后台运行不受限制」那一行之后，它的**卡片位置不能变**。
     *
     * 这是用户指着真机问"那一栏的灰框为什么有圆角"的回归测试。根因：`rowPosition`（决定哪两个角
     * 是圆角）是 [courseclock.timetable.settings.RowCardDecoration] 标注在 item 上的**派生**状态，
     * 不在 data class 的构造参数里；`SettingsList.refreshBatteryItem()` 曾经用 `copy(value = …)`
     * 换掉这一行，派生状态就掉回默认的 `SINGLE`（四角全圆角），再由 `refreshBatteryRow()` 把那个
     * 新对象塞回列表 —— 这一行于是从"「上课提醒」组里的中间行"变成一张独立卡片。
     * `SettingsActivity.onResume` 每次都走这条路，所以真机上几乎总是错的那一版。
     *
     * 所以必须在**真的 Activity** 上验：`setup()` 本身就会走一遍 create→start→resume，
     * 也就是生产代码里那次刷新。任何"刚 build 完就出图"的渲染都复现不出来 —— 上一版这条用例
     * 就是因为只验了 build 出来的那份列表，`copy()` 的 bug 它抓不到。
     */
    @Test
    fun 回前台刷新后台运行那一行之后卡片位置不变() {
        context.getPrefer().edit().clear().commit()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        val recycler = activity.rootView.getChildAt(0) as RecyclerView
        val adapter = recycler.adapter as SettingItemAdapter
        val position = adapter.data.indexOfFirst {
            it is HorizontalItem && it.id == SettingRowId.BATTERY_UNRESTRICTED
        }
        assertTrue("列表里找不到「后台运行不受限制」那一行", position >= 0)
        assertEquals("onResume 刷新之后这一行丢掉了组内位置：它会变成四角圆角的独立卡片",
                RowCardPosition.MIDDLE, adapter.data[position].rowPosition)

        // 真的把它绑到视图上再量圆角：item 上的位置最终是通过背景 drawable 落到屏幕上的。
        recycler.itemAnimator = null
        recycler.scrollToPosition(position)
        shadowOf(Looper.getMainLooper()).idle()
        recycler.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, 1080, 2400)
        val row = recycler.findViewHolderForAdapterPosition(position)!!.itemView
        val corners = cardCornerRadii(row.background)
        assertEquals("组中间行的上左角必须是直角（组内不画线，靠圆角拼成一张卡）", 0f, corners[0], 0.5f)
        assertEquals("组中间行的下左角必须是直角 —— 变圆就是变成了独立卡片", 0f, corners[6], 0.5f)
    }

    /**
     * 一层层剥到卡片那层 drawable，取它的圆角。
     *
     * 可点的行是 [PressHighlightCard]（整块卡片换色的按下高亮，取代了早先的
     * `LayerDrawable(卡片, 水波纹)`），不可点的行是裸 `GradientDrawable`。两者都持有同一份
     * `cornerRadii`，所以这里两种都接。
     */
    private fun cardCornerRadii(background: Drawable?): FloatArray {
        var current = background
        while (current != null) {
            when (current) {
                is GradientDrawable -> return current.cornerRadii!!
                is PressHighlightCard -> return current.cornerRadii
                is LayerDrawable -> current = current.getDrawable(0)
                is InsetDrawable -> current = current.drawable
                else -> break
            }
        }
        throw AssertionError("这一行的背景不是圆角矩形，卡片没了")
    }

    /**
     * 设置页里**不再有**任何"在应用内添加小部件"的入口。
     *
     * 这个功能被放弃了：各厂商桌面要么不支持 `requestPinAppWidget`，要么（HyperOS 实测）
     * 接受请求却既不弹确认框也不落实例；剩下的路都是"把用户丢进第三方列表里自己找"。
     * 小部件改为只从桌面的小部件列表添加，所以设置页不该再留这一行 —— 留着就是死按钮。
     */
    @Test
    fun settingsHasNoInAppAddWidgetEntry() {
        val titles = SettingsList(context).build().mapNotNull { (it as? HorizontalItem)?.title }
        assertFalse("设置页不该再出现「添加桌面小部件」这一行",
                titles.any { it.contains("小部件") })
    }

    @Test
    fun otherLaunchersDoNotNeedTheVendorBatteryStep() {
        register(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "other.launcher", "Launcher")
        assertFalse(BatteryOptimization.needsMiuiStep(context))
        // 没有厂商那一步的机器，行名同样是固定的「后台运行不受限制」，未放行时值就是「未设置」。
        assertEquals("未设置", SettingsList(context).batteryStateText())
    }
}
