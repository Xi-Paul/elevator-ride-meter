package kr.xi.ridemeter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 안티롤백 분석.
 * 브레이크 개방 직후 ~ 의도된 가속 개시 직전 구간의 미소 거동을 분리해 정량화한다.
 */
object Rollback {

    class Result(
        val short: Boolean = false,      // 출발 전 정지 구간 부족
        val none: Boolean = false,       // 가속 개시 전 유의 거동 없음
        val tBr: Float = 0f,
        val tOn: Float = 0f,
        val dur: Float = 0f,
        val vPk: Float = 0f,
        val xPk: Float = 0f,
        val aPk: Float = 0f,
        val sd: Float = 0f,
        val xNoise: Float = 0f,
        val travel: Float = 0f,
        val dirSame: Boolean = false,
        val valid: Boolean = false,
        val trT: FloatArray = FloatArray(0),
        val trA: FloatArray = FloatArray(0),
        val trV: FloatArray = FloatArray(0),
        val trX: FloatArray = FloatArray(0)
    )

    fun dirText(q: Result): String {
        if (q.xPk == 0f) return "—"
        val d = if (q.xPk > 0f) "상승" else "하강"
        if (q.travel == 0f) return d
        return d + if (q.dirSame) " (주행방향과 동일)" else " (주행방향과 반대)"
    }

    fun calc(r: Run): Result? {
        val S = r.samples
        if (S.size < 128) return null
        val dur = (S[S.size - 1].t - S[0].t).toDouble()
        if (dur <= 0.0) return null
        val fs = (S.size - 1) / dur
        val aRaw = DoubleArray(S.size) { S[it].ar.toDouble() }

        // 1 Hz 무지연 필터는 0.3 s 내외의 짧은 롤백 펄스를 크게 감쇠시키므로 의도된 가속만 남는다.
        val a1 = Iso.lowpass(aRaw, 1.0, fs)
        var iOnA1 = -1
        for (i in a1.indices) if (abs(a1[i]) > 0.1) { iOnA1 = i; break }
        if (iOnA1 < 0) return null

        val q1 = min(iOnA1 - Math.round(0.2 * fs).toInt(), Math.round(1.0 * fs).toInt())
        if (q1 < Math.round(0.4 * fs).toInt()) return Result(short = true)

        var b0 = 0.0
        for (i in 0 until q1) b0 += aRaw[i]
        b0 /= q1
        var sd = 0.0
        for (i in 0 until q1) sd += (aRaw[i] - b0) * (aRaw[i] - b0)
        sd = sqrt(sd / q1)

        // 30 ms 중심 이동평균 (시점 판정용)
        val w = max(1, Math.round(0.015 * fs).toInt())
        val sm = DoubleArray(S.size)
        for (i in S.indices) {
            var acc = 0.0; var n = 0
            for (k in max(0, i - w)..min(S.size - 1, i + w)) { acc += aRaw[k] - b0; n++ }
            sm[i] = acc / n
        }
        val smSd = sd / sqrt((2 * w + 1).toDouble())

        // 의도된 가속 개시: a1 판정점에서 뒤로 되짚어 조용해지는 지점
        val thrOn = max(0.05, 6 * smSd)
        var iOn = iOnA1
        while (iOn > q1 && abs(sm[iOn]) > thrOn) iOn--

        val thrBr = max(4 * smSd, 0.01)
        var iBr = -1
        for (i in q1 until iOn) if (abs(sm[i]) > thrBr) { iBr = i; break }
        if (iBr < 0) return Result(none = true, sd = sd.toFloat(), tOn = S[iOn].t - S[0].t)

        var v = 0.0; var x = 0.0; var vPk = 0.0; var xPk = 0.0; var xSgn = 0.0
        for (i in iBr + 1..iOn) {
            val dt = (S[i].t - S[i - 1].t).toDouble()
            if (dt <= 0.0 || dt > 0.2) continue
            v += ((aRaw[i] - b0) + (aRaw[i - 1] - b0)) / 2.0 * dt
            x += v * dt
            if (abs(v) > abs(vPk)) vPk = v
            if (abs(x) > abs(xPk)) { xPk = x; xSgn = sign(x) }
        }
        val T = (S[iOn].t - S[iBr].t).toDouble()
        val travel = sign(S[S.size - 1].s)
        // 변위 오차: 잔류 바이어스 항 + 백색잡음 적분 항
        val seBias = sd / sqrt(q1.toDouble())
        val xNoise = 0.5 * seBias * T * T + sd * sqrt(T / fs) * T / sqrt(3.0)
        var aPk = 0.0
        for (i in iBr..iOn) if (abs(sm[i]) > abs(aPk)) aPk = sm[i]

        // 그래프용 트레이스: 개방 0.5 s 전 ~ 가속 개시 0.25 s 후
        val w0 = max(0, iBr - Math.round(0.5 * fs).toInt())
        val w1 = min(S.size - 1, iOn + Math.round(0.25 * fs).toInt())
        val n = w1 - w0 + 1
        val trT = FloatArray(n); val trA = FloatArray(n); val trV = FloatArray(n); val trX = FloatArray(n)
        var vv = 0.0; var xx = 0.0
        for (i in w0..w1) {
            if (i > w0) {
                val dt = (S[i].t - S[i - 1].t).toDouble()
                if (dt > 0.0 && dt < 0.2) {
                    vv += ((aRaw[i] - b0) + (aRaw[i - 1] - b0)) / 2.0 * dt
                    xx += vv * dt
                }
            }
            val k = i - w0
            trT[k] = S[i].t - S[0].t
            trA[k] = sm[i].toFloat()
            trV[k] = (vv * 1000).toFloat()
            trX[k] = (xx * 1000).toFloat()
        }
        return Result(
            trT = trT, trA = trA, trV = trV, trX = trX,
            tBr = S[iBr].t - S[0].t, tOn = S[iOn].t - S[0].t, dur = T.toFloat(),
            vPk = vPk.toFloat(), xPk = xPk.toFloat(), aPk = aPk.toFloat(),
            sd = sd.toFloat(), xNoise = xNoise.toFloat(), travel = travel,
            dirSame = travel != 0f && xSgn.toFloat() == travel,
            valid = abs(xPk) > 3 * xNoise
        )
    }
}
