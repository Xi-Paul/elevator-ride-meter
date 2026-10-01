package kr.xi.ridemeter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 등속 구간에서 1 Hz 이상 성분이 임계값을 넘는 진동 이벤트를 찾아 주행 위치와 함께 반환.
 * 임계값은 MAD 기반 robust σ 를 쓰므로 충격 자체가 임계값을 밀어올리지 않는다.
 */
object VibEvent {

    class Ev(val t: Float, val s: Float, val amp: Float, val axis: String, val ratio: Float)

    class Spacing(val med: Float, val mean: Float, val n: Int)

    class Result(
        val events: List<Ev>,
        val sigma: Map<String, Float>,
        val thr: Map<String, Float>,
        val spacing: Spacing?,
        val cvSeg: Boolean
    )

    private val AXES = listOf("ar" to "수직", "xr" to "전후", "yr" to "좌우")

    fun calc(r: Run): Result? {
        val S = r.samples
        if (S.size < 128) return null
        val dur = (S[S.size - 1].t - S[0].t).toDouble()
        if (dur <= 0.0) return null
        val fs = (S.size - 1) / dur
        val seg = Dsp.segments(S)
        val i0 = seg?.c0 ?: 0
        val i1 = seg?.c1 ?: (S.size - 1)
        if (i1 - i0 < 64) return null

        val hp = HashMap<String, DoubleArray>()
        val sg = HashMap<String, Float>()
        for ((k, _) in AXES) {
            // 전 구간 고역통과 → 검출 창 경계에서 필터 과도응답이 생기지 않는다
            val raw = DoubleArray(S.size) { S[it].get(k).toDouble() }
            val lo = Iso.lowpass(raw, 1.0, fs)
            val h = DoubleArray(S.size) { raw[it] - lo[it] }
            hp[k] = h
            val win = DoubleArray(i1 - i0 + 1) { abs(h[i0 + it]) }
            win.sort()
            val mad = win[win.size / 2]
            sg[k] = (1.4826 * mad).toFloat()
        }
        val thr = HashMap<String, Float>()
        for ((k, _) in AXES) thr[k] = max(6f * sg[k]!!, 0.08f)

        val guard = max(2, Math.round(0.3 * fs).toInt())
        val ev = ArrayList<Ev>()
        var i = i0
        while (i <= i1) {
            var hit = false
            for ((k, _) in AXES) if (abs(hp[k]!![i]) > thr[k]!!) { hit = true; break }
            if (!hit) { i++; continue }
            var bi = i; var bv = 0.0; var bk = "ar"; var bn = "수직"
            for (j in i..min(i1, i + guard)) for ((k, nm) in AXES) {
                val v = abs(hp[k]!![j])
                if (v > bv) { bv = v; bi = j; bk = k; bn = nm }
            }
            ev.add(Ev(S[bi].t, S[bi].s, bv.toFloat(), bn, (bv / thr[bk]!!).toFloat()))
            i = bi + guard
        }

        var spacing: Spacing? = null
        if (ev.size >= 2) {
            val d = ArrayList<Float>()
            for (n in 1 until ev.size) d.add(abs(ev[n].s - ev[n - 1].s))
            val srt = d.sorted()
            var sum = 0f
            for (x in d) sum += x
            spacing = Spacing(srt[srt.size / 2], sum / d.size, d.size)
        }
        return Result(ev, sg, thr, spacing, seg != null)
    }
}
