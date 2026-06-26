package com.portal.overlays

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls a user-supplied feed for the scrolling ticker. Real data only (honors the project's no-fake-data
 * rule): the URL must be an RSS/Atom XML feed or a JSON document. Nothing is shown until the user provides
 * one. Refreshed every few minutes.
 */
class TickerClient(
    private val url: String,
    private val onItems: (List<String>) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({ loop() }, "ticker").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }

    private fun loop() {
        while (running.get()) {
            try {
                val items = when {
                    url.startsWith("finance:crypto") -> fetchCrypto()
                    url.startsWith("finance:stocks") -> fetchStocks()
                    else -> fetch(url)?.let { parse(it).take(MAX_ITEMS) } ?: emptyList()
                }
                if (items.isNotEmpty()) onItems(items)
            } catch (e: Exception) {
                Log.w(TAG, "ticker error: ${e.message}")
            }
            sleep(if (url.startsWith("finance:")) FINANCE_REFRESH_MS else REFRESH_MS)
        }
    }

    // ---- finance sources (keyless, no GMS) -------------------------------

    /** Live crypto quotes from CoinGecko's keyless simple-price API: "BTC $64,210 ▲2.3%". */
    private fun fetchCrypto(): List<String> {
        val ids = listOf("bitcoin", "ethereum", "solana", "ripple", "cardano", "dogecoin")
        val sym = mapOf(
            "bitcoin" to "BTC", "ethereum" to "ETH", "solana" to "SOL",
            "ripple" to "XRP", "cardano" to "ADA", "dogecoin" to "DOGE"
        )
        val body = fetch(
            "https://api.coingecko.com/api/v3/simple/price?ids=${ids.joinToString(",")}" +
                "&vs_currencies=usd&include_24hr_change=true"
        ) ?: return emptyList()
        val obj = JSONObject(body)
        return ids.mapNotNull { id ->
            val o = obj.optJSONObject(id) ?: return@mapNotNull null
            val price = o.optDouble("usd", Double.NaN)
            if (price.isNaN()) return@mapNotNull null
            val chg = o.optDouble("usd_24h_change", 0.0)
            "${sym[id]} ${formatUsd(price)} ${arrow(chg)}${formatPct(chg)}"
        }
    }

    /** Live stock quotes from Stooq's keyless CSV light-quote: "AAPL $189.20 ▲0.8%" (since open). */
    private fun fetchStocks(): List<String> {
        val symbols = listOf("aapl.us", "msft.us", "googl.us", "amzn.us", "nvda.us", "tsla.us")
        val body = fetch(
            "https://stooq.com/q/l/?s=${symbols.joinToString(",")}&f=sd2t2ohlc&h&e=csv"
        ) ?: return emptyList()
        // Header: Symbol,Date,Time,Open,High,Low,Close — change% computed close-vs-open (intraday).
        return body.trim().lines().drop(1).mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 7) return@mapNotNull null
            val ticker = c[0].uppercase().removeSuffix(".US")
            val open = c[3].toDoubleOrNull()
            val close = c[6].toDoubleOrNull()
            if (close == null || close <= 0.0) return@mapNotNull null
            val chg = if (open != null && open > 0.0) (close - open) / open * 100.0 else 0.0
            "$ticker ${formatUsd(close)} ${arrow(chg)}${formatPct(chg)}"
        }
    }

    private fun arrow(chg: Double) = if (chg >= 0) "▲" else "▼"
    private fun formatPct(chg: Double) = String.format(Locale.US, "%.1f%%", kotlin.math.abs(chg))
    private fun formatUsd(v: Double): String = when {
        v >= 1000 -> "$" + String.format(Locale.US, "%,.0f", v)
        v >= 1 -> "$" + String.format(Locale.US, "%.2f", v)
        else -> "$" + String.format(Locale.US, "%.4f", v)
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun fetch(spec: String): String? {
        val c = (URL(spec).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; requestMethod = "GET"
            setRequestProperty("User-Agent", "PortalOverlays/ticker")
        }
        return try {
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Auto-detect JSON vs XML and pull a list of short strings to scroll. */
    private fun parse(raw: String): List<String> {
        val body = raw.trim()
        return when {
            body.startsWith("{") || body.startsWith("[") -> parseJson(body)
            else -> parseFeed(body)
        }
    }

    private fun parseJson(body: String): List<String> = runCatching {
        val out = mutableListOf<String>()
        fun fromArray(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                when (val v = arr.opt(i)) {
                    is String -> out.add(v)
                    is JSONObject -> stringField(v)?.let { out.add(it) }
                }
            }
        }
        if (body.startsWith("[")) {
            fromArray(JSONArray(body))
        } else {
            val obj = JSONObject(body)
            // Common envelopes: {items:[…]} / {results:[…]} / {data:[…]} / {entries:[…]}
            val arr = listOf("items", "results", "data", "entries", "articles", "headlines")
                .firstNotNullOfOrNull { obj.optJSONArray(it) }
            if (arr != null) fromArray(arr) else stringField(obj)?.let { out.add(it) }
        }
        out.map { it.trim() }.filter { it.isNotBlank() }
    }.getOrElse { emptyList() }

    private fun stringField(o: JSONObject): String? =
        listOf("title", "headline", "name", "text", "message", "summary")
            .firstNotNullOfOrNull { o.optString(it, "").takeIf { s -> s.isNotBlank() } }

    /** Minimal RSS/Atom extraction: pull <title> contents, dropping the feed-level title. */
    private fun parseFeed(body: String): List<String> {
        val titles = TITLE_RE.findAll(body)
            .map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }
            .toList()
        // The first <title> is normally the channel/feed name, not an item — drop it when there are items.
        return if (titles.size > 1) titles.drop(1) else titles
    }

    private fun unescape(s: String): String {
        var t = s
        // Strip CDATA wrapper if present.
        CDATA_RE.find(t)?.let { t = it.groupValues[1] }
        return t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    }

    companion object {
        private const val TAG = "TickerClient"
        private const val MAX_ITEMS = 25
        private const val REFRESH_MS = 5 * 60_000L
        private const val FINANCE_REFRESH_MS = 60_000L   // prices move — poll once a minute
        private val TITLE_RE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val CDATA_RE = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    }
}
