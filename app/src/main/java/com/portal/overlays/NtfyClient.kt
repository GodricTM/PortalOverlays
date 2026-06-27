package com.portal.overlays

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming listener for an ntfy server. Connects to <server>/<topic>/json which keeps the
 * connection open and emits one JSON object per line. [server] defaults to the public ntfy.sh but
 * can point at a self-hosted instance; [token], when set, is sent as a Bearer token so read-protected
 * (private) topics work. Runs on its own thread and auto-reconnects with backoff. No third-party HTTP
 * client — plain HttpURLConnection + org.json keeps the APK lean and avoids any GMS dependency
 * (which Portal lacks).
 */
class NtfyClient(
    private val topic: String,
    private val server: String = "https://ntfy.sh",
    private val token: String = "",
    private val onConnected: (Boolean) -> Unit,
    private val onMessage: (title: String, message: String, priority: Int, tags: List<String>) -> Unit,
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

    /** Normalised server base, e.g. "https://ntfy.sh" — adds https:// if no scheme, drops trailing /. */
    private fun baseUrl(): String {
        var s = server.trim().ifBlank { "https://ntfy.sh" }
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        return s.trimEnd('/')
    }

    private fun loop() {
        var backoff = 2000L
        while (running.get()) {
            try {
                val url = URL("${baseUrl()}/${topic}/json")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 0 // stream stays open indefinitely
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/x-ndjson")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
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
            val title = obj.optString("title", "").ifBlank { topic }
            val message = obj.optString("message", "")
            // ntfy priority is 1 (min) .. 5 (max/urgent); default 3 when omitted.
            val priority = obj.optInt("priority", 3)
            val tags = obj.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
            } ?: emptyList()
            if (message.isNotBlank() || title.isNotBlank()) onMessage(title, message, priority, tags)
        } catch (e: Exception) {
            Log.w(TAG, "bad ntfy line: ${e.message}")
        }
    }

    companion object { private const val TAG = "NtfyClient" }
}
