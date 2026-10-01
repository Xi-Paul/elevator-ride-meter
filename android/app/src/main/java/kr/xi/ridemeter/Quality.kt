package kr.xi.ridemeter

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 측정 유효성 자동 판정. */
object Quality {

    class Issue(val level: String, val msg: String)   // level: warn | reject

    class Result(val verdict: String, val issues: List<Issue>, val tilt: Float, val fs: Float)

    fun verdictText(v: String): String = when (v) { "reject" -> "배제"; "warn" -> "주의"; else -> "정상" }

    fun calc(r: Run): Result {
        val S = r.samples
        if (S.size < 32)
            return Result("reject", listOf(Issue("reject", "샘플 수 부족")), 0f, 0f)
        val dur = (S[S.size - 1].t - S[0].t).toDouble()
        if (dur <= 0.0)
            return Result("reject", listOf(Issue("reject", "시간축 이상")), 0f, 0f)
        val fs = ((S.size - 1) / dur).toFloat()
        val iss = ArrayList<Issue>()

        // 1) 폰 자세 변화 — 기울면 중력이 수평축으로 새어나와 DC 성분이 생긴다 (1° ≈ 0.17 m/s²)
        var mx = 0.0; var my = 0.0
        for (p in S) { mx += p.x; my += p.y }
        mx /= S.size; my /= S.size
        val tilt = hypot(mx, my).toFloat()
        if (tilt > 0.15f) {
            val deg = Math.toDegrees(asin(min(1.0, tilt / 9.81).toDouble()))
            iss.add(Issue("reject", "측정 중 폰 자세 변화 추정 (수평축 DC %.2f m/s² ≈ %.1f° 기울기)".format(tilt, deg)))
        } else if (tilt > 0.08f) {
            iss.add(Issue("warn", "수평축 DC %.2f m/s² — 고정이 느슨했을 수 있음".format(tilt)))
        }

        // 2) 드리프트 보정량
        val b = abs(r.bias)
        if (b > 0.05f) iss.add(Issue("reject", "드리프트 보정량 과다 (%.0f mm/s²)".format(b * 1000f)))
        else if (b > 0.02f) iss.add(Issue("warn", "드리프트 보정량 %.0f mm/s²".format(b * 1000f)))

        // 3) 주행 자체가 없음
        val net = abs(S[S.size - 1].s)
        var vm = 0f
        for (p in S) vm = max(vm, abs(p.v))
        if (net < 0.1f || vm < 0.05f)
            iss.add(Issue("reject", "주행이 검출되지 않음 (변위 %.2f m, 최대속도 %.2f m/s)".format(net, vm)))

        // 4) 종료 시 정지 미확인 — v=0 보정 전제가 깨진다
        val nEnd = max(4, Math.round(0.5 * fs).toInt())
        var ae = 0f
        for (i in max(0, S.size - nEnd) until S.size) ae += abs(S[i].a)
        ae /= min(nEnd, S.size)
        if (ae > 0.1f)
            iss.add(Issue("reject", "종료 시점에 아직 주행 중 (마지막 0.5 s 평균 |a| %.2f m/s²)".format(ae)))

        // 4b) 감속 구간 미검출 — 주행 중에 기록을 끊으면 마지막 감속이 없다
        if (Dsp.segments(S) == null)
            iss.add(Issue("reject", "가속·등속·감속 구간을 분리하지 못함 (주행 중 기록을 끊었을 가능성)"))

        // 5) 시작 시 정지 미확인 — 영점 오염
        val nSt = max(4, Math.round(0.3 * fs).toInt())
        var m0 = 0f
        for (i in 0 until min(nSt, S.size)) m0 += S[i].a
        m0 /= min(nSt, S.size)
        var sd0 = 0f
        for (i in 0 until min(nSt, S.size)) sd0 += (S[i].a - m0) * (S[i].a - m0)
        sd0 = sqrt(sd0 / min(nSt, S.size))
        if (abs(m0) > 0.1f || sd0 > 0.1f)
            iss.add(Issue("warn", "출발 전 정지 상태 불확실 (초기 0.3 s |a| %.2f, σ %.2f m/s²)".format(abs(m0), sd0)))

        // 6) 샘플 누락 / 불균일
        var gaps = 0
        val dts = ArrayList<Float>(S.size)
        for (i in 1 until S.size) {
            val dt = S[i].t - S[i - 1].t
            dts.add(dt)
            if (dt > 0.2f) gaps++
        }
        dts.sort()
        val med = dts[dts.size / 2]
        var odd = 0
        for (d in dts) if (d > 2 * med || d < 0.5f * med) odd++
        if (gaps > 0) iss.add(Issue("reject", "데이터 공백 ${gaps}건 (0.2 s 초과)"))
        else if (odd.toFloat() / dts.size > 0.05f)
            iss.add(Issue("warn", "샘플 간격 불균일 %.0f%%".format(100f * odd / dts.size)))
        if (fs < 30f) iss.add(Issue("warn", "샘플링 %.0f Hz — 저크·스펙트럼 신뢰도 낮음".format(fs)))

        val verdict = when {
            iss.any { it.level == "reject" } -> "reject"
            iss.isNotEmpty() -> "warn"
            else -> "ok"
        }
        return Result(verdict, iss, tilt, fs)
    }
}
