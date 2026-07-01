package com.portal.overlays

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Local-only log of the last [MAX] tracks heard via Now Playing. Stored in SharedPreferences as JSON;
 * cover art is cached under app files as small JPEGs (from bitmap, remote URL, or content URI).
 */
class TrackHistory(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("overlays", Context.MODE_PRIVATE)
    private val cacheDir = File(app.filesDir, "track_art").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "track-history").apply { isDaemon = true }
    }

    data class Entry(
        val title: String,
        val artist: String,
        val artUri: String,
        val at: Long,
    )

    fun record(title: String, artist: String, artUri: String?, artBitmap: Bitmap? = null) {
        val t = title.trim()
        if (t.isBlank()) return
        val a = artist.trim()
        val uri = artUri?.trim().orEmpty()
        val arr = load()
        if (arr.length() > 0) {
            val head = arr.getJSONObject(0)
            if (head.optString("title") == t && head.optString("artist") == a) return
        }
        val next = JSONArray()
        next.put(JSONObject().apply {
            put("title", t); put("artist", a); put("artUri", uri); put("at", System.currentTimeMillis())
        })
        for (i in 0 until minOf(arr.length(), MAX - 1)) next.put(arr.getJSONObject(i))
        sp.edit().putString(KEY, next.toString()).apply()
        val key = cacheKey(uri, t, a)
        io.execute {
            when {
                artBitmap != null -> saveBitmap(key, artBitmap)
                uri.startsWith("http://", true) || uri.startsWith("https://", true) -> cacheRemoteArt(uri, key)
                uri.isNotBlank() -> cacheContentUri(uri, key)
            }
        }
    }

    fun entries(): List<Entry> = buildList {
        val arr = load()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(Entry(
                o.optString("title"),
                o.optString("artist"),
                o.optString("artUri"),
                o.optLong("at"),
            ))
        }
    }

    fun cacheKey(artUri: String, title: String, artist: String): String =
        artUri.trim().ifBlank { "local:${title.trim()}|${artist.trim()}" }

    fun loadArtSync(entry: Entry): Bitmap? {
        val key = cacheKey(entry.artUri, entry.title, entry.artist)
        loadCachedArtByKey(key)?.let { return it }
        val uri = entry.artUri.trim()
        if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) {
            cacheRemoteArt(uri, key)
            return loadCachedArtByKey(key)
        }
        if (uri.isNotBlank()) {
            cacheContentUri(uri, key)
            return loadCachedArtByKey(key)
        }
        return null
    }

    fun loadArt(entry: Entry, callback: (Bitmap?) -> Unit) {
        io.execute { callback(loadArtSync(entry)) }
    }

    fun formatWhen(at: Long): String {
        if (at <= 0L) return ""
        val diff = System.currentTimeMillis() - at
        return when {
            diff < 45_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000L}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
            diff < 604_800_000L -> "${diff / 86_400_000L}d ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(at))
        }
    }

    private fun loadCachedArtByKey(key: String): Bitmap? {
        if (key.isBlank()) return null
        val f = cacheFile(key)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    private fun load(): JSONArray = runCatching {
        JSONArray(sp.getString(KEY, "[]") ?: "[]")
    }.getOrElse { JSONArray() }

    private fun cacheFile(key: String): File =
        File(cacheDir, key.hashCode().toString(16) + ".jpg")

    private fun cacheRemoteArt(uri: String, key: String) {
        val f = cacheFile(key)
        if (f.exists()) return
        runCatching {
            val conn = (URL(uri).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode != 200) return
                val bytes = conn.inputStream.use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                saveBitmap(key, bmp)
                if (!bmp.isRecycled) bmp.recycle()
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun cacheContentUri(uriString: String, key: String) {
        val f = cacheFile(key)
        if (f.exists()) return
        runCatching {
            val uri = Uri.parse(uriString)
            app.contentResolver.openInputStream(uri)?.use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return
                saveBitmap(key, bmp)
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun saveBitmap(key: String, source: Bitmap) {
        val f = cacheFile(key)
        runCatching {
            val scaled = scaleDown(source, 320)
            FileOutputStream(f).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun scaleDown(source: Bitmap, maxPx: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxPx && h <= maxPx) return source
        val ratio = min(maxPx.toFloat() / w, maxPx.toFloat() / h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }

    companion object {
        private const val KEY = "trackHistory"
        private const val MAX = 20

        fun crossfadeArt(view: android.widget.ImageView?, apply: (android.widget.ImageView) -> Unit) {
            val iv = view ?: return
            if (iv.drawable == null || iv.alpha < 0.05f) {
                apply(iv); iv.alpha = 1f; return
            }
            iv.animate().cancel()
            iv.animate().alpha(0f).setDuration(140).withEndAction {
                apply(iv)
                iv.animate().alpha(1f).setDuration(220).start()
            }.start()
        }
    }
}
