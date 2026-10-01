package kr.xi.ridemeter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Report {

    class Meta(
        val site: String,
        val unit: String,
        val spec: String,
        val by: String,
        val addr: String,
        val lat: Double,
        val lon: Double,
        val acc: Double,
        val hasGeo: Boolean
    )

    private fun esc(t: String): String = t
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun f(x: Float) = "%.2f".format(x)

    private fun svgChart(S: List<Smp>, evs: List<VibEvent.Ev> = emptyList()): String {
        val w = 760f; val h = 240f
        val l = 46f; val r = 10f; val t = 12f; val b = 26f
        val cw = w - l - r; val ch = h - t - b; val mid = t + ch / 2f
        val dur = max(S[S.size - 1].t, 0.001f)
        var am = 0.5f; var vm = 0.5f; var jm = 0.5f
        for (p in S) { am = max(am, abs(p.a)); vm = max(vm, abs(p.v)); jm = max(jm, abs(p.j)) }
        val step = max(1, S.size / 700)
        val sb = StringBuilder()
        sb.append("""<line x1="$l" y1="$mid" x2="${l + cw}" y2="$mid" stroke="#c8ceda"/>""")
        val gstep = if (dur > 30) 10f else if (dur > 12) 5f else 2f
        var g = 0f
        while (g <= dur) {
            val x = l + g / dur * cw
            sb.append("""<line x1="$x" y1="$t" x2="$x" y2="${t + ch}" stroke="#eceff4"/>""")
            sb.append("""<text x="$x" y="${h - 8}" font-size="10" fill="#6b7383" text-anchor="middle">%.0f</text>""".format(g))
            g += gstep
        }
        val sg = Dsp.segments(S)
        if (sg != null) {
            for (i in intArrayOf(sg.a0, sg.a1, sg.d0, sg.d1)) {
                val x = l + S[i].t / dur * cw
                sb.append("""<line x1="$x" y1="$t" x2="$x" y2="${t + ch}" stroke="#9aa4b5" stroke-dasharray="3 3"/>""")
            }
            fun lab(i0: Int, i1: Int, s: String) {
                val x = l + (S[i0].t + S[i1].t) / 2f / dur * cw
                sb.append("""<text x="$x" y="${t + 12}" font-size="10" fill="#6b7383" text-anchor="middle">$s</text>""")
            }
            lab(sg.a0, sg.a1, "가속"); lab(sg.c0, sg.c1, "등속"); lab(sg.d0, sg.d1, "감속")
        }
        fun poly(which: Int, scale: Float, col: String) {
            val pts = StringBuilder()
            var i = 0
            while (i < S.size) {
                val p = S[i]
                val vv = when (which) { 0 -> p.a; 1 -> p.v; else -> p.j }
                pts.append("%.1f,%.1f ".format(l + p.t / dur * cw, mid - vv / (scale * 1.1f) * (ch / 2f)))
                i += step
            }
            sb.append("""<polyline fill="none" stroke="$col" stroke-width="1.4" points="$pts"/>""")
        }
        poly(2, jm, "#d9534f"); poly(1, vm, "#2b7fc4"); poly(0, am, "#d99a1a")
        for ((n, e) in evs.withIndex()) {
            val ex = l + e.t / dur * cw
            sb.append("""<line x1="$ex" y1="$t" x2="$ex" y2="${t + ch}" stroke="#b03a2e" stroke-width="0.7" stroke-dasharray="2 3"/>""")
            sb.append("""<text x="$ex" y="${t + ch + 11}" font-size="9" fill="#b03a2e" text-anchor="middle">${n + 1}</text>""")
        }
        sb.append("""<text x="$l" y="${h - 8}" font-size="10" fill="#6b7383">s</text>""")
        sb.append("""<text x="${l + cw}" y="${h - 8}" font-size="10" fill="#6b7383" text-anchor="end">a ±${f(am)} m/s² · v ±${f(vm)} m/s · j ±%.1f m/s³</text>""".format(jm))
        return """<svg viewBox="0 0 ${w.toInt()} ${h.toInt()}" width="100%" xmlns="http://www.w3.org/2000/svg">$sb</svg>"""
    }

    /** EVA-625 형식 3축 진동 파형 — ISO 2631-1 가중(Wk/Wd), 단위 milli(g) */
    private fun svgEva(r: Run): String {
        val S = r.samples
        if (S.size < 64) return ""
        val dur = (S[S.size - 1].t - S[0].t).toDouble()
        if (dur <= 0.0) return ""
        val fs = (S.size - 1) / dur
        val seg = Dsp.segments(S)
        val c0 = seg?.c0 ?: 0
        val c1 = seg?.c1 ?: (S.size - 1)

        val sx = Wbv.apply(DoubleArray(S.size) { S[it].xr * Wbv.MG }, fs, false)
        val sy = Wbv.apply(DoubleArray(S.size) { S[it].yr * Wbv.MG }, fs, false)
        val sz = Wbv.apply(DoubleArray(S.size) { S[it].ar * Wbv.MG }, fs, true)
        val px = Wbv.ppList(sx); val py = Wbv.ppList(sy); val pz = Wbv.ppList(sz)

        val jzList = ArrayList<Double>()
        for (i in S.indices) if (i < c0 || i > c1) jzList.add(sz[i])
        val jzMax = if (jzList.size > 16) Wbv.ppList(jzList.toDoubleArray()).max else 0f

        var vMax = 0f
        val va = ArrayList<Float>(S.size)
        for (p in S) { vMax = max(vMax, abs(p.v)); va.add(abs(p.v)) }
        va.sort()
        val v95 = va[(0.95 * (va.size - 1)).toInt()]

        val w = 900f; val l = 74f; val rp = 52f; val top = 6f; val bot = 30f
        val ph = floatArrayOf(104f, 104f, 104f, 124f)
        val h = top + ph.sum() + bot
        val cw = w - l - rp
        fun x(t: Float) = l + (t / dur * cw).toFloat()
        val stepN = max(1, S.size / 1100)
        val sb = StringBuilder()
        var y0 = top

        fun poly(arr: DoubleArray, yf: (Double) -> Float, col: String, from: Int, to: Int) {
            val pts = StringBuilder()
            var i = from
            while (i <= to) { pts.append("%.1f,%.1f ".format(x(S[i].t), yf(arr[i]))); i += stepN }
            sb.append("""<polyline fill="none" stroke="$col" stroke-width="0.9" points="$pts"/>""")
        }
        fun cross(yf: (Double) -> Float, i: Int, v: Double) {
            val cx = x(S[i].t); val cy = yf(v)
            sb.append("""<line x1="${cx - 9}" y1="$cy" x2="${cx + 9}" y2="$cy" stroke="#000" stroke-width="0.8"/>""")
            sb.append("""<line x1="$cx" y1="${cy - 12}" x2="$cx" y2="${cy + 12}" stroke="#000" stroke-width="0.8"/>""")
        }
        fun panel(hh: Float, label: String, range: Float, head: String, headR: String,
                  body: ((Double) -> Float, Float) -> Unit) {
            sb.append("""<rect x="$l" y="$y0" width="$cw" height="$hh" fill="#fff" stroke="#000" stroke-width="0.8"/>""")
            val mid = y0 + hh / 2f
            val yf = { v: Double -> (mid - v / range * (hh / 2f)).toFloat() }
            val ticks = if (range >= 20f) listOf(-range, -range / 2, 0f, range / 2, range)
                else listOf(-range, -range / 2, 0f, range / 2, range)
            for (tv in ticks) {
                val yy = yf(tv.toDouble())
                if (tv != 0f && abs(tv) == range / 2f) {
                    sb.append("""<line x1="$l" y1="$yy" x2="${l + cw}" y2="$yy" stroke="#000" stroke-dasharray="4 4" stroke-width="0.6"/>""")
                    sb.append("""<text x="${l + cw + 4}" y="${yy + 4}" font-size="11" fill="#000">%.1f</text>""".format(tv))
                }
                sb.append("""<text x="${l - 6}" y="${yy + 4}" font-size="11" fill="#000" text-anchor="end">%.0f</text>""".format(tv))
            }
            val yz = yf(0.0)
            sb.append("""<line x1="$l" y1="$yz" x2="${l + cw}" y2="$yz" stroke="#888" stroke-width="0.5"/>""")
            if (seg != null) for (ci in intArrayOf(c0, c1)) {
                val xx = x(S[ci].t)
                sb.append("""<line x1="$xx" y1="$y0" x2="$xx" y2="${y0 + hh}" stroke="#000" stroke-dasharray="3 3" stroke-width="0.6"/>""")
            }
            body(yf, hh)
            if (head.isNotEmpty()) sb.append("""<text x="${l + 8}" y="${y0 + 16}" font-size="12" fill="#000">$head</text>""")
            if (headR.isNotEmpty()) sb.append("""<text x="${l + cw - 8}" y="${y0 + 16}" font-size="12" fill="#000" text-anchor="end">$headR</text>""")
            sb.append("""<text transform="translate(16,$mid) rotate(-90)" font-size="13" text-anchor="middle" fill="#000">$label</text>""")
            y0 += hh
        }
        fun rng(zp: Float, base: Float): Float { var r2 = base; while (zp * 1.1f > r2) r2 *= 2f; return r2 }

        panel(ph[0], "Z Velocity m/s", max(0.5f, vMax * 1.25f),
            "Max Velocity: %.2f   V95: %.2f".format(vMax, v95), "") { yf, _ ->
            val pts = StringBuilder()
            var i = 0
            while (i < S.size) { pts.append("%.1f,%.1f ".format(x(S[i].t), yf(S[i].v.toDouble()))); i += stepN }
            sb.append("""<polyline fill="none" stroke="#0000cd" stroke-width="1.1" points="$pts"/>""")
        }
        panel(ph[1], "ISO X", rng(px.zp, 10f),
            "Max Pk/Pk: %.1f   A95: %.1f   0-Pk: %.1f".format(px.max, px.a95, px.zp), "") { yf, _ ->
            poly(sx, yf, "#00008b", 0, S.size - 1); cross(yf, px.iMax, sx[px.iMax])
        }
        panel(ph[2], "ISO Y", rng(py.zp, 10f),
            "Max Pk/Pk: %.1f   A95: %.1f   0-Pk: %.1f".format(py.max, py.a95, py.zp), "") { yf, _ ->
            poly(sy, yf, "#8b0000", 0, S.size - 1); cross(yf, py.iMax, sy[py.iMax])
        }
        panel(ph[3], "ISO Z", rng(pz.zp, 20f),
            "Max Pk/Pk: %.1f   A95: %.1f   0-Pk: %.1f".format(pz.max, pz.a95, pz.zp),
            if (jzMax > 0f) "Jerk Zone Max Pk/Pk: %.1f".format(jzMax) else "") { yf, hh ->
            poly(sz, yf, "#20b2aa", 0, max(0, c0))
            poly(sz, yf, "#00008b", max(0, c0), c1)
            poly(sz, yf, "#20b2aa", c1, S.size - 1)
            cross(yf, pz.iMax, sz[pz.iMax])
            if (seg != null) {
                sb.append("""<text x="${x(S[c0].t) + 4}" y="${y0 + hh - 6}" font-size="12" fill="#000">1</text>""")
                sb.append("""<text x="${x(S[c1].t) + 4}" y="${y0 + hh - 6}" font-size="12" fill="#000">2</text>""")
            }
        }
        val gs = if (dur > 12) 5f else 2f
        var t = 0f
        while (t <= dur) {
            sb.append("""<text x="${x(t)}" y="${h - 12}" font-size="12" fill="#000" text-anchor="middle">%.0f</text>""".format(t))
            t += gs
        }
        sb.append("""<text x="${l + cw / 2}" y="${h - 1}" font-size="12" fill="#000" text-anchor="middle">Seconds</text>""")
        sb.append("""<text x="$l" y="$top" font-size="11" fill="#000">Units: milli(g)</text>""")
        return """<svg viewBox="0 0 900 ${h.toInt()}" width="100%" xmlns="http://www.w3.org/2000/svg" style="background:#fff">$sb</svg>"""
    }

    private fun svgRollback(q: Rollback.Result): String {
        val tr = q.trT
        if (tr.size < 4) return ""
        val w = 760f; val h = 210f; val l = 46f; val rp = 46f; val t = 14f; val b = 26f
        val cw = w - l - rp; val ch = h - t - b
        val t0 = tr[0]; val t1 = tr[tr.size - 1]
        val dur = max(t1 - t0, 1e-3f)
        fun x(v: Float) = l + (v - t0) / dur * cw
        var aM = 0.05f; var xM = 0.5f
        for (i in tr.indices) {
            if (tr[i] > q.tOn) break
            aM = max(aM, abs(q.trA[i]))
            xM = max(xM, max(abs(q.trX[i]), abs(q.trV[i]) / 10f))
        }
        val mid = t + ch / 2f
        fun clamp(y: Float) = max(t, min(t + ch, y))
        fun ya(v: Float) = clamp(mid - v / (aM * 1.15f) * (ch / 2f))
        fun yx(v: Float) = clamp(mid - v / (xM * 1.15f) * (ch / 2f))
        val sb = StringBuilder()
        sb.append("""<line x1="$l" y1="$mid" x2="${l + cw}" y2="$mid" stroke="#c8ceda"/>""")
        val step = if (dur > 2f) 0.5f else if (dur > 1f) 0.2f else 0.1f
        var g = Math.ceil((t0 / step).toDouble()).toFloat() * step
        while (g <= t1) {
            val gx = x(g)
            sb.append("""<line x1="$gx" y1="$t" x2="$gx" y2="${t + ch}" stroke="#eceff4"/>""")
            sb.append("""<text x="$gx" y="${h - 8}" font-size="10" fill="#6b7383" text-anchor="middle">%.1f</text>""".format(g))
            g += step
        }
        for (mk in listOf(Triple(q.tBr, "브레이크 개방", "#d9534f"), Triple(q.tOn, "가속 개시", "#2b7fc4"))) {
            val mx = x(mk.first)
            sb.append("""<line x1="$mx" y1="$t" x2="$mx" y2="${t + ch}" stroke="${mk.third}" stroke-dasharray="4 3"/>""")
            sb.append("""<text x="${mx + 3}" y="${t + 11}" font-size="10" fill="${mk.third}">${mk.second}</text>""")
        }
        fun poly(arr: FloatArray, yf: (Float) -> Float, col: String, dash: Boolean, scale: Float) {
            val pts = StringBuilder()
            for (i in tr.indices) pts.append("%.1f,%.1f ".format(x(tr[i]), yf(arr[i] * scale)))
            sb.append("""<polyline fill="none" stroke="$col" stroke-width="1.5" ${if (dash) "stroke-dasharray=\"3 2\"" else ""} points="$pts"/>""")
        }
        poly(q.trV, ::yx, "#2b7fc4", true, 0.1f)
        poly(q.trX, ::yx, "#1a9e6a", false, 1f)
        poly(q.trA, ::ya, "#d99a1a", false, 1f)
        sb.append("""<text x="4" y="${t + 10}" font-size="10" fill="#d99a1a">a ±%.2f m/s²</text>""".format(aM))
        sb.append("""<text x="${w - 4}" y="${t + 10}" font-size="10" fill="#1a9e6a" text-anchor="end">x ±%.1f mm</text>""".format(xM))
        sb.append("""<text x="${w - 4}" y="${t + 22}" font-size="10" fill="#2b7fc4" text-anchor="end">v ±%.0f mm/s</text>""".format(xM * 10f))
        sb.append("""<text x="$l" y="${h - 8}" font-size="10" fill="#6b7383">s</text>""")
        return """<svg viewBox="0 0 760 210" width="100%" xmlns="http://www.w3.org/2000/svg">$sb</svg>"""
    }

    fun build(
        order: List<String>, runs: Map<String, Run>, meta: Meta,
        fcA: String, fcJ: String, dateStr: String, noiseOffset: Float = 0f,
        spec: FreqSource.Spec = FreqSource.Spec()
    ): String {
        val qual = HashMap<String, Quality.Result>()
        for (k in order) runs[k]?.let { qual[k] = Quality.calc(it) }
        val good = order.filter { qual[it]?.verdict != "reject" }

        val sum = StringBuilder(
            "<table><tr><th>회차</th><th>a max</th><th>a min</th><th>v max</th><th>v min</th>" +
                "<th>j max</th><th>j min</th><th>주행시간</th><th>거리</th><th>fs</th><th>판정</th></tr>"
        )
        for (k in order) {
            val r = runs[k] ?: continue
            val q = qual[k]!!
            val o = Dsp.stats(r.samples)
            val p = r.samples[r.samples.size - 1]
            val col = when (q.verdict) { "reject" -> "#b03a2e"; "warn" -> "#a06a00"; else -> "#1a7a4a" }
            val rowStyle = if (q.verdict == "reject") " style=\"color:#8a8f99\"" else ""
            sum.append(
                "<tr$rowStyle><td>${name(k)}</td><td>${f(o.aMax)}</td><td>${f(o.aMin)}</td>" +
                    "<td>${f(o.vMax)}</td><td>${f(o.vMin)}</td><td>${f(o.jMax)}</td><td>${f(o.jMin)}</td>" +
                    "<td>%.1f s</td><td>${f(p.s)} m</td><td>%.0f Hz</td>".format(p.t, r.fs) +
                    "<td style=\"color:$col\">${Quality.verdictText(q.verdict)}</td></tr>"
            )
        }
        sum.append("</table>")
        val withIssues = order.filter { qual[it]?.issues?.isNotEmpty() == true }
        if (withIssues.isNotEmpty()) {
            sum.append("<p class=\"note\"><b>자동 판정 사유</b><br>")
            sum.append(withIssues.joinToString("<br>") { k ->
                val q = qual[k]!!
                "${name(k)} [${Quality.verdictText(q.verdict)}] " + q.issues.joinToString("; ") { it.msg }
            })
            sum.append("</p>")
        }
        val nRej = order.size - good.size
        if (nRej > 0) sum.append("<p class=\"note\">배제 ${nRej}건은 아래 방향별 평균에서 제외했습니다. 개별 상세는 그대로 수록합니다.</p>")

        val avg = StringBuilder("<table><tr><th>방향</th><th>회수</th><th>|v| max 평균</th><th>|a| max 평균</th><th>|j| max 평균</th><th>거리 평균</th></tr>")
        for (dir in listOf("up", "down")) {
            val g = good.filter { it.startsWith(dir) }.mapNotNull { runs[it] }
            if (g.isEmpty()) continue
            var sv = 0f; var sa = 0f; var sj = 0f; var sd = 0f
            for (r in g) {
                val o = Dsp.stats(r.samples)
                sv += max(abs(o.vMax), abs(o.vMin))
                sa += max(abs(o.aMax), abs(o.aMin))
                sj += max(abs(o.jMax), abs(o.jMin))
                sd += r.samples[r.samples.size - 1].s
            }
            val n = g.size.toFloat()
            avg.append(
                "<tr><td>${if (dir == "up") "상승" else "하강"}</td><td>${g.size}</td>" +
                    "<td>${f(sv / n)}</td><td>${f(sa / n)}</td><td>${f(sj / n)}</td><td>${f(sd / n)}</td></tr>"
            )
        }
        avg.append("</table>")

        val isoTbl = StringBuilder(
            "<table><tr><th>회차</th><th>VPPV 수직 max/A95</th><th>전후 max/A95</th><th>좌우 max/A95</th>" +
                "<th>최대 가속</th><th>최대 감속</th><th>최대 저크</th><th>최대 속도</th><th>등속 구간</th></tr>"
        )
        for (k in order) {
            val r = runs[k] ?: continue
            val q = Iso.calc(r)
            if (q == null) {
                isoTbl.append("<tr><td>${name(k)}</td><td colspan=\"8\" style=\"color:#6b7383\">산출 불가</td></tr>")
                continue
            }
            fun g(a: Float, b: Float) = "%.3f / %.3f".format(a, b)
            isoTbl.append(
                "<tr><td>${name(k)}</td><td>${g(q.z.max, q.z.a95)}</td><td>${g(q.x.max, q.x.a95)}</td>" +
                    "<td>${g(q.y.max, q.y.a95)}</td><td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%.1f s%s</td></tr>"
                        .format(q.aMax, q.dMax, q.jMax, q.vMax, q.cvDur, if (q.full) "*" else "")
            )
        }
        isoTbl.append(
            "</table><p class=\"note\">VPPV = 영교차점 사이 peak-to-peak 진동값(m/s²), 등속 구간 기준. " +
                "A95 = 전체 VPPV의 95 백분위. 최대 가속·감속은 1 Hz 2극 버터워스, 속도는 10 Hz 저역통과 신호 적분. " +
                "* 는 등속 구간을 분리하지 못해 전 구간으로 대체.</p>"
        )

        val fsTbl = StringBuilder()
        var anyFs = false
        run {
            fsTbl.append("<table><tr><th>회차</th><th>피크 주파수</th><th>진폭</th><th>파장 λ = v/f</th>" +
                "<th>가장 가까운 후보</th><th>편차</th><th>추정</th></tr>")
            for (k in order) {
                val r = runs[k] ?: continue
                val q = FreqSource.calc(r, spec) ?: continue
                anyFs = true
                for ((n, row) in q.rows.withIndex()) {
                    val first = if (n == 0) "${name(k)} (v %.2f m/s)".format(q.v) else ""
                    val bestTxt = if (row.best == null) "—" else
                        row.best.label + " (λ %.3f m)".format(row.best.lam) +
                            (if (row.ambig != null) "<br><span style=\"color:#6b7383\">구분 불가: ${row.ambig}</span>" else "")
                    fsTbl.append(
                        ("<tr><td>$first</td><td>%.2f Hz</td><td>%.3f</td><td>%.3f m</td><td>$bestTxt</td>" +
                            "<td>%.1f %%</td><td>${row.estimate}</td></tr>")
                            .format(row.f, row.amp, row.lam, row.dev * 100f)
                    )
                }
            }
            fsTbl.append("</table>")
            fsTbl.append("<p class=\"note\">판별 원리: 회전 기인 진동은 <b>로프 이동거리</b>에 대해 주기적이므로 " +
                "카 기준 파장 λ = π·D / (로핑 × 차수)로 고정되고, 레일 이음부·브래킷은 <b>카 이동거리</b>에 대해 " +
                "주기적이므로 λ = 설치 피치와 같습니다. 측정 피크의 λ = v/f 를 두 계열과 대조해 추정합니다. " +
                "허용 편차는 8 % 또는 주파수 분해능 Δf의 1.5배 중 큰 값.</p>")
            fsTbl.append(
                ("<p class=\"note\">입력 제원: 시브 직경 %s · 로핑 %.0f:1 · 감속비 %.2f · 극수 %s · 레일 %.1f m · 브래킷 %.1f m. " +
                    "시브 직경과 극수를 입력하지 않으면 회전 계열 후보가 생성되지 않아 모두 레일 계열로만 대조됩니다.</p>")
                    .format(
                        if (spec.diaM > 0f) "%.0f mm".format(spec.diaM * 1000f) else "미입력",
                        spec.roping, spec.gear,
                        if (spec.poles > 0f) "%.0f".format(spec.poles) else "미입력",
                        spec.railM, spec.bracketM
                    )
            )
        }

        val anyNz = order.any { (runs[it]?.noise?.size ?: 0) > 4 }
        val nzTbl = StringBuilder()
        if (anyNz) {
            nzTbl.append("<table><tr><th>회차</th><th>구간</th><th>Leq</th><th>Lmax(Fast)</th><th>L10</th><th>L95</th><th>샘플</th></tr>")
            for (k in order) {
                val r = runs[k] ?: continue
                if (r.noise.size <= 4) continue
                val (cv, all, hasSeg) = NoiseStats.cruise(r, noiseOffset)
                fun row(lab: String, o: NoiseStats.Stat?) {
                    if (o == null) return
                    nzTbl.append(
                        "<tr><td>${name(k)}</td><td>$lab</td><td>%.1f</td><td>%.1f</td><td>%.1f</td><td>%.1f</td><td>${o.n}</td></tr>"
                            .format(o.leq, o.lmax, o.l10, o.l95)
                    )
                }
                row(if (hasSeg) "등속" else "전체(등속 미분리)", cv)
                row("전체 주행", all)
            }
            val unit = if (noiseOffset == 0f) "dBFS(A) — 미보정 상대값"
                else "dB(A) (보정 오프셋 %.1f dB 적용)".format(noiseOffset)
            nzTbl.append(
                "</table><p class=\"note\">단위 $unit. A 특성은 IEC 61672 해석식을 FFT 빈별로 적용해 산출. " +
                    "Leq = 에너지 평균, Lmax = 125 ms(Fast) 평균의 최대, L10/L95 = 상위 10 / 하위 5 백분위. " +
                    "스마트폰 마이크는 음압 교정·자유음장 보정이 되어 있지 않으므로 절대 음압 레벨이 아니라 상대 비교용입니다.</p>"
            )
        }

        val rbTbl = StringBuilder(
            "<table><tr><th>회차</th><th>개방 시점</th><th>가속 개시</th><th>롤백 구간</th><th>최대 변위</th>" +
                "<th>최대 속도</th><th>가속도 피크</th><th>방향</th><th>판정</th></tr>"
        )
        for (k in order) {
            val r = runs[k] ?: continue
            val q = Rollback.calc(r)
            when {
                q == null -> rbTbl.append("<tr><td>${name(k)}</td><td colspan=\"8\" style=\"color:#6b7383\">산출 불가</td></tr>")
                q.short -> rbTbl.append("<tr><td>${name(k)}</td><td colspan=\"8\" style=\"color:#6b7383\">출발 전 정지 구간 부족</td></tr>")
                q.none -> rbTbl.append("<tr><td>${name(k)}</td><td colspan=\"7\" style=\"color:#6b7383\">가속 개시 전 유의 거동 없음</td><td>양호</td></tr>")
                else -> rbTbl.append(
                    ("<tr><td>${name(k)}</td><td>%.2f s</td><td>%.2f s</td><td>%.0f ms</td><td>%.1f mm</td>" +
                        "<td>%.1f mm/s</td><td>%.3f m/s²</td><td>${Rollback.dirText(q)}</td><td>%s</td></tr>")
                        .format(q.tBr, q.tOn, q.dur * 1000f, q.xPk * 1000f, q.vPk * 1000f, q.aPk,
                            if (q.valid) "유의" else "잡음 이내 (±%.1f mm)".format(q.xNoise * 1000f))
                )
            }
        }
        rbTbl.append(
            "</table><p class=\"note\">브레이크 개방 시점 = 정지 구간 잡음의 4σ를 처음 초과한 시점, " +
                "가속 개시 = 1 Hz 필터 가속도가 0.1 m/s²를 처음 초과한 뒤 30 ms 이동평균으로 되짚은 시점. " +
                "두 시점 사이를 정지 구간 평균으로 바이어스 보정한 뒤 적분해 변위·속도를 산출. 부호 +는 상승 방향. " +
                "잡음 한계는 잔류 바이어스 항(0.5·SE·T²)과 백색잡음 적분 항(σ·√(T/fs)·T/√3)의 합으로 추정했으며, " +
                "이를 3배 이상 초과할 때만 유의로 표기.</p>"
        )

        val rbFig = StringBuilder()
        for (k in order) {
            val r = runs[k] ?: continue
            val q = Rollback.calc(r) ?: continue
            if (q.short || q.none || q.trT.isEmpty()) continue
            rbFig.append("<h4 style=\"font-size:13px;margin:14px 0 2px;color:#42506b\">${name(k)} — 개방 구간 확대</h4>")
            rbFig.append(svgRollback(q))
            rbFig.append(
                "<p class=\"note\">변위 %.1f mm · 속도 %.1f mm/s · 구간 %.0f ms · ${Rollback.dirText(q)} · %s</p>"
                    .format(q.xPk * 1000f, q.vPk * 1000f, q.dur * 1000f,
                        if (q.valid) "유의" else "잡음 이내 (±%.1f mm)".format(q.xNoise * 1000f))
            )
        }

        val body = StringBuilder()
        for (k in order) {
            val r = runs[k] ?: continue
            val S = r.samples
            val o = Dsp.stats(S)
            val p = S[S.size - 1]
            val hx = Dsp.axStats(S, "x")
            val hy = Dsp.axStats(S, "y")
            val jxs = Dsp.axStats(S, "jx")
            val jys = Dsp.axStats(S, "jy")
            val fft = StringBuilder("<table><tr><th>축 · 구간</th><th>길이</th><th>fs</th><th>Δf</th><th>피크 1</th><th>피크 2</th><th>피크 3</th></tr>")
            for (axk in listOf("ar", "xr", "yr")) for (wseg in listOf("acc", "const", "dec")) {
                val sp = Dsp.spectrum(r, wseg, axk) ?: continue
                if (axk != "ar" && sp.peaks.isEmpty()) continue
                fun pk(i: Int): String =
                    if (sp.peaks.size > i) "%.2f Hz / %.4f".format(sp.peaks[i][0], sp.peaks[i][1]) else "—"
                fft.append(
                    "<tr><td>${sp.axisName} · ${sp.segName}${if (sp.fallback) "*" else ""}</td>" +
                        "<td>%.1f s</td><td>%.0f Hz</td><td>%.2f Hz</td>".format(sp.dur, sp.fs, sp.df) +
                        "<td>${pk(0)}</td><td>${pk(1)}</td><td>${pk(2)}</td></tr>"
                )
            }
            fft.append("</table><p class=\"note\">피크 = 주파수 / 진폭(m/s²), 원시 가속도 기준. * 는 구간 샘플 부족으로 전체 구간 대체.</p>")
            val ve = VibEvent.calc(r)
            val evList = ve?.events ?: emptyList()
            val evTbl = StringBuilder()
            if (ve != null) {
                if (evList.isEmpty()) {
                    evTbl.append("<p class=\"note\">등속 구간에서 임계값을 넘는 진동 이벤트가 검출되지 않았습니다.</p>")
                } else {
                    evTbl.append(
                        "<p class=\"note\"><b>진동 이벤트 위치</b> — 등속 구간${if (ve.cvSeg) "" else "(전체 대체)"} " +
                            "1 Hz 이상 성분이 임계값(축별 robust σ(MAD)의 6배, 최소 0.08 m/s²)을 넘는 지점. 그래프 아래 번호와 대응.</p>"
                    )
                    evTbl.append("<table><tr><th>#</th><th>위치 (m)</th><th>시각 (s)</th><th>축</th><th>피크 (m/s²)</th><th>임계 대비</th></tr>")
                    for ((n, e) in evList.withIndex())
                        evTbl.append("<tr><td>${n + 1}</td><td>%.2f</td><td>%.2f</td><td>${e.axis}</td><td>%.3f</td><td>%.1f배</td></tr>"
                            .format(e.s, e.t, e.amp, e.ratio))
                    evTbl.append("</table>")
                    ve.spacing?.let {
                        evTbl.append("<p class=\"note\">이벤트 간 거리 중앙값 %.2f m · 평균 %.2f m (${it.n}구간). 가이드레일 이음부 피치와 대조해 보십시오.</p>"
                            .format(it.med, it.mean))
                    }
                    evTbl.append("<p class=\"note\">임계값 수직 %.3f · 전후 %.3f · 좌우 %.3f m/s² (등속 구간 robust σ 수직 %.3f · 전후 %.3f · 좌우 %.3f)</p>"
                        .format(ve.thr["ar"], ve.thr["xr"], ve.thr["yr"], ve.sigma["ar"], ve.sigma["xr"], ve.sigma["yr"]))
                }
            }
            val qq = qual[k]!!
            val qCol = when (qq.verdict) { "reject" -> "#b03a2e"; "warn" -> "#a06a00"; else -> "#1a7a4a" }
            val qNote = if (qq.issues.isEmpty()) "" else
                "<p class=\"note\">" + qq.issues.joinToString(" · ") { it.msg } + "</p>"
            val vib3 = "<h4 style=\"font-size:13px;margin:14px 0 2px;color:#42506b\">3축 진동 파형 (ISO 2631-1 가중, milli-g)</h4>" +
                svgEva(r) +
                "<p class=\"note\">ISO 2631-1 전신진동 가중 적용 — 수직 z는 Wk, 수평 x·y는 Wd. 단위 milli(g), 1 mg = 0.00981 m/s². " +
                "Max Pk/Pk는 영교차점 사이 peak-to-peak의 최대, A95는 그 95 백분위, 0-Pk는 최대 절댓값. " +
                "점선 1·2는 등속 구간 경계이며 Z축의 청록 구간이 가감속(저크) 구간입니다. " +
                "가중 필터는 쌍선형 변환(pre-warping 적용) 후 인과 1회 적용했고, 샘플링 200 Hz에서 규격 표 대비 편차는 25 Hz까지 1.0 dB 이내입니다.</p>"
            body.append(
                "<section><h3>${name(k)} <span style=\"font-size:12px;font-weight:400;color:$qCol\">" +
                    "[${Quality.verdictText(qq.verdict)}]</span></h3>$qNote${svgChart(S, evList)}" +
                    "<table><tr><th>항목</th><th>max</th><th>min</th></tr>" +
                    "<tr><td>가속도 (m/s²)</td><td>${f(o.aMax)}</td><td>${f(o.aMin)}</td></tr>" +
                    "<tr><td>속도 (m/s)</td><td>${f(o.vMax)}</td><td>${f(o.vMin)}</td></tr>" +
                    "<tr><td>저크 (m/s³)</td><td>${f(o.jMax)}</td><td>${f(o.jMin)}</td></tr>" +
                    hx.let { "<tr><td>수평 전후 (m/s²)</td><td>${f(it.mx)}</td><td>${f(it.mn)}</td></tr>" } +
                    hy.let { "<tr><td>수평 좌우 (m/s²)</td><td>${f(it.mx)}</td><td>${f(it.mn)}</td></tr>" } +
                    "<tr><td>저크 전후 (m/s³)</td><td>${f(jxs.mx)}</td><td>${f(jxs.mn)}</td></tr>" +
                    "<tr><td>저크 좌우 (m/s³)</td><td>${f(jys.mx)}</td><td>${f(jys.mn)}</td></tr></table>" +
                    "<p class=\"note\">수평 진동 전후 p-p ${f(hx.pp)} · rms ${f(hx.rms)} / 좌우 p-p ${f(hy.pp)} · rms ${f(hy.rms)} m/s²</p>" +
                    "<p class=\"note\">주행 시간 %.1f s · 이동 거리 ${f(p.s)} m · 샘플 ${S.size} @ %.0f Hz · 센서 ${esc(r.sensorName)} · 드리프트 보정 %.1f mm/s²</p>"
                        .format(p.t, r.fs, r.bias * 1000f) + vib3 + evTbl + fft + "</section>"
            )
        }

        val geoLine = if (meta.hasGeo)
            "위치 %.6f, %.6f (±%.0f m) · <a href=\"https://www.google.com/maps?q=%.6f,%.6f\">지도</a><br>"
                .format(meta.lat, meta.lon, meta.acc, meta.lat, meta.lon)
        else ""

        return """<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>승강기 주행 계측 리포트</title><style>
body{font-family:system-ui,"Noto Sans KR",sans-serif;color:#1b2029;max-width:840px;margin:0 auto;padding:24px 18px 60px;line-height:1.55}
h1{font-size:21px;margin:0 0 4px}
h2{font-size:15px;margin:22px 0 6px;color:#42506b}
h3{font-size:16px;margin:26px 0 8px;padding-bottom:4px;border-bottom:2px solid #1b2029}
table{width:100%;border-collapse:collapse;font-size:12.5px;margin:6px 0 4px}
th,td{border:1px solid #d5dae3;padding:5px 7px;text-align:right}
th{background:#f2f4f8;font-weight:600}
td:first-child,th:first-child{text-align:left}
.note{font-size:11.5px;color:#6b7383;margin:4px 0 0}
.hdr{font-size:12.5px;color:#42506b;margin:0 0 14px}
svg{border:1px solid #e3e7ee;border-radius:6px;background:#fff;margin:4px 0}
.lg{font-size:11.5px;color:#6b7383}
.lg i{display:inline-block;width:13px;height:3px;vertical-align:middle;margin:0 4px 0 10px}
@media print{section{break-inside:avoid}}
</style></head><body>
<h1>승강기 주행 계측 리포트</h1>
<p class="hdr">현장 ${esc(meta.site).ifEmpty { "—" }} · 호기/기종 ${esc(meta.unit).ifEmpty { "—" }} · 사양 ${esc(meta.spec).ifEmpty { "—" }}<br>
${if (meta.addr.isNotEmpty()) "주소 " + esc(meta.addr) + "<br>" else ""}$geoLine
측정자 ${esc(meta.by).ifEmpty { "—" }} · 작성 ${esc(dateStr)}</p>
<h2>1. 전 회차 측정값</h2>$sum
<h2>2. 방향별 평균 (배제 건 제외)</h2>$avg
<h2>3. ISO 18738 산출값 (참고)</h2>$isoTbl
${if (anyFs) "<h2>4. 주파수 원인 추정 (참고)</h2>$fsTbl" else ""}
${if (anyNz) "<h2>${if (anyFs) 5 else 4}. 소음 측정 (참고)</h2>$nzTbl" else ""}
<h2>${4 + (if (anyFs) 1 else 0) + (if (anyNz) 1 else 0)}. 안티롤백 분석 (참고)</h2>$rbTbl
<p class="lg"><i style="background:#d99a1a"></i>가속도<i style="background:#1a9e6a"></i>변위(mm)<i style="background:#2b7fc4"></i>속도(mm/s, 1/10 스케일)</p>
$rbFig
<h2>${5 + (if (anyFs) 1 else 0) + (if (anyNz) 1 else 0)}. 회차별 상세</h2>
<p class="lg"><i style="background:#d99a1a"></i>가속도<i style="background:#2b7fc4"></i>속도<i style="background:#d9534f"></i>저크</p>
$body
<p class="note" style="margin-top:22px">측정: 스마트폰 3축 가속도계, 정지 시 중력벡터에 투영한 연직 성분.
가속도 $fcA Hz / 저크 $fcJ Hz 2단 저역통과. 속도·거리는 사다리꼴 적분 후 종료 시 v=0 조건으로 선형 드리프트 보정.
FFT는 필터 전 원시 가속도에 Hann 윈도우 적용.
수평 2축은 보정 시 정한 수평면 직교 기저에 투영(전후 = 기기 세로축의 수평 성분, 좌우 = 그 직교축).
ISO 18738 항목은 규격의 산출 절차(영교차점 VPPV, 1 Hz 버터워스 구간 판정, 10 Hz 적분 속도)를 따르되 주파수 가중은 적용하지 않았고,
스마트폰 센서로 측정했으므로 규격의 계측기 요구사항은 충족하지 않습니다. 참고값입니다.</p>
</body></html>"""
    }

    fun name(key: String): String {
        val dir = if (key.startsWith("up")) "상승" else "하강"
        val n = key.filter { it.isDigit() }
        return "$dir ${n}회"
    }
}
