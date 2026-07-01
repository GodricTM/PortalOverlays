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
    /** Base URL of the ntfy server. Defaults to the public ntfy.sh; point it at a self-hosted
     *  instance (e.g. https://ntfy.example.com) to keep messages on your own server. */
    var ntfyServer: String
        get() = str("ntfyServer", "https://ntfy.sh"); set(v) = setStr("ntfyServer", v.trim())
    /** Optional access token for a protected / self-hosted topic, sent as an `Authorization: Bearer`
     *  header. Leave blank for public topics. */
    var ntfyToken: String
        get() = str("ntfyToken", ""); set(v) = setStr("ntfyToken", v.trim())

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
        get() = str("weatherCity", "New York"); set(v) = setStr("weatherCity", v.trim())
    var weatherFahrenheit: Boolean
        get() = bool("weatherFahrenheit", false); set(v) = setBool("weatherFahrenheit", v)

    // ---- battery widget ---------------------------------------------------
    var batteryEnabled: Boolean
        get() = bool("batteryEnabled", false); set(v) = setBool("batteryEnabled", v)

    // ---- now-playing widget ----------------------------------------------
    /** Float the currently playing track (art + title + transport) using notification access. */
    var nowPlayingEnabled: Boolean
        get() = bool("nowPlayingEnabled", true); set(v) = setBool("nowPlayingEnabled", v)
    /** When media is playing, open the full card automatically instead of starting as a small bubble. */
    var nowPlayingStartExpanded: Boolean
        get() = bool("nowPlayingStartExpanded", false); set(v) = setBool("nowPlayingStartExpanded", v)
    /** Docked widget shape: "bubble" (cover-art button), "strip" (floating bar) or "edge" (top/bottom band). */
    var nowPlayingDockStyle: String
        get() = str("nowPlayingDockStyle", "bubble"); set(v) = setStr("nowPlayingDockStyle", v)
    /** Hide the docked widget when nothing is playing; it reappears the moment media starts. */
    var nowPlayingAutoHide: Boolean
        get() = bool("nowPlayingAutoHide", true); set(v) = setBool("nowPlayingAutoHide", v)
    /** Edge bar position when the dock style is "edge": "top" or "bottom". */
    var nowPlayingEdgePosition: String
        get() = str("nowPlayingEdgePosition", "top"); set(v) = setStr("nowPlayingEdgePosition", v)
    /** Bubble dock look: "rounded", "circle", "square" or "minimal". */
    var nowPlayingBubbleStyle: String
        get() = str("nowPlayingBubbleStyle", "rounded"); set(v) = setStr("nowPlayingBubbleStyle", v)
    /** Bubble dock size: "small", "medium" or "large". */
    var nowPlayingBubbleSize: String
        get() = str("nowPlayingBubbleSize", "medium"); set(v) = setStr("nowPlayingBubbleSize", v)
    /** Background visualizer style for the full-screen now-playing overlay. */
    var nowPlayingVisualizerStyle: String
        get() = str("nowPlayingVisualizerStyle", "waves"); set(v) = setStr("nowPlayingVisualizerStyle", v)
    /** Full-screen layout style for the now-playing overlay. */
    var nowPlayingLayoutStyle: String
        get() = str("nowPlayingLayoutStyle", "sidecar"); set(v) = setStr("nowPlayingLayoutStyle", v)
    /** Show the playback progress bar and elapsed/total time on the full now-playing card. */
    var nowPlayingShowProgress: Boolean
        get() = bool("nowPlayingShowProgress", true); set(v) = setBool("nowPlayingShowProgress", v)
    /** Drive the full-card visualizer from live audio (Visualizer API) instead of a synthetic animation. */
    var nowPlayingSoundReactive: Boolean
        get() = bool("nowPlayingSoundReactive", false); set(v) = setBool("nowPlayingSoundReactive", v)

    // ---- screensaver (DreamService) --------------------------------------
    // The on-device screensaver Portal Overlays provides. Unlike the floating overlays (which a
    // running screensaver/dream composites over and hides), a dream draws ON the idle screen, so the
    // now-playing card + clock survive when the screen saver kicks in. The user picks this as the
    // device screensaver; see the Screensaver tab for activation.
    /** Background drawn behind the screensaver content: "black", "photo" or "web". */
    var screensaverBackground: String
        get() = str("screensaverBackground", "black"); set(v) = setStr("screensaverBackground", v)
    /** Web page URL used when the background is "web" (e.g. an Immich Kiosk page). */
    var screensaverWebUrl: String
        get() = str("screensaverWebUrl", ""); set(v) = setStr("screensaverWebUrl", v.trim())
    /** content:// URI of the still image used when the background is "photo". */
    var screensaverPhotoUri: String
        get() = str("screensaverPhotoUri", ""); set(v) = setStr("screensaverPhotoUri", v.trim())
    /** Show the large clock + date on the screensaver. */
    var screensaverShowClock: Boolean
        get() = bool("screensaverShowClock", true); set(v) = setBool("screensaverShowClock", v)
    /** Show the battery level on the screensaver. */
    var screensaverShowBattery: Boolean
        get() = bool("screensaverShowBattery", true); set(v) = setBool("screensaverShowBattery", v)
    /** Show the now-playing card (cover, title/artist, bouncing bars) on the screensaver. */
    var screensaverShowNowPlaying: Boolean
        get() = bool("screensaverShowNowPlaying", true); set(v) = setBool("screensaverShowNowPlaying", v)
    /** Screensaver now-playing presentation: "card" (floating bar) or "cover" (fullscreen art + visualizer). */
    var screensaverNowPlayingLayout: String
        get() = str("screensaverNowPlayingLayout", "card"); set(v) = setStr("screensaverNowPlayingLayout", v)
    /** Visualizer style behind the fullscreen "cover" now-playing on the screensaver. */
    var screensaverVisualizerStyle: String
        get() = str("screensaverVisualizerStyle", "spectrum"); set(v) = setStr("screensaverVisualizerStyle", v)
    /** Drive the screensaver visualizer from the live mic (experimental; laggy on Portal's shared mic). */
    var screensaverSoundReactive: Boolean
        get() = bool("screensaverSoundReactive", false); set(v) = setBool("screensaverSoundReactive", v)
    /** Keep the display bright while the screensaver is showing (so it truly stays on-screen). */
    var screensaverKeepBright: Boolean
        get() = bool("screensaverKeepBright", true); set(v) = setBool("screensaverKeepBright", v)
    /** One-time screensaver gesture hint toast ("Swipe art · double-tap clock") already shown. */
    var screensaverGestureHintShown: Boolean
        get() = bool("screensaverGestureHintShown", false); set(v) = setBool("screensaverGestureHintShown", v)

    // ---- sticky note widget ----------------------------------------------
    var noteEnabled: Boolean
        get() = bool("noteEnabled", false); set(v) = setBool("noteEnabled", v)
    var noteText: String
        get() = str("noteText", ""); set(v) = setStr("noteText", v)

    // ---- agenda / calendar widget ----------------------------------------
    /** Draggable card listing the next few events from the iCal feed. */
    var agendaEnabled: Boolean
        get() = bool("agendaEnabled", false); set(v) = setBool("agendaEnabled", v)
    /** Public iCalendar (.ics / webcal) URL shared by the agenda widget and the strip next-event item. */
    var calendarUrl: String
        get() = str("calendarUrl", ""); set(v) = setStr("calendarUrl", v.trim())

    // ---- status strip -----------------------------------------------------
    var stripEnabled: Boolean
        get() = bool("stripEnabled", true); set(v) = setBool("stripEnabled", v)
    /** "bottom" or "top". Bottom by default — Portal's system pills live in the top strip. */
    var stripPosition: String
        get() = str("stripPosition", "bottom"); set(v) = setStr("stripPosition", v)
    /** Visual style of the strip chrome. See OverlayService.stripStyleFor() for the catalogue. */
    var stripStyle: String
        get() = str("stripStyle", "default"); set(v) = setStr("stripStyle", v)
    var stripShowClock: Boolean
        get() = bool("stripShowClock", true); set(v) = setBool("stripShowClock", v)
    var stripShowDate: Boolean
        get() = bool("stripShowDate", true); set(v) = setBool("stripShowDate", v)
    var stripShowWeather: Boolean
        get() = bool("stripShowWeather", true); set(v) = setBool("stripShowWeather", v)
    var stripShowBattery: Boolean
        get() = bool("stripShowBattery", false); set(v) = setBool("stripShowBattery", v)
    var stripShowNetwork: Boolean
        get() = bool("stripShowNetwork", true); set(v) = setBool("stripShowNetwork", v)
    var stripShowNtfy: Boolean
        get() = bool("stripShowNtfy", true); set(v) = setBool("stripShowNtfy", v)
    /** Animated dot when audio/video is playing (reads the active media session). */
    var stripShowStreaming: Boolean
        get() = bool("stripShowStreaming", false); set(v) = setBool("stripShowStreaming", v)
    /** Green/red dot for an active VPN tunnel (generic — Android won't reveal which VPN app). */
    var stripShowVpn: Boolean
        get() = bool("stripShowVpn", false); set(v) = setBool("stripShowVpn", v)
    /** Wi-Fi signal bars; tap to show the device IP. */
    var stripShowWifi: Boolean
        get() = bool("stripShowWifi", true); set(v) = setBool("stripShowWifi", v)
    /** ISO week number (e.g. "W25"). Local-only, no network. */
    var stripShowWeek: Boolean
        get() = bool("stripShowWeek", true); set(v) = setBool("stripShowWeek", v)
    /** Rain in the next hour from Open-Meteo (e.g. "🌧 in 40min" / "no rain 1h"). */
    var stripShowRain: Boolean
        get() = bool("stripShowRain", true); set(v) = setBool("stripShowRain", v)
    /** Time until the next sunset or sunrise from Open-Meteo (e.g. "☀ 3h12m"). */
    var stripShowSun: Boolean
        get() = bool("stripShowSun", true); set(v) = setBool("stripShowSun", v)
    /** Wind speed from Open-Meteo (e.g. "💨 12 km/h"). */
    var stripShowWind: Boolean
        get() = bool("stripShowWind", false); set(v) = setBool("stripShowWind", v)
    /** UV index from Open-Meteo (e.g. "UV 6"). */
    var stripShowUv: Boolean
        get() = bool("stripShowUv", false); set(v) = setBool("stripShowUv", v)
    /** Severe-weather alert when WMO code is thunder/hail (e.g. "⛈ alert"). */
    var stripShowWeatherAlert: Boolean
        get() = bool("stripShowWeatherAlert", false); set(v) = setBool("stripShowWeatherAlert", v)
    /** Next calendar event from the iCal feed (e.g. "Standup · in 25m"). Needs a calendar URL. */
    var stripShowAgenda: Boolean
        get() = bool("stripShowAgenda", false); set(v) = setBool("stripShowAgenda", v)
    /** Foreground app / Portal UI label shown in the strip. */
    var stripShowContext: Boolean
        get() = bool("stripShowContext", true); set(v) = setBool("stripShowContext", v)
    /** Put Back / Home / Recents buttons on the right side of the strip. */
    var stripShowNavButtons: Boolean
        get() = bool("stripShowNavButtons", true); set(v) = setBool("stripShowNavButtons", v)

    // ---- banners ----------------------------------------------------------
    var bannerSeconds: Int
        get() = int("bannerSeconds", 5); set(v) = setInt("bannerSeconds", v.coerceIn(2, 30))
    var bannerPosition: String
        get() = str("bannerPosition", "top"); set(v) = setStr("bannerPosition", v)

    // ---- breaking news ----------------------------------------------------
    /** Route high-priority ntfy messages (priority 5, or a "breaking"/"urgent" tag) to a flashing
     *  full-attention popup instead of a normal banner. */
    var breakingNewsEnabled: Boolean
        get() = bool("breakingNewsEnabled", true); set(v) = setBool("breakingNewsEnabled", v)
    /** Read the headline aloud through the device TTS engine (prefers the sideloaded Portal TTS
     *  engine; silent fallback when no engine is installed). */
    var breakingNewsSpeak: Boolean
        get() = bool("breakingNewsSpeak", true); set(v) = setBool("breakingNewsSpeak", v)
    /** How long the breaking-news popup stays on screen, in seconds. */
    var breakingNewsSeconds: Int
        get() = int("breakingNewsSeconds", 9); set(v) = setInt("breakingNewsSeconds", v.coerceIn(4, 30))
    /** TTS read-out volume for the breaking-news headline, as a percent of the stream volume (0-100).
     *  Maps to the engine KEY_PARAM_VOLUME param (0.0-1.0). */
    var breakingNewsVolume: Int
        get() = int("breakingNewsVolume", 100); set(v) = setInt("breakingNewsVolume", v.coerceIn(0, 100))

    // ---- alerts -----------------------------------------------------------
    var alertVibrate: Boolean
        get() = bool("alertVibrate", true); set(v) = setBool("alertVibrate", v)
    /** Play a sound when an alert fires. */
    var alertSound: Boolean
        get() = bool("alertSound", false); set(v) = setBool("alertSound", v)
    /** Per-kind sound URIs (empty = the device's default notification tone). */
    fun soundUri(kind: String): String = str("sound_$kind", "")
    fun setSoundUri(kind: String, uri: String) = setStr("sound_$kind", uri)

    // ---- ticker -----------------------------------------------------------
    /** Thin scrolling text strip along the bottom edge. */
    var tickerEnabled: Boolean
        get() = bool("tickerEnabled", false); set(v) = setBool("tickerEnabled", v)
    /** Source feed URL - an RSS/Atom XML feed or a JSON array/object. Empty = nothing to show. */
    var tickerUrl: String
        get() = str("tickerUrl", TICKER_SOURCES.first().second); set(v) = setStr("tickerUrl", v.trim())
    /** "bottom" or "top" edge of the screen. */
    var tickerPosition: String
        get() = str("tickerPosition", "bottom"); set(v) = setStr("tickerPosition", v)
    /** Scroll speed in pixels/second. */
    var tickerSpeed: Int
        get() = int("tickerSpeed", 60); set(v) = setInt("tickerSpeed", v.coerceIn(20, 200))
    /** Visual style of the ticker chrome. See OverlayService.tickerStyleFor() for the catalogue. */
    var tickerStyle: String
        get() = str("tickerStyle", "default"); set(v) = setStr("tickerStyle", v)

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
    /** Lock button — immediately locks the display (accessibility GLOBAL_ACTION_LOCK_SCREEN). */
    var navLock: Boolean
        get() = bool("navLock", false); set(v) = setBool("navLock", v)
    var navVertical: Boolean
        get() = bool("navVertical", false); set(v) = setBool("navVertical", v)
    /** Lock the nav cluster in place so it can't be dragged off its position until unlocked. */
    var navLocked: Boolean
        get() = bool("navLocked", false); set(v) = setBool("navLocked", v)
    /** User dismissed the post-boot "nav won't work" overlay until the next reboot. */
    var navWarningDismissed: Boolean
        get() = bool("navWarningDismissed", false); set(v) = setBool("navWarningDismissed", v)
    /** BootReceiver couldn't re-write enabled_accessibility_services (needs adb). */
    var accessibilityBootRestoreFailed: Boolean
        get() = bool("accessibilityBootRestoreFailed", false); set(v) = setBool("accessibilityBootRestoreFailed", v)
    /** Visual style of the nav cluster — see [NAV_STYLES]. */
    var navStyle: String
        get() = str("navStyle", "pill"); set(v) = setStr("navStyle", v)
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
        /** Nav cluster styles: id → human label. Order drives the settings selector. */
        val STRIP_STYLES = listOf(
            "default" to "Dense Dark",
            "accented" to "Accented",
            "three-zones" to "Three Zones",
            "segments" to "Segments",
            "mono" to "Minimal Mono",
            "two-rows" to "Two Rows",
            "frosted" to "Frosted Glass",
            "chips" to "Tinted Chips",
            "aurora" to "Aurora",
            "daylight" to "Daylight",
            "hud" to "HUD Tactical",
            "sunset" to "Sunset",
            "ocean" to "Ocean",
            "graphite" to "Mono Graphite",
            "oled" to "OLED Black",
            "paper" to "E-ink Paper",
            "iconic" to "Iconic",
            "hicontrast" to "High Contrast",
            "sky" to "Sky",
        )

        val NAV_STYLES = listOf(
            "pill" to "Pill segments",
            "underline" to "Underline indicator",
            "ghost" to "Ghost pill",
            "squares" to "Floating squares",
            "glass" to "Dark glass",
            "label" to "Icon + label",
            "color" to "Colour-coded",
            "dot" to "Dot indicator",
        )

        val NOW_PLAYING_VISUALIZERS = listOf(
            "spectrum" to "Spectrum",
            "waves" to "Waves",
            "rings" to "Rings",
            "constellation" to "Constellation",
            "prism" to "Prism",
        )

        val SCREENSAVER_NP_LAYOUTS = listOf(
            "card" to "Card",
            "cover" to "Cover",
        )
        val NOW_PLAYING_LAYOUTS = listOf(
            "sidecar" to "Sidecar",
            "centered" to "Centered",
            "poster" to "Poster",
        )
        val NOW_PLAYING_DOCKS = listOf(
            "bubble" to "Bubble",
            "strip" to "Strip",
            "edge" to "Edge bar",
        )
        val NOW_PLAYING_BUBBLE_STYLES = listOf(
            "rounded" to "Rounded",
            "circle" to "Circle",
            "square" to "Square",
            "minimal" to "Minimal",
        )
        val NOW_PLAYING_SIZES = listOf(
            "small" to "Small",
            "medium" to "Medium",
            "large" to "Large",
        )

        val SCREENSAVER_BACKGROUNDS = listOf(
            "black" to "Black",
            "photo" to "Photo",
            "web" to "Web page",
        )

        // The ticker shares STRIP_STYLES (see OverlayService.tickerStyleFor), so there's no separate list.

        val TICKER_SOURCES = listOf(
            "BBC News" to "https://feeds.bbci.co.uk/news/rss.xml",
            "BBC World" to "https://feeds.bbci.co.uk/news/world/rss.xml",
            "AP Top News" to "https://apnews.com/hub/apf-topnews?output=rss",
            "NPR News Now" to "https://feeds.npr.org/510320/podcast.xml",
            "NPR News Now 5 min" to "https://feeds.npr.org/500005/podcast.xml",
        )

        /** Live-finance ticker sources — handled specially by TickerClient (not feed URLs). */
        val TICKER_FINANCE = listOf(
            "Crypto (CoinGecko)" to "finance:crypto",
            "Stocks (Stooq)" to "finance:stocks",
        )

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
