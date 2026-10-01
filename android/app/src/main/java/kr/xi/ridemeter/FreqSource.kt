package kr.xi.ridemeter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * 주파수 원인 추정.
 * 회전 기인 진동은 로프 이동거리에 대해 주기적이므로 카 기준 파장 λ = π·D/(로핑×차수) 로 고정되고,
 * 레일 이음부·브래킷은 카 이동거리에 대해 주기적이므로 λ = 설치 피치와 같다.
 * 측정 피크의 λ = v/f 를 두 계열과 대조해 추정한다.
 */
object FreqSource {

    class Spec(
        val diaM: Float = 0f,      // 시브 직경 (m), 0 = 미입력
        val roping: Float = 2f,
        val gear: Float = 1f,
        val poles: Float = 0f,
        val railM: Float = 5f,
        val bracketM: Float = 2.5f
    )

    class Cand(val lam: Float, val label: String, val rot: Boolean)

    class Row(
        val f: Double, val amp: Double, val lam: Float,
        val best: Cand?, val dev: Float, val hit: Boolean,
        val evMatch: Boolean, val ambig: String?
    ) {
        val estimate: String get() = when {
            best == null -> "—"
            hit -> (if (best.rot) "회전 기인" else "레일·공간 기인") +
                (if (evMatch) " · 충격 이벤트 간격과 일치" else "")
            else -> "미상"
        }
    }

    class Result(val rows: List<Row>, val v: Float, val df: Double, val axis: String)

    fun cruiseSpeed(r: Run): Float {
        val S = r.samples
        if (S.isEmpty()) return 0f
        val seg = Dsp.segments(S)
        val i0 = seg?.c0 ?: 0
        val i1 = seg?.c1 ?: (S.size - 1)
        var sum = 0f; var n = 0
        for (i in i0..i1) { sum += abs(S[i].v); n++ }
        return if (n > 0) sum / n else 0f
    }

    fun candidates(m: Spec): List<Cand> {
        val c = ArrayList<Cand>()
        if (m.diaM > 0f) {
            val lamRev = (PI * m.diaM / m.roping).toFloat()   // 시브 1회전당 카 이동거리
            for (n in 1..4) c.add(Cand(lamRev / n, "시브 ${n}차 (불평형·편심)", true))
            if (m.poles > 0f) {
                val lamE = lamRev / (m.poles / 2f * m.gear)   // 전기 1주기당 카 이동거리
                c.add(Cand(lamE, "모터 전기 1차 (코깅·역기전력 왜곡)", true))
                c.add(Cand(lamE / 6f, "모터 6f_e (토크 리플)", true))
                c.add(Cand(lamE / 12f, "모터 12f_e (토크 리플)", true))
                c.add(Cand(lamRev / (m.poles * m.gear), "슬롯·극 상호작용", true))
            }
            if (m.gear > 1.01f) c.add(Cand(lamRev / m.gear, "감속기 입력축 1차", true))
        }
        c.add(Cand(m.railM, "레일 이음부", false))
        c.add(Cand(m.railM / 2f, "레일 이음부 2차 고조", false))
        c.add(Cand(m.bracketM, "레일 브래킷 피치", false))
        return c
    }

    fun calc(r: Run, m: Spec, axis: String = "ar"): Result? {
        val sp = Dsp.spectrum(r, "const", axis) ?: return null
        if (sp.peaks.isEmpty()) return null
        val v = cruiseSpeed(r)
        if (v < 0.05f) return null
        val cand = candidates(m)
        val ve = VibEvent.calc(r)
        val evLam = ve?.spacing?.med

        var pmax = 0.0
        for (p in sp.peaks) pmax = max(pmax, p[1])
        val useful = sp.peaks.filter { it[1] >= max(0.01, 0.1 * pmax) }
        val src = if (useful.isNotEmpty()) useful else sp.peaks.take(1)

        val rows = src.map { pk ->
            val f = pk[0]
            val lam = (v / f).toFloat()
            var best: Cand? = null
            var dev = Float.MAX_VALUE
            for (c in cand) {
                val d = abs(lam - c.lam) / c.lam
                if (d < dev) { dev = d; best = c }
            }
            val tol = max(0.08, 1.5 * sp.df / f).toFloat()
            var ambig: String? = null
            if (best != null) for (c in cand)
                if (c.label != best.label && abs(c.lam - best.lam) / best.lam < 0.02f) ambig = c.label
            val evMatch = evLam != null && abs(lam - evLam) / evLam < 0.12f
            Row(f, pk[1], lam, best, dev, best != null && dev <= tol, evMatch, ambig)
        }
        return Result(rows, v, sp.df, sp.axisName)
    }
}
