package com.portal.overlays

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time audio reactor for the visualizers.
 *
 * Portal blocks the clean route — `Visualizer(0)` (the global output mix) fails with initCheck -3
 * because tapping the system mix needs the privileged `CAPTURE_AUDIO_OUTPUT` permission a sideloaded
 * app can't hold. So instead we listen to the **microphone**, which hears the music coming out of the
 * device's own speakers, and run a small [Goertzel] filter bank over the PCM to get per-band energy.
 * The visualizer views read [bands] each frame, so the bars actually move to whatever is playing in
 * the room.
 *
 * Needs `RECORD_AUDIO` (granted once). Every step is guarded: if the mic is busy (the Portal shares a
 * single near-field mic with its always-on listeners) or unavailable, [start] returns false and the
 * views fall back to the synthetic animation — it can never crash the host.
 */
class SoundReactor(private val bandCount: Int = 32) {

    @Volatile var active = false
        private set
    /** Smoothed 0..1 energy per band (low → high frequency). Read from the view's draw loop. */
    val bands = FloatArray(bandCount)
    /** Overall 0..1 loudness this frame — handy for global pulses/glows. */
    @Volatile var level = 0f
        private set

    private val sampleRate = 44100
    private val frameSamples = 2048
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var peak = 1f

    // Pre-computed Goertzel coefficients for each band's centre frequency.
    private val coeff = FloatArray(bandCount)
    private val centres = FloatArray(bandCount)

    init {
        // Log-spaced band centres from ~55 Hz (low bass) to ~14 kHz (presence/air).
        val fMin = 55.0; val fMax = 14000.0
        for (b in 0 until bandCount) {
            val f = fMin * (fMax / fMin).pow(b.toDouble() / (bandCount - 1))
            centres[b] = f.toFloat()
            val w = 2.0 * Math.PI * f / sampleRate
            coeff[b] = (2.0 * cos(w)).toFloat()
        }
    }

    fun start(): Boolean {
        if (active) return true
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) { Log.w(TAG, "getMinBufferSize=$minBuf"); return false }
            val bufBytes = max(minBuf, frameSamples * 2 * 2)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized (state=${rec.state})")
                runCatching { rec.release() }
                return false
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "AudioRecord did not start (state=${rec.recordingState})")
                runCatching { rec.stop(); rec.release() }
                return false
            }
            record = rec
            running = true
            active = true
            thread = Thread({ loop(rec) }, "sound-reactor").apply { isDaemon = true; start() }
            Log.i(TAG, "started: mic AudioRecord @ ${sampleRate}Hz, $bandCount bands")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "mic reactor failed: $t")
            stop()
            false
        }
    }

    fun stop() {
        active = false
        running = false
        thread?.let { runCatching { it.join(300) } }
        thread = null
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
        bands.fill(0f)
        level = 0f
        peak = 1f
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(frameSamples)
        while (running) {
            val n = try { rec.read(buf, 0, frameSamples) } catch (_: Throwable) { -1 }
            if (n <= 0) { if (n < 0) Thread.sleep(40); continue }
            analyze(buf, n)
        }
    }

    /** Goertzel power per band over one PCM frame, normalised with fast attack / slow release. */
    private fun analyze(buf: ShortArray, n: Int) {
        var sum = 0f
        var frameMax = 0f
        for (b in 0 until bandCount) {
            val c = coeff[b]
            var s0: Float
            var s1 = 0f
            var s2 = 0f
            var i = 0
            while (i < n) {
                s0 = buf[i] / 32768f + c * s1 - s2
                s2 = s1; s1 = s0
                i++
            }
            val power = s1 * s1 + s2 * s2 - c * s1 * s2
            val mag = ln(1f + sqrt(max(0f, power)))
            frameMax = max(frameMax, mag)
            val norm = (mag / peak).coerceIn(0f, 1f)
            bands[b] = if (norm > bands[b]) norm else bands[b] * 0.82f + norm * 0.18f
            sum += bands[b]
        }
        // Auto-gain: track the loudest band with a slow decay so quiet and loud both fill the range.
        peak = max(peak * 0.99f, max(frameMax, 1f))
        level = (sum / bandCount).coerceIn(0f, 1f)
    }

    /** Energy for an arbitrary 0..1 position across the spectrum (views have their own bar counts). */
    fun energyAt(fraction: Float): Float {
        if (bandCount == 0) return 0f
        val idx = (fraction.coerceIn(0f, 1f) * (bandCount - 1)).toInt()
        return bands[idx]
    }

    private companion object {
        const val TAG = "OverlaysSound"
    }
}
