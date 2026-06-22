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
    private var noteView: View? = null
    private var noteText: TextView? = null
    private var stripView: View? = null
    private var stripLp: WindowManager.LayoutParams? = null
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
    private var stripNavSpacer: View? = null
    private var stripNavBack: View? = null
    private var stripNavHome: View? = null
    private var stripNavRecents: View? = null
    private var navView: View? = null
    private var tickerView: View? = null
    private var tickerText: TextView? = null
    private var tickerAnim: ObjectAnimator? = null

    /** Draggable widgets paired with their layout params, so they can be re-clamped on rotation. */
    private val draggables = mutableListOf<Pair<View, WindowManager.LayoutParams>>()

    private var ntfy: NtfyClient? = null
    private var weather: WeatherClient? = null
    private var weatherCfgLoaded: String = ""
    private var ticker: TickerClient? = null
    private var tickerCfgLoaded: String = ""
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
            ACTION_ALERT -> showAlert(intent.getStringExtra(EXTRA_KIND) ?: KIND_REMINDER)
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

        if (prefs.nowPlayingEnabled) {
            if (nowPlayingView == null) showNowPlaying()
            npAutoExpandPending = prefs.nowPlayingStartExpanded
        } else {
            hideNowPlayingFull()
            nowPlayingView?.let(::safeRemove)
            nowPlayingView = null; nowPlayingMiniArt = null; nowPlayingMiniGlyph = null
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
            if (tickerView == null) showTicker()
            else {
                tickerAnim?.cancel()
                tickerAnim = null
                tickerText?.let { it.text = it.text.ifBlank { "…" } }
                tickerView?.post { startTickerScroll() }
            }
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
                    tickerText?.let { view -> view.text = items.joinToString("     •     ").ifBlank { "…" }; startTickerScroll() }
                }
            }.also { it.start() }
        }
        updateLiveText()
    }

    private fun syncBackgroundClients() {
        val topic = prefs.topic
        if (topic.isNotBlank()) {
            if (ntfy == null) {
                ntfy = NtfyClient(topic,
                    onConnected = { c -> main.post { connected = c; updateLiveText() } },
                    onMessage = { title, msg -> main.post { showBanner(title, msg) } }
                ).also { it.start() }
            }
        } else { ntfy?.stop(); ntfy = null; connected = false }

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

        val tickerUrl = prefs.tickerUrl
        if (prefs.tickerEnabled && tickerUrl.isNotBlank()) {
            if (ticker == null || tickerCfgLoaded != tickerUrl) {
                ticker?.stop(); tickerCfgLoaded = tickerUrl
                ticker = TickerClient(tickerUrl) { items ->
                    main.post {
                        tickerText?.let { it.text = items.joinToString("     •     ").ifBlank { "…" }; startTickerScroll() }
                    }
                }.also { it.start() }
            }
        } else { ticker?.stop(); ticker = null; tickerCfgLoaded = "" }
        updateLiveText()
    }

    private fun removeAllOverlays() {
        listOf(clockView, weatherView, batteryView, nowPlayingView, nowPlayingFullView, noteView, stripView, stripHandleView, navView, tickerView).forEach { it?.let(::safeRemove) }
        draggables.clear()
        clockView = null; weatherView = null; batteryView = null; nowPlayingView = null; noteView = null; stripView = null; stripHandleView = null; navView = null
        clockTime = null; clockDate = null; clockDot = null
        weatherTemp = null; weatherCond = null; weatherPlace = null
        batteryText = null
        stopNowPlayingProgressTicker()
        nowPlayingMiniArt = null; nowPlayingMiniGlyph = null
        clearNowPlayingFullRefs()
        noteText = null
        streamingAnim?.cancel(); streamingAnim = null
        stripContext = null
        stripClock = null; stripDate = null; stripWeather = null; stripBattery = null
        stripNetwork = null; stripNtfy = null
        stripStreamingDot = null; stripVpnDot = null; stripWifiBars = null
        stripWeek = null; stripRain = null; stripSun = null
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

    private fun showNowPlaying() {
        val button = FrameLayout(this).apply {
            background = rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), 24)
            elevation = dp(12).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF2A2F39.toInt(), 20)
            clipToOutline = true
            setImageResource(android.R.drawable.ic_media_play)
            isClickable = false
            isFocusable = false
        }
        button.addView(art, FrameLayout.LayoutParams(dp(64), dp(64)).also { it.gravity = Gravity.CENTER })
        val glyph = TextView(this).apply {
            text = ">"
            setTextColor(Color.WHITE)
            textSize = scaled(18f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = circle(0xAA000000.toInt())
        }
        button.addView(glyph, FrameLayout.LayoutParams(dp(30), dp(30)).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
        })

        nowPlayingMiniArt = art
        nowPlayingMiniGlyph = glyph
        nowPlayingView = addDraggable(button, "nowPlaying", dp(28), dp(700)) { showNowPlayingFull() }
        npAutoExpandPending = prefs.nowPlayingStartExpanded
        updateNowPlaying()
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
        val bar = nowPlayingFullProgress ?: return
        val controller = activeMediaController
        val state = controller?.playbackState
        val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (state == null || duration <= 0L) {
            nowPlayingFullElapsed?.text = "0:00"
            nowPlayingFullDuration?.text = "0:00"
            bar.progress = 0
            return
        }
        // Estimate the live position: last reported position + time elapsed since, scaled by speed.
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val delta = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            pos += (delta * state.playbackSpeed).toLong()
        }
        pos = pos.coerceIn(0L, duration)
        bar.progress = ((pos * 1000L) / duration).toInt()
        nowPlayingFullElapsed?.text = formatClock(pos)
        nowPlayingFullDuration?.text = formatClock(duration)
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

    private fun bindMediaController(controllers: List<MediaController>) {
        val best = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
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
        // "Start expanded": open the full card once, the first time we see something playing.
        if (playing && npAutoExpandPending && nowPlayingView != null && nowPlayingFullView == null) {
            npAutoExpandPending = false
            showNowPlayingFull()
        }
        updateNowPlayingProgress()

        if (mediaAccessMissing) {
            nowPlayingMiniGlyph?.text = "!"
            nowPlayingMiniArt?.setImageResource(android.R.drawable.ic_dialog_info)
            nowPlayingFullTitle?.text = "Notification access needed"
            nowPlayingFullSubtitle?.text = "Allow the listener, then refresh"
            nowPlayingFullApp?.text = "NOW PLAYING"
            nowPlayingFullPlay?.text = ">"
            nowPlayingFullArt?.setImageResource(android.R.drawable.ic_dialog_info)
            nowPlayingFullAlbum?.visibility = View.GONE
            nowPlayingFullAppIcon?.visibility = View.GONE
            return
        }
        if (controller == null || metadata == null) {
            nowPlayingMiniGlyph?.text = ">"
            nowPlayingMiniArt?.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingFullTitle?.text = "Nothing playing"
            nowPlayingFullSubtitle?.text = "Start media in any app"
            nowPlayingFullApp?.text = "NOW PLAYING"
            nowPlayingFullPlay?.text = ">"
            nowPlayingFullArt?.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingFullAlbum?.visibility = View.GONE
            nowPlayingFullAppIcon?.visibility = View.GONE
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
        // Source-app icon (e.g. the Spotify logo).
        nowPlayingFullAppIcon?.let { iv ->
            val icon = try { packageManager.getApplicationIcon(controller.packageName) } catch (_: Exception) { null }
            if (icon != null) { iv.setImageDrawable(icon); iv.visibility = View.VISIBLE } else iv.visibility = View.GONE
        }
        if (art != null) {
            nowPlayingMiniArt?.setImageBitmap(art)
            nowPlayingFullArt?.setImageBitmap(art)
        } else {
            nowPlayingMiniArt?.setImageResource(android.R.drawable.ic_media_play)
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
            makeDraggable(btn, bar, lp, "nav", pressFeedback = true, onLongPress = onLongPress) {
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

    private fun showStrip() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(withAlpha(STRIP_BASE, prefs.overlayOpacity)) }
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        var first = true
        // A thin divider before every element except the first.
        fun sep() {
            if (first) { first = false; return }
            val line = View(this).apply { setBackgroundColor(0x33FFFFFF) }
            bar.addView(line, LinearLayout.LayoutParams(dp(1), dp(16)).also { it.leftMargin = dp(14); it.rightMargin = dp(14) })
        }
        fun textSeg(): TextView {
            sep()
            val t = TextView(this).apply {
                setTextColor(0xFFD7DCE4.toInt())
                textSize = scaled(14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            bar.addView(t); return t
        }
        fun dot(diameter: Int): View {
            val v = View(this)
            bar.addView(v, LinearLayout.LayoutParams(dp(diameter), dp(diameter)))
            return v
        }
        fun tinyNavButton(glyph: String, label: String, action: () -> Boolean): View {
            val btn = TextView(this).apply {
                text = glyph
                setTextColor(0xFFD7DCE4.toInt())
                textSize = scaled(15f)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), 12)
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

        if (prefs.stripShowClock) stripClock = textSeg()
        if (prefs.stripShowDate) stripDate = textSeg()
        if (prefs.stripShowContext) stripContext = textSeg()
        if (prefs.stripShowWeather) stripWeather = textSeg()
        if (prefs.stripShowBattery) stripBattery = textSeg()
        if (prefs.stripShowNetwork) stripNetwork = textSeg()
        if (prefs.stripShowStreaming) {
            sep()
            stripStreamingDot = dot(10)
            val label = TextView(this).apply {
                text = "▶"; setTextColor(0xFF8A919D.toInt()); textSize = scaled(12f)
                setPadding(dp(6), 0, 0, 0)
            }
            bar.addView(label)
        }
        if (prefs.stripShowVpn) {
            sep()
            stripVpnDot = dot(10)
            bar.addView(TextView(this).apply {
                text = "VPN"; setTextColor(0xFFB7C0CC.toInt()); textSize = scaled(12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(6), 0, 0, 0)
            })
        }
        if (prefs.stripShowWifi) {
            sep()
            // Four ascending bars; filled count reflects signal level. Tap shows the IP.
            val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            val heights = intArrayOf(5, 8, 11, 14)
            heights.forEach { h ->
                val b = View(this).apply { setBackgroundColor(WIFI_DIM) }
                bars.addView(b, LinearLayout.LayoutParams(dp(3), dp(h)).also { it.rightMargin = dp(2) })
            }
            bars.setOnClickListener { showBanner("Wi-Fi", wifiInfoString()) }
            bar.addView(bars)
            stripWifiBars = bars
        }
        if (prefs.stripShowWeek) stripWeek = textSeg()
        if (prefs.stripShowRain) stripRain = textSeg()
        if (prefs.stripShowSun) stripSun = textSeg()
        if (prefs.stripShowNtfy) stripNtfy = textSeg()
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
            setTextColor(0xFF8A919D.toInt())
            textSize = scaled(15f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), 12)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            minWidth = dp(40); minHeight = dp(26)
            isAllCaps = false
            contentDescription = "Hide status strip"
            setOnClickListener { hideStrip() }
        }
        bar.addView(hideBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.leftMargin = dp(8) })

        val lp = baseParams(height = dp(36)).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or if (prefs.stripPosition == "top") Gravity.TOP else Gravity.BOTTOM
            if (prefs.stripPosition == "top") y = dp(70)
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

    private fun showTicker() {
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(withAlpha(STRIP_BASE, prefs.overlayOpacity)) }
            clipChildren = true; clipToPadding = true
        }
        val t = TextView(this).apply {
            text = "…"; setTextColor(0xFFD7DCE4.toInt()); textSize = scaled(14f)
            maxLines = 1; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
        }
        container.addView(t, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tickerText = t
        val top = prefs.tickerPosition == "top"
        val lp = baseParams(height = dp(30)).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or if (top) Gravity.TOP else Gravity.BOTTOM
            // Top is pinned to the screen edge. Bottom moves above the bottom strip when that strip
            // is also on the bottom edge.
            y = when {
                top -> 0
                prefs.stripEnabled && prefs.stripPosition == "bottom" -> dp(36)
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
        stripNtfy?.text = if (prefs.topic.isBlank()) "no topic"
            else if (connected) "ntfy connected" else "ntfy reconnecting…"
        updateStripIndicators()
        updateStripPlacement()
    }

    /** Keep the top strip below Portal's built-in system pills. */
    private fun updateStripPlacement() {
        val view = stripView ?: return
        val lp = stripLp ?: return
        val targetY = if (prefs.stripPosition == "top") dp(70) else 0
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
                bars.getChildAt(i).setBackgroundColor(if (level > i) WIFI_ON else WIFI_DIM)
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
        pressFeedback: Boolean = false, onLongPress: (() -> Unit)? = null, onTap: (() -> Unit)?
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
        const val ACTION_CONTEXT_CHANGED = "com.portal.overlays.CONTEXT_CHANGED"
        const val ACTION_ALERT = "com.portal.overlays.ALERT"
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
    }
}
