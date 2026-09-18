package courseclock.timetable.schedule_import.sues

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 真实语料回归：BitmapFactory 解码 → 候选表必须与 Python 参考实现（sudy_captcha_lite.py，
 * 1.0 档）逐项相同。期望值取自验证工作区全语料对拍用的 reference_all.json（该文件曾由
 * 桌面 JVM 与 arm64 ART 双端逐例一致确认）。样本 000 是常规例；011 是历史上的「灾难性
 * 错锁」例（真值 319，1.0 档首选偏到 110 但真值仍在候选表内）——单次提交协议下它就是
 * 已知会失败的那 ~4%，放进回归是为了锁住行为、防止算法漂移。
 *
 * 样本来自真实验证码图片，仅用于测试（corpus 000/011）。
 */
@RunWith(RobolectricTestRunner::class)
class SuesSliderSolverCorpusTest {

    private fun res(name: String): ByteArray =
            javaClass.getResourceAsStream("/sues_captcha/$name")!!.readBytes()

    @Test
    fun 样本000候选表与参考一致() {
        val candidates = SuesSliderSolver.solve(res("000_bg.jpg"), res("000_sl.png"))
        assertEquals(listOf(272, 68, 83, 92, 107, 73, 78, 97), candidates)
    }

    @Test
    fun 样本011候选表与参考一致() {
        val candidates = SuesSliderSolver.solve(res("011_bg.jpg"), res("011_sl.png"))
        assertEquals(listOf(319, 324, 314, 110, 336, 329, 341, 346), candidates)
    }
}
