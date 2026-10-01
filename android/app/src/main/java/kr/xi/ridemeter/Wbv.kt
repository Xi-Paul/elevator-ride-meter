package kr.xi.ridemeter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * ISO 2631-1 전신진동 주파수 가중 (Wk 수직 / Wd 수평).
 * 파라미터: Wk f1 0.4, f2 100, f3 12.5, f4 12.5, Q4 0.63, f5 2.37, Q5 0.91, f6 3.35, Q6 0.91
 *           Wd f1 0.4, f2 100, f3 2,    f4 2,    Q4 0.63, 상승 스텝 없음
 * s-영역 블록을 쌍선형 변환(pre-warping)해 바이쿼드로 만들고 인과 1회 적용한다.
 * 순·역 2회(filtfilt)를 쓰면 진폭응답이 제곱되어 규격과 어긋나므로 쓰지 않는다.
 */
object Wbv {

    const val MG = 1.0 / 0.00980665   // m/s² → milli-g

    class Bq(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private fun bilinear(b2: Double, b1: Double, b0: Double,
                         a2: Double, a1: Double, a0: Double, fs: Double): Bq {
        val k = 2 * fs
        val k2 = k * k
        val A0 = a2 * k2 + a1 * k + a0
        return Bq(
            (b2 * k2 + b1 * k + b0) / A0,
            2 * (b0 - b2 * k2) / A0,
            (b2 * k2 - b1 * k + b0) / A0,
            2 * (a0 - a2 * k2) / A0,
            (a2 * k2 - a1 * k + a0) / A0
        )
    }

    fun sections(fs: Double, wk: Boolean): List<Bq> {
        val f1 = 0.4; val f2 = 100.0
        val f3 = if (wk) 12.5 else 2.0
        val f4 = f3
        val q4 = 0.63
        val w = { f: Double -> 2 * fs * tan(PI * f / fs) }   // pre-warping
        val q1 = 1.0 / sqrt(2.0)
        val out = ArrayList<Bq>()
        val w1 = w(f1)
        out.add(bilinear(1.0, 0.0, 0.0, 1.0, w1 / q1, w1 * w1, fs))                  // Hh
        // Hl(100 Hz)은 fs가 충분할 때만. 낮은 fs에서는 차단이 나이퀴스트 아래로 내려온다.
        if (fs > 260) { val w2 = w(f2); out.add(bilinear(0.0, 0.0, w2 * w2, 1.0, w2 / q1, w2 * w2, fs)) }
        val w3 = w(f3); val w4 = w(f4)
        out.add(bilinear(0.0, w4 * w4 / w3, w4 * w4, 1.0, w4 / q4, w4 * w4, fs))     // Ht
        if (wk) {
            val w5 = w(2.37); val w6 = w(3.35); val q5 = 0.91; val q6 = 0.91
            out.add(bilinear(1.0, w5 / q5, w5 * w5, 1.0, w6 / q6, w6 * w6, fs))      // Hs
        }
        return out
    }

    private fun biq(a: DoubleArray, c: Bq): DoubleArray {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        val o = DoubleArray(a.size)
        for (i in a.indices) {
            val v = a[i]
            val y = c.b0 * v + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = y
            o[i] = y
        }
        return o
    }

    /** 앞쪽 1 s 영패딩으로 시작 과도응답을 흡수한 뒤 잘라낸다. */
    fun apply(src: DoubleArray, fs: Double, wk: Boolean): DoubleArray {
        val pad = Math.round(fs).toInt()
        var y = DoubleArray(pad + src.size)
        System.arraycopy(src, 0, y, pad, src.size)
        for (c in sections(fs, wk)) y = biq(y, c)
        return DoubleArray(src.size) { y[pad + it] }
    }

    class Pp(val max: Float, val a95: Float, val zp: Float, val iMax: Int, val n: Int)

    /** 영교차점 사이 peak-to-peak 목록 → 최대 / A95 / 0-Pk / 최대 발생 위치 */
    fun ppList(sig: DoubleArray): Pp {
        val zc = ArrayList<Int>()
        for (i in 1 until sig.size)
            if ((sig[i - 1] < 0 && sig[i] >= 0) || (sig[i - 1] >= 0 && sig[i] < 0)) zc.add(i)
        val pp = ArrayList<Double>()
        val idx = ArrayList<Int>()
        var k = 0
        while (k + 2 < zc.size) {
            var mx = -1e9; var mn = 1e9; var im = zc[k]
            for (i in zc[k] until zc[k + 2]) { if (sig[i] > mx) mx = sig[i]; if (sig[i] < mn) mn = sig[i] }
            val peak = max(abs(mx), abs(mn))
            for (i in zc[k] until zc[k + 2]) if (abs(sig[i]) == peak) { im = i; break }
            if (mx > mn) { pp.add(mx - mn); idx.add(im) }
            k++
        }
        var zp = 0.0
        for (v in sig) zp = max(zp, abs(v))
        if (pp.isEmpty()) return Pp(0f, 0f, zp.toFloat(), 0, 0)
        var bi = 0
        for (i in pp.indices) if (pp[i] > pp[bi]) bi = i
        val srt = pp.sorted()
        return Pp(pp[bi].toFloat(), srt[(0.95 * (srt.size - 1)).toInt()].toFloat(),
            zp.toFloat(), idx[bi], pp.size)
    }
}
