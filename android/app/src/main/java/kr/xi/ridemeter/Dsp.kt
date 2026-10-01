package kr.xi.ridemeter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** 한 샘플. t는 기록 시작 기준 초. */
class Smp(
    val t: Float,
    val ar: Float,   // 원시 연직 가속도 (필터 전)
    var a: Float,    // 필터 연직 가속도
    var v: Float,
    var j: Float,
    var s: Float,
    val xr: Float = 0f,   // 원시 수평 전후
    val yr: Float = 0f,   // 원시 수평 좌우
    var x: Float = 0f,    // 필터 수평 전후
    var y: Float = 0f,    // 필터 수평 좌우
    var jx: Float = 0f,
    var jy: Float = 0f
) {
    fun get(k: String): Float = when (k) {
        "ar" -> ar; "a" -> a; "v" -> v; "j" -> j; "s" -> s
        "xr" -> xr; "yr" -> yr; "x" -> x; "y" -> y; "jx" -> jx; else -> jy
    }
}

class AxStat(val mx: Float, val mn: Float, val pp: Float, val rms: Float)

/** 한 회차 측정. */
class Run {
    var samples: MutableList<Smp> = ArrayList()
    var fs: Float = 0f
    var bias: Float = 0f
    var done: Boolean = false
    var src: String? = null
    var sensorName: String = ""
    var noise: List<NoiseStats.Pt> = emptyList()
}

class Stat(
    val aMax: Float, val aMin: Float,
    val vMax: Float, val vMin: Float,
    val jMax: Float, val jMin: Float
)

class Seg(
    val a0: Int, val a1: Int,
    val c0: Int, val c1: Int,
    val d0: Int, val d1: Int
)

class Spec(
    val amp: DoubleArray,
    val df: Double,
    val fs: Double,
    val peaks: List<DoubleArray>,   // [f, amp]
    val n: Int,
    val dur: Double,
    val segName: String,
    val fallback: Boolean,
    val axisName: String = "수직"
)

object Dsp {

    fun stats(S: List<Smp>): Stat {
        var aMx = -1e9f; var aMn = 1e9f
        var vMx = -1e9f; var vMn = 1e9f
        var jMx = -1e9f; var jMn = 1e9f
        for (p in S) {
            aMx = max(aMx, p.a); aMn = min(aMn, p.a)
            vMx = max(vMx, p.v); vMn = min(vMn, p.v)
            jMx = max(jMx, p.j); jMn = min(jMn, p.j)
        }
        return Stat(aMx, aMn, vMx, vMn, jMx, jMn)
    }

    fun axStats(S: List<Smp>, k: String): AxStat {
        if (S.isEmpty()) return AxStat(0f, 0f, 0f, 0f)
        var mx = -1e9f; var mn = 1e9f; var m = 0.0
        for (p in S) { val u = p.get(k); mx = max(mx, u); mn = min(mn, u); m += u }
        m /= S.size
        var sq = 0.0
        for (p in S) { val u = p.get(k) - m; sq += u * u }
        return AxStat(mx, mn, mx - mn, sqrt(sq / S.size).toFloat())
    }

    /** 가속 / 등속 / 감속 구간 인덱스. 판정 불가 시 null. */
    fun segments(S: List<Smp>): Seg? {
        val n = S.size
        if (n < 10) return null
        var vm = 0f
        for (p in S) vm = max(vm, abs(p.v))
        if (vm < 0.05f) return null
        var i0 = 0
        while (i0 < n && abs(S[i0].a) < 0.1f) i0++
        var i3 = n - 1
        while (i3 > 0 && abs(S[i3].a) < 0.1f) i3--
        var i1 = i0
        while (i1 < n && abs(S[i1].v) < 0.9f * vm) i1++
        var i2 = i3
        while (i2 > 0 && abs(S[i2].v) < 0.9f * vm) i2--
        if (!(i0 < i1 && i1 <= i2 && i2 < i3)) return null
        return Seg(i0, i1, i1, i2, i2, i3)
    }

    /** 제자리 radix-2 FFT. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + len / 2]; val xi = im[i + k + len / 2]
                    val vr = xr * cr - xi * ci
                    val vi = xr * ci + xi * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val t = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun axName(a: String): String = when (a) { "xr" -> "전후"; "yr" -> "좌우"; else -> "수직" }

    private fun segName(w: String): String = when (w) {
        "acc" -> "가속"; "const" -> "등속"; "dec" -> "감속"; else -> "전체"
    }

    /** want: acc | const | dec | all, axis: ar | xr | yr */
    fun spectrum(run: Run, want: String, axis: String = "ar"): Spec? {
        val full = run.samples
        if (full.size < 32) return null
        var S: List<Smp> = full
        var used = "all"
        if (want != "all") {
            val sg = segments(full)
            if (sg != null) {
                val r = when (want) {
                    "acc" -> intArrayOf(sg.a0, sg.a1)
                    "const" -> intArrayOf(sg.c0, sg.c1)
                    else -> intArrayOf(sg.d0, sg.d1)
                }
                if (r[1] - r[0] + 1 >= 32) { S = full.subList(r[0], r[1] + 1); used = want }
            }
        }
        val dur = (S[S.size - 1].t - S[0].t).toDouble()
        if (dur <= 0.0) return null
        val fs = (S.size - 1) / dur
        var mean = 0.0
        for (p in S) mean += p.get(axis)
        mean /= S.size
        var n = 1
        while (n < S.size) n = n shl 1
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in S.indices) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (S.size - 1))
            re[i] = (S[i].get(axis) - mean) * w
        }
        fft(re, im)
        val half = n / 2
        val amp = DoubleArray(half)
        val df = fs / n
        for (k in 0 until half) amp[k] = 2.0 * hypot(re[k], im[k]) / (S.size * 0.5)
        val peaks = ArrayList<DoubleArray>()
        for (k in 2 until half - 1) {
            if (k * df >= 0.3 && amp[k] > amp[k - 1] && amp[k] >= amp[k + 1])
                peaks.add(doubleArrayOf(k * df, amp[k]))
        }
        peaks.sortByDescending { it[1] }
        val top = if (peaks.size > 5) peaks.subList(0, 5).toList() else peaks.toList()
        return Spec(amp, df, fs, top, S.size, dur, segName(used), used != want, axName(axis))
    }

    /** 종료 시 v=0 조건으로 가속도 오프셋을 역산해 제거하고 속도·거리를 재적분. */
    fun debias(S: MutableList<Smp>): Float {
        val T = S[S.size - 1].t
        if (T <= 0f) return 0f
        val bias = S[S.size - 1].v / T
        var vv = 0f; var ss = 0f
        for (i in S.indices) {
            val p = S[i]
            p.a -= bias
            if (i > 0) {
                val q = S[i - 1]
                val dt = p.t - q.t
                vv += (p.a + q.a) / 2f * dt
                ss += vv * dt
            }
            p.v = vv; p.s = ss
        }
        return bias
    }

    fun sgn(x: Float): Float = sign(x)
}
