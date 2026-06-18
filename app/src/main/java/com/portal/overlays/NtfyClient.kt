package com.portal.overlays

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming listener for ntfy.sh. Connects to https://ntfy.sh/<topic>/json which keeps the
 * connection open and emits one JSON object per line. Runs on its own thread and auto-reconnects
 * with backoff. No third-party HTTP client — plain HttpURLConnection + org.json keeps the APK lean
 * and avoids any GMS dependency (which Portal lacks).
 */
class NtfyClient(
    private val topic: String,
    private val onConnected: (Boolean) -> Unit,
    private val onMessage: (title: String, message: String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var conn: HttpURLConnection? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({ loop() }, "ntfy-$topic").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        try { conn?.disconnect() } catch (_: Exception) {}
        thread?.interrupt()
    }

    private fun loop() {
        var backoff = 2000L
        while (running.get()) {
            try {
                val url = URL("https://ntfy.sh/${topic}/json")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 0 // stream stays open indefinitely
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/x-ndjson")
                }
                conn = c
                c.connect()
                if (c.responseCode != 200) {
                    onConnected(false)
                    throw RuntimeException("ntfy HTTP ${c.responseCode}")
                }
                onConnected(true)
                backoff = 2000L
                BufferedReader(InputStreamReader(c.inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (running.get() && line != null) {
                        handleLine(line!!)
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "ntfy connection dropped: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
                conn = null
                onConnected(false)
            }
            if (!running.get()) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun handleLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        try {
            val obj = JSONObject(trimmed)
            // ntfy emits keepalive/open events too — only surface actual messages.
            if (obj.optString("event") != "message") return
            val title = obj.optString("title", "").ifBlank { "ntfy.sh/$topic" }
            val message = obj.optString("message", "")
            if (message.isNotBlank() || title.isNotBlank()) onMessage(title, message)
        } catch (e: Exception) {
            Log.w(TAG, "bad ntfy line: ${e.message}")
        }
    }

    companion object { private const val TAG = "NtfyClient" }
}
