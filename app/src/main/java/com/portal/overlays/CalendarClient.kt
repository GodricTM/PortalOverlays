package com.portal.overlays

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls a public iCalendar (.ics) URL and surfaces the next upcoming events. Keyless and GMS-free, so
 * it works with any "secret iCal address" from Google / Apple / Outlook (and `webcal://` links). Real
 * data only — nothing is shown until the user supplies a link. Refreshed every 15 minutes.
 *
 * Handles non-recurring events plus a basic RRULE next-occurrence expansion (FREQ DAILY/WEEKLY/
 * MONTHLY/YEARLY with INTERVAL + UNTIL), which covers the common "weekly standup" / "yearly birthday"
 * cases. BYDAY and other RRULE refinements are not expanded (v1).
 */
class CalendarClient(
    private val url: String,
    private val onEvents: (List<Event>) -> Unit,
) {
    data class Event(val startEpoch: Long, val allDay: Boolean, val title: String)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({ loop() }, "calendar").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }

    private fun loop() {
        while (running.get()) {
            try {
                val body = fetch(url)
                if (body != null) onEvents(parse(body))
            } catch (e: Exception) {
                Log.w(TAG, "calendar error: ${e.message}")
            }
            sleep(REFRESH_MS)
        }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun fetch(spec: String): String? {
        val httpSpec = spec.replaceFirst(Regex("(?i)^webcal://"), "https://")
        val c = (URL(httpSpec).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000; requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PortalOverlays/calendar")
        }
        return try {
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parse(raw: String): List<Event> {
        // RFC 5545 line unfolding: a CRLF followed by a space/tab continues the previous line.
        val unfolded = raw.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "")
        val now = System.currentTimeMillis()
        val todayStart = startOfToday()
        val out = ArrayList<Event>()
        var inEvent = false
        var dtStartLine: String? = null
        var summary: String? = null
        var rrule: String? = null
        for (line in unfolded.split("\n")) {
            when {
                line.startsWith("BEGIN:VEVENT", true) -> { inEvent = true; dtStartLine = null; summary = null; rrule = null }
                line.startsWith("END:VEVENT", true) -> {
                    val start = dtStartLine?.let { parseDate(it) }
                    if (inEvent && start?.first != null) {
                        val occ = nextOccurrence(start.first!!, rrule, now)
                        val title = summary?.let { unescape(it) }?.takeIf { it.isNotBlank() } ?: "(busy)"
                        val relevant = if (start.second) occ >= todayStart else occ >= now - 60 * 60_000L
                        if (relevant) out.add(Event(occ, start.second, title))
                    }
                    inEvent = false
                }
                inEvent && line.startsWith("DTSTART", true) -> dtStartLine = line
                inEvent && line.startsWith("SUMMARY", true) -> summary = line.substringAfter(":", "")
                inEvent && line.startsWith("RRULE", true) -> rrule = line.substringAfter(":", "")
            }
        }
        return out.sortedBy { it.startEpoch }.take(MAX_EVENTS)
    }

    /** (epoch, isAllDay) from a DTSTART line, handling UTC ("…Z"), floating/TZID local, and VALUE=DATE. */
    private fun parseDate(line: String): Pair<Long?, Boolean> {
        val value = line.substringAfter(":", "").trim()
        val params = line.substringBefore(":").uppercase()
        val allDay = params.contains("VALUE=DATE") || (value.length == 8 && !value.contains("T"))
        return try {
            when {
                allDay -> SimpleDateFormat("yyyyMMdd", Locale.US)
                    .apply { timeZone = TimeZone.getDefault() }.parse(value)?.time to true
                value.endsWith("Z") -> SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time to false
                else -> SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                    .apply { timeZone = TimeZone.getDefault() }.parse(value)?.time to false
            }
        } catch (e: Exception) { null to allDay }
    }

    /** Advance a recurring event to its first occurrence at/after [now]; non-recurring returns [start]. */
    private fun nextOccurrence(start: Long, rrule: String?, now: Long): Long {
        if (rrule == null || start >= now) return start
        val parts = rrule.split(";").mapNotNull {
            val kv = it.split("=", limit = 2); if (kv.size == 2) kv[0].uppercase() to kv[1] else null
        }.toMap()
        val freq = parts["FREQ"]?.uppercase() ?: return start
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val until = parts["UNTIL"]?.let { parseDate("DTSTART:$it").first }
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        var guard = 0
        while (cal.timeInMillis < now && guard < 4000) {
            when (freq) {
                "DAILY" -> cal.add(Calendar.DAY_OF_MONTH, interval)
                "WEEKLY" -> cal.add(Calendar.DAY_OF_MONTH, 7 * interval)
                "MONTHLY" -> cal.add(Calendar.MONTH, interval)
                "YEARLY" -> cal.add(Calendar.YEAR, interval)
                else -> return start
            }
            guard++
            if (until != null && cal.timeInMillis > until) return start // series ended → filtered as past
        }
        return cal.timeInMillis
    }

    private fun unescape(s: String): String =
        s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",")
            .replace("\\;", ";").replace("\\\\", "\\").trim()

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        private const val TAG = "CalendarClient"
        private const val MAX_EVENTS = 8
        private const val REFRESH_MS = 15 * 60_000L
    }
}
