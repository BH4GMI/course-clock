package courseclock.timetable.schedule_import.sues

import android.graphics.BitmapFactory

/**
 * SUES 统一身份认证滑块验证码的缺口定位器。
 *
 * 与验证工作区（sudy_captcha_lite.py / bench_android_java/Bench.java，变体 B）逐行同源：
 * 整幅 80×240 滑块画布作模板——绝不裁剪，裁剪在语料上实测整体偏 +19px；掩码取亮度
 * 299R+587G+114B > 12000 的像素；掩码内三通道做 ccorr（num=ΣT·I，den=sqrt(ΣT²)·sqrt(ΣI²)）；
 * int 累加、byte 平面，只在最后归一化时用 double。只在 1.0 档（不缩放）搜索——这是 80 例
 * 真实语料上「单次提交 |x−真值|≤2」判据的最优配置（77/80），正对 App 端「拖一次滑块」的协议；
 * 若将来改为「同一 id 连试多个候选」，应换多档缩放并重测（见验证工作区报告 §5.6.1）。
 *
 * 端侧实测（SM8850 / arm64-v8a / ART）：单例 0.73–0.79ms，解码另计 0.23ms；
 * 老手机（Cortex-A53 级）推断 5–15ms，都在一帧以内。候选表曾在 JVM 与 ART 双端
 * 与 Python 参考在全语料上逐例一致（读取 PIL 导出的原始平面比对）。
 */
object SuesSliderSolver {

    /** 掩码亮度阈值：299R+587G+114B > 阈值×1000 的像素算滑块拼图本体。 */
    private const val PIECE_THRESHOLD = 12

    /** 候选之间的最小间距（自然像素）。 */
    private const val CANDIDATE_MIN_SEP = 5

    /** 最多返回几个候选。 */
    const val MAX_CANDIDATES = 8

    /**
     * 求解缺口 x：返回按匹配分数降序的候选列表（自然像素，0..bgW-sliderW）。
     * 解码失败、尺寸不符或掩码为空时返回空表，由调用方降级为手动。
     */
    fun solve(bgBytes: ByteArray, sliderBytes: ByteArray): List<Int> {
        val bg = decodePlanes(bgBytes) ?: return emptyList()
        val slider = decodePlanes(sliderBytes) ?: return emptyList()
        return solvePlanes(bg, slider)
    }

    /**
     * 解码图片字节为三通道字节平面。
     *
     * `inPremultiplied = false`：与 PIL 的 RGBA→RGB 语义对齐——预乘会把透明像素的 RGB
     * 抹成 0，滑块 PNG 的拼图本体恰恰带半透明边缘，必须保留原始 RGB。
     */
    internal fun decodePlanes(bytes: ByteArray): Planes? {
        val opts = BitmapFactory.Options()
        opts.inPremultiplied = false
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val r = ByteArray(n)
        val g = ByteArray(n)
        val b = ByteArray(n)
        for (i in 0 until n) {
            val p = px[i]
            r[i] = (p ushr 16).toByte()
            g[i] = (p ushr 8).toByte()
            b[i] = p.toByte()
        }
        return Planes(w, h, r, g, b)
    }

    /** 纯计算核心：不碰 Bitmap，纯 JVM 可测，便于与参考实现对拍。 */
    internal fun solvePlanes(bg: Planes, slider: Planes): List<Int> {
        val xMax = bg.w - slider.w
        if (xMax < 0 || slider.h <= 0 || slider.h > bg.h) return emptyList()

        // 建模板：掩码内像素的背景下标偏移与 RGB，normT = sqrt(ΣT²)
        val n = slider.w * slider.h
        val off = IntArray(n)
        val tr = ByteArray(n)
        val tg = ByteArray(n)
        val tb = ByteArray(n)
        var count = 0
        var sumSq = 0L
        for (v in 0 until slider.h) {
            val base = v * slider.w
            for (u in 0 until slider.w) {
                val i = base + u
                val r = slider.r[i].toInt() and 0xFF
                val g = slider.g[i].toInt() and 0xFF
                val b = slider.b[i].toInt() and 0xFF
                if (299 * r + 587 * g + 114 * b > PIECE_THRESHOLD * 1000) {
                    off[count] = v * bg.w + u
                    tr[count] = r.toByte()
                    tg[count] = g.toByte()
                    tb[count] = b.toByte()
                    sumSq += r.toLong() * r + g.toLong() * g + b.toLong() * b
                    count++
                }
            }
        }
        if (count == 0) return emptyList()
        val normT = Math.sqrt(sumSq.toDouble())

        // 变体 B：int 累加 + byte 平面。num/den 上界 ≈ 2332×3×255² < 2^31，不会溢出。
        val scores = DoubleArray(xMax + 1)
        for (x in 0..xMax) {
            var num = 0
            var den = 0
            for (k in 0 until count) {
                val i = off[k] + x
                val ir = bg.r[i].toInt() and 0xFF
                val ig = bg.g[i].toInt() and 0xFF
                val ib = bg.b[i].toInt() and 0xFF
                num += (tr[k].toInt() and 0xFF) * ir +
                        (tg[k].toInt() and 0xFF) * ig +
                        (tb[k].toInt() and 0xFF) * ib
                den += ir * ir + ig * ig + ib * ib
            }
            scores[x] = if (den <= 0) 0.0 else num / (normT * Math.sqrt(den.toDouble()))
        }
        return rankedCandidates(scores)
    }

    /** 按分数降序选候选，任意两个候选至少相距 [CANDIDATE_MIN_SEP] 像素。 */
    internal fun rankedCandidates(scores: DoubleArray): List<Int> {
        val idx = IntArray(scores.size) { it }
        for (i in idx.indices) {
            var best = i
            for (j in i + 1 until idx.size) {
                if (scores[idx[j]] > scores[idx[best]]) best = j
            }
            val t = idx[i]
            idx[i] = idx[best]
            idx[best] = t
        }
        val chosen = ArrayList<Int>(MAX_CANDIDATES)
        for (i in idx.indices) {
            var ok = true
            for (c in chosen) {
                if (Math.abs(idx[i] - c) < CANDIDATE_MIN_SEP) {
                    ok = false
                    break
                }
            }
            if (ok) {
                chosen.add(idx[i])
                if (chosen.size == MAX_CANDIDATES) break
            }
        }
        return chosen
    }
}

/** 三通道字节平面（与 PIL 的 `img.convert("RGB").split()` 逐字节一致）。 */
internal class Planes(val w: Int, val h: Int,
                      val r: ByteArray, val g: ByteArray, val b: ByteArray)
