package com.portal.overlays

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaMetadata
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.animation.ObjectAnimator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.text.format.Formatter
import android.text.TextUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the system-overlay surface. It draws every overlay on top of any app
 * (including the Immortal launcher) and runs the ntfy.sh + weather listeners. Overlays supported:
 *   - clock widget (12/24h, seconds, date)        - weather widget (Open-Meteo)
 *   - battery widget                               - now-playing widget
 *   - sticky-note widget
 *   - status strip (configurable live-info bar)    - toast banners (ntfy-driven)
 *   - alert popups (doorbell / timer / reminder)   - navigation cluster (back / home / recents / control center)
 * Appearance (accent colour, opacity, corner radius, text scale) is read from [Prefs] on every
 * refresh so changes in the control panel apply live.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var clockView: View? = null
    private var clockTime: TextView? = null
    private var clockDate: TextView? = null
    private var clockDot: View? = null
    private var weatherView: View? = null
    private var weatherTemp: TextView? = null
    private var weatherCond: TextView? = null
    private var weatherPlace: TextView? = null
    private var batteryView: View? = null
    private var batteryText: TextView? = null
    private var nowPlayingView: View? = null
    private var nowPlayingMiniArt: ImageView? = null
    private var nowPlayingMiniGlyph: TextView? = null
    // Strip-dock elements (the "much larger" docked widget: cover + title/artist + progress).
    private var nowPlayingStripArt: ImageView? = null
    private var nowPlayingStripTitle: TextView? = null
    private var nowPlayingStripSubtitle: TextView? = null
    private var nowPlayingStripProgress: android.widget.ProgressBar? = null
    private var nowPlayingStripGlyph: TextView? = null
    // Edge-bar-only extras (source-app icon, mini equaliser, elapsed/total time).
    private var nowPlayingEdgeAppIcon: ImageView? = null
    private var nowPlayingEdgeEq: MiniEqualizerView? = null
    private var nowPlayingEdgeElapsed: TextView? = null
    private var nowPlayingEdgeDuration: TextView? = null
    // Which dock variant is currently built ("style|showProgress"); lets a settings sync rebuild on change.
    private var nowPlayingDockBuilt: String? = null
    private var nowPlayingFullView: View? = null
    private var nowPlayingFullArt: ImageView? = null
    private var nowPlayingFullTitle: TextView? = null
    private var nowPlayingFullSubtitle: TextView? = null
    private var nowPlayingFullApp: TextView? = null
    private var nowPlayingFullPlay: TextView? = null
    private var nowPlayingFullAppIcon: ImageView? = null
    private var nowPlayingFullAlbum: TextView? = null
    private var nowPlayingFullProgress: android.widget.ProgressBar? = null
    private var nowPlayingFullElapsed: TextView? = null
    private var nowPlayingFullDuration: TextView? = null
    // Ticks once a second while the full card is open to advance the progress bar/time.
    private val nowPlayingProgressTick = object : Runnable {
        override fun run() { updateNowPlayingProgress(); main.postDelayed(this, 1000) }
    }
    private var nowPlayingVisualizer: NowPlayingVisualizerView? = null
    private var soundReactor: SoundReactor? = null
    private var noteView: View? = null
    private var noteText: TextView? = null
    private var agendaView: View? = null
    private var agendaList: LinearLayout? = null
    private var stripView: View? = null
    private var stripLp: WindowManager.LayoutParams? = null
    // Rendered height of the strip window; the bottom ticker rides above it. Larger for big-text styles.
    private var stripHeightPx = 0
    // When the user taps the strip's hide button, the strip collapses to a small restore handle.
    // Runtime-only: a fresh service start shows the strip again.
    private var stripUserHidden = false
    private var stripHandleView: View? = null
    // Componentised status-strip elements (each separated by a thin divider).
    private var stripContext: TextView? = null
    private var stripClock: TextView? = null
    private var stripDate: TextView? = null
    private var stripWeather: TextView? = null
    private var stripBattery: TextView? = null
    private var stripNetwork: TextView? = null
    private var stripNtfy: TextView? = null
    private var stripStreamingDot: View? = null
    private var streamingAnim: ObjectAnimator? = null
    private var stripVpnDot: View? = null
    private var stripWifiBars: LinearLayout? = null
    private var stripWeek: TextView? = null
    private var stripRain: TextView? = null
    private var stripSun: TextView? = null
    private var stripAgenda: TextView? = null
    private var stripNavSpacer: View? = null
    private var stripNavBack: View? = null
    private var stripNavHome: View? = null
    private var stripNavRecents: View? = null
    // Wi-Fi bar colours come from the active strip style; updateStripIndicators() reads these.
    private var stripWifiOn = WIFI_ON
    private var stripWifiDim = WIFI_DIM
    private var navView: View? = null
    private var tickerView: View? = null
    private var tickerText: TextView? = null
    private var tickerAnim: ObjectAnimator? = null

    /** Draggable widgets paired with their layout params, so they can be re-clamped on rotation. */
    private val draggables = mutableListOf<Pair<View, WindowManager.LayoutParams>>()

    private var ntfy: NtfyClient? = null
    private var ntfyCfgLoaded: String = ""
    // Lazily-created TTS for breaking-news alerts. Silent no-op on Portals without a TTS engine.
    private var speaker: Speaker? = null
    private var weather: WeatherClient? = null
    private var weatherCfgLoaded: String = ""
    private var ticker: TickerClient? = null
    private var tickerCfgLoaded: String = ""
    private var calendar: CalendarClient? = null
    private var calCfgLoaded: String = ""
    @Volatile private var calEvents: List<CalendarClient.Event> = emptyList()
    @Volatile private var connected = false
    @Volatile private var lastWeather: String = ""
    @Volatile private var weatherExtras: WeatherClient.Extras = WeatherClient.Extras()
    private var lastNetworkRxBytes = -1L
    private var lastNetworkTxBytes = -1L
    private var lastNetworkSampleMs = 0L
    private var lastNetworkSpeed = "↓ 0 B/s ↑ 0 B/s"

    private var mediaProjection: MediaProjection? = null
    private var countdownView: View? = null
    private var capturing = false
    private val artworkCache = ConcurrentHashMap<String, Bitmap>()
    private val failedArtwork = ConcurrentHashMap.newKeySet<String>()
    private val artworkIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "now-playing-artwork").apply { isDaemon = true }
    }
    private val artworkRequestSerial = AtomicInteger(0)
    @Volatile private var pendingArtworkUri: String? = null

    private val mediaListenerComponent by lazy { ComponentName(this, NotifyListenerService::class.java) }
    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaSessionsListening = false
    private var mediaAccessMissing = false
    private var activeMediaController: MediaController? = null
    /** One-shot: when "start expanded" is on, auto-open the full card the first time playback is seen. */
    private var npAutoExpandPending = false

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            main.post { bindMediaController(controllers.orEmpty()) }
        }
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { updateNowPlaying() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { updateNowPlaying() }
        override fun onSessionDestroyed() { bindMediaController(emptyList()) }
    }
    private val mediaPoll = object : Runnable {
        override fun run() {
            if (!prefs.nowPlayingEnabled || !mediaSessionsListening) return
            refreshActiveMediaSessions()
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            ACTION_REFRESH -> syncFromPrefs()
            ACTION_SYNC_WIDGETS -> syncWidgetState()
            ACTION_SYNC_TICKER -> syncTickerOnly()
            ACTION_CONTEXT_CHANGED -> updateLiveText()
            ACTION_TEST_BANNER -> showBanner("Test banner", "This is how an ntfy message appears.")
            ACTION_BANNER -> showBanner(
                intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Notification" },
                intent.getStringExtra(EXTRA_TEXT).orEmpty()
            )
            ACTION_MEDIA_REFRESH -> if (prefs.nowPlayingEnabled) {
                startMediaSessions()
                refreshActiveMediaSessions()
            }
            ACTION_PREVIEW_NOW_PLAYING -> if (canDrawOverlays()) {
                startMediaSessions()
                refreshActiveMediaSessions()
                showNowPlayingFull()
            }
            ACTION_ALERT -> showAlert(intent.getStringExtra(EXTRA_KIND) ?: KIND_REMINDER)
            ACTION_BREAKING -> showBreakingNews(
                intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Breaking news" },
                intent.getStringExtra(EXTRA_TEXT).orEmpty()
            )
            ACTION_SCREENSHOT -> startScreenshot()
            ACTION_PROJECTION_RESULT -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data != null) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mpm.getMediaProjection(code, data)?.also {
                        it.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() { mediaProjection = null }
                        }, main)
                    }
                    runCountdownThenCapture()
                } else {
                    capturing = false
                    showBanner("Screenshot canceled", "Screen-capture permission was not granted.")
                }
            }
            else -> syncFromPrefs()
        }
        main.removeCallbacks(tick)
        main.post(tick)
        return START_STICKY
    }

    override fun onDestroy() { stopEverything(); super.onDestroy() }

    private val tick = object : Runnable {
        override fun run() { updateLiveText(); main.postDelayed(this, 1000) }
    }

    // ---- lifecycle / sync -------------------------------------------------

    private fun syncFromPrefs() {
        // Rebuild every overlay so appearance changes apply cleanly.
        removeAllOverlays()
        // Without "draw over other apps" we can't add a single overlay window — adding one throws
        // BadTokenException and would crash the service. Stay alive, flag it in the notification, and
        // skip everything that would draw (the listeners only produce overlays, so stop them too).
        if (!canDrawOverlays()) {
            updateNotification(permissionNeeded = true)
            ntfy?.stop(); ntfy = null; connected = false
            weather?.stop(); weather = null; weatherCfgLoaded = ""; lastWeather = ""
            return
        }
        updateNotification(permissionNeeded = false)
        if (prefs.clockEnabled) showClock()
        if (prefs.weatherEnabled) showWeather()
        if (prefs.batteryEnabled) showBattery()
        if (prefs.nowPlayingEnabled) showNowPlaying()
        // Listen to media sessions if either the Now Playing widget OR the strip streaming dot needs it.
        if (prefs.nowPlayingEnabled || (prefs.stripEnabled && prefs.stripShowStreaming)) startMediaSessions()
        else stopMediaSessions()
        if (prefs.noteEnabled) showNote()
        if (prefs.agendaEnabled) showAgenda()
        if (prefs.stripEnabled) { if (stripUserHidden) showStripHandle() else showStrip() }
        if (prefs.navEnabled) showNav()
        // The ticker rides with the strip: when the user has the strip hidden, keep the ticker
        // hidden too so the bottom edge is fully clear.
        if (prefs.tickerEnabled && prefs.tickerUrl.isNotBlank() && !stripUserHidden) showTicker()
        syncBackgroundClients()
    }

    private fun syncWidgetState() {
        if (!canDrawOverlays()) {
            updateNotification(permissionNeeded = true)
            ntfy?.stop(); ntfy = null; connected = false
            weather?.stop(); weather = null; weatherCfgLoaded = ""; lastWeather = ""
            return
        }
        updateNotification(permissionNeeded = false)

        if (prefs.clockEnabled) {
            if (clockView == null) showClock()
        } else {
            clockView?.let(::safeRemove)
            clockView = null; clockTime = null; clockDate = null; clockDot = null
        }

        if (prefs.weatherEnabled) {
            if (weatherView == null) showWeather()
        } else {
            weatherView?.let(::safeRemove)
            weatherView = null; weatherTemp = null; weatherCond = null; weatherPlace = null
        }

        if (prefs.batteryEnabled) {
            if (batteryView == null) showBattery()
        } else {
            batteryView?.let(::safeRemove)
            batteryView = null; batteryText = null
        }

        if (prefs.noteEnabled) {
            if (noteView == null) showNote() else noteText?.text = prefs.noteText.ifBlank { "(empty note — set it in the app)" }
        } else {
            noteView?.let(::safeRemove)
            noteView = null; noteText = null
        }

        if (prefs.agendaEnabled) {
            if (agendaView == null) showAgenda() else updateAgenda()
        } else {
            agendaView?.let(::safeRemove)
            agendaView = null; agendaList = null
        }

        if (prefs.nowPlayingEnabled) {
            // Rebuild the dock if it's missing or the chosen shape/progress option changed.
            if (nowPlayingView == null || nowPlayingDockBuilt != nowPlayingDockKey()) {
                nowPlayingView?.let(::safeRemove)
                nowPlayingView = null; clearNowPlayingDockRefs()
                showNowPlaying()
            }
            npAutoExpandPending = prefs.nowPlayingStartExpanded
        } else {
            hideNowPlayingFull()
            nowPlayingView?.let(::safeRemove)
            nowPlayingView = null; clearNowPlayingDockRefs()
            nowPlayingDockBuilt = null
            npAutoExpandPending = false
        }

        if (prefs.nowPlayingEnabled || (prefs.stripEnabled && prefs.stripShowStreaming)) startMediaSessions()
        else stopMediaSessions()

        syncBackgroundClients()
    }

    private fun syncTickerOnly() {
        if (!canDrawOverlays()) {
            updateNotification(permissionNeeded = true)
            return
        }
        if (prefs.tickerEnabled && prefs.tickerUrl.isNotBlank() && !stripUserHidden) {
            // Rebuild so style / position / edge changes apply immediately, keeping the current text.
            val prevText = tickerText?.text?.toString()?.takeIf { it.isNotBlank() && it != "…" }
            tickerAnim?.cancel(); tickerAnim = null
            tickerView?.let(::safeRemove); tickerView = null; tickerText = null
            showTicker()
            prevText?.let { tickerText?.text = it }
        } else {
            ticker?.stop(); ticker = null; tickerCfgLoaded = ""
            tickerAnim?.cancel(); tickerAnim = null
            tickerView?.let(::safeRemove)
            tickerView = null; tickerText = null
        }

        val tickerUrl = prefs.tickerUrl
        if (prefs.tickerEnabled && tickerUrl.isNotBlank() && tickerCfgLoaded != tickerUrl) {
            ticker?.stop(); tickerCfgLoaded = tickerUrl
            ticker = TickerClient(tickerUrl) { items ->
                main.post {
                    tickerText?.let { view -> view.text = tickerJoin(items).ifBlank { "…" }; startTickerScroll() }
                }
            }.also { it.start() }
        }
        updateLiveText()
    }

    private fun syncBackgroundClients() {
        val topic = prefs.topic
        // Reconnect when the topic, server, or token changes (not just on first start).
        val ntfyCfg = "${prefs.ntfyServer}|$topic|${prefs.ntfyToken}"
        if (topic.isNotBlank()) {
            if (ntfy == null || ntfyCfgLoaded != ntfyCfg) {
                ntfy?.stop(); ntfyCfgLoaded = ntfyCfg; connected = false
                ntfy = NtfyClient(topic,
                    server = prefs.ntfyServer,
                    token = prefs.ntfyToken,
                    onConnected = { c -> main.post { connected = c; updateLiveText() } },
                    onMessage = { title, msg, priority, tags ->
                        main.post {
                            if (prefs.breakingNewsEnabled && isBreaking(priority, tags)) showBreakingNews(title, msg)
                            else showBanner(title, msg)
                        }
                    }
                ).also { it.start() }
            }
        } else { ntfy?.stop(); ntfy = null; ntfyCfgLoaded = ""; connected = false }

        val needWeather = prefs.weatherEnabled || prefs.stripShowWeather || prefs.stripShowRain || prefs.stripShowSun
        val city = prefs.weatherCity
        val cfg = "$city|${prefs.weatherFahrenheit}"
        if (needWeather && city.isNotBlank()) {
            if (weather == null || weatherCfgLoaded != cfg) {
                weather?.stop(); weatherCfgLoaded = cfg
                weather = WeatherClient(city, prefs.weatherFahrenheit) { temp, cond, place, extras ->
                    main.post {
                        lastWeather = "$temp $cond"
                        weatherExtras = extras
                        weatherTemp?.text = temp
                        weatherCond?.text = cond
                        weatherPlace?.text = place
                        updateLiveText()
                    }
                }.also { it.start() }
            }
        } else { weather?.stop(); weather = null; weatherCfgLoaded = ""; lastWeather = ""; weatherExtras = WeatherClient.Extras() }

        val needCalendar = prefs.agendaEnabled || prefs.stripShowAgenda
        val calUrl = prefs.calendarUrl
        if (needCalendar && calUrl.isNotBlank()) {
            if (calendar == null || calCfgLoaded != calUrl) {
                calendar?.stop(); calCfgLoaded = calUrl
                calendar = CalendarClient(calUrl) { events ->
                    main.post {
                        calEvents = events
                        updateAgenda()
                        updateLiveText()
                    }
                }.also { it.start() }
            }
        } else { calendar?.stop(); calendar = null; calCfgLoaded = ""; calEvents = emptyList(); updateAgenda() }

        val tickerUrl = prefs.tickerUrl
        if (prefs.tickerEnabled && tickerUrl.isNotBlank()) {
            if (ticker == null || tickerCfgLoaded != tickerUrl) {
                ticker?.stop(); tickerCfgLoaded = tickerUrl
                ticker = TickerClient(tickerUrl) { items ->
                    main.post {
                        tickerText?.let { it.text = tickerJoin(items).ifBlank { "…" }; startTickerScroll() }
                    }
                }.also { it.start() }
            }
        } else { ticker?.stop(); ticker = null; tickerCfgLoaded = "" }
        updateLiveText()
    }

    private fun removeAllOverlays() {
        listOf(clockView, weatherView, batteryView, nowPlayingView, nowPlayingFullView, noteView, agendaView, stripView, stripHandleView, navView, tickerView).forEach { it?.let(::safeRemove) }
        draggables.clear()
        clockView = null; weatherView = null; batteryView = null; nowPlayingView = null; noteView = null; stripView = null; stripHandleView = null; navView = null
        agendaView = null; agendaList = null
        clockTime = null; clockDate = null; clockDot = null
        weatherTemp = null; weatherCond = null; weatherPlace = null
        batteryText = null
        stopNowPlayingProgressTicker()
        clearNowPlayingDockRefs()
        nowPlayingDockBuilt = null
        clearNowPlayingFullRefs()
        noteText = null
        streamingAnim?.cancel(); streamingAnim = null
        stripContext = null
        stripClock = null; stripDate = null; stripWeather = null; stripBattery = null
        stripNetwork = null; stripNtfy = null
        stripStreamingDot = null; stripVpnDot = null; stripWifiBars = null
        stripWeek = null; stripRain = null; stripSun = null; stripAgenda = null
        stripNavSpacer = null; stripNavBack = null; stripNavHome = null; stripNavRecents = null
        stripLp = null
        tickerAnim?.cancel(); tickerAnim = null
        tickerView = null; tickerText = null
    }

    private fun stopEverything() {
        main.removeCallbacks(tick)
        ntfy?.stop(); ntfy = null
        weather?.stop(); weather = null; weatherCfgLoaded = ""
        ticker?.stop(); ticker = null; tickerCfgLoaded = ""
        calendar?.stop(); calendar = null; calCfgLoaded = ""
        speaker?.stop(); speaker = null
        artworkIo.shutdownNow()
        stopMediaSessions()
        hideCountdown(); mediaProjection?.stop(); mediaProjection = null
        removeAllOverlays()
    }

    // ---- clock widget -----------------------------------------------------

    private fun showClock() {
        val card = cardColumn()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = circle(DOT_OFF)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).also { it.rightMargin = dp(12) }
        }
        val time = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = scaled(26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(dot); row.addView(time); card.addView(row)
        val date = TextView(this).apply {
            setTextColor(0xFFAEB4BF.toInt()); textSize = scaled(13f); setPadding(dp(24), dp(2), 0, 0)
        }
        card.addView(date)
        clockDot = dot; clockTime = time; clockDate = date
        clockView = addDraggable(card, "clock", dp(28), dp(96))
        updateLiveText()
    }

    // ---- weather widget --------------------------------------------------

    private fun showWeather() {
        val card = cardColumn()
        val temp = TextView(this).apply {
            text = "--"; setTextColor(Color.WHITE); textSize = scaled(28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val cond = TextView(this).apply { text = "loading…"; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(15f) }
        val place = TextView(this).apply { text = prefs.weatherCity; setTextColor(0xFF8A919D.toInt()); textSize = scaled(12f) }
        card.addView(temp); card.addView(cond); card.addView(place)
        weatherTemp = temp; weatherCond = cond; weatherPlace = place
        weatherView = addDraggable(card, "weather", dp(28), dp(230))
    }

    // ---- battery widget --------------------------------------------------

    private fun showBattery() {
        val card = cardColumn()
        val t = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = scaled(22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(t)
        batteryText = t
        batteryView = addDraggable(card, "battery", dp(28), dp(420))
        updateLiveText()
    }

    // ---- now-playing widget ---------------------------------------------

    /** Identity of the currently-built dock so a settings change can trigger a rebuild. */
    private fun nowPlayingDockKey() = listOf(
        prefs.nowPlayingDockStyle, prefs.nowPlayingShowProgress,
        prefs.nowPlayingBubbleStyle, prefs.nowPlayingBubbleSize, prefs.nowPlayingEdgePosition
    ).joinToString("|")

    private fun showNowPlaying() {
        nowPlayingDockBuilt = nowPlayingDockKey()
        when (prefs.nowPlayingDockStyle) {
            "strip" -> showNowPlayingStrip()
            "edge" -> showNowPlayingEdge()
            else -> showNowPlayingBubble()
        }
    }

    /** The small floating cover-art button, in the chosen style + size. Tap to open the full card. */
    private fun showNowPlayingBubble() {
        val style = prefs.nowPlayingBubbleStyle
        val (artDp, glyphDp) = when (prefs.nowPlayingBubbleSize) {
            "small" -> 48 to 24
            "large" -> 84 to 38
            else -> 64 to 30
        }
        val artRadius = when (style) {
            "circle" -> artDp / 2
            "square" -> 4
            "minimal" -> 16
            else -> 20
        }
        val button = FrameLayout(this).apply {
            background = when (style) {
                "minimal" -> null
                "circle" -> circle(withAlpha(CARD_BASE, prefs.overlayOpacity))
                "square" -> rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), 8)
                else -> rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), 24)
            }
            elevation = dp(12).toFloat()
            val pad = if (style == "minimal") 0 else 6
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF2A2F39.toInt(), artRadius)
            clipToOutline = true
            if (style == "minimal") elevation = dp(10).toFloat()
            setImageResource(android.R.drawable.ic_media_play)
            isClickable = false
            isFocusable = false
        }
        button.addView(art, FrameLayout.LayoutParams(dp(artDp), dp(artDp)).also { it.gravity = Gravity.CENTER })
        val glyph = TextView(this).apply {
            text = ">"
            setTextColor(Color.WHITE)
            textSize = scaled(if (glyphDp >= 38) 22f else if (glyphDp <= 24) 14f else 18f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = circle(0xAA000000.toInt())
        }
        button.addView(glyph, FrameLayout.LayoutParams(dp(glyphDp), dp(glyphDp)).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
        })

        nowPlayingMiniArt = art
        nowPlayingMiniGlyph = glyph
        nowPlayingView = addDraggable(button, "nowPlaying", dp(28), dp(700)) { showNowPlayingFull() }
        npAutoExpandPending = prefs.nowPlayingStartExpanded
        updateNowPlaying()
    }

    /**
     * Full-width now-playing band pinned to the top or bottom edge (like the status strip). It auto
     * shows/hides with playback and reuses the strip element refs. Tap to open the full card.
     */
    private fun showNowPlayingEdge() {
        val top = prefs.nowPlayingEdgePosition == "top"
        val showProgress = prefs.nowPlayingShowProgress
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(withAlpha(STRIP_BASE, prefs.overlayOpacity)) }
            // Slightly taller top padding so the top band clears the Portal system pills.
            setPadding(dp(18), if (top) dp(10) else dp(8), dp(16), dp(8))
            setOnClickListener { showNowPlayingFull() }
        }

        // Source-app logo (e.g. Spotify), hidden until a session with an icon is bound.
        val appIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        bar.addView(appIcon, LinearLayout.LayoutParams(dp(20), dp(20)).also { it.rightMargin = dp(10) })

        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF2A2F39.toInt(), 8)
            clipToOutline = true
            setImageResource(android.R.drawable.ic_media_play)
        }
        bar.addView(art, LinearLayout.LayoutParams(dp(34), dp(34)).also { it.rightMargin = dp(12) })

        val title = TextView(this).apply {
            text = "Nothing playing"
            setTextColor(Color.WHITE); textSize = scaled(15f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(300)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val subtitle = TextView(this).apply {
            text = ""
            setTextColor(0xFFAEB4BF.toInt()); textSize = scaled(13f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220)
            setPadding(dp(12), 0, 0, 0)
        }
        bar.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Flexible gap so the controls cluster pins to the right edge regardless of title length.
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))

        // Mini equaliser — pulses while playing.
        val eq = MiniEqualizerView(this).apply { accentColor = prefs.accentColor }
        bar.addView(eq, LinearLayout.LayoutParams(dp(22), dp(18)).also { it.rightMargin = dp(14) })

        // elapsed — progress — total (only with the progress/length option on).
        val elapsed = TextView(this).apply {
            text = "0:00"; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(12f)
            typeface = Typeface.MONOSPACE
        }
        val duration = TextView(this).apply {
            text = "0:00"; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(12f)
            typeface = Typeface.MONOSPACE
        }
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(prefs.accentColor)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
        }
        if (showProgress) {
            bar.addView(elapsed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bar.addView(progress, LinearLayout.LayoutParams(dp(150), dp(4)).also { it.leftMargin = dp(10); it.rightMargin = dp(10) })
            bar.addView(duration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.rightMargin = dp(14) })
        }

        // Transport: previous — play/pause — next.
        fun transport(label: String, size: Float, diameter: Int, tap: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE); textSize = scaled(size)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = circle(0x55000000)
            setOnClickListener { tap() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(dp(diameter), dp(diameter)).also { lp -> lp.leftMargin = dp(6) }) }

        transport("◀", 14f, 32) { activeMediaController?.transportControls?.skipToPrevious() }
        val glyph = transport(">", 17f, 38) { togglePlayback() }
        transport("▶", 14f, 32) { activeMediaController?.transportControls?.skipToNext() }

        nowPlayingStripArt = art
        nowPlayingStripTitle = title
        nowPlayingStripSubtitle = subtitle
        nowPlayingStripProgress = if (showProgress) progress else null
        nowPlayingStripGlyph = glyph
        nowPlayingEdgeAppIcon = appIcon
        nowPlayingEdgeEq = eq
        nowPlayingEdgeElapsed = if (showProgress) elapsed else null
        nowPlayingEdgeDuration = if (showProgress) duration else null

        val lp = baseParams(height = dp(50)).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or if (top) Gravity.TOP else Gravity.BOTTOM
            y = 0
        }
        if (!safeAddView(bar, lp)) { clearNowPlayingDockRefs(); return }
        nowPlayingView = bar
        npAutoExpandPending = prefs.nowPlayingStartExpanded
        updateNowPlaying()
    }

    /**
     * The "larger" docked widget: a horizontal strip with cover art, title/artist and a slim live
     * progress bar. Tapping it opens the full card; the play/pause glyph toggles playback in place.
     */
    private fun showNowPlayingStrip() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), prefs.cornerRadius.coerceAtLeast(14))
            setPadding(dp(10), dp(9), dp(12), dp(9))
            elevation = dp(12).toFloat()
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF2A2F39.toInt(), 14)
            clipToOutline = true
            setImageResource(android.R.drawable.ic_media_play)
        }
        row.addView(art, LinearLayout.LayoutParams(dp(56), dp(56)).also { it.rightMargin = dp(12) })

        val title = TextView(this).apply {
            text = "Nothing playing"
            setTextColor(Color.WHITE); textSize = scaled(16f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val subtitle = TextView(this).apply {
            text = "Tap when media is playing"
            setTextColor(0xFFB6BCC6.toInt()); textSize = scaled(13f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, 0)
        }
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(prefs.accentColor)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(subtitle)
            if (prefs.nowPlayingShowProgress) addView(progress, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
            ).also { it.topMargin = dp(7) })
        }
        row.addView(column, LinearLayout.LayoutParams(dp(196), ViewGroup.LayoutParams.WRAP_CONTENT))

        val glyph = TextView(this).apply {
            text = ">"
            setTextColor(Color.WHITE); textSize = scaled(20f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = circle(0x552A303A)
            setOnClickListener { togglePlayback() }
        }
        row.addView(glyph, LinearLayout.LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(10) })

        nowPlayingStripArt = art
        nowPlayingStripTitle = title
        nowPlayingStripSubtitle = subtitle
        nowPlayingStripProgress = if (prefs.nowPlayingShowProgress) progress else null
        nowPlayingStripGlyph = glyph
        nowPlayingView = addDraggable(row, "nowPlaying", dp(28), dp(700)) { showNowPlayingFull() }
        npAutoExpandPending = prefs.nowPlayingStartExpanded
        updateNowPlaying()
    }

    /** Null every reference into the docked widget (bubble, strip or edge bar). */
    private fun clearNowPlayingDockRefs() {
        nowPlayingMiniArt = null; nowPlayingMiniGlyph = null
        nowPlayingStripArt = null; nowPlayingStripTitle = null; nowPlayingStripSubtitle = null
        nowPlayingStripProgress = null; nowPlayingStripGlyph = null
        nowPlayingEdgeAppIcon = null; nowPlayingEdgeEq = null
        nowPlayingEdgeElapsed = null; nowPlayingEdgeDuration = null
    }

    /**
     * Should the docked widget be on screen right now? With auto-hide on it shows only while audio is
     * actively playing (or buffering between tracks) on a real session — a pause, stop, or a lingering
     * stale session (e.g. Spotify left paused after you close it, or the idle Alexa runtime) hides it.
     */
    private fun nowPlayingShouldShow(): Boolean {
        if (!prefs.nowPlayingAutoHide) return true
        val c = activeMediaController ?: return false
        if (mediaAccessMissing) return false
        val st = c.playbackState?.state
        return (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) && isRealMedia(c)
    }

    /**
     * Apply the "hide when nothing is playing" behaviour to the docked widget. Fades in when playback
     * starts and fades out when it pauses/stops; the end-action re-checks live state so a fast
     * play/pause flip can't strand the widget in the wrong visibility.
     */
    private fun applyNowPlayingDockVisibility() {
        val v = nowPlayingView ?: return
        val shouldShow = nowPlayingShouldShow()
        v.animate().cancel()
        if (shouldShow) {
            if (v.visibility != View.VISIBLE) v.alpha = 0f
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(180).start()
        } else {
            v.animate().alpha(0f).setDuration(160).withEndAction {
                if (nowPlayingShouldShow()) {
                    v.alpha = 1f; v.visibility = View.VISIBLE
                } else v.visibility = View.GONE
            }.start()
        }
    }

    private fun showNowPlayingFull() {
        if (nowPlayingFullView != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xF20A0C10.toInt())
            isClickable = true
        }
        val layoutStyle = prefs.nowPlayingLayoutStyle
        val visualizer = NowPlayingVisualizerView(this).apply {
            accentColor = prefs.accentColor
            style = prefs.nowPlayingVisualizerStyle
        }
        root.addView(visualizer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val close = TextView(this).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = scaled(32f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = circle(0x662A303A)
            setOnClickListener { hideNowPlayingFull() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(58), dp(58)).also {
            it.gravity = Gravity.TOP or Gravity.END
            it.setMargins(0, dp(28), dp(34), 0)
        })

        // Screen-off button, sits just left of the close (×). Locks the display via the
        // accessibility service (GLOBAL_ACTION_LOCK_SCREEN turns the screen off + locks).
        val screenOff = TextView(this).apply {
            text = "🔒"
            setTextColor(Color.WHITE)
            textSize = scaled(24f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = circle(0x662A303A)
            setOnClickListener {
                if (!NavAccessibilityService.lock()) {
                    showBanner("Screen off unavailable", "Enable the accessibility service first.")
                }
            }
        }
        root.addView(screenOff, FrameLayout.LayoutParams(dp(58), dp(58)).also {
            it.gravity = Gravity.TOP or Gravity.END
            it.setMargins(0, dp(28), dp(106), 0) // 34 (close margin) + 58 (close width) + 14 gap
        })

        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF202631.toInt(), 34)
            clipToOutline = true
            elevation = dp(18).toFloat()
            setImageResource(android.R.drawable.ic_media_play)
            isClickable = true
            isFocusable = true
            setOnClickListener { /* keep the full now-playing overlay open */ }
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Source-app row: the playing app's icon (e.g. the Spotify logo) plus its name.
        val appIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        val app = TextView(this).apply {
            text = "NOW PLAYING"
            setTextColor(prefs.accentColor)
            textSize = scaled(14f)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.12f
        }
        val appRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appIcon, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.rightMargin = dp(10) })
            addView(app)
        }
        val title = TextView(this).apply {
            text = "Nothing playing"
            setTextColor(Color.WHITE)
            textSize = scaled(46f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(12), 0, 0)
        }
        val subtitle = TextView(this).apply {
            text = "Start media in any app"
            setTextColor(0xFFC2C8D2.toInt())
            textSize = scaled(24f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, dp(8))
        }
        val album = TextView(this).apply {
            text = ""
            setTextColor(0xFF98A0AC.toInt())
            textSize = scaled(18f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(22))
            visibility = View.GONE
        }

        // Playback progress: elapsed — bar — total.
        val elapsed = TextView(this).apply {
            text = "0:00"; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(16f)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val durationLabel = TextView(this).apply {
            text = "0:00"; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(16f)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(prefs.accentColor)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
        }
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(28))
            addView(elapsed)
            addView(progressBar, LinearLayout.LayoutParams(0, dp(6), 1f).also { it.leftMargin = dp(16); it.rightMargin = dp(16) })
            addView(durationLabel)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun bigControl(label: String, width: Int = 76, tap: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = scaled(if (width > 90) 34f else 28f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(0xAA252B35.toInt(), 24)
            setOnClickListener { tap() }
        }.also {
            controls.addView(it, LinearLayout.LayoutParams(dp(width), dp(76)).also { lp -> lp.rightMargin = dp(14) })
        }
        bigControl("<<") { activeMediaController?.transportControls?.skipToPrevious() }
        val play = bigControl(">", 104) { togglePlayback() }
        bigControl(">>") { activeMediaController?.transportControls?.skipToNext() }

        info.addView(appRow)
        info.addView(title, LinearLayout.LayoutParams(dp(840), ViewGroup.LayoutParams.WRAP_CONTENT))
        info.addView(subtitle, LinearLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT))
        info.addView(album, LinearLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (prefs.nowPlayingShowProgress) info.addView(progressRow, LinearLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT))
        info.addView(controls)

        when (layoutStyle) {
            "centered" -> {
                info.gravity = Gravity.CENTER_HORIZONTAL
                appRow.gravity = Gravity.CENTER
                app.gravity = Gravity.CENTER
                title.gravity = Gravity.CENTER
                subtitle.gravity = Gravity.CENTER
                album.gravity = Gravity.CENTER
                progressRow.gravity = Gravity.CENTER_VERTICAL
                controls.gravity = Gravity.CENTER
                subtitle.maxLines = 3
                title.textSize = scaled(54f)
                subtitle.textSize = scaled(27f)
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(88), dp(68), dp(88), dp(70))
                }
                content.addView(art, LinearLayout.LayoutParams(dp(500), dp(500)).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                    it.bottomMargin = dp(30)
                })
                content.addView(info, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                root.addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.gravity = Gravity.CENTER })
            }
            "poster" -> {
                info.gravity = Gravity.CENTER_VERTICAL
                app.gravity = Gravity.START
                title.gravity = Gravity.START
                subtitle.gravity = Gravity.START
                subtitle.maxLines = 4
                title.textSize = scaled(50f)
                subtitle.textSize = scaled(24f)
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(88), dp(78), dp(88), dp(78))
                }
                content.addView(art, LinearLayout.LayoutParams(dp(430), dp(620)).also {
                    it.rightMargin = dp(48)
                })
                content.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                root.addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ).also { it.gravity = Gravity.CENTER })
            }
            else -> {
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(88), dp(86), dp(88), dp(80))
                }
                content.addView(art, LinearLayout.LayoutParams(dp(430), dp(430)).also { it.rightMargin = dp(54) })
                content.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                root.addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
        }

        nowPlayingFullView = root
        nowPlayingVisualizer = visualizer
        // Live audio reactor: drives the visualizer from the actual output mix when enabled and the
        // Visualizer API is available (needs RECORD_AUDIO; falls back to the synthetic animation).
        if (prefs.nowPlayingSoundReactive) {
            soundReactor = SoundReactor().also { if (it.start()) visualizer.reactor = it }
        }
        nowPlayingFullArt = art
        nowPlayingFullTitle = title
        nowPlayingFullSubtitle = subtitle
        nowPlayingFullApp = app
        nowPlayingFullPlay = play
        nowPlayingFullAppIcon = appIcon
        nowPlayingFullAlbum = album
        nowPlayingFullProgress = progressBar
        nowPlayingFullElapsed = elapsed
        nowPlayingFullDuration = durationLabel

        val lp = baseParams(focusable = true).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }
        if (!safeAddView(root, lp)) {
            clearNowPlayingFullRefs()
            return
        }
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
        updateNowPlaying()
        startNowPlayingProgressTicker()
    }

    private fun hideNowPlayingFull() {
        val root = nowPlayingFullView ?: return
        stopNowPlayingProgressTicker()
        nowPlayingVisualizer?.playing = false
        soundReactor?.stop(); soundReactor = null
        root.animate().alpha(0f).setDuration(140).withEndAction { safeRemove(root) }.start()
        clearNowPlayingFullRefs()
    }

    /** Null every reference into the full now-playing card. */
    private fun clearNowPlayingFullRefs() {
        nowPlayingFullView = null
        nowPlayingVisualizer = null
        nowPlayingFullArt = null
        nowPlayingFullTitle = null
        nowPlayingFullSubtitle = null
        nowPlayingFullApp = null
        nowPlayingFullPlay = null
        nowPlayingFullAppIcon = null
        nowPlayingFullAlbum = null
        nowPlayingFullProgress = null
        nowPlayingFullElapsed = null
        nowPlayingFullDuration = null
    }

    private fun startNowPlayingProgressTicker() {
        main.removeCallbacks(nowPlayingProgressTick)
        if (prefs.nowPlayingShowProgress && nowPlayingFullProgress != null) main.post(nowPlayingProgressTick)
    }

    private fun stopNowPlayingProgressTicker() { main.removeCallbacks(nowPlayingProgressTick) }

    /** Advance the progress bar + elapsed/total labels from the live playback state. */
    private fun updateNowPlayingProgress() {
        val fullBar = nowPlayingFullProgress
        val stripBar = nowPlayingStripProgress
        if (fullBar == null && stripBar == null) return
        val controller = activeMediaController
        val state = controller?.playbackState
        val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (state == null || duration <= 0L) {
            nowPlayingFullElapsed?.text = "0:00"
            nowPlayingFullDuration?.text = "0:00"
            nowPlayingEdgeElapsed?.text = "0:00"
            nowPlayingEdgeDuration?.text = "0:00"
            fullBar?.progress = 0
            stripBar?.progress = 0
            return
        }
        // Estimate the live position: last reported position + time elapsed since, scaled by speed.
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val delta = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            pos += (delta * state.playbackSpeed).toLong()
        }
        pos = pos.coerceIn(0L, duration)
        val pct = ((pos * 1000L) / duration).toInt()
        fullBar?.progress = pct
        stripBar?.progress = pct
        val elapsedText = formatClock(pos)
        val durationText = formatClock(duration)
        nowPlayingFullElapsed?.text = elapsedText
        nowPlayingFullDuration?.text = durationText
        nowPlayingEdgeElapsed?.text = elapsedText
        nowPlayingEdgeDuration?.text = durationText
    }

    private fun formatClock(ms: Long): String {
        val totalSec = (ms / 1000L).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun startMediaSessions() {
        if (mediaSessionsListening) {
            refreshActiveMediaSessions()
            return
        }
        val mgr = (getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager) ?: return
        mediaSessionManager = mgr
        try {
            mgr.addOnActiveSessionsChangedListener(activeSessionsListener, mediaListenerComponent, main)
            mediaSessionsListening = true
            mediaAccessMissing = false
            refreshActiveMediaSessions()
            main.removeCallbacks(mediaPoll)
            main.postDelayed(mediaPoll, 500)
        } catch (_: SecurityException) {
            mediaAccessMissing = true
            updateNowPlaying()
        }
    }

    private fun stopMediaSessions() {
        activeMediaController?.unregisterCallback(mediaCallback)
        activeMediaController = null
        if (mediaSessionsListening) {
            try { mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener) } catch (_: Exception) {}
        }
        main.removeCallbacks(mediaPoll)
        mediaSessionsListening = false
    }

    private fun refreshActiveMediaSessions() {
        val mgr = mediaSessionManager ?: return
        try {
            mediaAccessMissing = false
            bindMediaController(mgr.getActiveSessions(mediaListenerComponent).orEmpty())
        } catch (_: SecurityException) {
            mediaAccessMissing = true
            updateNowPlaying()
        } catch (_: Exception) {}
    }

    /**
     * A session is worth showing only when it exposes a real track (non-blank title) AND is in a
     * meaningful transport state. This filters out always-on "ambient" sessions like the Portal's
     * Alexa runtime, which parks at state=NONE with empty metadata yet still reports as active.
     */
    private fun isRealMedia(c: MediaController): Boolean {
        val st = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val meaningful = st == PlaybackState.STATE_PLAYING ||
            st == PlaybackState.STATE_PAUSED ||
            st == PlaybackState.STATE_BUFFERING
        if (!meaningful) return false
        val md = c.metadata ?: return false
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        return !title.isNullOrBlank()
    }

    private fun bindMediaController(controllers: List<MediaController>) {
        // Prefer something actually playing a real track, then any real (e.g. paused) track. Fall back
        // to a playing session even without a title so transient art-only sessions still bind.
        val best = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && isRealMedia(it) }
            ?: controllers.firstOrNull { isRealMedia(it) }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (activeMediaController?.sessionToken == best?.sessionToken) {
            updateNowPlaying()
            return
        }
        activeMediaController?.unregisterCallback(mediaCallback)
        activeMediaController = best
        best?.registerCallback(mediaCallback, main)
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        val controller = activeMediaController
        val metadata = controller?.metadata
        val state = controller?.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        nowPlayingVisualizer?.playing = playing
        nowPlayingEdgeEq?.playing = playing
        // "Start expanded": open the full card once, the first time we see something playing.
        if (playing && npAutoExpandPending && nowPlayingView != null && nowPlayingFullView == null) {
            npAutoExpandPending = false
            showNowPlayingFull()
        }
        updateNowPlayingProgress()
        // Show/hide the docked widget based on whether anything is currently playing.
        applyNowPlayingDockVisibility()

        if (mediaAccessMissing) {
            nowPlayingMiniGlyph?.text = "!"
            nowPlayingMiniArt?.setImageResource(android.R.drawable.ic_dialog_info)
            nowPlayingStripGlyph?.text = "!"
            nowPlayingStripArt?.setImageResource(android.R.drawable.ic_dialog_info)
            nowPlayingStripTitle?.text = "Notification access needed"
            nowPlayingStripSubtitle?.text = "Allow the listener, then refresh"
            nowPlayingStripProgress?.progress = 0
            nowPlayingFullTitle?.text = "Notification access needed"
            nowPlayingFullSubtitle?.text = "Allow the listener, then refresh"
            nowPlayingFullApp?.text = "NOW PLAYING"
            nowPlayingFullPlay?.text = ">"
            nowPlayingFullArt?.setImageResource(android.R.drawable.ic_dialog_info)
            nowPlayingFullAlbum?.visibility = View.GONE
            nowPlayingFullAppIcon?.visibility = View.GONE
            nowPlayingEdgeAppIcon?.visibility = View.GONE
            return
        }
        if (controller == null || metadata == null) {
            nowPlayingMiniGlyph?.text = ">"
            nowPlayingMiniArt?.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingStripGlyph?.text = ">"
            nowPlayingStripArt?.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingStripTitle?.text = "Nothing playing"
            nowPlayingStripSubtitle?.text = "Start media in any app"
            nowPlayingStripProgress?.progress = 0
            nowPlayingFullTitle?.text = "Nothing playing"
            nowPlayingFullSubtitle?.text = "Start media in any app"
            nowPlayingFullApp?.text = "NOW PLAYING"
            nowPlayingFullPlay?.text = ">"
            nowPlayingFullArt?.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingFullAlbum?.visibility = View.GONE
            nowPlayingFullAppIcon?.visibility = View.GONE
            nowPlayingEdgeAppIcon?.visibility = View.GONE
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel(controller.packageName)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: appLabel(controller.packageName)
        val art = loadArtwork(metadata)

        nowPlayingMiniGlyph?.text = if (playing) "||" else ">"
        nowPlayingStripGlyph?.text = if (playing) "||" else ">"
        nowPlayingStripTitle?.text = title
        nowPlayingStripSubtitle?.text = artist
        nowPlayingFullTitle?.text = title
        nowPlayingFullSubtitle?.text = artist
        nowPlayingFullApp?.text = appLabel(controller.packageName).uppercase(Locale.getDefault())
        nowPlayingFullPlay?.text = if (playing) "||" else ">"

        // Album line (hidden when absent or identical to the track title).
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        nowPlayingFullAlbum?.let {
            if (!album.isNullOrBlank() && !album.equals(title, ignoreCase = true)) {
                it.text = album; it.visibility = View.VISIBLE
            } else it.visibility = View.GONE
        }
        // Source-app icon (e.g. the Spotify logo), shared by the full card and the edge bar.
        val appIcon = try { packageManager.getApplicationIcon(controller.packageName) } catch (_: Exception) { null }
        listOf(nowPlayingFullAppIcon, nowPlayingEdgeAppIcon).forEach { iv ->
            iv ?: return@forEach
            if (appIcon != null) { iv.setImageDrawable(appIcon); iv.visibility = View.VISIBLE } else iv.visibility = View.GONE
        }
        if (art != null) {
            nowPlayingMiniArt?.setImageBitmap(art)
            nowPlayingStripArt?.setImageBitmap(art)
            nowPlayingFullArt?.setImageBitmap(art)
        } else {
            nowPlayingMiniArt?.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingStripArt?.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingFullArt?.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun loadArtwork(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }

        val uriString = listOf(
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        ).firstOrNull { !it.isNullOrBlank() } ?: return null

        val uri = try {
            android.net.Uri.parse(uriString)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme == "http" || scheme == "https") {
            artworkCache[uriString]?.let { return it }
            if (!failedArtwork.contains(uriString) && pendingArtworkUri != uriString) {
                requestRemoteArtwork(uriString)
            }
            return null
        }

        return try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun requestRemoteArtwork(uriString: String) {
        pendingArtworkUri = uriString
        val serial = artworkRequestSerial.incrementAndGet()
        artworkIo.execute {
            val bitmap = try {
                fetchRemoteArtwork(uriString)
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) {
                artworkCache[uriString] = bitmap
                failedArtwork.remove(uriString)
            } else {
                failedArtwork.add(uriString)
            }
            main.post {
                if (artworkRequestSerial.get() != serial) return@post
                if (pendingArtworkUri == uriString) pendingArtworkUri = null
                if (activeMediaController?.metadata != null) updateNowPlaying()
            }
        }
    }

    private fun fetchRemoteArtwork(uriString: String): Bitmap? {
        val bytes = fetchRemoteBytes(uriString) ?: return null
        return decodeArtworkBytes(bytes)
    }

    private fun fetchRemoteBytes(uriString: String): ByteArray? {
        val conn = (URL(uriString).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.inputStream.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun decodeArtworkBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 1024, 1024)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        var halfWidth = srcWidth / 2
        var halfHeight = srcHeight / 2
        while ((halfHeight / sample) >= reqHeight && (halfWidth / sample) >= reqWidth) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun togglePlayback() {
        val controller = activeMediaController ?: return
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    // ---- sticky note widget ----------------------------------------------

    private fun showNote() {
        val card = cardColumn().apply { background = rounded(withAlpha(NOTE_BASE, prefs.overlayOpacity), prefs.cornerRadius) }
        val t = TextView(this).apply {
            text = prefs.noteText.ifBlank { "(empty note — set it in the app)" }
            setTextColor(0xFF2A2300.toInt()); textSize = scaled(16f); maxWidth = dp(280)
        }
        card.addView(t)
        noteText = t
        noteView = addDraggable(card, "note", dp(320), dp(96))
    }

    // ---- agenda / calendar widget ----------------------------------------

    private fun showAgenda() {
        val card = cardColumn()
        card.addView(TextView(this).apply {
            text = "Agenda"; setTextColor(0xFF8A919D.toInt()); textSize = scaled(12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(list)
        agendaList = list
        agendaView = addDraggable(card, "agenda", dp(28), dp(330))
        updateAgenda()
    }

    /** Rebuild the agenda card's event rows from the latest calendar fetch. */
    private fun updateAgenda() {
        val list = agendaList ?: return
        list.removeAllViews()
        val events = calEvents.take(5)
        if (events.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (prefs.calendarUrl.isBlank()) "Add an iCal link in the app" else "No upcoming events"
                setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(14f)
            })
            return
        }
        events.forEachIndexed { i, e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(View(this).apply {
                background = circle(if (i == 0) prefs.accentColor else 0xFF5A6168.toInt())
            }, LinearLayout.LayoutParams(dp(8), dp(8)).also { it.rightMargin = dp(10) })
            row.addView(TextView(this).apply {
                text = if (e.title.length > 24) e.title.take(23) + "…" else e.title
                setTextColor(Color.WHITE); textSize = scaled(15f); maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = eventWhen(e); setTextColor(0xFF9AA0AC.toInt()); textSize = scaled(13f)
                setPadding(dp(10), 0, 0, 0)
            })
            list.addView(row)
        }
    }

    /** Compact relative/absolute time for an event, e.g. "in 25m" / "13:00" / "tomorrow" / "Wed 9:00". */
    private fun eventWhen(e: CalendarClient.Event): String {
        val now = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat(if (prefs.clock24h) "HH:mm" else "h:mm a", Locale.getDefault())
        val days = ((startOfDay(e.startEpoch) - startOfDay(now)) / 86_400_000L).toInt()
        return when {
            e.allDay -> when (days) { 0 -> "today"; 1 -> "tomorrow"; else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(e.startEpoch) }
            e.startEpoch <= now -> "now"
            e.startEpoch - now < 60 * 60_000L -> "in ${((e.startEpoch - now) / 60_000L).toInt().coerceAtLeast(1)}m"
            days == 0 -> timeFmt.format(e.startEpoch)
            days == 1 -> "tmrw ${timeFmt.format(e.startEpoch)}"
            else -> SimpleDateFormat("EEE ${if (prefs.clock24h) "HH:mm" else "h:mma"}", Locale.getDefault()).format(e.startEpoch)
        }
    }

    private fun startOfDay(epoch: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = epoch
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Single next-event line for the status strip. */
    private fun agendaString(): String {
        val e = calEvents.firstOrNull()
            ?: return if (prefs.calendarUrl.isBlank()) "no calendar" else "no events"
        val title = if (e.title.length > 22) e.title.take(21) + "…" else e.title
        return "$title · ${eventWhen(e)}"
    }

    // ---- navigation cluster ----------------------------------------------

    private fun showNav() {
        val vertical = prefs.navVertical
        val style = prefs.navStyle
        val bar = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        styleNavBar(bar, style)
        val lp = baseParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getX("nav", dp(1556)); y = prefs.getY("nav", dp(972))
        }
        var index = 0
        fun add(glyph: String, label: String, needsAccessibility: Boolean = true,
                action: () -> Boolean, onLongPress: (() -> Unit)? = null) {
            val btn = buildNavButton(glyph, label, index++, style)
            val gap = if (style == "squares" || style == "color") dp(10) else dp(6)
            // Preserve the per-style size from buildNavButton; just add the inter-button margin.
            val mlp = (btn.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (vertical) mlp.bottomMargin = gap else mlp.rightMargin = gap
            bar.addView(btn, mlp)
            makeDraggable(btn, bar, lp, "nav", pressFeedback = true, onLongPress = onLongPress,
                locked = { prefs.navLocked }) {
                if (needsAccessibility) runNavAction(label, action) else action()
            }
        }
        if (prefs.navBack) add("‹", "Back", action = { NavAccessibilityService.back() })
        if (prefs.navHome) add("⌂", "Home", action = { NavAccessibilityService.home() })
        if (prefs.navRecents) add("▢", "Recents", action = { NavAccessibilityService.recents() })
        if (prefs.navControlCenter) add("⌄", "Center", action = { NavAccessibilityService.controlCenter() })
        if (prefs.navScreenshot) add("📸", "Shot", needsAccessibility = false, action = { startScreenshot(); true })
        if (prefs.navLock) add("🔒", "Lock", action = { NavAccessibilityService.lock() })
        if (bar.childCount == 0) return
        clampParamsToScreen(bar, lp)
        if (!safeAddView(bar, lp)) return
        navView = bar
        draggables.add(bar to lp)
    }

    /** Apply the chosen [Prefs.NAV_STYLES] treatment to the cluster container. */
    private fun styleNavBar(bar: LinearLayout, style: String) {
        when (style) {
            "ghost" -> { bar.background = rounded(withAlpha(CARD_BASE, 35), 28); bar.setPadding(dp(8), dp(8), dp(8), dp(8)) }
            "squares", "color" -> bar.setPadding(0, 0, 0, 0) // each button floats on its own
            "glass" -> { bar.background = rounded(0x66101216, 30); bar.setPadding(dp(10), dp(8), dp(10), dp(8)); bar.elevation = dp(10).toFloat() }
            "dot" -> { bar.background = rounded(withAlpha(CARD_BASE, 60), 24); bar.setPadding(dp(8), dp(6), dp(8), dp(6)) }
            else -> { bar.background = rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), 28); bar.setPadding(dp(8), dp(8), dp(8), dp(8)); bar.elevation = dp(10).toFloat() }
        }
    }

    /** Build one nav button view styled per [style]. Returns the view to add to the bar. */
    private fun buildNavButton(glyph: String, label: String, index: Int, style: String): View {
        fun glyphView(size: Float = 26f, color: Int = Color.WHITE) = TextView(this).apply {
            text = glyph; setTextColor(color); textSize = scaled(size); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val size = dp(56)
        return when (style) {
            "ghost" -> glyphView(color = 0xCCFFFFFF.toInt()).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            "squares" -> glyphView().apply {
                background = rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), 16)
                elevation = dp(8).toFloat()
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            "underline" -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(glyphView(), LinearLayout.LayoutParams(size, dp(48)))
                addView(View(this@OverlayService).apply { setBackgroundColor(prefs.accentColor) },
                    LinearLayout.LayoutParams(dp(26), dp(3)).also { it.topMargin = dp(2) })
            }
            "glass" -> glyphView().apply {
                background = rounded(0x33FFFFFF, 16)
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            "label" -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                addView(glyphView(22f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.gravity = Gravity.CENTER_HORIZONTAL })
                addView(TextView(this@OverlayService).apply {
                    text = label; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(10f); gravity = Gravity.CENTER
                })
            }
            "color" -> glyphView().apply {
                background = rounded(NAV_COLORS[index % NAV_COLORS.size], 16)
                elevation = dp(8).toFloat()
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            "dot" -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(glyphView(20f), LinearLayout.LayoutParams(dp(44), dp(36)))
                addView(View(this@OverlayService).apply { background = circle(0x66FFFFFF) },
                    LinearLayout.LayoutParams(dp(4), dp(4)).also { it.topMargin = dp(2) })
            }
            else -> glyphView().apply { layoutParams = LinearLayout.LayoutParams(size, size) } // pill
        }
    }

    /**
     * Run an accessibility-backed nav action. Distinguishes "service not enabled" (tell the user how to
     * enable it) from "enabled but the action returned false" — which on Portal Mini means the system UI
     * has no such feature (e.g. no Recents/Overview). In that case we fall back where we sensibly can.
     */
    private fun runNavAction(label: String, action: () -> Boolean) {
        if (!NavAccessibilityService.isEnabled) {
            showBanner("Navigation not active", "Enable the Overlays accessibility service (see the app's Navigation tab).")
            return
        }
        if (action()) return
        when (label) {
            // Portal+ shows its native (Facebook) overview here; smaller Portals (e.g. Mini) have no
            // overview screen, so recents() returns false — fall back to our own app switcher.
            "Recents" -> showAppSwitcher()
            else -> showBanner("$label unavailable", "This Portal's system UI didn't accept the action.")
        }
    }

    /**
     * Fallback for devices (e.g. Portal Mini) with no system Recents/Overview screen: a simple grid of
     * launchable apps. Tapping one switches to it. Uses real installed apps only — no placeholders.
     */
    private fun showAppSwitcher() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                Triple(ri.loadLabel(pm).toString(), ri.loadIcon(pm), launch)
            }
            .distinctBy { it.third.component?.packageName ?: it.first }
            .sortedBy { it.first.lowercase(Locale.getDefault()) }
        if (apps.isEmpty()) { showBanner("No apps found", "Couldn't list launchable apps on this device."); return }

        val root = FrameLayout(this).apply { setBackgroundColor(0xE60A0C10.toInt()); isClickable = true }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF15181E.toInt(), 22); setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        panel.addView(TextView(this).apply {
            text = "Switch app"; setTextColor(Color.WHITE); textSize = scaled(22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, 0, 0, dp(16))
        })
        val grid = android.widget.GridLayout(this).apply { columnCount = 5 }
        apps.take(20).forEach { (label, icon, launch) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(0x00000000, 14)
                setOnClickListener {
                    safeRemove(root)
                    runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            cell.addView(ImageView(this).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(dp(56), dp(56)))
            cell.addView(TextView(this).apply {
                text = label; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(11f); gravity = Gravity.CENTER
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(80); setPadding(0, dp(6), 0, 0)
            })
            grid.addView(cell)
        }
        panel.addView(grid)
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER })
        root.setOnClickListener { safeRemove(root) }
        val lp = baseParams(focusable = true).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT; height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }
        safeAddView(root, lp)
    }

    // ---- status strip ----------------------------------------------------

    /** Palette + chrome for the status strip. One per selectable style id. */
    private data class StripStyle(
        val barColor: Int,
        val barColor2: Int = barColor,        // gradient end; == barColor for a solid fill
        val gradient: Boolean = false,
        val barAlpha: Int = -1,               // -1 => follow prefs.overlayOpacity
        val text: Int = 0xFFD7DCE4.toInt(),   // primary segment text
        val muted: Int = 0xFF8A919D.toInt(),  // date / secondary text
        val sep: Int = 0x33FFFFFF,            // separator line colour; 0 => no lines
        val mono: Boolean = false,
        val chip: Int = 0,                    // 0 none · 1 tinted pill · 2 bordered cell
        val btnBg: Int = CARD_BASE,           // nav/hide button fill
        val wifiOn: Int = WIFI_ON,
        val wifiDim: Int = WIFI_DIM,
        val accents: Map<String, Int> = emptyMap(),
        val glyphs: Boolean = false,          // prepend a mono category glyph before each segment
        val textScale: Float = 1f,            // multiplies segment/glyph text size (accessibility)
        val bold: Boolean = false             // bold weight on segment text
    )

    /** Monochrome category markers for the glyph styles. VS15 (U+FE0E) forces text, not emoji,
     *  rendering for the U+2600-block symbols on the Portal's Android 9. Wi-Fi has its own bars. */
    private val stripGlyphs = mapOf(
        "context" to "▣",
        "weather" to "☀︎",
        "battery" to "▮",
        "network" to "⇅",
        "week" to "▦",
        "rain" to "☂︎",
        "sun" to "☼",
        "agenda" to "◉",
        "ntfy" to "✉︎"
    )

    /** Current sky gradient (start, end) from today's real sun times, falling back to a nominal
     *  06:30 sunrise / 20:00 sunset when weather isn't available, so it still tracks the clock. */
    private fun skyGradientNow(): Pair<Int, Int> {
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        fun toMin(epoch: Long): Int? {
            if (epoch <= 0L) return null
            val c = java.util.Calendar.getInstance(); c.timeInMillis = epoch
            return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        }
        val sunrise = toMin(weatherExtras.todaySunriseEpoch) ?: 390
        val sunset = toMin(weatherExtras.todaySunsetEpoch) ?: 1200
        return SkyStrip.gradientFor(nowMin, sunrise, sunset)
    }

    private fun stripStyleFor(id: String): StripStyle = when (id) {
        "accented" -> StripStyle(
            barColor = STRIP_BASE,
            accents = mapOf(
                "weather" to 0xFF5DCAA5.toInt(), "network" to 0xFF5DCAA5.toInt(),
                "battery" to 0xFF5DCAA5.toInt(), "rain" to 0xFF85B7EB.toInt(),
                "sun" to 0xFFEF9F27.toInt(), "ntfy" to 0xFFAFA9EC.toInt(),
                "context" to 0xFFAFA9EC.toInt()
            )
        )
        // Single-row approximation of the centred-clock three-zone layout.
        "three-zones" -> StripStyle(
            barColor = 0xFF0A0A0E.toInt(), sep = 0x22FFFFFF,
            accents = mapOf(
                "ntfy" to 0xFFAFA9EC.toInt(), "sun" to 0xFFEF9F27.toInt(),
                "rain" to 0xFF85B7EB.toInt()
            )
        )
        "segments" -> StripStyle(
            barColor = STRIP_BASE, sep = 0, chip = 2,
            accents = mapOf(
                "weather" to 0xFF85B7EB.toInt(), "network" to 0xFF5DCAA5.toInt(),
                "battery" to 0xFF5DCAA5.toInt(), "rain" to 0xFF85B7EB.toInt(),
                "sun" to 0xFFEF9F27.toInt(), "ntfy" to 0xFFAFA9EC.toInt()
            )
        )
        "mono" -> StripStyle(
            barColor = 0xFF080808.toInt(), text = 0xFFB8B8B8.toInt(),
            muted = 0xFF6E6E6E.toInt(), sep = 0x14FFFFFF, mono = true
        )
        // Single-row approximation of the two-row layout.
        "two-rows" -> StripStyle(
            barColor = STRIP_BASE,
            accents = mapOf(
                "sun" to 0xFFEF9F27.toInt(), "ntfy" to 0xFFAFA9EC.toInt(),
                "rain" to 0xFF85B7EB.toInt()
            )
        )
        // Translucent frost — true backdrop blur needs API 31+, not available on Portal.
        "frosted" -> StripStyle(
            barColor = 0xFFFFFFFF.toInt(), barAlpha = 18,
            text = 0xFFFFFFFF.toInt(), muted = 0xB3FFFFFF.toInt(),
            sep = 0x33FFFFFF, btnBg = 0x33FFFFFF, wifiOn = 0xFFFFFFFF.toInt(),
            accents = mapOf(
                "weather" to 0xFF9CC6F0.toInt(), "rain" to 0xFF9CC6F0.toInt(),
                "sun" to 0xFFFBC56B.toInt(), "ntfy" to 0xFFC7C3F4.toInt()
            )
        )
        "chips" -> StripStyle(
            barColor = 0xFF0D0D12.toInt(), text = 0xFFBFBFBF.toInt(),
            sep = 0, chip = 1,
            accents = mapOf(
                "weather" to 0xFF9CC6F0.toInt(), "network" to 0xFF6FD3B2.toInt(),
                "battery" to 0xFF6FD3B2.toInt(), "week" to 0xFF9AA0AC.toInt(),
                "rain" to 0xFF9CC6F0.toInt(), "sun" to 0xFFFBC56B.toInt(),
                "ntfy" to 0xFFC7C3F4.toInt(), "context" to 0xFFFFFFFF.toInt()
            )
        )
        "aurora" -> StripStyle(
            barColor = 0xFF2A2470.toInt(), barColor2 = 0xFF3C7C8E.toInt(), gradient = true,
            text = 0xFFFFFFFF.toInt(), muted = 0xCCFFFFFF.toInt(), sep = 0x38FFFFFF,
            btnBg = 0x33FFFFFF, wifiOn = 0xFFFFFFFF.toInt()
        )
        "daylight" -> StripStyle(
            barColor = 0xFFF6F6F8.toInt(), text = 0xFF1C1D21.toInt(), muted = 0xFF9A9EA8.toInt(),
            sep = 0x1A000000, btnBg = 0x14000000,
            wifiOn = 0xFF1C1D21.toInt(), wifiDim = 0x33000000,
            accents = mapOf(
                "weather" to 0xFF2F7BCE.toInt(), "network" to 0xFF1F9E72.toInt(),
                "battery" to 0xFF1F9E72.toInt(), "rain" to 0xFF2F7BCE.toInt(),
                "sun" to 0xFFC47A12.toInt(), "ntfy" to 0xFF534AB7.toInt(),
                "context" to 0xFF1C1D21.toInt()
            )
        )
        "hud" -> StripStyle(
            barColor = 0xFF07090C.toInt(), text = 0xFF36D1C4.toInt(), muted = 0xFF3D7C77.toInt(),
            sep = 0x3836D1C4, mono = true, btnBg = 0x2236D1C4,
            wifiOn = 0xFF36D1C4.toInt(), wifiDim = 0x3336D1C4,
            accents = mapOf("sun" to 0xFFF2B53C.toInt(), "ntfy" to 0xFFFF7A6B.toInt())
        )
        // Warm amber→magenta gradient.
        "sunset" -> StripStyle(
            barColor = 0xFFB5532A.toInt(), barColor2 = 0xFF7A2F6E.toInt(), gradient = true,
            text = 0xFFFFFFFF.toInt(), muted = 0xCCFFE6D9.toInt(), sep = 0x40FFFFFF,
            btnBg = 0x33FFFFFF, wifiOn = 0xFFFFFFFF.toInt(),
            accents = mapOf(
                "weather" to 0xFFFFD9A0.toInt(), "network" to 0xFFFFE0B0.toInt(),
                "battery" to 0xFFFFE0B0.toInt(), "rain" to 0xFFE6B8F0.toInt(),
                "sun" to 0xFFFFC36B.toInt(), "ntfy" to 0xFFFFD0E0.toInt()
            )
        )
        // Cool teal→indigo gradient.
        "ocean" -> StripStyle(
            barColor = 0xFF0E5A6B.toInt(), barColor2 = 0xFF1E2A6E.toInt(), gradient = true,
            text = 0xFFFFFFFF.toInt(), muted = 0xCCDDEFFF.toInt(), sep = 0x38FFFFFF,
            btnBg = 0x33FFFFFF, wifiOn = 0xFFFFFFFF.toInt(),
            accents = mapOf(
                "weather" to 0xFF8FE3D8.toInt(), "network" to 0xFF8FE3D8.toInt(),
                "battery" to 0xFF8FE3D8.toInt(), "rain" to 0xFFA7C6FF.toInt(),
                "sun" to 0xFFFFD98A.toInt(), "ntfy" to 0xFFC7C3F4.toInt()
            )
        )
        // Greyscale gradient, monospace — neutral and quiet.
        "graphite" -> StripStyle(
            barColor = 0xFF242424.toInt(), barColor2 = 0xFF101012.toInt(), gradient = true,
            text = 0xFFE0E0E0.toInt(), muted = 0xFF8A8A8A.toInt(), sep = 0x1FFFFFFF, mono = true
        )
        // True-black, OLED-friendly minimal.
        "oled" -> StripStyle(
            barColor = 0xFF000000.toInt(), text = 0xFFEDEDED.toInt(), muted = 0xFF7C7C7C.toInt(),
            sep = 0x1AFFFFFF
        )
        // Warm off-white e-ink / paper look, pairs with Daylight.
        "paper" -> StripStyle(
            barColor = 0xFFEDE8DD.toInt(), text = 0xFF2A2723.toInt(), muted = 0xFF8C857A.toInt(),
            sep = 0x1F000000, btnBg = 0x14000000,
            wifiOn = 0xFF2A2723.toInt(), wifiDim = 0x33000000,
            accents = mapOf(
                "weather" to 0xFF3A6B8A.toInt(), "network" to 0xFF2E7D5B.toInt(),
                "battery" to 0xFF2E7D5B.toInt(), "rain" to 0xFF3A6B8A.toInt(),
                "sun" to 0xFFB5731A.toInt(), "ntfy" to 0xFF6A4AA8.toInt(),
                "context" to 0xFF2A2723.toInt()
            )
        )
        // Icon-led dark style — a category glyph before each segment, no divider lines.
        "iconic" -> StripStyle(
            barColor = 0xFF0D0F14.toInt(), text = 0xFFD7DCE4.toInt(), muted = 0xFF8A919D.toInt(),
            sep = 0, glyphs = true,
            accents = mapOf(
                "weather" to 0xFF85B7EB.toInt(), "network" to 0xFF5DCAA5.toInt(),
                "battery" to 0xFF5DCAA5.toInt(), "week" to 0xFF9AA0AC.toInt(),
                "rain" to 0xFF85B7EB.toInt(), "sun" to 0xFFEF9F27.toInt(),
                "ntfy" to 0xFFAFA9EC.toInt(), "context" to 0xFFD7DCE4.toInt()
            )
        )
        // High-contrast accessibility — black ground, AA-safe bright text, larger & bold, with glyphs.
        "hicontrast" -> StripStyle(
            barColor = 0xFF000000.toInt(), text = 0xFFFFFFFF.toInt(), muted = 0xFFFFE34D.toInt(),
            sep = 0x66FFFFFF, bold = true, textScale = 1.25f, glyphs = true,
            btnBg = 0x33FFFFFF, wifiOn = 0xFFFFFFFF.toInt(), wifiDim = 0x55FFFFFF,
            accents = mapOf(
                "weather" to 0xFF7FE0FF.toInt(), "network" to 0xFF74F0B0.toInt(),
                "battery" to 0xFF74F0B0.toInt(), "rain" to 0xFF7FE0FF.toInt(),
                "sun" to 0xFFFFC83D.toInt(), "ntfy" to 0xFFF0A0FF.toInt(),
                "context" to 0xFFFFFFFF.toInt()
            )
        )
        // Sun-driven sky gradient that tracks the time of day — matches the Immortal launcher's
        // Sky background. The gradient is recomputed live each tick (see updateLiveText).
        "sky" -> {
            val (a, b) = skyGradientNow()
            StripStyle(
                barColor = a, barColor2 = b, gradient = true,
                text = 0xFFFFFFFF.toInt(), muted = 0xCCFFFFFF.toInt(), sep = 0x33FFFFFF,
                btnBg = 0x33FFFFFF, wifiOn = 0xFFFFFFFF.toInt(), wifiDim = 0x55FFFFFF,
                accents = mapOf(
                    "weather" to 0xFFEAF6FF.toInt(), "network" to 0xFFD7F5E6.toInt(),
                    "battery" to 0xFFD7F5E6.toInt(), "rain" to 0xFFBFE3FF.toInt(),
                    "sun" to 0xFFFFD98A.toInt(), "ntfy" to 0xFFE6D9FF.toInt(),
                    "context" to 0xFFFFFFFF.toInt()
                )
            )
        }
        else -> StripStyle(barColor = STRIP_BASE)   // "default" — Dense Dark
    }

    private fun showStrip() {
        val style = stripStyleFor(prefs.stripStyle)
        stripWifiOn = style.wifiOn
        stripWifiDim = style.wifiDim
        val fillAlpha = if (style.barAlpha >= 0) style.barAlpha else prefs.overlayOpacity
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = if (style.gradient)
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(withAlpha(style.barColor, fillAlpha), withAlpha(style.barColor2, fillAlpha))
                )
            else GradientDrawable().apply { setColor(withAlpha(style.barColor, fillAlpha)) }
            val vpad = if (style.textScale > 1f) dp(3) else dp(6)
            setPadding(dp(20), vpad, dp(20), vpad)
        }
        var first = true
        // A thin divider before every element except the first (suppressed for chip/cell styles).
        // Slightly thicker for the large-text accessibility style.
        fun sep() {
            if (first) { first = false; return }
            if (style.sep == 0 || style.chip != 0) return
            val line = View(this).apply { setBackgroundColor(style.sep) }
            bar.addView(line, LinearLayout.LayoutParams(if (style.textScale > 1f) dp(2) else dp(1), dp(16)).also { it.leftMargin = dp(14); it.rightMargin = dp(14) })
        }
        // kind keys an accent colour; null uses the style's primary text colour.
        fun textSeg(kind: String? = null): TextView {
            val isFirst = first
            sep()
            val color = kind?.let { style.accents[it] } ?: style.text
            // Optional category glyph, tinted to match its segment, ahead of the value.
            if (style.glyphs) {
                kind?.let { stripGlyphs[it] }?.let { glyph ->
                    val gv = TextView(this).apply {
                        text = glyph
                        setTextColor(color)
                        textSize = scaled(13f * style.textScale)
                        includeFontPadding = false
                        setPadding(0, 0, dp(5), 0)
                    }
                    bar.addView(gv, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { if (!isFirst && style.sep == 0 && style.chip == 0) it.leftMargin = dp(12) })
                }
            }
            val t = TextView(this).apply {
                setTextColor(color)
                textSize = scaled(14f * style.textScale)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (style.mono) typeface = Typeface.MONOSPACE
                if (style.bold) setTypeface(typeface, Typeface.BOLD)
            }
            if (style.chip != 0) {
                val ph = dp(10)
                t.setPadding(ph, dp(2), ph, dp(2))
                t.background = if (style.chip == 1)
                    rounded((color and 0x00FFFFFF) or 0x26000000, 12)          // ~15% tint of the accent
                else GradientDrawable().apply {                                  // bordered cell
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(1), (color and 0x00FFFFFF) or 0x55000000)
                }
                bar.addView(t, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.leftMargin = dp(3); it.rightMargin = dp(3) })
            } else {
                bar.addView(t)
            }
            return t
        }
        fun dot(diameter: Int): View {
            val v = View(this)
            bar.addView(v, LinearLayout.LayoutParams(dp(diameter), dp(diameter)))
            return v
        }
        fun tinyNavButton(glyph: String, label: String, action: () -> Boolean): View {
            val btn = TextView(this).apply {
                text = glyph
                setTextColor(style.text)
                textSize = scaled(15f)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rounded(style.btnBg, 12)
                setPadding(dp(14), dp(4), dp(14), dp(4))
                minWidth = dp(48)
                minHeight = dp(30)
                isAllCaps = false
                contentDescription = label
                setOnClickListener {
                    if (!NavAccessibilityService.isEnabled) {
                        showBanner("Navigation not active", "Enable the Overlays accessibility service (see the app's Navigation tab).")
                    } else if (!action()) {
                        showBanner("$label unavailable", "The Portal system UI didn't accept the action.")
                    }
                }
            }
            bar.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.leftMargin = dp(3)
                it.rightMargin = dp(3)
            })
            return btn
        }

        if (prefs.stripShowClock) stripClock = textSeg().apply {
            if (!style.mono) typeface = Typeface.create("sans-serif-medium", if (style.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        if (prefs.stripShowDate) stripDate = textSeg().apply { setTextColor(style.muted) }
        if (prefs.stripShowContext) stripContext = textSeg("context")
        if (prefs.stripShowWeather) stripWeather = textSeg("weather")
        if (prefs.stripShowBattery) stripBattery = textSeg("battery")
        if (prefs.stripShowNetwork) stripNetwork = textSeg("network").apply {
            // Reserve a fixed width sized to the widest realistic readout so the per-second refresh
            // (e.g. "0 B/s" -> "12.3 MB/s") can't shove the segments after it left and right.
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val widest = "↓ 88.8 MB/s ↑ 88.8 MB/s"
            width = paint.measureText(widest).toInt() + paddingLeft + paddingRight + dp(2)
        }
        if (prefs.stripShowStreaming) {
            sep()
            stripStreamingDot = dot(10)
            val label = TextView(this).apply {
                text = "▶"; setTextColor(style.muted); textSize = scaled(12f)
                setPadding(dp(6), 0, 0, 0)
            }
            bar.addView(label)
        }
        if (prefs.stripShowVpn) {
            sep()
            stripVpnDot = dot(10)
            bar.addView(TextView(this).apply {
                text = "VPN"; setTextColor(style.text); textSize = scaled(12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(6), 0, 0, 0)
            })
        }
        if (prefs.stripShowWifi) {
            sep()
            // Four ascending bars; filled count reflects signal level. Tap shows the IP.
            val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            val heights = intArrayOf(5, 8, 11, 14)
            heights.forEach { h ->
                val b = View(this).apply { setBackgroundColor(style.wifiDim) }
                bars.addView(b, LinearLayout.LayoutParams(dp(3), dp(h)).also { it.rightMargin = dp(2) })
            }
            bars.setOnClickListener { showBanner("Wi-Fi", wifiInfoString()) }
            bar.addView(bars)
            stripWifiBars = bars
        }
        if (prefs.stripShowWeek) stripWeek = textSeg("week")
        if (prefs.stripShowRain) stripRain = textSeg("rain")
        if (prefs.stripShowSun) stripSun = textSeg("sun")
        if (prefs.stripShowAgenda) stripAgenda = textSeg("agenda")
        if (prefs.stripShowNtfy) stripNtfy = textSeg("ntfy")
        if (prefs.stripShowNavButtons) {
            sep()
            stripNavSpacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
            bar.addView(stripNavSpacer)
            stripNavBack = tinyNavButton("‹", "Back", action = { NavAccessibilityService.back() })
            stripNavHome = tinyNavButton("⌂", "Home", action = { NavAccessibilityService.home() })
            stripNavRecents = tinyNavButton("▢", "Recents", action = { NavAccessibilityService.recents() })
        }

        // Hide button at the far right: collapses the strip to a small restore handle so whatever
        // is underneath (e.g. the bottom of a page) can be read. If there were no nav buttons, a
        // flexible spacer pushes it to the edge.
        if (!prefs.stripShowNavButtons) {
            bar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        }
        val hideBtn = TextView(this).apply {
            text = if (prefs.stripPosition == "top") "▴" else "▾"
            setTextColor(style.muted)
            textSize = scaled(15f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(style.btnBg, 12)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            minWidth = dp(40); minHeight = dp(26)
            isAllCaps = false
            contentDescription = "Hide status strip"
            setOnClickListener { hideStrip() }
        }
        bar.addView(hideBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.leftMargin = dp(8) })

        stripHeightPx = if (style.textScale > 1f) dp(44) else dp(36)
        val lp = baseParams(height = stripHeightPx).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or if (prefs.stripPosition == "top") Gravity.TOP else Gravity.BOTTOM
            // Sit flush against the chosen edge — the top strip goes right to the top, no inset.
            y = 0
        }
        if (!safeAddView(bar, lp)) return
        stripView = bar
        stripLp = lp
        updateLiveText()
    }

    /** Collapse the strip (and the ticker that rides with it) to a small restore handle. */
    private fun hideStrip() {
        stripUserHidden = true
        stripView?.let { safeRemove(it) }
        stripView = null
        stripLp = null
        hideTicker()
        showStripHandle()
    }

    /** Bring the strip and ticker back and drop the restore handle. */
    private fun restoreStrip() {
        stripUserHidden = false
        stripHandleView?.let { safeRemove(it) }
        stripHandleView = null
        if (prefs.stripEnabled) showStrip()
        if (prefs.tickerEnabled && prefs.tickerUrl.isNotBlank()) showTicker()
    }

    /** Remove just the ticker overlay; the feed client keeps running so restore is instant. */
    private fun hideTicker() {
        tickerAnim?.cancel(); tickerAnim = null
        tickerView?.let(::safeRemove)
        tickerView = null; tickerText = null
    }

    /** A small tappable chevron pinned to the strip's edge while the strip is hidden. */
    private fun showStripHandle() {
        if (stripHandleView != null) return
        val handle = TextView(this).apply {
            text = if (prefs.stripPosition == "top") "▾" else "▴"
            setTextColor(0xFFD7DCE4.toInt())
            textSize = scaled(15f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(withAlpha(STRIP_BASE, prefs.overlayOpacity), 14)
            setPadding(dp(18), dp(5), dp(18), dp(5))
            isAllCaps = false
            contentDescription = "Show status strip"
            setOnClickListener { restoreStrip() }
        }
        val lp = baseParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.END or if (prefs.stripPosition == "top") Gravity.TOP else Gravity.BOTTOM
            x = dp(16)
            y = if (prefs.stripPosition == "top") dp(70) else dp(8)
        }
        if (!safeAddView(handle, lp)) return
        stripHandleView = handle
    }

    // ---- ticker ----------------------------------------------------------

    /** Resolved chrome for the scrolling ticker. */
    private data class TickerStyle(
        val barColor: Int,
        val barColor2: Int,
        val gradient: Boolean,
        val barAlpha: Int,                // -1 => follow prefs.overlayOpacity
        val text: Int,
        val mono: Boolean,
        val bold: Boolean,
        val textScale: Float,
        val sep: String,                  // joiner drawn between headlines
        val sky: Boolean                  // dynamic sun-driven gradient (repainted each tick)
    )

    /** The ticker shares the status strip's full style catalogue (so the two stay in sync). Per-item
     *  strip chrome — chips, glyphs, dividers — doesn't apply to a single scrolling line, but the bar
     *  fill, text colour, and mono / bold / text-scale all carry across. */
    private fun tickerStyleFor(id: String): TickerStyle {
        val s = stripStyleFor(id)
        val sep = when (id) {
            "hud" -> "   ::   "
            "aurora", "ocean", "sunset", "sky" -> "     ◆     "
            else -> if (s.mono) "     ·     " else "     •     "
        }
        return TickerStyle(
            barColor = s.barColor, barColor2 = s.barColor2, gradient = s.gradient, barAlpha = s.barAlpha,
            text = s.text, mono = s.mono, bold = s.bold, textScale = s.textScale, sep = sep, sky = (id == "sky")
        )
    }

    private fun tickerBackground(style: TickerStyle, fillAlpha: Int): GradientDrawable =
        if (style.gradient)
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(withAlpha(style.barColor, fillAlpha), withAlpha(style.barColor2, fillAlpha)))
        else GradientDrawable().apply { setColor(withAlpha(style.barColor, fillAlpha)) }

    /** Join headlines with the current ticker style's separator glyph. */
    private fun tickerJoin(items: List<String>): String =
        items.joinToString(tickerStyleFor(prefs.tickerStyle).sep)

    private fun showTicker() {
        val style = tickerStyleFor(prefs.tickerStyle)
        val fillAlpha = if (style.barAlpha >= 0) style.barAlpha else prefs.overlayOpacity
        val container = FrameLayout(this).apply {
            background = tickerBackground(style, fillAlpha)
            clipChildren = true; clipToPadding = true
        }
        val t = TextView(this).apply {
            text = "…"; setTextColor(style.text); textSize = scaled(14f * style.textScale)
            maxLines = 1; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
            if (style.mono) typeface = Typeface.MONOSPACE
            if (style.bold) setTypeface(typeface, Typeface.BOLD)
        }
        container.addView(t, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tickerText = t
        val top = prefs.tickerPosition == "top"
        val lp = baseParams(height = if (style.textScale > 1f) dp(38) else dp(30)).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or if (top) Gravity.TOP else Gravity.BOTTOM
            // Top is pinned to the screen edge. Bottom moves above the bottom strip when that strip
            // is also on the bottom edge.
            // The ticker rides under/over the strip when they share an edge: below the top strip,
            // and above the bottom strip. Otherwise it sits flush against its own edge.
            y = when {
                top && prefs.stripEnabled && prefs.stripPosition == "top" -> if (stripHeightPx > 0) stripHeightPx else dp(36)
                top -> 0
                prefs.stripEnabled && prefs.stripPosition == "bottom" -> if (stripHeightPx > 0) stripHeightPx else dp(36)
                else -> 0
            }
        }
        if (!safeAddView(container, lp)) { tickerText = null; return }
        tickerView = container
        container.post { startTickerScroll() }
    }

    /** (Re)start the right-to-left marquee. Overlay windows can't reliably get focus, so we animate
     *  translationX ourselves instead of relying on TextView's focus-gated marquee. */
    private fun startTickerScroll() {
        val t = tickerText ?: return
        tickerAnim?.cancel()
        val screenW = resources.displayMetrics.widthPixels
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        t.measure(unspec, unspec)
        val textW = t.measuredWidth.coerceAtLeast(1)
        val durationMs = ((screenW + textW).toFloat() / prefs.tickerSpeed * 1000f).toLong().coerceAtLeast(2000L)
        t.translationX = screenW.toFloat()
        tickerAnim = ObjectAnimator.ofFloat(t, "translationX", screenW.toFloat(), -textW.toFloat()).apply {
            duration = durationMs
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    // ---- toast banner ----------------------------------------------------

    private fun showBanner(title: String, message: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(withAlpha(BANNER_BASE, prefs.overlayOpacity), prefs.cornerRadius)
            clipToOutline = true
            elevation = dp(10).toFloat()
            minimumWidth = dp(420) // comfortable width so the notification text fits
        }
        card.addView(View(this).apply {
            setBackgroundColor(prefs.accentColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5))
        })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(16))
        }
        body.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = scaled(18f); maxWidth = dp(820)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (message.isNotBlank()) body.addView(TextView(this).apply {
            text = message; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(15f); maxWidth = dp(820); setPadding(0, dp(4), 0, 0)
        })
        card.addView(body)

        val container = FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0) }
        container.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        val top = prefs.bannerPosition != "bottom"
        val lp = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            gravity = if (top) Gravity.TOP else Gravity.BOTTOM
            y = dp(80)
        }
        if (!safeAddView(container, lp)) return

        val from = (if (top) -1 else 1) * dp(160).toFloat()
        container.translationY = from; container.alpha = 0f
        container.animate().translationY(0f).alpha(1f).setDuration(260).start()
        val dismiss = Runnable {
            container.animate().translationY(from).alpha(0f).setDuration(220)
                .withEndAction { safeRemove(container) }.start()
        }
        card.setOnClickListener { main.removeCallbacks(dismiss); dismiss.run() }
        main.postDelayed(dismiss, prefs.bannerSeconds * 1000L)
    }

    // ---- breaking news ----------------------------------------------------

    /** A high-priority ntfy message (priority 5, or a "breaking"/"urgent" tag) gets the full
     *  treatment instead of a normal banner. */
    private fun isBreaking(priority: Int, tags: List<String>): Boolean =
        priority >= 5 || tags.any { it.equals("breaking", true) || it.equals("urgent", true) }

    private fun ensureSpeaker(): Speaker =
        speaker ?: Speaker(this).also { speaker = it; it.start() }

    /**
     * Flashing full-width "BREAKING NEWS" popup with a strobing red eyebrow, a chime + haptics, and
     * a spoken read-out of the headline through the Portal TTS engine. Stays visual-only (still
     * flashes and chimes) on devices without a TTS engine installed.
     */
    private fun showBreakingNews(title: String, message: String) {
        val headline = title.ifBlank { message }
        if (headline.isBlank()) return
        val sub = if (title.isBlank()) "" else message

        val red = 0xFFFF1F2D.toInt()
        val cardBg = withAlpha(0xFF1A0508.toInt(), prefs.overlayOpacity)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, prefs.cornerRadius)
            clipToOutline = true
            elevation = dp(18).toFloat()
            minimumWidth = dp(520)
        }

        // Flashing red eyebrow band:  ● BREAKING NEWS
        val dot = View(this).apply {
            background = circle(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(11), dp(11)).also { it.rightMargin = dp(10) }
        }
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(red)
            setPadding(dp(22), dp(9), dp(22), dp(9))
            addView(dot)
            addView(TextView(this@OverlayService).apply {
                text = "BREAKING NEWS"; setTextColor(Color.WHITE); textSize = scaled(15f)
                letterSpacing = 0.18f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            })
        }
        card.addView(band)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(18))
        }
        body.addView(TextView(this).apply {
            text = headline; setTextColor(Color.WHITE); textSize = scaled(22f); maxWidth = dp(900)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (sub.isNotBlank()) body.addView(TextView(this).apply {
            text = sub; setTextColor(0xFFE6B8BC.toInt()); textSize = scaled(15f); maxWidth = dp(900); setPadding(0, dp(6), 0, 0)
        })
        card.addView(body)

        val container = FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0) }
        container.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        val lp = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP
            y = dp(70)
        }
        if (!safeAddView(container, lp)) return

        // Entrance: drop in + settle.
        val drop = -dp(180).toFloat()
        container.translationY = drop; container.alpha = 0f
        card.scaleX = 0.92f; card.scaleY = 0.92f
        container.animate().translationY(0f).alpha(1f).setDuration(260).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(260).start()

        // Strobe the eyebrow + pulse the dot for urgency.
        val bandFlash = ObjectAnimator.ofFloat(band, "alpha", 1f, 0.3f).apply {
            duration = 380; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE; start()
        }
        val dotPulseX = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 0.55f).apply {
            duration = 380; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE; start()
        }
        val dotPulseY = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 0.55f).apply {
            duration = 380; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE; start()
        }

        // Sound + haptics — breaking news always grabs attention.
        vibrate()
        runCatching {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(applicationContext, uri)?.play()
        }

        // Speak the headline through the (preferably Sherpa) Portal TTS engine.
        if (prefs.breakingNewsSpeak) {
            ensureSpeaker().speak(
                "Breaking news. $headline",
                Locale.getDefault(),
                prefs.breakingNewsVolume / 100f
            )
        }

        val dismiss = Runnable {
            bandFlash.cancel(); dotPulseX.cancel(); dotPulseY.cancel()
            container.animate().translationY(drop).alpha(0f).setDuration(240)
                .withEndAction { safeRemove(container) }.start()
        }
        card.setOnClickListener { main.removeCallbacks(dismiss); dismiss.run() }
        main.postDelayed(dismiss, prefs.breakingNewsSeconds * 1000L)
    }

    // ---- alert overlay ---------------------------------------------------

    private fun showAlert(kind: String) {
        val (glyph, title, body, color) = when (kind) {
            KIND_DOORBELL -> Quad("🔔", "Doorbell", "Someone is at the door.", 0xFF4C8DFF.toInt())
            KIND_TIMER -> Quad("⏰", "Timer finished", "Your timer is up.", 0xFFFF9F0A.toInt())
            else -> Quad("📌", "Reminder", "You asked to be reminded.", 0xFF34C759.toInt())
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(0xFF1B1E24.toInt(), 22)
            setPadding(dp(40), dp(32), dp(40), dp(28)); elevation = dp(16).toFloat()
        }
        card.addView(TextView(this).apply { text = glyph; textSize = scaled(48f); gravity = Gravity.CENTER })
        card.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = scaled(26f); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, dp(12), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = body; setTextColor(0xFFC2C8D2.toInt()); textSize = scaled(16f); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        val btn = TextView(this).apply {
            text = "Dismiss"; setTextColor(Color.WHITE); textSize = scaled(18f); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(color, 14); setPadding(dp(40), dp(16), dp(40), dp(16))
        }
        card.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(24) })

        val root = FrameLayout(this)
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER })
        val lp = baseParams(focusable = true).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT; height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
            flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND; dimAmount = 0.6f
        }
        if (!safeAddView(root, lp)) return
        if (prefs.alertVibrate) vibrate()
        if (prefs.alertSound) playAlertSound(kind)
        card.scaleX = 0.85f; card.scaleY = 0.85f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
        btn.setOnClickListener { safeRemove(root) }
        root.setOnClickListener { safeRemove(root) }
    }

    /** Play the per-kind alert sound, or the device's default notification tone if none is set. */
    private fun playAlertSound(kind: String) {
        val uriStr = prefs.soundUri(kind)
        val uri = if (uriStr.isNotBlank()) android.net.Uri.parse(uriStr)
            else android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        runCatching { android.media.RingtoneManager.getRingtone(applicationContext, uri)?.play() }
    }

    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 220), -1))
            else @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 220, 120, 220), -1)
        } catch (_: Exception) {}
    }

    // ---- live text update -------------------------------------------------

    private fun updateLiveText() {
        val now = Date()
        val timeFmt = SimpleDateFormat(
            (if (prefs.clock24h) "HH:mm" else "h:mm") + (if (prefs.clockSeconds) ":ss" else "") + (if (prefs.clock24h) "" else " a"),
            Locale.getDefault()
        )
        val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        clockTime?.text = timeFmt.format(now)
        clockDot?.background = circle(if (connected) DOT_ON else DOT_OFF)
        clockDate?.apply { visibility = if (prefs.clockShowDate) View.VISIBLE else View.GONE; text = dateFmt.format(now) }

        if (batteryText != null) batteryText?.text = batteryString()
        noteText?.text = prefs.noteText.ifBlank { "(empty note — set it in the app)" }

        stripContext?.text = UiContextState.displayLabel(this)
        stripClock?.text = timeFmt.format(now)
        stripDate?.text = dateFmt.format(now)
        stripWeather?.text = lastWeather.ifBlank { "…" }
        stripBattery?.text = batteryString()
        stripNetwork?.text = networkSpeedString()
        stripWeek?.text = weekString()
        stripRain?.text = rainString()
        stripSun?.text = sunString()
        stripAgenda?.text = agendaString()
        stripNtfy?.text = if (prefs.topic.isBlank()) "no topic"
            else if (connected) "ntfy connected" else "ntfy reconnecting…"
        updateStripIndicators()
        updateStripPlacement()
        // The Sky style's gradient drifts with the time of day — repaint the bar each tick.
        if (prefs.stripStyle == "sky") {
            (stripView as? LinearLayout)?.let { bar ->
                val (a, b) = skyGradientNow()
                val alpha = prefs.overlayOpacity
                bar.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(withAlpha(a, alpha), withAlpha(b, alpha))
                )
            }
        }
        // Same for the Sky ticker background.
        if (prefs.tickerStyle == "sky") {
            (tickerView as? FrameLayout)?.let { c ->
                val (a, b) = skyGradientNow()
                val alpha = prefs.overlayOpacity
                c.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(withAlpha(a, alpha), withAlpha(b, alpha))
                )
            }
        }
    }

    /** Keep the strip pinned flush to its chosen edge (top or bottom). */
    private fun updateStripPlacement() {
        val view = stripView ?: return
        val lp = stripLp ?: return
        val targetY = 0
        if (lp.y != targetY) {
            lp.y = targetY
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
        }
    }

    /** ISO-8601 week number, e.g. "W25". Local, no network. */
    private fun weekString(): String {
        val cal = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY; minimalDaysInFirstWeek = 4
        }
        return "W${cal.get(java.util.Calendar.WEEK_OF_YEAR)}"
    }

    /** "🌧 in 40min" / "🌧 now" / "no rain 1h" / "…" using the last weather fetch. */
    private fun rainString(): String {
        val e = weatherExtras
        val now = System.currentTimeMillis()
        val start = e.rainStartEpoch
        return when {
            start != null && start <= now + 60_000L -> "🌧 now"
            start != null -> {
                val mins = ((start - now) / 60_000L).toInt().coerceAtLeast(1)
                "🌧 in ${mins}min"
            }
            e.rainHorizonEpoch > now -> "no rain 1h"
            else -> "…"
        }
    }

    /** "☀ 3h12m" to sunset or "🌅 5h" to sunrise, recomputed every tick from the stored epoch. */
    private fun sunString(): String {
        val e = weatherExtras
        if (e.sunEventEpoch <= 0L) return "…"
        val delta = e.sunEventEpoch - System.currentTimeMillis()
        if (delta <= 0L) return "…"
        val h = (delta / 3_600_000L).toInt()
        val m = ((delta % 3_600_000L) / 60_000L).toInt()
        val icon = if (e.sunIsSunset) "☀" else "🌅"
        val span = if (h > 0) "${h}h${m}m" else "${m}m"
        return "$icon $span"
    }

    /** Refresh the live status dots/bars (streaming, VPN, Wi-Fi). Called from the 1s tick. */
    private fun updateStripIndicators() {
        stripStreamingDot?.let { dotView ->
            if (isMediaPlaying()) {
                dotView.background = circle(prefs.accentColor)
                if (streamingAnim == null) {
                    streamingAnim = ObjectAnimator.ofFloat(dotView, "alpha", 1f, 0.25f).apply {
                        duration = 750; repeatMode = ObjectAnimator.REVERSE
                        repeatCount = ObjectAnimator.INFINITE; start()
                    }
                }
            } else {
                streamingAnim?.cancel(); streamingAnim = null
                dotView.alpha = 1f; dotView.background = circle(DOT_OFF)
            }
        }
        stripVpnDot?.background = circle(if (isVpnActive()) DOT_ON else VPN_OFF)
        stripWifiBars?.let { bars ->
            val level = wifiLevel()
            for (i in 0 until bars.childCount) {
                bars.getChildAt(i).setBackgroundColor(if (level > i) stripWifiOn else stripWifiDim)
            }
        }
    }

    private fun isMediaPlaying(): Boolean =
        activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }.getOrDefault(false)
    }

    /** Wi-Fi signal as 0..4 bars, or -1 when not connected to Wi-Fi. */
    @Suppress("DEPRECATION")
    private fun wifiLevel(): Int {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return -1
        return runCatching {
            val info = wm.connectionInfo ?: return -1
            if (info.networkId == -1) return -1
            WifiManager.calculateSignalLevel(info.rssi, 5).coerceIn(0, 4)
        }.getOrDefault(-1)
    }

    @Suppress("DEPRECATION")
    private fun wifiIp(): String? {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ipInt = wm?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) return Formatter.formatIpAddress(ipInt)
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
        }.getOrNull()
    }

    private fun wifiInfoString(): String {
        val level = wifiLevel()
        val quality = when {
            level < 0 -> "not connected"
            level >= 4 -> "excellent"
            level == 3 -> "good"
            level == 2 -> "fair"
            level == 1 -> "weak"
            else -> "very weak"
        }
        return "IP: ${wifiIp() ?: "unknown"}\nSignal: $quality"
    }

    private fun batteryString(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val icon = if (charging) "⚡" else "🔋"
        return if (level in 0..100) "$icon $level%" else "$icon --"
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun networkSpeedString(): String {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = System.currentTimeMillis()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            lastNetworkSpeed = "network unavailable"
            return lastNetworkSpeed
        }

        val prevRx = lastNetworkRxBytes
        val prevTx = lastNetworkTxBytes
        val prevMs = lastNetworkSampleMs
        lastNetworkRxBytes = rx
        lastNetworkTxBytes = tx
        lastNetworkSampleMs = now

        if (prevRx < 0 || prevTx < 0 || prevMs <= 0L) {
            return lastNetworkSpeed
        }

        val seconds = ((now - prevMs).coerceAtLeast(1)).toDouble() / 1000.0
        val down = ((rx - prevRx).coerceAtLeast(0) / seconds).toLong()
        val up = ((tx - prevTx).coerceAtLeast(0) / seconds).toLong()
        lastNetworkSpeed = "↓ ${rateString(down)} ↑ ${rateString(up)}"
        return lastNetworkSpeed
    }

    private fun rateString(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L ->
            String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }

    // ---- screenshot (countdown + MediaProjection) ------------------------

    private fun startScreenshot() {
        if (capturing) return
        if (mediaProjection != null) { runCountdownThenCapture(); return }
        // No capture token yet — ask for consent once via the invisible activity.
        startActivity(Intent(this, ScreenshotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun runCountdownThenCapture() {
        if (capturing) return
        capturing = true
        var n = prefs.screenshotDelay
        if (n <= 0) {
            hideCountdown()
            removeAllOverlays()
            main.postDelayed({ capture() }, 180)
            return
        }

        val label = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = scaled(96f); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = circle(0xCC000000.toInt())
            val s = dp(160); minWidth = s; minHeight = s
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val root = FrameLayout(this)
        root.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER })
        val lp = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT; height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }
        if (!safeAddView(root, lp)) { capturing = false; return }
        countdownView = root

        val ticker = object : Runnable {
            override fun run() {
                if (n <= 0) {
                    hideCountdown()
                    removeAllOverlays()
                    main.postDelayed({ capture() }, 180) // let the overlay clear the captured frame
                    return
                }
                label.text = n.toString()
                label.scaleX = 0.7f; label.scaleY = 0.7f; label.alpha = 0.4f
                label.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
                n--
                main.postDelayed(this, 1000)
            }
        }
        main.post(ticker)
    }

    private fun hideCountdown() { countdownView?.let { safeRemove(it) }; countdownView = null }

    private fun capture() {
        val mp = mediaProjection
        if (mp == null) {
            capturing = false
            showBanner("Screenshot failed", "Capture permission was lost — tap the button again.")
            syncFromPrefs()
            return
        }
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val w = metrics.widthPixels; val h = metrics.heightPixels; val dpi = metrics.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("shot").also { it.start() }
        val bg = Handler(thread.looper)
        var vd: VirtualDisplay? = null
        // The virtual display delivers several frames; only the first should be saved.
        val handled = java.util.concurrent.atomic.AtomicBoolean(false)

        fun cleanup() {
            try { vd?.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            thread.quitSafely()
            capturing = false
            syncFromPrefs()
        }

        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            if (!handled.compareAndSet(false, true)) { image.close(); return@setOnImageAvailableListener }
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val buffer = plane.buffer
                val strideW = rowStride / pixelStride
                // The plane buffer can end on a short final row (GPU stride padding), so a bitmap
                // sized to the full rowStride*h would make copyPixelsFromBuffer memcpy past the
                // buffer end — a native SIGSEGV that the catch below can't stop (it took the app
                // down on Portal/Android 9). Bound the copy to the rows the buffer actually holds.
                val rows = minOf(h, buffer.remaining() / rowStride)
                if (rows <= 0) {
                    main.post { showBanner("Screenshot failed", "Empty capture buffer.") }
                } else {
                    val full = Bitmap.createBitmap(strideW, rows, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(buffer)
                    val cropW = minOf(w, strideW)
                    val bmp = if (full.width != cropW || full.height != rows)
                        Bitmap.createBitmap(full, 0, 0, cropW, rows) else full
                    val where = saveShot(bmp)
                    main.post {
                        if (where != null) showBanner("Screenshot saved", where)
                        else showBanner("Screenshot failed", "Couldn't write the file.")
                    }
                }
            } catch (e: Exception) {
                main.post { showBanner("Screenshot failed", e.message ?: "Unknown error.") }
            } finally {
                image.close()
                main.post { cleanup() }
            }
        }, bg)

        try {
            vd = mp.createVirtualDisplay(
                "overlays-shot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, bg
            )
        } catch (e: Exception) {
            showBanner("Screenshot failed", e.message ?: "Couldn't start capture."); cleanup()
        }
    }

    /**
     * Saves [bmp] and returns a short "where it landed" message for the banner, or null only if
     * every path failed.
     *
     * Both branches go through MediaStore on purpose. On Portal/Android 9 the app process never
     * gets the sdcard_rw gid (it targets SDK 29 and WRITE_EXTERNAL_STORAGE is maxSdkVersion=28), so
     * a direct File write to shared storage fails even when the permission is "granted" — but the
     * media provider performs the write under its own uid, so an openOutputStream on a MediaStore
     * uri works. If even that fails we fall back to the app-private external dir (no permission
     * needed) so a screenshot is never silently lost.
     */
    private fun saveShot(bmp: Bitmap): String? {
        val name = "portal_${System.currentTimeMillis()}.png"

        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                } else {
                    // Pre-Q has no RELATIVE_PATH; point DATA at Pictures/Screenshots and let the
                    // media provider create the folder and write the file under its own uid.
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                        "Screenshots"
                    )
                    put(MediaStore.Images.Media.DATA, java.io.File(dir, name).absolutePath)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    return "Saved to Pictures/Screenshots."
                } catch (_: Exception) {
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // No-permission fallback: the app-private external dir is always writable.
        val appDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        if (appDir != null && (appDir.exists() || appDir.mkdirs())) {
            val file = java.io.File(appDir, name)
            if (writePng(bmp, file)) {
                scanFile(file)
                return "Saved to app storage (couldn't reach the gallery)."
            }
        }
        return null
    }

    private fun writePng(bmp: Bitmap, file: java.io.File): Boolean = try {
        java.io.FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Exception) { false }

    private fun scanFile(file: java.io.File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), arrayOf("image/png"), null
            )
        } catch (_: Exception) {}
    }

    // ---- view / window helpers -------------------------------------------

    private fun cardColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), prefs.cornerRadius)
        setPadding(dp(18), dp(14), dp(18), dp(14)); elevation = dp(8).toFloat()
    }

    private fun addDraggable(view: View, key: String, defX: Int, defY: Int, onTap: (() -> Unit)? = null): View {
        val lp = baseParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getX(key, defX); y = prefs.getY(key, defY)
        }
        clampParamsToScreen(view, lp)
        makeDraggable(view, view, lp, key, onTap = onTap)
        if (!safeAddView(view, lp)) return view
        draggables.add(view to lp)
        return view
    }

    /**
     * Drag [moveView] by touching [touchTarget]; movement under the slop counts as a tap. When
     * [pressFeedback] is set the target dims while held; [onLongPress] (if given) fires after the system
     * long-press timeout and suppresses the tap.
     */
    private fun makeDraggable(
        touchTarget: View, moveView: View, lp: WindowManager.LayoutParams, posKey: String,
        pressFeedback: Boolean = false, onLongPress: (() -> Unit)? = null,
        locked: () -> Boolean = { false }, onTap: (() -> Unit)?
    ) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var longFired = false
        val slop = dp(8)
        val longPress = Runnable { longFired = true; touchTarget.alpha = 1f; onLongPress?.invoke() }
        touchTarget.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; longFired = false
                    if (pressFeedback) touchTarget.alpha = 0.55f
                    if (onLongPress != null) main.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Locked overlays still tap (so the buttons keep working) but never move.
                    if (locked()) return@setOnTouchListener true
                    val dx = (e.rawX - downX).roundToInt(); val dy = (e.rawY - downY).roundToInt()
                    if (abs(dx) > slop || abs(dy) > slop) { moved = true; main.removeCallbacks(longPress) }
                    val (x, y) = clampToScreen(moveView, startX + dx, startY + dy)
                    lp.x = x; lp.y = y
                    wm.updateViewLayout(moveView, lp); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (pressFeedback) touchTarget.alpha = 1f
                    if (e.action == MotionEvent.ACTION_UP) {
                        if (moved) prefs.setPos(posKey, lp.x, lp.y) else if (!longFired) onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun baseParams(height: Int = WindowManager.LayoutParams.WRAP_CONTENT, focusable: Boolean = false): WindowManager.LayoutParams {
        // NB: no FLAG_LAYOUT_NO_LIMITS. On Portal's old compositor that flag defeats damage-region
        // clearing, so a moving overlay (dragged, or panned when a keyboard opens) leaves ghost trails.
        // We clamp every overlay on-screen ourselves, so the flag isn't needed.
        var f = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!focusable) f = f or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT
        ).apply {
            // Don't let a soft keyboard (in this or any app) pan/resize our overlays — that pan is what
            // smears the nav cluster across the screen and steals touches while typing.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    /**
     * Keep a dragged overlay fully on screen and out of the corners: an edge margin on every side
     * plus a larger top inset so widgets never tuck under Portal's top system pills.
     */
    private fun clampToScreen(view: View, x: Int, y: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val margin = dp(12); val topInset = dp(64)
        val w = if (view.width > 0) view.width else view.measuredWidth
        val h = if (view.height > 0) view.height else view.measuredHeight
        val maxX = (dm.widthPixels - w - margin).coerceAtLeast(margin)
        val maxY = (dm.heightPixels - h - margin).coerceAtLeast(topInset)
        return Pair(x.coerceIn(margin, maxX), y.coerceIn(topInset, maxY))
    }

    /**
     * Pull a not-yet-attached overlay's params onto the current screen. Defaults and saved positions
     * are stored in absolute pixels, so a coordinate tuned for landscape (e.g. the nav cluster at the
     * bottom-right of a 1920-wide display) would otherwise land off-screen in portrait/vertical. We
     * measure the view first so the clamp accounts for its size.
     */
    private fun clampParamsToScreen(view: View, lp: WindowManager.LayoutParams) {
        if (view.width == 0 && view.measuredWidth == 0) {
            val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(unspec, unspec)
        }
        val (x, y) = clampToScreen(view, lp.x, lp.y)
        lp.x = x; lp.y = y
    }

    /** Re-clamp every widget after a rotation so nothing is stranded off the new screen bounds. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post {
            draggables.toList().forEach { (view, lp) ->
                if (!view.isAttachedToWindow) return@forEach
                val (x, y) = clampToScreen(view, lp.x, lp.y)
                if (x != lp.x || y != lp.y) {
                    lp.x = x; lp.y = y
                    try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun canDrawOverlays() = Settings.canDrawOverlays(this)

    /**
     * Add an overlay window without ever crashing the service. If "draw over other apps" is missing
     * or was just revoked, [WindowManager.addView] throws (BadTokenException / SecurityException) —
     * we swallow it and report failure so callers can bail out cleanly instead of taking the app down.
     */
    private fun safeAddView(view: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, lp); true
    } catch (_: Exception) { false }

    private fun safeRemove(v: View) { try { if (v.isAttachedToWindow) wm.removeView(v) } catch (_: Exception) {} }
    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun withAlpha(color: Int, opacityPct: Int) = (color and 0x00FFFFFF) or ((255 * opacityPct / 100) shl 24)
    private fun scaled(size: Float) = size * prefs.textScale / 100f
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildNotification(permissionNeeded: Boolean = false): Notification {
        val channelId = "overlays"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Overlays", NotificationManager.IMPORTANCE_LOW)
            )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, channelId)
            .setContentTitle(if (permissionNeeded) "Overlays needs permission" else "Overlays running")
            .setContentText(
                if (permissionNeeded) "Tap to allow \"Draw over other apps\""
                else "ntfy listener + on-top overlays"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi).setOngoing(true).build()
    }

    /** Re-post the foreground notification so it reflects whether the overlay permission is missing. */
    private fun updateNotification(permissionNeeded: Boolean) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(permissionNeeded))
        } catch (_: Exception) {}
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: Int)

    companion object {
        const val ACTION_REFRESH = "com.portal.overlays.REFRESH"
        const val ACTION_SYNC_WIDGETS = "com.portal.overlays.SYNC_WIDGETS"
        const val ACTION_SYNC_TICKER = "com.portal.overlays.SYNC_TICKER"
        const val ACTION_STOP = "com.portal.overlays.STOP"
        const val ACTION_TEST_BANNER = "com.portal.overlays.TEST_BANNER"
        const val ACTION_BANNER = "com.portal.overlays.BANNER"
        const val ACTION_MEDIA_REFRESH = "com.portal.overlays.MEDIA_REFRESH"
        const val ACTION_PREVIEW_NOW_PLAYING = "com.portal.overlays.PREVIEW_NOW_PLAYING"
        const val ACTION_CONTEXT_CHANGED = "com.portal.overlays.CONTEXT_CHANGED"
        const val ACTION_ALERT = "com.portal.overlays.ALERT"
        const val ACTION_BREAKING = "com.portal.overlays.BREAKING"
        const val ACTION_SCREENSHOT = "com.portal.overlays.SCREENSHOT"
        const val ACTION_PROJECTION_RESULT = "com.portal.overlays.PROJECTION_RESULT"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val KIND_DOORBELL = "doorbell"
        const val KIND_TIMER = "timer"
        const val KIND_REMINDER = "reminder"

        private const val NOTIF_ID = 1001
        private val CARD_BASE = 0xFF1B1E24.toInt()
        private val BANNER_BASE = 0xFF22262E.toInt()
        private val STRIP_BASE = 0xFF101216.toInt()
        private val NOTE_BASE = 0xFFFFE08A.toInt()
        private val DOT_ON = 0xFF34C759.toInt()
        private val DOT_OFF = 0xFF5A6172.toInt()
        private val VPN_OFF = 0xFFE5484D.toInt()
        private val WIFI_ON = 0xFFD7DCE4.toInt()
        private const val WIFI_DIM = 0x33FFFFFF
        // Per-button tints for the "colour-coded" nav style (back, home, recents, …).
        private val NAV_COLORS = intArrayOf(
            0xFF4C8DFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9F0A.toInt(),
            0xFFBF5AF2.toInt(), 0xFF64D2FF.toInt(), 0xFFFF375F.toInt(), 0xFF8E8E93.toInt()
        )

        fun send(context: Context, action: String, kind: String? = null) {
            val i = Intent(context, OverlayService::class.java).setAction(action)
            if (kind != null) i.putExtra(EXTRA_KIND, kind)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun sendBanner(context: Context, title: String, text: String) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_BANNER)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun sendBreaking(context: Context, title: String, text: String) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_BREAKING)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
