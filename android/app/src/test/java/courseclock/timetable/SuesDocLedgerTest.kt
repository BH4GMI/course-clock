package courseclock.timetable.schedule_import

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `onPageFinished` 的去重必须按**文档身份**，不能按地址。
 *
 * 现场证据（真机 2026-09-15）：统一身份认证页在提交账号密码前后，`onPageFinished` 拿到的地址
 * **逐字节相同**（173/173 字符相同，`performance.timeOrigin` 却是 22:23:32，晚于日志里最后一条
 * 22:23:17.396）——表单 `action` 是未被网关改写的主机名，POST 之后 302 回同一个 WebVPN 地址。
 * 旧实现 `if (url == suesLastUrl) return` 记下该地址之后，把登录之后那一份**新文档**的回调也
 * 一并吞掉，于是两件事都没执行：
 *
 * 1.「保密提示」复选框没有被勾选（用户看到的现象）；
 * 2. 密码过期页的「点击跳过」同样不会执行（同一个入口）。
 *
 * 勾选脚本本身没问题：现场在页面上手动执行那段脚本，4 秒后仍是勾选状态，容器零 DOM 变动。
 */
class SuesDocLedgerTest {

    @Test
    fun `同一份文档的重复回调只处理一次`() {
        val ledger = SuesDocLedger()
        ledger.onDocumentStarted()
        assertTrue("新文档的第一次回调要处理", ledger.claim())
        assertFalse("同一份文档的重复回调必须忽略", ledger.claim())
        assertFalse(ledger.claim())
    }

    @Test
    fun `同地址的新文档必须重新处理`() {
        val ledger = SuesDocLedger()
        ledger.onDocumentStarted()
        assertTrue("登录页第一次加载要处理", ledger.claim())

        // 提交账号密码之后：地址一模一样，但这是新的一份文档
        ledger.onDocumentStarted()
        assertTrue("同地址的新文档必须再处理一次，否则勾选和「点击跳过」都会被吞掉", ledger.claim())

        // 这一份新文档自己也会有重复回调
        assertFalse(ledger.claim())
    }

    @Test
    fun `处理过的文档不会因为回调变多而被再次认领`() {
        val ledger = SuesDocLedger()
        ledger.onDocumentStarted()
        ledger.onDocumentStarted()   // 两次 onPageStarted、只有一次 onPageFinished
        assertTrue(ledger.claim())
        assertFalse(ledger.claim())
        ledger.onDocumentStarted()
        assertTrue(ledger.claim())
    }
}
