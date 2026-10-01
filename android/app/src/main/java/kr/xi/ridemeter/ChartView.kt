package kr.xi.ridemeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class ChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var runData: Run? = null
    var spec: Spec? = null
    var fftMode = false
    var showA = true
    var showV = true
    var showJ = true
    var showR = false
    var showX = false
    var showY = false
    var title = ""

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#2B3648")
    }
    private val pDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#3F4D63")
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8D9AB0"); textSize = 26f
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7D6E") }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val padL = 14f; val padR = 14f; val padT = 14f; val padB = 34f
        pText.textSize = if (width > height * 1.6f) 22f else 26f
        val cw = w - padL - padR; val ch = h - padT - padB

        if (fftMode) { drawFft(c, padL, padT, cw, ch, h, w, padR); return }

        val mid = padT + ch / 2f
        c.drawLine(padL, mid, padL + cw, mid, pGrid)
        val r = runData
        if (r == null || r.samples.size < 2) {
            pText.textAlign = Paint.Align.CENTER
            c.drawText(if (title.isEmpty()) "데이터 없음" else "$title 데이터 없음", w / 2f, mid, pText)
            pText.textAlign = Paint.Align.LEFT
            return
        }
        val S = r.samples
        val T = max(S[S.size - 1].t, 5f)
        var am = 0.5f; var vm = 0.5f; var jm = 0.5f; var hm = 0.2f
        for (p in S) {
            am = max(am, abs(p.a)); am = max(am, abs(p.ar))
            vm = max(vm, abs(p.v)); jm = max(jm, abs(p.j))
            hm = max(hm, abs(p.x)); hm = max(hm, abs(p.y))
        }
        if (r.done) {
            val sg = Dsp.segments(S)
            if (sg != null) {
                for (i in intArrayOf(sg.a0, sg.a1, sg.d0, sg.d1)) {
                    val x = padL + S[i].t / T * cw
                    c.drawLine(x, padT, x, padT + ch, pDash)
                }
                pText.textAlign = Paint.Align.CENTER
                c.drawText("가속", padL + (S[sg.a0].t + S[sg.a1].t) / 2f / T * cw, padT + 24f, pText)
                c.drawText("등속", padL + (S[sg.c0].t + S[sg.c1].t) / 2f / T * cw, padT + 24f, pText)
                c.drawText("감속", padL + (S[sg.d0].t + S[sg.d1].t) / 2f / T * cw, padT + 24f, pText)
                pText.textAlign = Paint.Align.LEFT
            }
        }
        val stepN = max(1, S.size / 900)
        if (showR) trace(c, S, stepN, 3, am * 1.1f, "#5D6B82", padL, mid, cw, ch, T)
        if (showX) trace(c, S, stepN, 4, hm * 1.1f, "#9B8CFF", padL, mid, cw, ch, T)
        if (showY) trace(c, S, stepN, 5, hm * 1.1f, "#63D39A", padL, mid, cw, ch, T)
        if (showJ) trace(c, S, stepN, 2, jm * 1.1f, "#FF7D6E", padL, mid, cw, ch, T)
        if (showV) trace(c, S, stepN, 1, vm * 1.1f, "#5CC8FF", padL, mid, cw, ch, T)
        if (showA) trace(c, S, stepN, 0, am * 1.1f, "#F2B33D", padL, mid, cw, ch, T)

        pText.textAlign = Paint.Align.LEFT
        c.drawText("$title  0 – %.0f s".format(T), padL, h - 8f, pText)
        pText.textAlign = Paint.Align.RIGHT
        val sc = StringBuilder()
        if (showA || showR) sc.append("±%.2f m/s²  ".format(am))
        if (showV) sc.append("±%.2f m/s  ".format(vm))
        if (showJ) sc.append("±%.1f m/s³  ".format(jm))
        if (showX || showY) sc.append("수평 ±%.3f".format(hm))
        c.drawText(sc.toString(), w - padR, h - 8f, pText)
        pText.textAlign = Paint.Align.LEFT
    }

    private fun trace(
        c: Canvas, S: List<Smp>, step: Int, which: Int, scale: Float, col: String,
        padL: Float, mid: Float, cw: Float, ch: Float, T: Float
    ) {
        pLine.color = Color.parseColor(col)
        var px = 0f; var py = 0f; var first = true
        var i = 0
        while (i < S.size) {
            val p = S[i]
            val vv = when (which) { 0 -> p.a; 1 -> p.v; 2 -> p.j; 3 -> p.ar; 4 -> p.x; else -> p.y }
            val x = padL + p.t / T * cw
            val y = mid - vv / scale * (ch / 2f)
            if (!first) c.drawLine(px, py, x, y, pLine)
            px = x; py = y; first = false
            i += step
        }
    }

    private fun drawFft(
        c: Canvas, padL: Float, padT: Float, cw: Float, ch: Float,
        h: Float, w: Float, padR: Float
    ) {
        val sp = spec
        if (sp == null) {
            pText.textAlign = Paint.Align.CENTER
            c.drawText("스펙트럼 없음 (기록 완료 후 표시)", w / 2f, padT + ch / 2f, pText)
            pText.textAlign = Paint.Align.LEFT
            return
        }
        val fmax = sp.fs / 2.0
        val base = padT + ch
        var am = 0.0
        for (k in 1 until sp.amp.size) am = max(am, sp.amp[k])
        if (am <= 0.0) am = 1.0
        val stepF = when { fmax > 150 -> 50.0; fmax > 80 -> 25.0; fmax > 40 -> 10.0; else -> 5.0 }
        var f = stepF
        pText.textAlign = Paint.Align.CENTER
        while (f < fmax) {
            val x = padL + (f / fmax * cw).toFloat()
            c.drawLine(x, padT, x, base, pGrid)
            c.drawText("%.0f".format(f), x, h - 8f, pText)
            f += stepF
        }
        pText.textAlign = Paint.Align.LEFT
        pLine.color = Color.parseColor("#F2B33D")
        var px = 0f; var py = 0f; var first = true
        for (k in 1 until sp.amp.size) {
            val x = padL + (k * sp.df / fmax * cw).toFloat()
            val y = base - (sp.amp[k] / am * ch).toFloat()
            if (!first) c.drawLine(px, py, x, y, pLine)
            px = x; py = y; first = false
        }
        for (i in sp.peaks.indices) {
            if (i >= 3) break
            val p = sp.peaks[i]
            val x = padL + (p[0] / fmax * cw).toFloat()
            val y = base - (p[1] / am * ch).toFloat()
            c.drawCircle(x, y, 6f, pDot)
        }
        pText.textAlign = Paint.Align.RIGHT
        c.drawText(
            "%s · %s%s %.1fs %d샘플 @%.0fHz  Δf %.2fHz".format(
                sp.axisName, sp.segName, if (sp.fallback) "(대체)" else "", sp.dur, sp.n, sp.fs, sp.df
            ), w - padR, padT + 24f, pText
        )
        c.drawText("peak %.4f m/s²".format(am), w - padR, h - 8f, pText)
        pText.textAlign = Paint.Align.LEFT
        c.drawText("Hz", padL, h - 8f, pText)
    }
}
