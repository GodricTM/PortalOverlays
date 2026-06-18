package com.portal.overlays

import android.content.Context

/**
 * SharedPreferences-backed settings store shared by the control-panel activity and the overlay
 * service. Every overlay, widget and appearance option the UI exposes is persisted here so the
 * service can rebuild the exact same surface after a refresh or a reboot.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("overlays", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun setStr(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun setInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    // ---- service ----------------------------------------------------------
    var serviceEnabled: Boolean
        get() = bool("serviceEnabled", false); set(v) = setBool("serviceEnabled", v)

    // ---- onboarding -------------------------------------------------------
    /** First-run permission walkthrough has been completed/dismissed at least once. */
    var onboardingDone: Boolean
        get() = bool("onboardingDone", false); set(v) = setBool("onboardingDone", v)

    // ---- ntfy -------------------------------------------------------------
    var topic: String
        get() = str("topic", ""); set(v) = setStr("topic", v.trim())

    // ---- appearance -------------------------------------------------------
    var accentColor: Int
        get() = int("accentColor", DEFAULT_ACCENT); set(v) = setInt("accentColor", v)
    /** 30..100 — opacity of widget/strip/banner backgrounds. */
    var overlayOpacity: Int
        get() = int("overlayOpacity", 100); set(v) = setInt("overlayOpacity", v.coerceIn(30, 100))
    /** Corner radius in dp for cards. */
    var cornerRadius: Int
        get() = int("cornerRadius", 18); set(v) = setInt("cornerRadius", v.coerceIn(0, 40))
    /** Text scale percent applied to overlay text, 80..160. */
    var textScale: Int
        get() = int("textScale", 100); set(v) = setInt("textScale", v.coerceIn(80, 160))

    // ---- clock widget -----------------------------------------------------
    var clockEnabled: Boolean
        get() = bool("clockEnabled", false); set(v) = setBool("clockEnabled", v)
    var clock24h: Boolean
        get() = bool("clock24h", true); set(v) = setBool("clock24h", v)
    var clockSeconds: Boolean
        get() = bool("clockSeconds", false); set(v) = setBool("clockSeconds", v)
    var clockShowDate: Boolean
        get() = bool("clockShowDate", true); set(v) = setBool("clockShowDate", v)

    // ---- weather widget ---------------------------------------------------
    var weatherEnabled: Boolean
        get() = bool("weatherEnabled", false); set(v) = setBool("weatherEnabled", v)
    var weatherCity: String
        get() = str("weatherCity", ""); set(v) = setStr("weatherCity", v.trim())
    var weatherFahrenheit: Boolean
        get() = bool("weatherFahrenheit", false); set(v) = setBool("weatherFahrenheit", v)

    // ---- battery widget ---------------------------------------------------
    var batteryEnabled: Boolean
        get() = bool("batteryEnabled", false); set(v) = setBool("batteryEnabled", v)

    // ---- now-playing widget ----------------------------------------------
    /** Float the currently playing track (art + title + transport) using notification access. */
    var nowPlayingEnabled: Boolean
        get() = bool("nowPlayingEnabled", false); set(v) = setBool("nowPlayingEnabled", v)

    // ---- sticky note widget ----------------------------------------------
    var noteEnabled: Boolean
        get() = bool("noteEnabled", false); set(v) = setBool("noteEnabled", v)
    var noteText: String
        get() = str("noteText", ""); set(v) = setStr("noteText", v)

    // ---- status strip -----------------------------------------------------
    var stripEnabled: Boolean
        get() = bool("stripEnabled", true); set(v) = setBool("stripEnabled", v)
    /** "bottom" or "top". Bottom by default — Portal's system pills live in the top strip. */
    var stripPosition: String
        get() = str("stripPosition", "bottom"); set(v) = setStr("stripPosition", v)
    var stripShowClock: Boolean
        get() = bool("stripShowClock", true); set(v) = setBool("stripShowClock", v)
    var stripShowDate: Boolean
        get() = bool("stripShowDate", true); set(v) = setBool("stripShowDate", v)
    var stripShowWeather: Boolean
        get() = bool("stripShowWeather", false); set(v) = setBool("stripShowWeather", v)
    var stripShowBattery: Boolean
        get() = bool("stripShowBattery", false); set(v) = setBool("stripShowBattery", v)
    var stripShowNetwork: Boolean
        get() = bool("stripShowNetwork", true); set(v) = setBool("stripShowNetwork", v)
    var stripShowNtfy: Boolean
        get() = bool("stripShowNtfy", true); set(v) = setBool("stripShowNtfy", v)

    // ---- banners ----------------------------------------------------------
    var bannerSeconds: Int
        get() = int("bannerSeconds", 5); set(v) = setInt("bannerSeconds", v.coerceIn(2, 30))
    var bannerPosition: String
        get() = str("bannerPosition", "top"); set(v) = setStr("bannerPosition", v)

    // ---- alerts -----------------------------------------------------------
    var alertVibrate: Boolean
        get() = bool("alertVibrate", true); set(v) = setBool("alertVibrate", v)

    // ---- system-notification mirror --------------------------------------
    /** Mirror other apps' notifications (WhatsApp, Messenger, …) as floating banners. */
    var mirrorNotifications: Boolean
        get() = bool("mirrorNotifications", false); set(v) = setBool("mirrorNotifications", v)
    /** Skip persistent/ongoing notifications (music players, foreground services). */
    var mirrorSkipOngoing: Boolean
        get() = bool("mirrorSkipOngoing", true); set(v) = setBool("mirrorSkipOngoing", v)

    // ---- navigation cluster ----------------------------------------------
    // Floating navigation is the headline feature — on by default with Back / Home / Recents.
    var navEnabled: Boolean
        get() = bool("navEnabled", true); set(v) = setBool("navEnabled", v)
    var navBack: Boolean
        get() = bool("navBack", true); set(v) = setBool("navBack", v)
    var navHome: Boolean
        get() = bool("navHome", true); set(v) = setBool("navHome", v)
    var navRecents: Boolean
        get() = bool("navRecents", true); set(v) = setBool("navRecents", v)
    var navControlCenter: Boolean
        get() = bool("navControlCenter", false); set(v) = setBool("navControlCenter", v)
    var navScreenshot: Boolean
        get() = bool("navScreenshot", true); set(v) = setBool("navScreenshot", v)
    var navVertical: Boolean
        get() = bool("navVertical", false); set(v) = setBool("navVertical", v)
    /** Countdown seconds before a screenshot is taken (0 = instant). */
    var screenshotDelay: Int
        get() = int("screenshotDelay", 3); set(v) = setInt("screenshotDelay", v.coerceIn(0, 10))

    // ---- per-overlay saved positions -------------------------------------
    fun getX(key: String, def: Int) = sp.getInt("x_$key", def)
    fun getY(key: String, def: Int) = sp.getInt("y_$key", def)
    fun setPos(key: String, x: Int, y: Int) =
        sp.edit().putInt("x_$key", x).putInt("y_$key", y).apply()
    fun resetPositions() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("x_") || it.startsWith("y_") }.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        const val DEFAULT_ACCENT = 0xFFFF375F.toInt()
        val ACCENT_PRESETS = listOf(
            0xFF4C8DFF.toInt(), // blue
            0xFF34C759.toInt(), // green
            0xFFFF9F0A.toInt(), // amber
            0xFFFF375F.toInt(), // red
            0xFFBF5AF2.toInt(), // purple
            0xFF64D2FF.toInt(), // cyan
        )
    }
}
