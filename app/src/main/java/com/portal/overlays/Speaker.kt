package com.portal.overlays

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Best-effort spoken output for full-attention alerts (breaking news).
 *
 * Portal devices ship without Google services, so they have no system TTS out of the box. This
 * wrapper prefers the sideloaded Sherpa-ONNX Portal TTS engine
 * ([SHERPA_PKG] = "com.k2fsa.sherpa.onnx.tts.engine") when it is installed, and otherwise falls
 * back to whatever generic TTS service the device happens to expose. If nothing speech-capable is
 * present [available] stays false and [speak] is a silent no-op — callers must always remain useful
 * visually (the breaking-news popup still flashes without audio).
 *
 * Initialisation is asynchronous: an utterance requested before the engine is ready is held in
 * [pending] and spoken the moment init completes, so the first breaking-news alert isn't dropped.
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var usingSherpa = false
    // Most recent utterance requested before the engine finished initialising.
    private var pending: Pending? = null

    /** True once an engine has initialised and reported at least a usable default language. */
    val available: Boolean get() = ready

    private data class Pending(val text: String, val locale: Locale?, val volume: Float)

    fun start() {
        if (tts != null) return
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                runCatching {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                Log.i(TAG, "TTS ready (engine=${tts?.defaultEngine}, sherpa=$usingSherpa)")
                pending?.let { pending = null; speak(it.text, it.locale, it.volume) }
            } else {
                Log.w(TAG, "TTS init failed (status=$status, sherpa=$usingSherpa)")
                if (usingSherpa) {
                    // Sherpa is installed but failed to init (e.g. no voice models copied yet) —
                    // retry once with the platform default engine before giving up.
                    usingSherpa = false
                    runCatching { tts?.shutdown() }
                    tts = TextToSpeech(context.applicationContext, makeListener())
                }
            }
        }
        usingSherpa = isInstalled(context, SHERPA_PKG)
        tts = if (usingSherpa) {
            TextToSpeech(context.applicationContext, listener, SHERPA_PKG)
        } else {
            TextToSpeech(context.applicationContext, listener)
        }
    }

    /** Re-create the init listener for the fallback attempt (same body, fresh object). */
    private fun makeListener() = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            Log.i(TAG, "TTS ready on fallback engine ${tts?.defaultEngine}")
            pending?.let { pending = null; speak(it.text, it.locale, it.volume) }
        } else {
            Log.w(TAG, "No usable TTS engine on this device; alerts will be silent")
        }
    }

    /**
     * Speak [text], flushing anything in progress. [localeHint] nudges the engine toward a matching
     * voice; the Sherpa engine falls back to its first installed voice when the locale isn't present.
     * [volume] (0..1) scales the utterance relative to the stream volume via the engine KEY_PARAM_VOLUME
     * bundle param. No-op (but queued) when the engine hasn't finished initialising; a true no-op
     * when none exists.
     */
    fun speak(text: String, localeHint: Locale? = null, volume: Float = 1f) {
        if (text.isBlank()) return
        val v = volume.coerceIn(0f, 1f)
        val engine = tts
        if (engine == null || !ready) {
            pending = Pending(text, localeHint, v)
            if (engine == null) start()
            return
        }
        if (localeHint != null) {
            runCatching {
                val res = engine.isLanguageAvailable(localeHint)
                if (res >= TextToSpeech.LANG_AVAILABLE) engine.language = localeHint
            }
        }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, v) }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "breaking-${System.currentTimeMillis()}")
    }

    fun stop() {
        pending = null
        ready = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object {
        private const val TAG = "Speaker"
        const val SHERPA_PKG = "com.k2fsa.sherpa.onnx.tts.engine"

        private fun isInstalled(context: Context, pkg: String): Boolean =
            runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

        /** True if the device has *any* TTS engine (Sherpa or generic) — used for UI hints. */
        fun engineAvailable(context: Context): Boolean {
            if (isInstalled(context, SHERPA_PKG)) return true
            return runCatching {
                val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
            }.getOrDefault(false)
        }
    }
}
