package com.portal.overlays

/** Maps plain-language queries to control-panel tabs and sections. */
object SettingsSearch {

    data class Result(
        val tabId: String,
        val tabLabel: String,
        val section: String,
        val description: String,
        val keywords: List<String>,
        val score: Int = 0,
    )

    private data class Entry(
        val tabId: String,
        val tabLabel: String,
        val section: String,
        val description: String,
        val keywords: String,
    )

    val quickChips = listOf(
        "screensaver", "ntfy", "edge bar", "weather", "crypto", "nav", "labs", "history",
    )

    private val index = listOf(
        Entry("WIDGETS", "Widgets", "Clock", "Floating time, seconds, and date", "clock time seconds date 24 hour widget"),
        Entry("WIDGETS", "Widgets", "Weather", "Live Open-Meteo conditions widget", "weather city temperature fahrenheit celsius widget open-meteo"),
        Entry("WIDGETS", "Widgets", "Battery", "Battery level widget", "battery percent charge widget"),
        Entry("WIDGETS", "Widgets", "Sticky note", "Draggable reminder note", "note sticky text reminder widget"),
        Entry("WIDGETS", "Widgets", "Agenda", "Calendar feed card and strip line", "agenda calendar ics webcal events widget"),
        Entry("WIDGETS", "Widgets", "Safe zones", "Widgets avoid strip, ticker, and chrome when dragging", "widget safe zone collision drag nudge strip ticker"),

        Entry("NOW_PLAYING", "Now Playing", "Dock shape", "Bubble, strip, or edge bar dock", "now playing dock bubble strip edge bar shape widget"),
        Entry("NOW_PLAYING", "Now Playing", "Edge bar", "Full-width top or bottom now playing band", "edge bar top bottom transport progress equalizer"),
        Entry("NOW_PLAYING", "Now Playing", "Bubble style", "Rounded, circle, square, or minimal cover bubble", "bubble rounded circle square minimal size small large"),
        Entry("NOW_PLAYING", "Now Playing", "Auto-hide", "Hide dock when nothing is playing", "auto hide idle pause stop now playing"),
        Entry("NOW_PLAYING", "Now Playing", "Full card layout", "Sidecar, centered, or poster fullscreen player", "fullscreen layout sidecar centered poster visualizer"),
        Entry("NOW_PLAYING", "Now Playing", "Visualizer", "Waves, rings, constellation, prism, spectrum", "visualizer waves rings spectrum constellation prism"),
        Entry("NOW_PLAYING", "Now Playing", "Progress bar", "Elapsed time and track length on card", "progress bar elapsed duration length time"),
        Entry("NOW_PLAYING", "Now Playing", "Track history", "Last ~20 tracks on the full card", "history recent tracks log local"),
        Entry("NOW_PLAYING", "Now Playing", "Preview", "Preview the full now playing card in-app", "preview full card now playing"),

        Entry("SCREENSAVER", "Screensaver", "Background", "Black, photo, or web page (Immich kiosk)", "screensaver background black photo web immich kiosk url"),
        Entry("SCREENSAVER", "Screensaver", "Cover layout", "Fullscreen album art and visualizer on idle", "screensaver cover card layout visualizer fullscreen"),
        Entry("SCREENSAVER", "Screensaver", "Gestures", "Swipe art, double-tap clock to wake", "screensaver swipe album art double tap clock wake gesture remote"),
        Entry("SCREENSAVER", "Screensaver", "Clock & battery", "Large idle clock, date, and battery", "screensaver clock date battery idle"),
        Entry("SCREENSAVER", "Screensaver", "Activate", "Set as device screensaver or use set_screensaver.bat", "screensaver dream activate picker adb immortal"),

        Entry("STRIP", "Status strip", "Position & style", "Top or bottom strip and 19 visual styles", "strip status bar style dense aurora sky position top bottom"),
        Entry("STRIP", "Status strip", "Weather extras", "Rain, sunset, wind, UV, severe alert", "strip rain sunset wind uv alert weather open-meteo"),
        Entry("STRIP", "Status strip", "Foreground app", "Tap active app for open, info, force stop", "strip foreground app package open force stop"),
        Entry("STRIP", "Status strip", "ntfy preview", "Tap ntfy line for last push message", "strip ntfy notify preview last message"),
        Entry("STRIP", "Status strip", "Nav buttons", "Back, home, recents on the strip", "strip nav back home recents buttons"),
        Entry("STRIP", "Status strip", "Indicators", "Streaming, VPN, Wi-Fi, network speed", "strip streaming vpn wifi network speed week"),

        Entry("TICKER", "Ticker", "Feed URL", "RSS, Atom, JSON, or finance watchlist", "ticker rss atom json feed url news"),
        Entry("TICKER", "Ticker", "Finance watchlist", "finance:crypto:BTC,ETH or finance:stocks:AAPL,TSLA", "ticker finance crypto stocks watchlist coingecko stooq bbc ap npr"),
        Entry("TICKER", "Ticker", "Placement", "Top or bottom scrolling strip", "ticker position top bottom speed scroll"),

        Entry("SETTINGS", "Settings", "Weather location", "City for widgets and strip weather", "settings weather city location fahrenheit units"),
        Entry("SETTINGS", "Settings", "Labs", "Experimental live-audio visualizer reactor", "settings labs experimental live audio mic reactor visualizer"),

        Entry("NOTIFY", "Notifications", "ntfy topic", "Push banners from ntfy.sh or self-hosted server", "ntfy notify topic server push banner token self-hosted"),
        Entry("NOTIFY", "Notifications", "Mirror", "Show other apps' notifications as banners", "notify mirror whatsapp messenger system notification"),
        Entry("NOTIFY", "Notifications", "Breaking news", "Urgent full-screen popup and spoken TTS alert", "breaking news urgent priority tts speak popup"),
        Entry("NOTIFY", "Notifications", "Alert sounds", "Per-kind doorbell, timer, reminder tones", "sound alert doorbell timer reminder ringtone"),

        Entry("NAV", "Navigation", "Floating cluster", "Back, home, recents, screenshot, lock", "nav navigation back home recents screenshot lock cluster"),
        Entry("NAV", "Navigation", "Lock position", "Pin the nav cluster so it cannot be dragged", "nav lock position pin drag"),
        Entry("NAV", "Navigation", "Button style", "Pill, ghost, glass, and other nav looks", "nav style pill ghost glass underline icon"),
        Entry("NAV", "Navigation", "App switcher", "Portal Mini fallback when recents is missing", "nav app switcher recents overview mini"),

        Entry("LOOK", "Appearance", "Accent & opacity", "Highlight colour and overlay transparency", "appearance accent color opacity theme"),
        Entry("LOOK", "Appearance", "Corners & text", "Corner radius and text scale", "appearance radius corners text scale size"),

        Entry("ABOUT", "About", "Permissions", "Overlay, accessibility, notification access setup", "about permission overlay accessibility adb enable_portal_permissions"),
        Entry("ABOUT", "About", "Updates", "Check for new GitHub releases", "about update version download release"),
    )

    fun search(query: String): List<Result> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val words = q.split(" ").filter { it.length > 1 }
        return index.mapNotNull { entry ->
            var score = 0
            if (entry.section.lowercase().contains(q)) score += 120
            if (entry.tabLabel.lowercase().contains(q)) score += 80
            if (entry.description.lowercase().contains(q)) score += 40
            if (entry.keywords.contains(q)) score += 100
            words.forEach { word ->
                if (entry.section.lowercase().contains(word)) score += 30
                if (entry.tabLabel.lowercase().contains(word)) score += 20
                if (entry.description.lowercase().contains(word)) score += 15
                if (entry.keywords.contains(word)) score += 25
            }
            if (score == 0) return@mapNotNull null
            Result(
                tabId = entry.tabId,
                tabLabel = entry.tabLabel,
                section = entry.section,
                description = entry.description,
                keywords = entry.keywords.split(" ").filter { it.length > 2 }.take(6),
                score = score,
            )
        }.sortedByDescending { it.score }
    }

    fun browseByTab(): List<Pair<String, List<Result>>> {
        return index
            .groupBy { it.tabId }
            .map { (tabId, entries) ->
                val label = entries.first().tabLabel
                label to entries.map { e ->
                    Result(
                        tabId = e.tabId,
                        tabLabel = e.tabLabel,
                        section = e.section,
                        description = e.description,
                        keywords = e.keywords.split(" ").filter { it.length > 2 }.take(6),
                    )
                }
            }
            .sortedBy { it.first }
    }
}
