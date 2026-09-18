package courseclock.timetable.schedule_import.sues

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑块求解器的纯计算核心测试（不碰 Bitmap，纯 JVM）。
 *
 * 合成用例构造方式：440×240 背景每像素用确定性哈希着色（各通道独立），滑块 = 背景在
 * x0 处 80×240 窗口的逐像素拷贝，掩码外置零。这样在 x0 处 ccorr 恰为 1.0（模板与背景
 * 完全相同），且随机纹理在别处不产生系统性高分——用它验证「找得准 + 候选表结构正确」。
 * 真实语料的回归（BitmapFactory 解码 → 候选表与 Python 参考逐项相同）在
 * [SuesSliderSolverCorpusTest]。
 */
class SuesSliderSolverTest {

    private fun hash(x: Int, y: Int, salt: Int): Int {
        var h = (x * 73 + y * 151 + salt * 977) * -1640531527
        h = h xor (h ushr 13)
        return (h ushr 24) and 0xFF
    }

    private fun synthPlanes(x0: Int): Pair<Planes, Planes> {
        val w = 440
        val h = 240
        val bg = Planes(w, h, ByteArray(w * h), ByteArray(w * h), ByteArray(w * h))
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                bg.r[i] = hash(x, y, 1).toByte()
                bg.g[i] = hash(x, y, 2).toByte()
                bg.b[i] = hash(x, y, 3).toByte()
            }
        }
        // 滑块：掩码内（一个圆角块）拷贝背景，掩码外全黑（亮度 0，进不了模板）
        val sw = 80
        val sl = Planes(sw, h, ByteArray(sw * h), ByteArray(sw * h), ByteArray(sw * h))
        for (y in 0 until h) {
            for (u in 0 until sw) {
                val inMask = u in 15..65 && y in 60..180
                val i = y * sw + u
                if (inMask) {
                    val j = y * w + x0 + u
                    sl.r[i] = bg.r[j]
                    sl.g[i] = bg.g[j]
                    sl.b[i] = bg.b[j]
                }
            }
        }
        return bg to sl
    }

    @Test
    fun 定位合成缺口在正负2以内() {
        val (bg, sl) = synthPlanes(173)
        val candidates = SuesSliderSolver.solvePlanes(bg, sl)
        assertTrue("候选表不应为空", candidates.isNotEmpty())
        assertEquals(173, candidates.first())
    }

    @Test
    fun 候选表满足数量与间距约束() {
        val (bg, sl) = synthPlanes(90)
        val candidates = SuesSliderSolver.solvePlanes(bg, sl)
        assertTrue(candidates.size in 1..SuesSliderSolver.MAX_CANDIDATES)
        for (i in candidates.indices) {
            assertTrue("候选必须在合法范围", candidates[i] in 0..360)
            for (j in i + 1 until candidates.size) {
                assertTrue("任意两个候选至少相距 5 像素",
                        kotlin.math.abs(candidates[i] - candidates[j]) >= 5)
            }
        }
    }

    @Test
    fun 全黑滑块掩码为空返回空表() {
        val bg = Planes(440, 240, ByteArray(440 * 240), ByteArray(440 * 240), ByteArray(440 * 240))
        val sl = Planes(80, 240, ByteArray(80 * 240), ByteArray(80 * 240), ByteArray(80 * 240))
        assertTrue(SuesSliderSolver.solvePlanes(bg, sl).isEmpty())
    }

    @Test
    fun 尺寸不符返回空表() {
        val bg = Planes(60, 240, ByteArray(60 * 240), ByteArray(60 * 240), ByteArray(60 * 240))
        val sl = synthPlanes(0).second
        assertTrue("滑块比背景宽必须拒绝", SuesSliderSolver.solvePlanes(bg, sl).isEmpty())
    }

    @Test
    fun 排名按分数降序且去重() {
        val scores = doubleArrayOf(0.1, 0.9, 0.5, 0.9, 0.3, 0.7, 0.2, 0.8,
                0.05, 0.4, 0.6, 0.15, 0.35, 0.45, 0.65, 0.25)
        val ranked = SuesSliderSolver.rankedCandidates(scores)
        // 降序 1(.9),3(.9),7(.8),5(.7),14(.65),10(.6),2(.5),13(.45)…
        // 去重链：留 1；3 距 1 太近；留 7；5 距 7 太近；留 14；10 距 14 太近；2 距 1 太近；
        // 13 距 14 太近；其后全部被 {1,7,14} 挡住 → 只剩 3 个
        assertEquals(listOf(1, 7, 14), ranked)
    }
}
