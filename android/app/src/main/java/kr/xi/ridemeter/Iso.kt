package kr.xi.ridemeter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * ISO 18738-1 산출 절차를 따른 참고값 계산.
 * 규격의 계측기 요구사항(ISO 8041 정확도)은 스마트폰 센서로 충족할 수 없으므로 참고용.
 * 주파수 가중(Wk/Wd)은 적용하지 않음.
 */
object Iso {

    class Vppv(val max: Float, val a95: Float, val n: Int)

    class Result(
        val fs: Float,
        val z: Vppv, val x: Vppv, val y: Vppv,
        val vMax: Float, val jMax: Float,
        val aMax: Float, val dMax: Float,
        val cvDur: Float, val full: Boolean
    )

    private class Bq(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** 2극 버터워스 저역통과 (쌍선형 변환) */
    private fun butter2(fc: Double, fs: Double): Bq {
        val w = tan(PI * fc / fs)
        val k = sqrt(2.0) * w
        val n = w * w
        val d = 1 + k + n
        return Bq(n / d, 2 * n / d, n / d, 2 * (n - 1) / d, (1 - k + n) / d)
    }

    private fun biquad(a: DoubleArray, c: Bq): DoubleArray {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        val out = DoubleArray(a.size)
        for (i in a.indices) {
            val v = a[i]
            val y = c.b0 * v + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = y
            out[i] = y
        }
        return out
    }

    private fun reversed(a: DoubleArray): DoubleArray {
        val o = DoubleArray(a.size)
        for (i in a.indices) o[i] = a[a.size - 1 - i]
        return o
    }

    /** 순·역방향 2회 적용 → 위상 지연 제거 */
    private fun filtfilt(a: DoubleArray, c: Bq): DoubleArray =
        reversed(biquad(reversed(biquad(a, c)), c))

    /** 영교차점 사이 peak-to-peak 진동값 목록 → Max, A95 */
    fun vppv(sig: DoubleArray): Vppv {
        val zc = ArrayList<Int>()
        for (i in 1 until sig.size)
            if ((sig[i - 1] < 0 && sig[i] >= 0) || (sig[i - 1] >= 0 && sig[i] < 0)) zc.add(i)
        val p = ArrayList<Double>()
        var k = 0
        while (k + 2 < zc.size) {
            var mx = -1e9; var mn = 1e9
            for (i in zc[k] until zc[k + 2]) { mx = max(mx, sig[i]); mn = min(mn, sig[i]) }
            if (mx > mn) p.add(mx - mn)
            k++
        }
        if (p.isEmpty()) return Vppv(0f, 0f, 0)
        p.sort()
        val idx = Math.floor(0.95 * (p.size - 1)).toInt()
        return Vppv(p[p.size - 1].toFloat(), p[idx].toFloat(), p.size)
    }

    /** 2극 버터워스 저역통과, 순·역방향 적용(위상 무지연) */
    fun lowpass(a: DoubleArray, fc: Double, fs: Double): DoubleArray = filtfilt(a, butter2(fc, fs))

    fun calc(r: Run): Result? {
        val S = r.samples
        if (S.size < 64) return null
        val dur = (S[S.size - 1].t - S[0].t).toDouble()
        if (dur <= 0.0) return null
        val fs = (S.size - 1) / dur

        val aRaw = DoubleArray(S.size) { S[it].ar.toDouble() }
        val xRaw = DoubleArray(S.size) { S[it].xr.toDouble() }
        val yRaw = DoubleArray(S.size) { S[it].yr.toDouble() }

        // 1 Hz 필터로 비등속(가·감속) 구간 판정, 앞뒤 0.5 s 확장
        val a1 = filtfilt(aRaw, butter2(1.0, fs))
        val pad = Math.round(0.5 * fs).toInt()
        val ext = BooleanArray(S.size)
        for (i in a1.indices) if (abs(a1[i]) > 0.1) {
            val lo = max(0, i - pad); val hi = min(S.size - 1, i + pad)
            for (k in lo..hi) ext[k] = true
        }
        var i0 = -1; var i3 = -1
        for (i in ext.indices) if (ext[i]) { if (i0 < 0) i0 = i; i3 = i }
        if (i0 < 0) return null

        val blocks = ArrayList<IntArray>()
        var st = -1
        for (i in ext.indices) {
            if (ext[i] && st < 0) st = i
            if (st >= 0 && (!ext[i] || i == ext.size - 1)) {
                blocks.add(intArrayOf(st, if (ext[i]) i else i - 1)); st = -1
            }
        }
        val cv: IntArray? = if (blocks.size >= 2)
            intArrayOf(blocks[0][1], blocks[blocks.size - 1][0]) else null

        // 속도: 10 Hz 저역통과 신호 적분 후 종료 v=0 조건으로 드리프트 제거
        val a10 = filtfilt(aRaw, butter2(10.0, fs))
        val vv = DoubleArray(S.size)
        for (i in 1 until S.size)
            vv[i] = vv[i - 1] + (a10[i] + a10[i - 1]) / 2.0 * (S[i].t - S[i - 1].t)
        val bias = vv[vv.size - 1] / dur
        for (i in vv.indices) vv[i] -= bias * (S[i].t - S[0].t)
        var vMax = 0.0
        for (u in vv) vMax = max(vMax, abs(u))

        var jMax = 0.0
        for (i in max(1, i0)..min(S.size - 1, i3)) {
            val dt = (S[i].t - S[i - 1].t).toDouble()
            if (dt <= 0.0) continue
            jMax = max(jMax, abs((a10[i] - a10[i - 1]) / dt))
        }
        var aMax = 0.0; var dMax = 0.0
        for (i in i0..i3) { aMax = max(aMax, a1[i]); dMax = min(dMax, a1[i]) }

        // 진동: 등속 구간에서 1 Hz 이하 운동 성분 제거 후 VPPV
        val seg = cv ?: intArrayOf(i0, i3)
        val cut = butter2(1.0, fs)
        fun hp(src: DoubleArray): DoubleArray {
            val n = seg[1] - seg[0] + 1
            val cutArr = DoubleArray(n) { src[seg[0] + it] }
            val lo = filtfilt(cutArr, cut)
            return DoubleArray(n) { cutArr[it] - lo[it] }
        }
        if (seg[1] - seg[0] < 32) return null
        return Result(
            fs.toFloat(), vppv(hp(aRaw)), vppv(hp(xRaw)), vppv(hp(yRaw)),
            vMax.toFloat(), jMax.toFloat(), aMax.toFloat(), dMax.toFloat(),
            S[seg[1]].t - S[seg[0]].t, cv == null
        )
    }
}
