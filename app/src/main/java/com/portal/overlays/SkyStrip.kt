package com.portal.overlays

/**
 * Time-of-day sky gradient for the "Sky" status-strip style — a port of the Immortal launcher's
 * SkyColors so the strip matches the launcher's sun-driven background: dawn pinks, midday blue,
 * dusk orange, night near-black. Pure ARGB ints (no Compose) so it can run in the overlay service.
 * Returns a (start, end) pair used left→right across the thin strip.
 */
object SkyStrip {

    // Palette anchors (start, end) for each phase of the day — same values as SkyColors.
    private val NIGHT = 0xFF0B1026.toInt() to 0xFF05060E.toInt()
    private val DAWN = 0xFF2A3A6B.toInt() to 0xFFEF9A6B.toInt() // deep blue → warm pink
    private val MORNING = 0xFF3A7BD5.toInt() to 0xFF8FD3F4.toInt()
    private val MIDDAY = 0xFF2980B9.toInt() to 0xFF6DD5FA.toInt()
    private val EVENING = 0xFF3A7BD5.toInt() to 0xFF8FD3F4.toInt()
    private val DUSK = 0xFF2C3E66.toInt() to 0xFFE96443.toInt() // blue → sunset orange
    private val TWILIGHT = 0xFF1A1F3D.toInt() to 0xFF3A2A4D.toInt()

    /** Gradient (start, end) for [nowMin] given today's [sunriseMin]/[sunsetMin]
     *  (all minutes-of-day, 0..1439). Blends smoothly between phase anchors. */
    fun gradientFor(nowMin: Int, sunriseMin: Int, sunsetMin: Int): Pair<Int, Int> {
        val dawnStart = sunriseMin - 40
        val dawnEnd = sunriseMin + 40
        val morningEnd = sunriseMin + 120
        val midday = (sunriseMin + sunsetMin) / 2
        val eveningStart = sunsetMin - 120
        val duskStart = sunsetMin - 40
        val duskEnd = sunsetMin + 40
        val twilightEnd = sunsetMin + 80

        return when {
            nowMin < dawnStart -> NIGHT
            nowMin < dawnEnd -> blend(NIGHT, DAWN, frac(nowMin, dawnStart, dawnEnd))
            nowMin < morningEnd -> blend(DAWN, MORNING, frac(nowMin, dawnEnd, morningEnd))
            nowMin < midday -> blend(MORNING, MIDDAY, frac(nowMin, morningEnd, midday))
            nowMin < eveningStart -> blend(MIDDAY, EVENING, frac(nowMin, midday, eveningStart))
            nowMin < duskStart -> blend(EVENING, DUSK, frac(nowMin, eveningStart, duskStart))
            nowMin < duskEnd -> blend(DUSK, TWILIGHT, frac(nowMin, duskStart, duskEnd))
            nowMin < twilightEnd -> blend(TWILIGHT, NIGHT, frac(nowMin, duskEnd, twilightEnd))
            else -> NIGHT
        }
    }

    private fun frac(now: Int, start: Int, end: Int): Float =
        if (end <= start) 1f else ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun blend(a: Pair<Int, Int>, b: Pair<Int, Int>, t: Float): Pair<Int, Int> =
        lerp(a.first, b.first, t) to lerp(a.second, b.second, t)

    /** Per-channel ARGB interpolation. */
    private fun lerp(a: Int, b: Int, t: Float): Int {
        val af = (a ushr 24) and 0xFF; val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF; val ab = a and 0xFF
        val bf = (b ushr 24) and 0xFF; val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF; val bb = b and 0xFF
        fun mix(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return (mix(af, bf) shl 24) or (mix(ar, br) shl 16) or (mix(ag, bg) shl 8) or mix(ab, bb)
    }
}
