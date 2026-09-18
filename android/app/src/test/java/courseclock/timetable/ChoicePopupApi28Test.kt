package courseclock.timetable

import android.graphics.drawable.InsetDrawable
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.settings.ChoicePopup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 钉住 `ChoicePopup.panelBackground` 在 API 28 上仍可构造。
 *
 * 背景：`GradientDrawable.setPadding(IIII)` 自 API 29 才有（`api-versions.xml` 记 since=29），
 * 本工程 `minSdk 21`。Android 5–9 上点「显示主题」/作息时间表那一行会 `NoSuchMethodError`。
 * 修法是改成 `InsetDrawable`（API 1）。本用例强制 `@Config(sdk = [28])`，一旦有人把
 * `setPadding` 写回去，这里会直接挂掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChoicePopupApi28Test {

    @Test
    @Config(qualifiers = "zh-rCN-xxhdpi")
    fun 面板背景在API28上是InsetDrawable且不碰API29方法() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bg = ChoicePopup.panelBackground(context)
        assertTrue(
                "panelBackground 应当是 InsetDrawable（不能用 GradientDrawable.setPadding，API 29+）",
                bg is InsetDrawable)
    }
}
