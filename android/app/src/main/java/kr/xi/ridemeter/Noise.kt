package kr.xi.ridemeter

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 마이크 소음 측정.
 * 폰 마이크는 음압 교정이 되어 있지 않으므로 산출값은 dBFS(A)이며,
 * 사용자가 소음계와 비교해 입력한 오프셋을 더해 dB(A) 근사로 표기한다.
 */
class Noise {

        companion object { private const val NFFT = 2048 }

    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    val points = ArrayList<NoiseStats.Pt>()
    var sampleRate = 44100
        private set

    /** 시작 성공 여부. 권한이 없거나 초기화 실패 시 false. */
    fun start(): Boolean {
        val rates = intArrayOf(44100, 48000, 22050)
        for (sr in rates) {
            val minBuf = AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, NFFT * 4)
                )
            } catch (e: Exception) { null }
            val r2 = if (r != null && r.state == AudioRecord.STATE_INITIALIZED) r else {
                try { r?.release() } catch (_: Exception) {}
                try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        max(minBuf, NFFT * 4)
                    )
                } catch (e: Exception) { null }
            }
            if (r2 == null || r2.state != AudioRecord.STATE_INITIALIZED) {
                try { r2?.release() } catch (_: Exception) {}
                continue
            }
            rec = r2
            sampleRate = sr
            break
        }
        val r = rec ?: return false
        points.clear()
        val win = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2.0 * PI * it / (NFFT - 1)) }
        val aw = DoubleArray(NFFT / 2)
        for (k in 1 until NFFT / 2) aw[k] = 10.0.pow(NoiseStats.aWeight(k.toDouble() * sampleRate / NFFT) / 10.0)
        running = true
        try { r.startRecording() } catch (e: Exception) { running = false; return false }
        val t0 = System.nanoTime()
        thread = Thread {
            val pcm = ShortArray(NFFT)
            val re = DoubleArray(NFFT)
            val im = DoubleArray(NFFT)
            while (running) {
                var got = 0
                while (got < NFFT && running) {
                    val n = r.read(pcm, got, NFFT - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < NFFT) continue
                for (i in 0 until NFFT) { re[i] = pcm[i] / 32768.0 * win[i]; im[i] = 0.0 }
                Dsp.fft(re, im)
                var pA = 0.0; var pZ = 0.0
                for (k in 1 until NFFT / 2) {
                    val amp = 2.0 * hypot(re[k], im[k]) / (NFFT * 0.5)
                    val pw = amp * amp / 2.0
                    pZ += pw; pA += pw * aw[k]
                }
                fun dbfs(v: Double) = (10.0 * log10(max(v, 1e-14) / 0.5)).toFloat()
                val t = ((System.nanoTime() - t0) / 1e9).toFloat()
                synchronized(points) { points.add(NoiseStats.Pt(t, dbfs(pA), dbfs(pZ))) }
            }
        }
        thread?.start()
        return true
    }

    fun stop(): List<NoiseStats.Pt> {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
        return synchronized(points) { ArrayList(points) }
    }
}
