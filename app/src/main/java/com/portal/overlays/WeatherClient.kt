package com.portal.overlays

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real current-conditions weather for the floating widget via Open-Meteo. Open-Meteo needs no API
 * key and has no Google dependency, so it works on Portal (which lacks GMS). A city name is geocoded
 * once, then current temperature + weather code are polled every 15 minutes.
 */
class WeatherClient(
    private val city: String,
    private val fahrenheit: Boolean = false,
    private val onUpdate: (temp: String, condition: String, place: String) -> Unit,
) {
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
                        onUpdate("--", "city not found", city)
                        sleep(60_000); continue
                    }
                }
                val w = fetchCurrent(lat, lon)
                if (w != null) onUpdate(w.first, w.second, place)
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

    private fun fetchCurrent(lat: Double, lon: Double): Pair<String, String>? {
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val obj = getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code$unit")
            ?: return null
        val cur = obj.optJSONObject("current") ?: return null
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        val code = cur.optInt("weather_code", -1)
        if (temp.isNaN()) return null
        val symbol = if (fahrenheit) "°F" else "°C"
        return Pair("${Math.round(temp)}$symbol", describe(code))
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
