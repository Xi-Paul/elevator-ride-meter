package kr.xi.ridemeter

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    // ---- 상태 ----
    private val runs = HashMap<String, Run>()
    private var dir = "up"
    private var trial = 1
    private var nTrials = 3
    private var recKey: String? = null

    private lateinit var sm: SensorManager
    private var sensor: Sensor? = null
    private var sensorLabel = ""

    private var calib = true
    private var calibN = 0
    private var calibX = 0.0; private var calibY = 0.0; private var calibZ = 0.0
    private var calibT0 = 0L
    private var gx = 0f; private var gy = 0f; private var gz = 0f; private var gMag = 9.81f
    private var h1x = 0f; private var h1y = 0f; private var h1z = 0f
    private var h2x = 0f; private var h2y = 0f; private var h2z = 0f

    private var t0 = 0L
    private var lastT = 0f
    private var lastA = 0f
    private var vAcc = 0f
    private var sAcc = 0f
    private val aSt = FloatArray(2)
    private val jSt = FloatArray(2)
    private val xSt = FloatArray(2)
    private val ySt = FloatArray(2)
    private val jxSt = FloatArray(2)
    private val jySt = FloatArray(2)
    private var lastX = 0f
    private var lastY = 0f

    // 자동정지 상태기계
    private var phase = "wait"
    private var phT = 0f
    private var sA = 0f

    private var geoLat = 0.0; private var geoLon = 0.0; private var geoAcc = 0.0
    private var hasGeo = false

    private val ui = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var micOn = false
    private lateinit var btnMic: Button
    private lateinit var btnAbort: Button
    private var prevRun: Run? = null
    private var pendingCsv: String? = null
    private var pendingHtml: String? = null

    // ---- 뷰 ----
    private lateinit var chart: ChartView
    private lateinit var tvFs: TextView
    private lateinit var tvA: TextView
    private lateinit var tvV: TextView
    private lateinit var tvJ: TextView
    private lateinit var tvAmm: TextView
    private lateinit var tvVmm: TextView
    private lateinit var tvJmm: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvMsg: TextView
    private lateinit var tvGeo: TextView
    private lateinit var btnGo: Button
    private lateinit var btnUp: Button
    private lateinit var btnDown: Button
    private lateinit var trialBar: LinearLayout
    private lateinit var spRate: Spinner
    private lateinit var spAuto: Spinner
    private lateinit var spFc: Spinner
    private lateinit var spJfc: Spinner
    private lateinit var spSeg: Spinner
    private lateinit var spAxis: Spinner
    private lateinit var spFax: Spinner
    private lateinit var tvK1: TextView
    private lateinit var tvK2: TextView
    private lateinit var tvK3: TextView
    private lateinit var spTrials: Spinner
    private lateinit var tgFft: ToggleButton
    private lateinit var cbA: CheckBox
    private lateinit var cbV: CheckBox
    private lateinit var cbJ: CheckBox
    private lateinit var cbR: CheckBox
    private lateinit var cbX: CheckBox
    private lateinit var cbY: CheckBox
    private lateinit var cbUncal: CheckBox
    private lateinit var cbAutoDir: CheckBox
    private lateinit var cbNoise: CheckBox
    private lateinit var edNcal: EditText
    private lateinit var edDia: EditText
    private lateinit var edPole: EditText
    private lateinit var edGear: EditText
    private lateinit var edRail: EditText
    private lateinit var edBrk: EditText
    private lateinit var spRope: Spinner
    private var noiseRec: Noise? = null
    private lateinit var edSite: EditText
    private lateinit var edUnit: EditText
    private lateinit var edSpec: EditText
    private lateinit var edBy: EditText
    private lateinit var edAddr: EditText
    private lateinit var edMail: EditText
    private var lastReportHtml: String? = null

    private val RATES = intArrayOf(0, 100, 200, 400, 800)
    private val RATE_LABELS = arrayOf("최대", "100 Hz", "200 Hz", "400 Hz", "800 Hz")
    private val AUTO_S = floatArrayOf(0f, 1f, 2f, 3f, 5f, 10f, 15f, 20f, 30f, 45f, 60f)
    private val AUTO_LABELS = arrayOf("끔", "1 s", "2 s", "3 s", "5 s", "10 s", "15 s", "20 s", "30 s", "45 s", "60 s")
    private val FC = arrayOf("2", "3", "5", "8", "12", "20")
    private val JFC = arrayOf("1", "2", "4", "8")
    private val SEG_KEY = arrayOf("const", "acc", "dec", "all")
    private val AXIS_LABELS = arrayOf("수직 (가속도·속도·저크)", "수평 전후 (진동)", "수평 좌우 (진동)")
    private val FAX_KEY = arrayOf("ar", "xr", "yr")
    private val SEG_LABELS = arrayOf("등속", "가속", "감속", "전체")

    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        writeUri(uri, pendingCsv); pendingCsv = null
    }
    private val saveHtml = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        writeUri(uri, pendingHtml); pendingHtml = null
    }
    private val openCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCsv(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        chart = findViewById(R.id.chart)
        tvFs = findViewById(R.id.tvFs)
        tvA = findViewById(R.id.tvA); tvV = findViewById(R.id.tvV); tvJ = findViewById(R.id.tvJ)
        tvAmm = findViewById(R.id.tvAmm); tvVmm = findViewById(R.id.tvVmm); tvJmm = findViewById(R.id.tvJmm)
        tvStats = findViewById(R.id.tvStats)
        tvSummary = findViewById(R.id.tvSummary)
        tvMsg = findViewById(R.id.tvMsg)
        tvGeo = findViewById(R.id.tvGeo)
        btnGo = findViewById(R.id.btnGo)
        btnUp = findViewById(R.id.btnUp); btnDown = findViewById(R.id.btnDown)
        trialBar = findViewById(R.id.trialBar)
        spRate = findViewById(R.id.spRate); spAuto = findViewById(R.id.spAuto)
        spFc = findViewById(R.id.spFc); spJfc = findViewById(R.id.spJfc); spSeg = findViewById(R.id.spSeg)
        spTrials = findViewById(R.id.spTrials)
        tgFft = findViewById(R.id.tgFft)
        cbA = findViewById(R.id.cbA); cbV = findViewById(R.id.cbV)
        cbJ = findViewById(R.id.cbJ); cbR = findViewById(R.id.cbR)
        cbX = findViewById(R.id.cbX); cbY = findViewById(R.id.cbY)
        spAxis = findViewById(R.id.spAxis); spFax = findViewById(R.id.spFax)
        tvK1 = findViewById(R.id.tvK1); tvK2 = findViewById(R.id.tvK2); tvK3 = findViewById(R.id.tvK3)
        cbUncal = findViewById(R.id.cbUncal)
        cbAutoDir = findViewById(R.id.cbAutoDir)
        cbNoise = findViewById(R.id.cbNoise)
        edNcal = findViewById(R.id.edNcal)
        edDia = findViewById(R.id.edDia); edPole = findViewById(R.id.edPole)
        edGear = findViewById(R.id.edGear); edRail = findViewById(R.id.edRail); edBrk = findViewById(R.id.edBrk)
        spRope = findViewById(R.id.spRope)
        edSite = findViewById(R.id.edSite); edUnit = findViewById(R.id.edUnit)
        edSpec = findViewById(R.id.edSpec); edBy = findViewById(R.id.edBy); edAddr = findViewById(R.id.edAddr)
        edMail = findViewById(R.id.edMail)

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        fillSpinner(spRate, RATE_LABELS, 2)
        fillSpinner(spAuto, AUTO_LABELS, 1)
        fillSpinner(spFc, FC.map { "$it Hz" }.toTypedArray(), 1)
        fillSpinner(spJfc, JFC.map { "$it Hz" }.toTypedArray(), 1)
        fillSpinner(spSeg, SEG_LABELS, 0)
        fillSpinner(spAxis, AXIS_LABELS, 0)
        fillSpinner(spFax, arrayOf("수직", "전후", "좌우"), 0)
        fillSpinner(spRope, arrayOf("로핑 1:1", "로핑 2:1", "로핑 4:1"), 1)
        fillSpinner(spTrials, arrayOf("1회", "2회", "3회", "4회", "5회"), 2)

        spTrials.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                nTrials = pos + 1
                if (trial > nTrials) trial = nTrials
                buildTrials(); refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spSeg.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spFax.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spAxis.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                cbA.isChecked = pos == 0; cbV.isChecked = pos == 0; cbJ.isChecked = pos == 0
                cbX.isChecked = pos == 1; cbY.isChecked = pos == 2
                spFax.setSelection(pos)
                refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnUp.setOnClickListener { dir = "up"; refresh() }
        btnDown.setOnClickListener { dir = "down"; refresh() }
        btnGo.setOnClickListener { if (recKey == key()) stopRec(false) else startRec() }
        findViewById<Button>(R.id.btnCsv).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.btnLoad).setOnClickListener { openCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetCur() }
        findViewById<Button>(R.id.btnReport).setOnClickListener { exportReport() }
        findViewById<Button>(R.id.btnMail).setOnClickListener { mailReport() }
        findViewById<Button>(R.id.btnGeo).setOnClickListener { requestGeo() }
        btnMic = findViewById(R.id.btnMic)
        btnAbort = findViewById(R.id.btnAbort)
        btnAbort.setOnClickListener { confirmAbort() }
        btnMic.setOnClickListener { toggleMic() }

        val redraw = View.OnClickListener { refresh() }
        cbA.setOnClickListener(redraw); cbV.setOnClickListener(redraw)
        cbJ.setOnClickListener(redraw); cbR.setOnClickListener(redraw)
        cbX.setOnClickListener(redraw); cbY.setOnClickListener(redraw)
        tgFft.setOnClickListener(redraw)

        setMicUi()
        // 접이식 섹션 — 기본은 접힌 상태, 머리글을 누르면 펼쳐진다
        for ((h, b) in listOf(R.id.hdrOpt to R.id.boxOpt, R.id.hdrSum to R.id.boxSum, R.id.hdrInfo to R.id.boxInfo)) {
            val head = findViewById<TextView>(h)
            val body = findViewById<LinearLayout>(b)
            head.setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                head.text = (if (open) "▾  " else "▸  ") + head.text.toString().substring(3)
            }
        }

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "dev" } catch (e: Exception) { "dev" }
        findViewById<TextView>(R.id.tvVer).text = "버전 $ver"
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { Updater.check(this, true) { msg(it) } }
        Updater.check(this, false) { msg(it) }

        loadAll()
        buildTrials()
        refresh()
        msg("휴대폰을 카 바닥이나 손잡이에 고정하고, 정지 상태에서 시작을 누르세요.")
    }

    private fun fillSpinner(sp: Spinner, items: Array<String>, sel: Int) {
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        sp.setSelection(sel)
    }

    private fun key() = dir + trial
    private fun getRun(k: String): Run = runs.getOrPut(k) { Run() }
    private fun msg(t: String) { tvMsg.text = t }

    // ---- 회차 칩 ----
    private fun buildTrials() {
        trialBar.removeAllViews()
        for (n in 1..nTrials) {
            val b = Button(this)
            b.text = "${n}회"
            b.textSize = 12f
            b.setBackgroundResource(R.drawable.chip_bg)
            b.setTextColor(0xFFE8EDF5.toInt())
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = 6
            b.layoutParams = lp
            b.setOnClickListener { trial = n; refresh() }
            trialBar.addView(b)
        }
    }

    // ---- 기록 ----
    private fun startRec() {
        if (recKey != null) { msg("다른 회차를 기록 중입니다."); return }
        val uncal = cbUncal.isChecked
        var s: Sensor? = null
        if (uncal && Build.VERSION.SDK_INT >= 26) s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s == null) { msg("가속도 센서를 찾을 수 없습니다."); return }
        sensor = s
        sensorLabel = if (s.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) "무보정" else "표준"

        val rate = RATES[spRate.selectedItemPosition]
        val periodUs = if (rate == 0) SensorManager.SENSOR_DELAY_FASTEST else (1_000_000 / rate)

        val k = key()
        prevRun = runs[k]?.takeIf { it.samples.isNotEmpty() }
        runs[k] = Run()
        recKey = k
        calib = true; calibN = 0; calibX = 0.0; calibY = 0.0; calibZ = 0.0; calibT0 = 0L
        aSt[0] = 0f; aSt[1] = 0f; jSt[0] = 0f; jSt[1] = 0f
        phase = "wait"; phT = 0f; sA = 0f
        tgFft.isChecked = false

        val ok = sm.registerListener(this, s, periodUs)
        if (!ok) { recKey = null; msg("센서 등록에 실패했습니다."); return }
        if (cbNoise.isChecked) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3)
                msg("마이크 권한을 허용한 뒤 다시 시작하세요. 이번 측정은 소음 없이 진행합니다.")
            } else {
                val nr = Noise()
                if (nr.start()) noiseRec = nr else msg("마이크를 열 수 없어 소음 측정을 건너뜁니다.")
            }
        }
        refresh()
        msg("[${Report.name(k)}] 영점 잡는 중… 2초간 움직이지 마세요.")
    }

    private fun confirmAbort() {
        val k = recKey ?: return
        val n = runs[k]?.samples?.size ?: 0
        if (n > 200) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("${n}개 샘플이 기록됐습니다. 정말 폐기할까요?")
                .setPositiveButton("폐기") { _, _ -> abortRec() }
                .setNegativeButton("계속 측정", null)
                .show()
        } else abortRec()
    }

    /** 측정 강제 취소: 센서를 즉시 끊고 이번 회차 데이터를 폐기, 직전 상태로 되돌린다 */
    private fun abortRec() {
        val k = recKey ?: return
        sm.unregisterListener(this)
        noiseRec?.stop(); noiseRec = null
        ui.removeCallbacks(ticker)
        recKey = null
        val restored = prevRun != null
        if (restored) runs[k] = prevRun!! else runs.remove(k)
        prevRun = null
        refresh()
        msg("[${Report.name(k)}] 측정을 취소했습니다. 이번 데이터는 저장하지 않았습니다." +
            if (restored) " 직전 측정값은 그대로 유지됩니다." else "")
    }

    private fun stopRec(auto: Boolean) {
        val k = recKey ?: return
        sm.unregisterListener(this)
        val np = noiseRec?.stop() ?: emptyList()
        noiseRec = null
        recKey = null
        val r = getRun(k)
        if (np.isNotEmpty()) r.noise = np
        if (r.samples.size < 10) {
            val had = prevRun != null
            if (had) runs[k] = prevRun!! else runs[k] = Run()
            prevRun = null
            refresh()
            msg("데이터가 부족합니다. 다시 시도하세요." + if (had) " 직전 측정값은 유지됩니다." else "")
            return
        }
        prevRun = null
        r.bias = Dsp.debias(r.samples)
        r.done = true
        r.sensorName = sensorLabel

        // 방향 자동 판정: 순 변위 부호(+ = 상승)
        var moved = ""
        val net = r.samples[r.samples.size - 1].s
        if (cbAutoDir.isChecked && abs(net) > 0.1f) {
            val det = if (net > 0f) "up" else "down"
            if (det != k.replace(Regex("\\d+$"), "")) {
                val n0 = k.filter { it.isDigit() }.toIntOrNull() ?: 1
                var slot = 0
                val occupied = { key: String -> runs[key]?.samples?.isNotEmpty() == true }
                if (!occupied(det + n0)) slot = n0
                else for (n in 1..nTrials) if (!occupied(det + n)) { slot = n; break }
                if (slot > 0) {
                    runs[det + slot] = r
                    runs.remove(k)
                    java.io.File(filesDir, "$k.csv").delete()
                    dir = det; trial = slot
                    saveRun(det + slot, r)
                    moved = " — 순 변위 %.2f m로 ${if (det == "up") "상승" else "하강"} 판정, ${Report.name(det + slot)}로 이동".format(net)
                } else {
                    moved = " — ${if (det == "up") "상승" else "하강"}으로 판정되나 빈 회차가 없어 그대로 둡니다"
                }
            }
        }
        if (moved.isEmpty()) saveRun(k, r)
        refresh()
        msg("[${Report.name(key())}] ${if (auto) "정지 감지 → 자동 종료" else "기록 종료"} · %.0f Hz · 드리프트 %.1f mm/s²".format(r.fs, r.bias * 1000f) + moved)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val k = recKey ?: return
        val r = getRun(k)
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]

        if (calib) {
            if (calibT0 == 0L) calibT0 = e.timestamp
            calibX += x; calibY += y; calibZ += z; calibN++
            val el = (e.timestamp - calibT0) / 1e9
            if (el >= 2.0 && calibN > 4) {
                val mx = (calibX / calibN).toFloat()
                val my = (calibY / calibN).toFloat()
                val mz = (calibZ / calibN).toFloat()
                gMag = sqrt(mx * mx + my * my + mz * mz)
                if (gMag < 1f) { msg("중력값이 비정상입니다. 다시 시도하세요."); stopRec(false); return }
                gx = mx / gMag; gy = my / gMag; gz = mz / gMag
                // 수평면 직교 기저: 기기 Y축(세로)을 수평면에 투영 → h1(전후), h2 = ĝ × h1(좌우)
                var rx = 0f; var ry = 1f; var rz = 0f
                if (abs(gy) > 0.9f) { rx = 0f; ry = 0f; rz = 1f }
                val d = rx * gx + ry * gy + rz * gz
                var ax2 = rx - d * gx; var ay2 = ry - d * gy; var az2 = rz - d * gz
                val hn = sqrt(ax2 * ax2 + ay2 * ay2 + az2 * az2)
                h1x = ax2 / hn; h1y = ay2 / hn; h1z = az2 / hn
                h2x = gy * h1z - gz * h1y
                h2y = gz * h1x - gx * h1z
                h2z = gx * h1y - gy * h1x
                xSt[0] = 0f; xSt[1] = 0f; ySt[0] = 0f; ySt[1] = 0f
                jxSt[0] = 0f; jxSt[1] = 0f; jySt[0] = 0f; jySt[1] = 0f
                lastX = 0f; lastY = 0f
                r.fs = (calibN / el).toFloat()
                calib = false
                t0 = e.timestamp; lastT = 0f; lastA = 0f; vAcc = 0f; sAcc = 0f
                ui.post {
                    msg("[${Report.name(k)}] 영점 완료 · $sensorLabel %.0f Hz (|g| = %.3f). 기록 중.".format(r.fs, gMag))
                    refresh()
                }
                startTicker()
            }
            return
        }

        val t = ((e.timestamp - t0) / 1e9).toFloat()
        val dt = t - lastT
        if (dt <= 0f || dt > 0.5f) { lastT = t; return }
        lastT = t

        val ar = x * gx + y * gy + z * gz - gMag
        val xr = x * h1x + y * h1y + z * h1z
        val yr = x * h2x + y * h2y + z * h2z
        val fcA = FC[spFc.selectedItemPosition].toFloat()
        val fcJ = JFC[spJfc.selectedItemPosition].toFloat()
        val a = lpf(ar, dt, fcA, aSt)
        val j = lpf((a - lastA) / dt, dt, fcJ, jSt)
        val hx = lpf(xr, dt, fcA, xSt)
        val hy = lpf(yr, dt, fcA, ySt)
        val jx = lpf((hx - lastX) / dt, dt, fcJ, jxSt)
        val jy = lpf((hy - lastY) / dt, dt, fcJ, jySt)
        lastX = hx; lastY = hy
        vAcc += (a + lastA) / 2f * dt
        sAcc += vAcc * dt
        lastA = a
        r.samples.add(Smp(t, ar, a, vAcc, j, sAcc, xr, yr, hx, hy, jx, jy))
        if (r.samples.size % 64 == 0) r.fs = r.samples.size / max(t, 0.001f)

        val holdIdx = spAuto.selectedItemPosition
        val hold = AUTO_S[holdIdx]
        if (hold > 0f) {
            val hi = 0.15f; val lo = 0.05f
            when (phase) {
                "wait" -> if (abs(a) > hi) { phT += dt; if (phT >= 0.3f) { phase = "acc"; sA = Dsp.sgn(a); phT = 0f } } else phT = 0f
                "acc", "cruise" -> {
                    if (a * sA < -hi) { phT += dt; if (phT >= 0.3f) { phase = "dec"; phT = 0f } }
                    else { phT = 0f; if (phase == "acc" && abs(a) < lo) phase = "cruise" }
                }
                "dec" -> {
                    if (abs(a) < lo) { phT += dt; if (phT >= hold) { ui.post { stopRec(true) } ; return } }
                    else phT = 0f
                }
            }
        }
    }

    private fun lpf(x: Float, dt: Float, fc: Float, st: FloatArray): Float {
        val rc = 1f / (2f * PI.toFloat() * fc)
        val al = dt / (rc + dt)
        st[0] += al * (x - st[0])
        st[1] += al * (st[0] - st[1])
        return st[1]
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (recKey == null) return
            refresh()
            ui.postDelayed(this, 120)
        }
    }
    private fun startTicker() { ui.removeCallbacks(ticker); ui.postDelayed(ticker, 120) }

    // ---- 표시 ----
    /** 측정 중에는 설정·부가 UI를 감추고 수치와 그래프만 남긴다. */
    private fun setRecUi(on: Boolean) {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val content = findViewById<LinearLayout>(R.id.content)
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v.tag == "cfg") v.visibility = if (on) View.GONE else View.VISIBLE
        }
        findViewById<LinearLayout>(R.id.bottomBar).visibility = if (on) View.GONE else View.VISIBLE
        btnMic.visibility = if (on) View.GONE else View.VISIBLE
        btnAbort.visibility = if (on) View.VISIBLE else View.GONE
        // 가로 측정 화면에서는 수치를 조금 줄이고 파형에 높이를 몰아준다
        val big = if (!on) 24f else if (land) 26f else 40f
        val sub = if (on && !land) 13f else 11f
        tvA.textSize = big; tvV.textSize = big; tvJ.textSize = big
        tvAmm.textSize = sub; tvVmm.textSize = sub; tvJmm.textSize = sub
        tvK1.textSize = sub; tvK2.textSize = sub; tvK3.textSize = sub
        tvFs.textSize = if (on) 14f else 12f
        tvMsg.textSize = if (on) 14f else 12f
        val dp = resources.displayMetrics.density
        val hDp = when {
            !on -> 220f
            land -> (resources.configuration.screenHeightDp - 170).coerceIn(120, 420).toFloat()
            else -> 300f
        }
        chart.layoutParams = chart.layoutParams.apply { height = (hDp * dp).toInt() }
        chart.requestLayout()
        // 측정 중에는 현재 방향을 고정해 주행 중 흔들림으로 회전하지 않게 한다
        requestedOrientation = if (on)
            (if (land) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
    }

    private fun refresh() {
        val k = key()
        val r = getRun(k)
        btnUp.isSelected = dir == "up"
        btnDown.isSelected = dir == "down"
        for (i in 0 until trialBar.childCount) {
            val b = trialBar.getChildAt(i) as Button
            val n = i + 1
            b.isSelected = n == trial
            val rr = runs[dir + n]
            b.text = "${n}회" + if (recKey == dir + n) " ●" else if (rr != null && rr.samples.isNotEmpty()) " ✓" else ""
        }
        setRecUi(recKey != null)
        tvFs.text = if (r.fs > 0f) "%.0f Hz%s".format(r.fs, if (recKey == k) " · $phase" else "") else "— Hz"

        val S = r.samples
        if (S.isEmpty()) {
            tvA.text = "0.00"; tvV.text = "0.00"; tvJ.text = "0.00"
            tvAmm.text = "max 0.00\nmin 0.00"; tvVmm.text = "max 0.00\nmin 0.00"; tvJmm.text = "max 0.00\nmin 0.00"
            tvStats.text = "데이터 없음"
        } else {
            val p = S[S.size - 1]
            val o = Dsp.stats(S)
            val axPos = spAxis.selectedItemPosition
            if (axPos == 0) {
                tvK1.text = "가속도 m/s²"; tvK2.text = "속도 m/s"; tvK3.text = "저크 m/s³"
                tvA.text = "%.2f".format(p.a); tvV.text = "%.2f".format(p.v); tvJ.text = "%.2f".format(p.j)
                tvAmm.text = "max %.2f\nmin %.2f".format(o.aMax, o.aMin)
                tvVmm.text = "max %.2f\nmin %.2f".format(o.vMax, o.vMin)
                tvJmm.text = "max %.2f\nmin %.2f".format(o.jMax, o.jMin)
            } else {
                val ak = if (axPos == 1) "x" else "y"
                val jk = if (axPos == 1) "jx" else "jy"
                val nm = if (axPos == 1) "전후" else "좌우"
                val ac = Dsp.axStats(S, ak)
                val jc = Dsp.axStats(S, jk)
                tvK1.text = "가속도 $nm m/s²"; tvK2.text = "RMS $nm m/s²"; tvK3.text = "저크 $nm m/s³"
                tvA.text = "%.3f".format(p.get(ak)); tvV.text = "%.3f".format(ac.rms); tvJ.text = "%.2f".format(p.get(jk))
                tvAmm.text = "max %.3f\nmin %.3f".format(ac.mx, ac.mn)
                tvVmm.text = "p-p %.3f\n".format(ac.pp)
                tvJmm.text = "max %.2f\nmin %.2f".format(jc.mx, jc.mn)
            }
            val hx = Dsp.axStats(S, "x"); val hy = Dsp.axStats(S, "y")
            val base = ("주행 %.1f s · 거리 %.2f m · 샘플 %d · 드리프트 %.1f mm/s² · 센서 %s\n" +
                "수평 진동 전후 p-p %.3f · rms %.3f / 좌우 p-p %.3f · rms %.3f m/s²")
                .format(p.t, p.s, S.size, r.bias * 1000f, if (r.sensorName.isEmpty()) "—" else r.sensorName,
                    hx.pp, hx.rms, hy.pp, hy.rms)
            val off = edNcal.text.toString().toFloatOrNull() ?: 0f
            val nz = if (r.done && r.noise.isNotEmpty()) NoiseStats.cruise(r, off) else null
            val nzLine = if (nz?.first == null) "" else
                "\n소음 등속%s Leq %.1f · Lmax %.1f · L95 %.1f %s"
                    .format(if (nz.third) "" else "(전체 대체)", nz.first!!.leq, nz.first!!.lmax,
                        nz.first!!.l95, nz.first!!.unit)
            val qv = if (r.done) Quality.calc(r) else null
            val qLine = when {
                qv == null -> ""
                qv.verdict == "ok" -> "\n유효성: 정상"
                else -> "\n유효성: ${Quality.verdictText(qv.verdict)} — " + qv.issues.joinToString(" · ") { it.msg }
            }
            val rb = if (r.done) Rollback.calc(r) else null
            val rbLine = when {
                rb == null -> ""
                rb.short -> "\n안티롤백: 출발 전 정지 구간이 짧아 분석 불가 (주행 전 0.5 s 이상 기록 필요)"
                rb.none -> "\n안티롤백: 가속 개시 전 유의 거동 없음"
                else -> ("\n안티롤백 개방 %.2fs → 가속 %.2fs (%.0f ms) · 변위 %.1f mm · 속도 %.1f mm/s · %s%s")
                    .format(rb.tBr, rb.tOn, rb.dur * 1000f, rb.xPk * 1000f, rb.vPk * 1000f,
                        Rollback.dirText(rb), if (rb.valid) "" else " · 잡음 이내")
            }
            val q = if (r.done) Iso.calc(r) else null
            tvStats.text = (if (q == null) base else base +
                ("\nISO 18738(참고) 등속 %.1fs%s · VPPV 수직 %.3f/A95 %.3f · 전후 %.3f/%.3f · 좌우 %.3f/%.3f\n" +
                    "최대 가속 %.2f · 감속 %.2f m/s² · 최대 저크 %.2f m/s³ · 최대 속도 %.2f m/s")
                    .format(q.cvDur, if (q.full) "(전체 대체)" else "", q.z.max, q.z.a95,
                        q.x.max, q.x.a95, q.y.max, q.y.a95, q.aMax, q.dMax, q.jMax, q.vMax)) + rbLine + nzLine + qLine
        }

        chart.runData = r
        chart.title = Report.name(k)
        chart.showA = cbA.isChecked; chart.showV = cbV.isChecked
        chart.showJ = cbJ.isChecked; chart.showR = cbR.isChecked
        chart.showX = cbX.isChecked; chart.showY = cbY.isChecked
        chart.fftMode = tgFft.isChecked
        chart.spec = if (tgFft.isChecked && r.done) Dsp.spectrum(r, SEG_KEY[spSeg.selectedItemPosition], FAX_KEY[spFax.selectedItemPosition]) else null
        chart.invalidate()

        btnGo.text = when {
            recKey == k -> "정지"
            recKey != null -> "${Report.name(recKey!!)} 기록 중"
            S.isNotEmpty() -> "${Report.name(k)} 다시 측정"
            else -> "${Report.name(k)} 시작"
        }
        btnGo.isEnabled = recKey == null || recKey == k

        val sb = StringBuilder("회차      a max/min    v max/min    j max/min   거리\n")
        for (d in listOf("up", "down")) for (n in 1..nTrials) {
            val rr = runs[d + n]
            val nm = (if (d == "up") "상승" else "하강") + n
            if (rr == null || rr.samples.isEmpty()) { sb.append("%-8s —\n".format(nm)); continue }
            val o = Dsp.stats(rr.samples)
            sb.append("%-8s %5.2f/%5.2f %5.2f/%5.2f %5.2f/%5.2f %5.2f\n"
                .format(nm, o.aMax, o.aMin, o.vMax, o.vMin, o.jMax, o.jMin, rr.samples[rr.samples.size - 1].s))
        }
        tvSummary.text = sb.toString()
    }

    private fun resetCur() {
        if (recKey != null) return
        runs.remove(key())
        File(filesDir, key() + ".csv").delete()
        refresh(); msg("[${Report.name(key())}] 초기화됨.")
    }

    // ---- 저장 / 불러오기 ----
    private fun runToCsv(r: Run): String {
        val sb = StringBuilder("t_s,a_raw_mps2,a_filt_mps2,v_mps,jerk_mps3,s_m,ax_raw_mps2,ay_raw_mps2,ax_filt_mps2,ay_filt_mps2,jx_mps3,jy_mps3\n")
        for (p in r.samples)
            sb.append("%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f\n"
                .format(p.t, p.ar, p.a, p.v, p.j, p.s, p.xr, p.yr, p.x, p.y, p.jx, p.jy))
        return sb.toString()
    }

    private fun saveRun(k: String, r: Run) {
        try { File(filesDir, "$k.csv").writeText(runToCsv(r)) } catch (_: Exception) {}
    }

    private fun loadAll() {
        for (d in listOf("up", "down")) for (n in 1..5) {
            val f = File(filesDir, "$d$n.csv")
            if (!f.exists()) continue
            try {
                val r = parseCsv(f.readText()) ?: continue
                runs["$d$n"] = r
            } catch (_: Exception) {}
        }
    }

    private fun parseCsv(text: String): Run? {
        val lines = text.split("\n").filter { it.isNotBlank() }
        if (lines.size < 3) return null
        val head = lines[0].split(",").map { it.trim() }
        val iT = head.indexOf("t_s"); val iA = head.indexOf("a_filt_mps2")
        if (iT < 0 || iA < 0) return null
        val iAr = head.indexOf("a_raw_mps2"); val iV = head.indexOf("v_mps")
        val iJ = head.indexOf("jerk_mps3"); val iS = head.indexOf("s_m")
        val iXr = head.indexOf("ax_raw_mps2"); val iYr = head.indexOf("ay_raw_mps2")
        val iX = head.indexOf("ax_filt_mps2"); val iY = head.indexOf("ay_filt_mps2")
        val iJx = head.indexOf("jx_mps3"); val iJy = head.indexOf("jy_mps3")
        val r = Run()
        for (i in 1 until lines.size) {
            val c = lines[i].split(",")
            val t = c.getOrNull(iT)?.toFloatOrNull() ?: continue
            val a = c.getOrNull(iA)?.toFloatOrNull() ?: continue
            fun col(idx: Int): Float =
                if (idx >= 0) c.getOrNull(idx)?.toFloatOrNull() ?: 0f else 0f
            r.samples.add(Smp(
                t,
                if (iAr >= 0) c.getOrNull(iAr)?.toFloatOrNull() ?: a else a,
                a, col(iV), col(iJ), col(iS),
                col(iXr), col(iYr), col(iX), col(iY), col(iJx), col(iJy)
            ))
        }
        if (r.samples.size < 2) return null
        r.done = true
        r.fs = (r.samples.size - 1) / max(r.samples[r.samples.size - 1].t, 0.001f)
        return r
    }

    private fun exportCsv() {
        val r = runs[key()]
        if (r == null || r.samples.isEmpty()) { msg("저장할 데이터가 없습니다."); return }
        pendingCsv = runToCsv(r)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        saveCsv.launch("elevator_${key()}_$stamp.csv")
    }

    private fun importCsv(uri: Uri) {
        try {
            val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val r = parseCsv(txt)
            if (r == null) { msg("이 앱에서 저장한 CSV 형식이 아닙니다."); return }
            r.src = uri.lastPathSegment
            runs[key()] = r
            saveRun(key(), r)
            refresh(); msg("[${Report.name(key())}] 불러왔습니다.")
        } catch (e: Exception) { msg("불러오기 실패: ${e.message}") }
    }

    private fun exportReport() {
        val order = ArrayList<String>()
        for (d in listOf("up", "down")) for (n in 1..nTrials) {
            val r = runs[d + n]
            if (r != null && r.samples.isNotEmpty()) order.add(d + n)
        }
        if (order.isEmpty()) { msg("리포트에 포함할 데이터가 없습니다."); return }
        val meta = Report.Meta(
            edSite.text.toString(), edUnit.text.toString(), edSpec.text.toString(),
            edBy.text.toString(), edAddr.text.toString(), geoLat, geoLon, geoAcc, hasGeo
        )
        val nOff = edNcal.text.toString().toFloatOrNull() ?: 0f
        val spec = machSpec()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
        pendingHtml = Report.build(order, runs, meta, FC[spFc.selectedItemPosition], JFC[spJfc.selectedItemPosition], date, nOff, spec)
        lastReportHtml = pendingHtml
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
        val unit = edUnit.text.toString().replace(Regex("\\s+"), "").ifEmpty { "unit" }
        saveHtml.launch("ride_report_${unit}_$stamp.html")
    }

    private fun writeUri(uri: Uri?, content: String?) {
        if (uri == null || content == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            msg("저장했습니다.")
        } catch (e: Exception) { msg("저장 실패: ${e.message}") }
    }

    /** 리포트를 첨부한 상태로 메일 앱을 띄운다. 무인 발송은 SMTP 서버가 필요해 지원하지 않는다. */
    private fun mailReport() {
        var html = lastReportHtml
        if (html == null) { exportReport(); html = lastReportHtml }
        if (html == null) { msg("먼저 리포트를 생성하세요."); return }
        try {
            val dir = java.io.File(cacheDir, "reports")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date())
            val unit = edUnit.text.toString().replace(Regex("\\s+"), "").ifEmpty { "unit" }
            val f = java.io.File(dir, "ride_report_${unit}_$stamp.html")
            f.writeText(html)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f
            )
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
            val subject = "[승강기 주행계측] ${edSite.text} ${edUnit.text} $date".replace(Regex("\\s+"), " ").trim()
            val sb = StringBuilder()
            sb.append("승강기 주행 계측 리포트를 보냅니다.\n\n")
            sb.append("현장: ${edSite.text.ifBlank { "—" }}\n")
            sb.append("호기/기종: ${edUnit.text.ifBlank { "—" }}\n")
            sb.append("사양: ${edSpec.text.ifBlank { "—" }}\n")
            if (edAddr.text.isNotBlank()) sb.append("주소: ${edAddr.text}\n")
            if (hasGeo) sb.append("위치: %.6f, %.6f (±%.0f m)\n".format(geoLat, geoLon, geoAcc))
            sb.append("측정자: ${edBy.text.ifBlank { "—" }}\n\n")
            sb.append("첨부된 HTML 파일을 브라우저로 열면 표와 그래프를 볼 수 있고, 인쇄 → PDF 저장이 가능합니다.")

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, sb.toString())
                val to = edMail.text.toString().split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
                if (to.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "리포트 보내기"))
            msg("메일 앱을 열었습니다. 수신자·본문·첨부가 채워져 있습니다.")
        } catch (e: Exception) {
            msg("메일 전송 준비 실패: ${e.message}")
        }
    }

    private fun machSpec(): FreqSource.Spec {
        fun num(e: EditText, d: Float): Float {
            val v = e.text.toString().toFloatOrNull()
            return if (v != null && v > 0f) v else d
        }
        val rope = floatArrayOf(1f, 2f, 4f)[spRope.selectedItemPosition]
        return FreqSource.Spec(
            diaM = num(edDia, 0f) / 1000f, roping = rope, gear = num(edGear, 1f),
            poles = num(edPole, 0f), railM = num(edRail, 5f), bracketM = num(edBrk, 2.5f)
        )
    }

    // ---- 음성 명령 ----
    private fun setMicUi() {
        btnMic.text = if (micOn) "🎤 음성\n켜짐" else "🎤 음성\n꺼짐"
        btnMic.setTextColor(if (micOn) 0xFF63D39A.toInt() else 0xFF8D9AB0.toInt())
    }

    private fun toggleMic() {
        if (micOn) { micOn = false; sr?.cancel(); sr?.destroy(); sr = null; setMicUi(); msg("음성 명령을 껐습니다."); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { msg("이 기기에서 음성 인식을 쓸 수 없습니다."); return }
        micOn = true; setMicUi()
        startListening()
        msg("음성 명령 켜짐 — \"상승 일회 시작\", \"정지\", \"리포트\" 처럼 말하세요.")
    }

    private fun startListening() {
        if (!micOn) return
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        sr = r
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (Build.VERSION.SDK_INT >= 23) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var hit: String? = null
                var said = ""
                if (list != null) for (c in list) { said = c; hit = voiceCmd(c); if (hit != null) break }
                if (hit != null) msg("🎤 $hit") else if (said.isNotEmpty()) msg("🎤 \"$said\" — 인식되지 않는 명령")
                ui.postDelayed({ startListening() }, 150)
            }
            override fun onError(e: Int) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    micOn = false; setMicUi(); msg("마이크 권한이 없습니다.")
                    return
                }
                ui.postDelayed({ startListening() }, 400)
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        try { r.startListening(it) } catch (e: Exception) { msg("음성 인식 시작 실패: ${e.message}") }
    }

    private val NUMW = mapOf(
        "일" to 1, "하나" to 1, "첫" to 1, "이" to 2, "둘" to 2, "두" to 2,
        "삼" to 3, "셋" to 3, "세" to 3, "사" to 4, "넷" to 4, "네" to 4, "오" to 5, "다섯" to 5
    )

    /** 인식 실패 시 null */
    private fun voiceCmd(raw: String): String? {
        val t = raw.replace(Regex("\\s+"), "")
        var done: String? = null
        if (Regex("상승|올라|업").containsMatchIn(t)) { dir = "up"; done = "상승 탭" }
        if (Regex("하강|내려|다운").containsMatchIn(t)) { dir = "down"; done = "하강 탭" }
        val m = Regex("([1-5일이삼사오하나둘두셋세넷네다섯첫])\\s*(회|번)").find(t)
        if (m != null) {
            val g = m.groupValues[1]
            val n = g.toIntOrNull() ?: NUMW[g]
            if (n != null && n <= nTrials) { trial = n; done = (if (done != null) "$done · " else "") + "${n}회" }
        }
        if (Regex("취소|폐기|버려|무효").containsMatchIn(t)) {
            if (recKey != null) { abortRec(); return "측정 취소" }
        }
        if (Regex("정지|멈춰|멈춤|스톱|그만|종료").containsMatchIn(t)) {
            if (recKey != null) { stopRec(false); return "정지" }
        }
        if (Regex("시작|출발|기록|측정").containsMatchIn(t)) {
            if (recKey == null) { refresh(); startRec(); return (if (done != null) "$done · " else "") + "시작" }
        }
        if (Regex("초기화|리셋|지워").containsMatchIn(t)) { resetCur(); return "초기화" }
        if (Regex("리포트|보고서").containsMatchIn(t)) { exportReport(); return "리포트" }
        if (Regex("저장").containsMatchIn(t)) { exportCsv(); return "CSV 저장" }
        if (Regex("스펙트럼|주파수").containsMatchIn(t)) { tgFft.isChecked = true; refresh(); return "스펙트럼" }
        if (Regex("시간|파형|그래프").containsMatchIn(t)) { tgFft.isChecked = false; refresh(); return "시간 그래프" }
        if (Regex("수직").containsMatchIn(t)) { spAxis.setSelection(0); return "수직 축" }
        if (Regex("전후|앞뒤").containsMatchIn(t)) { spAxis.setSelection(1); return "전후 축" }
        if (Regex("좌우|옆").containsMatchIn(t)) { spAxis.setSelection(2); return "좌우 축" }
        if (done != null) { refresh(); return done }
        return null
    }

    // ---- 위치 ----
    private fun requestGeo() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        tvGeo.text = "위치 확인 중…"
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lm.removeUpdates(this)
                onGeo(loc)
            }
        }
        try {
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            ui.postDelayed({
                lm.removeUpdates(listener)
                if (!hasGeo) tvGeo.text = "위치를 가져오지 못했습니다."
            }, 20000)
        } catch (e: Exception) { tvGeo.text = "위치 오류: ${e.message}" }
    }

    private fun onGeo(loc: Location) {
        geoLat = loc.latitude; geoLon = loc.longitude
        geoAcc = loc.accuracy.toDouble(); hasGeo = true
        tvGeo.text = "%.6f, %.6f (±%.0f m)".format(geoLat, geoLon, geoAcc)
        if (geoAcc > 100) msg("위치 정확도 ±%.0f m — 실내라 오차가 큽니다. 건물 밖에서 다시 시도하세요.".format(geoAcc))
        Thread {
            try {
                val gc = Geocoder(this, Locale.KOREA)
                @Suppress("DEPRECATION")
                val list = gc.getFromLocation(geoLat, geoLon, 1)
                if (!list.isNullOrEmpty()) {
                    val ad = list[0]
                    val name = ad.featureName ?: ""
                    val full = ad.getAddressLine(0) ?: ""
                    ui.post {
                        if (edSite.text.isEmpty() && name.isNotEmpty() && !name.first().isDigit()) edSite.setText(name)
                        if (edAddr.text.isEmpty() && full.isNotEmpty()) edAddr.setText(full)
                        msg(if (full.isNotEmpty()) "위치 확인: $full" else "좌표만 확보했습니다.")
                    }
                }
            } catch (e: Exception) {
                ui.post { msg("좌표는 확보했으나 주소 조회에 실패했습니다.") }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 1 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) requestGeo()
        else if (rc == 1) msg("위치 권한이 거부되었습니다.")
        if (rc == 2 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) toggleMic()
        else if (rc == 2) msg("마이크 권한이 거부되었습니다.")
        if (rc == 3 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) msg("마이크 권한 허용됨. 다시 시작하면 소음도 함께 측정합니다.")
    }

    override fun onDestroy() {
        super.onDestroy()
        sm.unregisterListener(this)
        micOn = false
        noiseRec?.stop(); noiseRec = null
        sr?.destroy(); sr = null
        ui.removeCallbacksAndMessages(null)
    }
}
