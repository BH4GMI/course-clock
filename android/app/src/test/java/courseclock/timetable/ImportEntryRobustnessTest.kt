package courseclock.timetable

import android.content.Intent
import android.net.http.SslCertificate
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.schedule_import.LoginWebActivity
import courseclock.timetable.schedule_import.WebViewLoginFragment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow

/**
 * 导入**入口**的健壮性：两处都曾经是"回调/参数来得不是时候就崩"。
 *
 * 1. [WebViewLoginFragment] 的门户探测是**自续**的（每 600ms 一发、最多 15 发，约 9 秒窗口），
 *    而 `View.postDelayed` 投出去的消息**不会**因为 `onDestroyView` 而消失 —— Robolectric 实测：
 *    视图从窗口上摘掉之后 runnable 照跑。于是"登录成功后看一眼就走"会让那一发落在已经销毁的
 *    视图上，`_binding!!` 直接抛 `NullPointerException`。
 * 2. [LoginWebActivity] 因为要接外部「用其他 App 打开 .wakeup_schedule」而带 intent-filter
 *    （即导出），extras 由调用方说了算。以前是 `getStringExtra("url")!!`：缺 url 就崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImportEntryRobustnessTest {

    @Test
    fun 登录证书无效时取消连接且不给出绕过入口() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), LoginWebActivity::class.java)
                .putExtra("import_type", "sues").putExtra("url", "https://vpn.example/")
        val controller = Robolectric.buildActivity(LoginWebActivity::class.java, intent).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.wv_course)
        val handler = Shadow.newInstanceOf(SslErrorHandler::class.java)
        val error = SslError(SslError.SSL_UNTRUSTED,
                Shadow.newInstanceOf(SslCertificate::class.java), "https://vpn.example/")
        shadowOf(webView).webViewClient.onReceivedSslError(webView, handler, error)
        assertTrue(shadowOf(handler).wasCancelCalled())
        assertFalse(shadowOf(handler).wasProceedCalled())
        assertTrue(activity.findViewById<TextView>(R.id.tv_auto_hint).text.contains("证书验证失败"))
        controller.pause().stop().destroy()
    }

    @Test
    fun 视图销毁之后探测入口不再触碰视图() {
        // 没走 onCreateView，_binding 为 null —— 与 onDestroyView 之后的状态一致。
        val fragment = WebViewLoginFragment()
        fragment.suesProbePage("https://vpn.example/portal") // 修复前：NullPointerException
    }

    @Test
    fun 缺url的导入intent不崩而是结束自己() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), LoginWebActivity::class.java)
                .putExtra("import_type", "sues")
        val controller = Robolectric.buildActivity(LoginWebActivity::class.java, intent).setup()

        assertTrue("参数不完整的导入应当结束自己，而不是留一个空白页或崩掉",
                controller.get().isFinishing)
    }

    @Test
    fun 带url的导入intent照常进入webview页() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), LoginWebActivity::class.java)
                .putExtra("import_type", "sues")
                .putExtra("url", "https://vpn.example/")
        val controller = Robolectric.buildActivity(LoginWebActivity::class.java, intent).setup()

        assertFalse("正常入口不应该结束", controller.get().isFinishing)
        assertTrue("应当挂上 WebView 登录页",
                controller.get().supportFragmentManager.fragments.any { it is WebViewLoginFragment })
    }
}
