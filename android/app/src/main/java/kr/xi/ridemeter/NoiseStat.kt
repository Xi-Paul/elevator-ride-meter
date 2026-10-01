package kr.xi.ridemeter

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** 소음 레벨 통계 (Android 의존 없음 — 단위 시험 가능) */
object NoiseStats {

    class Pt(val t: Float, val a: Float, val z: Float)

    class Stat(
        val leq: Float, val lmax: Float, val l10: Float, val l95: Float,
        val n: Int, val offset: Float
    ) {
        val unit: String get() = if (offset == 0f) "dBFS(A)" else "dB(A)"
    }

    /** IEC 61672 A 특성 (해석식), dB */
    fun aWeight(f: Double): Double {
        val f2 = f * f
        val num = 12194.0 * 12194.0 * f2 * f2
        val den = (f2 + 20.6 * 20.6) *
            sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) *
            (f2 + 12194.0 * 12194.0)
        return 20.0 * log10(num / den) + 2.0
    }

    fun stats(pts: List<Pt>, t0: Float, t1: Float, offset: Float): Stat? {
        val sel = pts.filter { it.t in t0..t1 }
        if (sel.size < 4) return null
        var sum = 0.0
        for (p in sel) sum += 10.0.pow(p.a / 10.0)
        val leq = (10.0 * log10(sum / sel.size)).toFloat() + offset
        val dt = max(0.02f, (sel[sel.size - 1].t - sel[0].t) / max(1, sel.size - 1))
        val win = max(1, Math.round(0.125 / dt).toInt())
        var lmax = -200.0
        var i = 0
        while (i + win <= sel.size) {
            var e = 0.0
            for (k in i until i + win) e += 10.0.pow(sel[k].a / 10.0)
            lmax = max(lmax, 10.0 * log10(e / win))
            i++
        }
        val srt = sel.map { it.a }.sorted()
        val l95 = srt[(0.05 * (srt.size - 1)).toInt()] + offset
        val l10 = srt[(0.90 * (srt.size - 1)).toInt()] + offset
        return Stat(leq, lmax.toFloat() + offset, l10, l95, sel.size, offset)
    }

    /** 등속 구간(없으면 전체) 소음 지표: (등속, 전체, 등속분리여부) */
    fun cruise(r: Run, offset: Float): Triple<Stat?, Stat?, Boolean> {
        val pts = r.noise
        if (pts.isEmpty() || r.samples.isEmpty()) return Triple(null, null, false)
        val S = r.samples
        val seg = Dsp.segments(S)
        val t0 = if (seg != null) S[seg.c0].t else S[0].t
        val t1 = if (seg != null) S[seg.c1].t else S[S.size - 1].t
        return Triple(
            stats(pts, t0, t1, offset),
            stats(pts, S[0].t, S[S.size - 1].t, offset),
            seg != null
        )
    }
}
