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
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
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
    private var nowPlayingVisualizer: NowPlayingVisualizerView? = null
    private var noteView: View? = null
    private var noteText: TextView? = null
    private var stripView: View? = null
    private var stripText: TextView? = null
    private var navView: View? = null

    /** Draggable widgets paired with their layout params, so they can be re-clamped on rotation. */
    private val draggables = mutableListOf<Pair<View, WindowManager.LayoutParams>>()

    private var ntfy: NtfyClient? = null
    private var weather: WeatherClient? = null
    private var weatherCfgLoaded: String = ""
    @Volatile private var connected = false
    @Volatile private var lastWeather: String = ""
    private var lastNetworkRxBytes = -1L
    private var lastNetworkTxBytes = -1L
    private var lastNetworkSampleMs = 0L
    private var lastNetworkSpeed = "↓ 0 B/s ↑ 0 B/s"

    private var mediaProjection: MediaProjection? = null
    private var countdownView: View? = null
    private var capturing = false

    private val mediaListenerComponent by lazy { ComponentName(this, NotifyListenerService::class.java) }
    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaSessionsListening = false
    private var mediaAccessMissing = false
    private var activeMediaController: MediaController? = null

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
        if (prefs.clockEnabled) showClock()
        if (prefs.weatherEnabled) showWeather()
        if (prefs.batteryEnabled) showBattery()
        if (prefs.nowPlayingEnabled) {
            showNowPlaying()
            startMediaSessions()
        } else {
            stopMediaSessions()
        }
        if (prefs.noteEnabled) showNote()
        if (prefs.stripEnabled) showStrip()
        if (prefs.navEnabled) showNav()

        val topic = prefs.topic
        if (topic.isNotBlank()) {
            if (ntfy == null) {
                ntfy = NtfyClient(topic,
                    onConnected = { c -> main.post { connected = c; updateLiveText() } },
                    onMessage = { title, msg -> main.post { showBanner(title, msg) } }
                ).also { it.start() }
            }
        } else { ntfy?.stop(); ntfy = null; connected = false }

        val needWeather = prefs.weatherEnabled || prefs.stripShowWeather
        val city = prefs.weatherCity
        val cfg = "$city|${prefs.weatherFahrenheit}"
        if (needWeather && city.isNotBlank()) {
            if (weather == null || weatherCfgLoaded != cfg) {
                weather?.stop(); weatherCfgLoaded = cfg
                weather = WeatherClient(city, prefs.weatherFahrenheit) { temp, cond, place ->
                    main.post {
                        lastWeather = "$temp $cond"
                        weatherTemp?.text = temp
                        weatherCond?.text = cond
                        weatherPlace?.text = place
                        updateLiveText()
                    }
                }.also { it.start() }
            }
        } else { weather?.stop(); weather = null; weatherCfgLoaded = ""; lastWeather = "" }
        updateLiveText()
    }

    private fun removeAllOverlays() {
        listOf(clockView, weatherView, batteryView, nowPlayingView, nowPlayingFullView, noteView, stripView, navView).forEach { it?.let(::safeRemove) }
        draggables.clear()
        clockView = null; weatherView = null; batteryView = null; nowPlayingView = null; noteView = null; stripView = null; navView = null
        clockTime = null; clockDate = null; clockDot = null
        weatherTemp = null; weatherCond = null; weatherPlace = null
        batteryText = null
        nowPlayingFullView = null
        nowPlayingMiniArt = null; nowPlayingMiniGlyph = null
        nowPlayingFullArt = null; nowPlayingFullTitle = null; nowPlayingFullSubtitle = null; nowPlayingFullApp = null; nowPlayingFullPlay = null
        nowPlayingVisualizer = null
        noteText = null; stripText = null
    }

    private fun stopEverything() {
        main.removeCallbacks(tick)
        ntfy?.stop(); ntfy = null
        weather?.stop(); weather = null; weatherCfgLoaded = ""
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
        updateNowPlaying()
    }

    private fun showNowPlayingFull() {
        if (nowPlayingFullView != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xF20A0C10.toInt())
            isClickable = true
        }
        val visualizer = NowPlayingVisualizerView(this).apply {
            accentColor = prefs.accentColor
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(88), dp(86), dp(88), dp(80))
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF202631.toInt(), 34)
            clipToOutline = true
            elevation = dp(18).toFloat()
            setImageResource(android.R.drawable.ic_media_play)
        }
        content.addView(art, LinearLayout.LayoutParams(dp(430), dp(430)).also { it.rightMargin = dp(54) })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val app = TextView(this).apply {
            text = "NOW PLAYING"
            setTextColor(prefs.accentColor)
            textSize = scaled(14f)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.12f
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
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, dp(34))
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

        info.addView(app)
        info.addView(title, LinearLayout.LayoutParams(dp(840), ViewGroup.LayoutParams.WRAP_CONTENT))
        info.addView(subtitle, LinearLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT))
        info.addView(controls)
        content.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        nowPlayingFullView = root
        nowPlayingVisualizer = visualizer
        nowPlayingFullArt = art
        nowPlayingFullTitle = title
        nowPlayingFullSubtitle = subtitle
        nowPlayingFullApp = app
        nowPlayingFullPlay = play

        val lp = baseParams(focusable = true).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }
        wm.addView(root, lp)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
        updateNowPlaying()
    }

    private fun hideNowPlayingFull() {
        val root = nowPlayingFullView ?: return
        nowPlayingVisualizer?.playing = false
        root.animate().alpha(0f).setDuration(140).withEndAction { safeRemove(root) }.start()
        nowPlayingFullView = null
        nowPlayingFullArt = null
        nowPlayingFullTitle = null
        nowPlayingFullSubtitle = null
        nowPlayingFullApp = null
        nowPlayingFullPlay = null
        nowPlayingVisualizer = null
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

        if (mediaAccessMissing) {
            nowPlayingMiniGlyph?.text = "!"
            nowPlayingMiniArt?.setImageResource(android.R.drawable.ic_dialog_info)
            nowPlayingFullTitle?.text = "Notification access needed"
            nowPlayingFullSubtitle?.text = "Allow the listener, then refresh"
            nowPlayingFullApp?.text = "NOW PLAYING"
            nowPlayingFullPlay?.text = ">"
            nowPlayingFullArt?.setImageResource(android.R.drawable.ic_dialog_info)
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
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel(controller.packageName)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: appLabel(controller.packageName)
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        nowPlayingMiniGlyph?.text = if (playing) "||" else ">"
        nowPlayingFullTitle?.text = title
        nowPlayingFullSubtitle?.text = artist
        nowPlayingFullApp?.text = appLabel(controller.packageName).uppercase(Locale.getDefault())
        nowPlayingFullPlay?.text = if (playing) "||" else ">"
        if (art != null) {
            nowPlayingMiniArt?.setImageBitmap(art)
            nowPlayingFullArt?.setImageBitmap(art)
        } else {
            nowPlayingMiniArt?.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingFullArt?.setImageResource(android.R.drawable.ic_media_play)
        }
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
        val bar = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            background = rounded(withAlpha(CARD_BASE, prefs.overlayOpacity), 28)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(10).toFloat()
        }
        val lp = baseParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getX("nav", dp(1556)); y = prefs.getY("nav", dp(972))
        }
        fun add(glyph: String, action: () -> Boolean) {
            val b = TextView(this).apply {
                text = glyph; setTextColor(Color.WHITE); textSize = scaled(26f); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val size = dp(56)
            val mlp = LinearLayout.LayoutParams(size, size)
            if (vertical) mlp.bottomMargin = dp(6) else mlp.rightMargin = dp(6)
            bar.addView(b, mlp)
            makeDraggable(b, bar, lp, "nav") {
                if (!action()) showBanner("Navigation not active", "Enable the Overlays accessibility service (see the app's Navigation tab).")
            }
        }
        if (prefs.navBack) add("‹") { NavAccessibilityService.back() }
        if (prefs.navHome) add("⌂") { NavAccessibilityService.home() }
        if (prefs.navRecents) add("▢") { NavAccessibilityService.recents() }
        if (prefs.navControlCenter) add("⌄") { NavAccessibilityService.controlCenter() }
        if (prefs.navScreenshot) add("📸") { startScreenshot(); true }
        if (bar.childCount == 0) return
        clampParamsToScreen(bar, lp)
        navView = bar
        wm.addView(bar, lp)
        draggables.add(bar to lp)
    }

    // ---- status strip ----------------------------------------------------

    private fun showStrip() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(withAlpha(STRIP_BASE, prefs.overlayOpacity)) }
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        val text = TextView(this).apply { setTextColor(0xFFD7DCE4.toInt()); textSize = scaled(14f) }
        bar.addView(text)
        stripText = text
        val lp = baseParams(height = dp(36)).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or if (prefs.stripPosition == "top") Gravity.TOP else Gravity.BOTTOM
            if (prefs.stripPosition == "top") y = dp(70)
        }
        stripView = bar
        wm.addView(bar, lp)
        updateLiveText()
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
        wm.addView(container, lp)

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
        wm.addView(root, lp)
        if (prefs.alertVibrate) vibrate()
        card.scaleX = 0.85f; card.scaleY = 0.85f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
        btn.setOnClickListener { safeRemove(root) }
        root.setOnClickListener { safeRemove(root) }
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

        stripText?.let {
            val parts = mutableListOf<String>()
            if (prefs.stripShowClock) parts += timeFmt.format(now)
            if (prefs.stripShowDate) parts += dateFmt.format(now)
            if (prefs.stripShowWeather && lastWeather.isNotBlank()) parts += lastWeather
            if (prefs.stripShowBattery) parts += batteryString()
            if (prefs.stripShowNetwork) parts += networkSpeedString()
            if (prefs.stripShowNtfy) parts += if (prefs.topic.isBlank()) "no topic"
                else if (connected) "ntfy connected" else "ntfy reconnecting…"
            it.text = parts.joinToString("    •    ")
        }
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
        if (n <= 0) { hideCountdown(); capture(); return }

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
        countdownView = root
        wm.addView(root, lp)

        val ticker = object : Runnable {
            override fun run() {
                if (n <= 0) {
                    hideCountdown()
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
        if (mp == null) { capturing = false; showBanner("Screenshot failed", "Capture permission was lost — tap the button again."); return }
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val w = metrics.widthPixels; val h = metrics.heightPixels; val dpi = metrics.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("shot").also { it.start() }
        val bg = Handler(thread.looper)
        var vd: VirtualDisplay? = null

        fun cleanup() {
            try { vd?.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            thread.quitSafely()
            capturing = false
        }

        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val full = Bitmap.createBitmap(rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                val bmp = if (full.width != w) Bitmap.createBitmap(full, 0, 0, w, h) else full
                val uri = saveToGallery(bmp)
                main.post { showBanner("Screenshot saved", if (uri != null) "Saved to Pictures/Screenshots." else "Couldn't write the file.") }
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

    private fun saveToGallery(bmp: Bitmap): android.net.Uri? {
        val name = "portal_${System.currentTimeMillis()}.png"
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                uri
            } catch (_: Exception) { null }
        } else {
            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
            val screenshotsDir = java.io.File(picturesDir, "Screenshots")
            if (!screenshotsDir.exists() && !screenshotsDir.mkdirs()) return null
            val file = java.io.File(screenshotsDir, name)
            try {
                java.io.FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.net.Uri.fromFile(file)
            } catch (_: Exception) { null }
        }
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
        makeDraggable(view, view, lp, key, onTap)
        wm.addView(view, lp)
        draggables.add(view to lp)
        return view
    }

    /** Drag [moveView] by touching [touchTarget]; movement under the slop counts as a tap. */
    private fun makeDraggable(
        touchTarget: View, moveView: View, lp: WindowManager.LayoutParams, posKey: String, onTap: (() -> Unit)?
    ) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val slop = dp(8)
        touchTarget.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).roundToInt(); val dy = (e.rawY - downY).roundToInt()
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    val (x, y) = clampToScreen(moveView, startX + dx, startY + dy)
                    lp.x = x; lp.y = y
                    wm.updateViewLayout(moveView, lp); true
                }
                MotionEvent.ACTION_UP -> { if (moved) prefs.setPos(posKey, lp.x, lp.y) else onTap?.invoke(); true }
                else -> false
            }
        }
    }

    private fun baseParams(height: Int = WindowManager.LayoutParams.WRAP_CONTENT, focusable: Boolean = false): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) f = f or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT
        )
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

    private fun safeRemove(v: View) { try { if (v.isAttachedToWindow) wm.removeView(v) } catch (_: Exception) {} }
    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun withAlpha(color: Int, opacityPct: Int) = (color and 0x00FFFFFF) or ((255 * opacityPct / 100) shl 24)
    private fun scaled(size: Float) = size * prefs.textScale / 100f
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildNotification(): Notification {
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
            .setContentTitle("Overlays running")
            .setContentText("ntfy listener + on-top overlays")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: Int)

    companion object {
        const val ACTION_REFRESH = "com.portal.overlays.REFRESH"
        const val ACTION_STOP = "com.portal.overlays.STOP"
        const val ACTION_TEST_BANNER = "com.portal.overlays.TEST_BANNER"
        const val ACTION_BANNER = "com.portal.overlays.BANNER"
        const val ACTION_MEDIA_REFRESH = "com.portal.overlays.MEDIA_REFRESH"
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
