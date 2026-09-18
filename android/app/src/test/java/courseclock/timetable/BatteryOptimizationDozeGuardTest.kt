package courseclock.timetable

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.utils.BatteryOptimization
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 钉住 [BatteryOptimization.isExempt] 的**上界**：API 23 及以上必须真的去问系统。
 *
 * 背景：Doze 白名单是 API 23 才有的，`PowerManager#isIgnoringBatteryOptimizations` 在
 * API 21/22 上不存在（SDK 的 `api-versions.xml` 记 `since=23`），而本工程 `minSdk 21`。
 * 因此 [BatteryOptimization.isExempt] 里加了 `SDK_INT < 23 -> false` 的守卫。
 *
 * 这里守的是另一半：**守卫不能把新机器也一起挡掉**。若哪天条件写反或写宽，设置页
 * 「后台运行」那一行会在所有手机上永远显示「未设置」，用户点进去也永远说不清状态 ——
 * 那是比崩溃更隐蔽的错。所以断言"系统说豁免就是豁免、说不豁免就是不豁免"。
 *
 * 低于 23 的分支为什么没有单测：本工程 Robolectric 4.16 只提供 API 23→36 的 android-all，
 * `@Config(sdk = [21])` 会直接报 `API level 21 is not available`（实测）。那一条由 lint 的
 * `NewApi` 把关 —— 它的 baseline 条目已删除，去掉守卫会立刻变成 error（也已实测）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BatteryOptimizationDozeGuardTest {

    /** 让假系统"说"本 App 是否已被豁免电池优化。 */
    private fun systemSaysExempt(says: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, says)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 系统说已豁免时_isExempt_返回true() {
        systemSaysExempt(true)
        assertTrue(BatteryOptimization.isExempt(ApplicationProvider.getApplicationContext()))
    }

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 系统说没豁免时_isExempt_返回false() {
        systemSaysExempt(false)
        assertFalse(BatteryOptimization.isExempt(ApplicationProvider.getApplicationContext()))
    }
}
