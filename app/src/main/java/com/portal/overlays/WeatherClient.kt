package com.portal.overlays

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real weather for the floating widget via Open-Meteo. Open-Meteo needs no API key and has no Google
 * dependency, so it works on Portal (which lacks GMS). A city name is geocoded once, then current
 * conditions — plus 15-minute precipitation and today's/tomorrow's sun times — are polled every 15
 * minutes. Time-sensitive values (rain countdown, sunset countdown) are returned as absolute epoch
 * millis so the caller can recompute a smooth "in X min" each second without re-fetching.
 */
class WeatherClient(
    private val city: String,
    private val fahrenheit: Boolean = false,
    private val onUpdate: (temp: String, condition: String, place: String, extras: Extras) -> Unit,
) {
    /** Time-sensitive extras the caller recomputes locally each tick. Epochs are UTC millis. */
    data class Extras(
        /** When precipitation next starts within the look-ahead window, or null if none/unknown. */
        val rainStartEpoch: Long? = null,
        /** End of the window we actually checked for rain (so "no rain" can be stated honestly). */
        val rainHorizonEpoch: Long = 0L,
        /** Next sun event (sunrise or sunset) as epoch millis, or 0 if unknown. */
        val sunEventEpoch: Long = 0L,
        /** True when the next sun event is a sunset, false when it's a sunrise. */
        val sunIsSunset: Boolean = false,
    )

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({ loop() }, "weather").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }

    private fun loop() {
        var lat = 0.0; var lon = 0.0; var place = city; var located = false
        while (running.get()) {
            try {
                if (!located) {
                    val geo = geocode(city)
                    if (geo != null) {
                        lat = geo.first; lon = geo.second; place = geo.third; located = true
                    } else {
                        onUpdate("--", "city not found", city, Extras())
                        sleep(60_000); continue
                    }
                }
                val w = fetchForecast(lat, lon)
                if (w != null) onUpdate(w.first, w.second, place, w.third)
            } catch (e: Exception) {
                Log.w(TAG, "weather error: ${e.message}")
            }
            sleep(15 * 60_000L)
        }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun geocode(name: String): Triple<Double, Double, String>? {
        val q = URLEncoder.encode(name, "UTF-8")
        val obj = getJson("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json")
            ?: return null
        val results = obj.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        val label = buildString {
            append(r.optString("name"))
            r.optString("country_code").takeIf { it.isNotBlank() }?.let { append(", $it") }
        }
        return Triple(r.getDouble("latitude"), r.getDouble("longitude"), label)
    }

    private fun fetchForecast(lat: Double, lon: Double): Triple<String, String, Extras>? {
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val obj = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&minutely_15=precipitation" +
                "&daily=sunrise,sunset" +
                "&forecast_days=2&timezone=auto$unit"
        ) ?: return null
        val cur = obj.optJSONObject("current") ?: return null
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        val code = cur.optInt("weather_code", -1)
        if (temp.isNaN()) return null
        val symbol = if (fahrenheit) "°F" else "°C"

        val offsetSec = obj.optLong("utc_offset_seconds", 0L)
        val (sunEpoch, sunIsSunset) = nextSunEpoch(obj, offsetSec)
        val extras = Extras(
            rainStartEpoch = nextRainEpoch(obj, offsetSec),
            rainHorizonEpoch = System.currentTimeMillis() + 60 * 60_000L,
            sunEventEpoch = sunEpoch,
            sunIsSunset = sunIsSunset,
        )
        return Triple("${Math.round(temp)}$symbol", describe(code), extras)
    }

    /** First 15-min bucket within the next hour whose precipitation > 0, as UTC epoch millis. */
    private fun nextRainEpoch(obj: JSONObject, offsetSec: Long): Long? {
        val m = obj.optJSONObject("minutely_15") ?: return null
        val times = m.optJSONArray("time") ?: return null
        val precip = m.optJSONArray("precipitation") ?: return null
        val now = System.currentTimeMillis()
        val horizon = now + 60 * 60_000L
        for (i in 0 until minOf(times.length(), precip.length())) {
            val epoch = parseLocalIso(times.optString(i), offsetSec) ?: continue
            if (epoch < now - 15 * 60_000L || epoch > horizon) continue
            if (precip.optDouble(i, 0.0) > 0.0) return epoch
        }
        return null
    }

    /** Next sunrise/sunset after now → (epoch, isSunset). Scans today then tomorrow. */
    private fun nextSunEpoch(obj: JSONObject, offsetSec: Long): Pair<Long, Boolean> {
        val daily = obj.optJSONObject("daily") ?: return 0L to false
        val sunrise = daily.optJSONArray("sunrise") ?: return 0L to false
        val sunset = daily.optJSONArray("sunset") ?: return 0L to false
        val now = System.currentTimeMillis()
        var best = Long.MAX_VALUE; var isSunset = false
        for (i in 0 until sunrise.length()) {
            parseLocalIso(sunrise.optString(i), offsetSec)?.let { if (it in (now + 1)until best) { best = it; isSunset = false } }
        }
        for (i in 0 until sunset.length()) {
            parseLocalIso(sunset.optString(i), offsetSec)?.let { if (it in (now + 1)until best) { best = it; isSunset = true } }
        }
        return if (best == Long.MAX_VALUE) 0L to false else best to isSunset
    }

    /** Parse an Open-Meteo local-time ISO string ("2026-06-18T21:30") to a UTC epoch using the offset. */
    private fun parseLocalIso(s: String?, offsetSec: Long): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            (fmt.parse(s)?.time ?: return null) - offsetSec * 1000L
        }.getOrNull()
    }

    private fun getJson(spec: String): JSONObject? {
        val c = (URL(spec).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; requestMethod = "GET"
        }
        return try {
            if (c.responseCode != 200) return null
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** WMO weather interpretation codes → short label + emoji. */
    private fun describe(code: Int): String = when (code) {
        0 -> "☀️ Clear"
        1, 2 -> "🌤️ Partly cloudy"
        3 -> "☁️ Cloudy"
        45, 48 -> "🌫️ Fog"
        51, 53, 55, 56, 57 -> "🌦️ Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️ Rain"
        71, 73, 75, 77, 85, 86 -> "🌨️ Snow"
        95, 96, 99 -> "⛈️ Storm"
        else -> "Weather"
    }

    companion object { private const val TAG = "WeatherClient" }
}
