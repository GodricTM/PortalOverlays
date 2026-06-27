package com.portal.overlays

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The screensaver content — the now-playing card/cover, clock, battery and background — built against
 * a plain [Context] so it can be hosted by either [NowPlayingDreamService] (the real screen saver) or
 * [ScreensaverPreviewActivity] (the in-app Preview button). One code path, no drift.
 *
 * [createView] builds the view tree; [start] begins media + reactor + the clock tick; [stop] tears it
 * all down. A tap anywhere calls [onExit] (the host finishes). Reads media sessions through the same
 * notification-listener access the overlays use, so it works whether or not [OverlayService] is up.
 */
class ScreensaverScene(
    private val ctx: Context,
    private val onExit: () -> Unit
) {
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var clockTime: TextView? = null
    private var clockDate: TextView? = null
    private var battery: TextView? = null

    private var npCard: View? = null
    private var npArt: ImageView? = null
    private var npTitle: TextView? = null
    private var npArtist: TextView? = null
    private var npApp: TextView? = null
    private var npAppIcon: ImageView? = null
    private var npEq: MiniEqualizerView? = null
    private var npProgress: android.widget.ProgressBar? = null
    private var npElapsed: TextView? = null
    private var npDuration: TextView? = null
    private var npVisualizer: NowPlayingVisualizerView? = null
    private var reactor: SoundReactor? = null
    private val coverLayout get() = prefs.screensaverNowPlayingLayout == "cover"

    private val listenerComponent by lazy { ComponentName(ctx, NotifyListenerService::class.java) }
    private var mediaSessionManager: MediaSessionManager? = null
    private var listening = false
    private var activeController: MediaController? = null

    private val artworkIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dream-artwork").apply { isDaemon = true }
    }
    @Volatile private var artUriShowing: String? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            main.post { bind(controllers.orEmpty()) }
        }
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { render() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { render() }
        override fun onSessionDestroyed() { bind(emptyList()) }
    }
    private val tick = object : Runnable {
        override fun run() {
            updateClock(); updateBattery(); updateProgress()
            main.postDelayed(this, 1000)
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!listening) return
            refreshSessions()
            main.postDelayed(this, 2000)
        }
    }

    // ---- host lifecycle ---------------------------------------------------

    fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val cover = prefs.screensaverShowNowPlaying && coverLayout
        if (cover) buildNowPlayingCover(root) else buildBackground(root)
        if (prefs.screensaverShowClock || prefs.screensaverShowBattery) buildStatus(root)
        if (prefs.screensaverShowNowPlaying && !cover) buildNowPlayingCard(root)
        // Top-most transparent catcher: a tap anywhere exits. Sits above the WebView, which would
        // otherwise swallow touches (see PortalDevKit gotchas), so dismissal always works.
        root.addView(View(ctx).apply {
            isClickable = true
            setOnClickListener { onExit() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateClock(); updateBattery()
        return root
    }

    fun start() {
        if (prefs.screensaverShowNowPlaying) {
            startMedia()
            if (prefs.screensaverSoundReactive) {
                reactor = SoundReactor().also {
                    if (it.start()) { npVisualizer?.reactor = it; npEq?.reactor = it }
                }
            }
        }
        main.post(tick)
    }

    fun stop() {
        main.removeCallbacks(tick)
        main.removeCallbacks(poll)
        stopMedia()
        reactor?.stop(); reactor = null
        webView?.let { it.stopLoading(); it.destroy() }
        webView = null
    }

    /** Whether the keep-bright preference is on (the dream host applies it to its window). */
    fun keepBright() = prefs.screensaverKeepBright

    // ---- background -------------------------------------------------------

    private fun buildBackground(root: FrameLayout) {
        when (prefs.screensaverBackground) {
            "web" -> {
                val url = prefs.screensaverWebUrl
                if (url.isBlank()) return
                val wv = WebView(ctx).apply {
                    setBackgroundColor(Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    @Suppress("DEPRECATION")
                    settings.mediaPlaybackRequiresUserGesture = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    runCatching { loadUrl(url) }
                        .onFailure { Log.w(TAG, "web background failed: $it") }
                }
                webView = wv
                root.addView(wv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            "photo" -> {
                val uri = prefs.screensaverPhotoUri
                val bmp = if (uri.isNotBlank()) loadLocalBitmap(Uri.parse(uri)) else null
                if (bmp != null) {
                    root.addView(ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }
            // "black" — the root is already black.
        }
        // A soft bottom scrim keeps the clock/now-playing legible over busy photos and web pages.
        root.addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)).also {
            it.gravity = Gravity.BOTTOM
        })
    }

    // ---- clock + battery --------------------------------------------------

    private fun buildStatus(root: FrameLayout) {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (prefs.screensaverShowClock) {
            clockTime = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 92f
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), 0x99000000.toInt())
            }
            clockDate = TextView(ctx).apply {
                setTextColor(0xFFE6E9EE.toInt())
                textSize = 24f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
                setPadding(dp(4), 0, 0, 0)
            }
            col.addView(clockTime)
            col.addView(clockDate)
        }
        if (prefs.screensaverShowBattery) {
            battery = TextView(ctx).apply {
                setTextColor(0xFFC2C8D2.toInt())
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
                setPadding(dp(4), dp(8), 0, 0)
            }
            col.addView(battery)
        }
        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.setMargins(dp(56), dp(56), dp(56), 0)
        })
    }

    private fun updateClock() {
        clockTime ?: return
        val now = Date()
        val timeFmt = if (prefs.clock24h) "HH:mm" else "h:mm"
        clockTime?.text = SimpleDateFormat(timeFmt, Locale.getDefault()).format(now)
        clockDate?.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
    }

    private fun updateBattery() {
        val bt = battery ?: return
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Mains-powered Portals (e.g. the Portal+) carry no battery and report level 0 / not-present —
        // a "0%" readout there is just misleading, so hide the line unless a real battery is present.
        val present = status?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) ?: false
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val charging = (status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) == BatteryManager.BATTERY_STATUS_CHARGING
        if (!present || level < 0) { bt.visibility = View.GONE; return }
        bt.visibility = View.VISIBLE
        val pct = (level * 100 / scale)
        bt.text = (if (charging) "⚡ " else "") + "$pct%"
    }

    // ---- now-playing card -------------------------------------------------

    private fun buildNowPlayingCard(root: FrameLayout) {
        val accent = prefs.accentColor
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xCC14171D.toInt()); cornerRadius = dp(22).toFloat()
            }
            setPadding(dp(20), dp(18), dp(28), dp(18))
            visibility = View.GONE
        }
        val art = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xFF202631.toInt()); cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            setImageResource(android.R.drawable.ic_media_play)
        }
        card.addView(art, LinearLayout.LayoutParams(dp(108), dp(108)).also { it.rightMargin = dp(20) })

        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val appIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        val app = TextView(ctx).apply {
            text = "NOW PLAYING"; setTextColor(accent); textSize = 12f
            letterSpacing = 0.12f; typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        val appRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(appIcon, LinearLayout.LayoutParams(dp(20), dp(20)).also { it.rightMargin = dp(8) })
            addView(app)
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "Nothing playing"; setTextColor(Color.WHITE); textSize = 26f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val eq = MiniEqualizerView(ctx).apply { accentColor = accent }
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(eq, LinearLayout.LayoutParams(dp(26), dp(20)).also { it.leftMargin = dp(14) })
        val artist = TextView(ctx).apply {
            text = "Start media in any app"; setTextColor(0xFFC2C8D2.toInt()); textSize = 18f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        val elapsed = TextView(ctx).apply {
            text = "0:00"; setTextColor(0xFFAAB2BE.toInt()); textSize = 13f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val duration = TextView(ctx).apply {
            text = "0:00"; setTextColor(0xFFAAB2BE.toInt()); textSize = 13f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
        }
        val progressRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(elapsed)
            addView(bar, LinearLayout.LayoutParams(0, dp(5), 1f).also { it.leftMargin = dp(12); it.rightMargin = dp(12) })
            addView(duration)
        }
        info.addView(appRow)
        info.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) })
        info.addView(artist)
        info.addView(progressRow)
        card.addView(info, LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT))

        npCard = card; npArt = art; npTitle = title; npArtist = artist; npApp = app; npAppIcon = appIcon
        npEq = eq; npProgress = bar; npElapsed = elapsed; npDuration = duration

        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also {
            it.gravity = Gravity.BOTTOM or Gravity.START
            it.setMargins(dp(56), 0, dp(56), dp(56))
        })
    }

    /**
     * Fullscreen "cover": a full-bleed [NowPlayingVisualizerView] behind large album art and track
     * text. The visualizer is always present (idles to a calm frame); the art/info block fades in with
     * playback. Shares the same member refs so [render]/[updateProgress] drive it unchanged.
     */
    private fun buildNowPlayingCover(root: FrameLayout) {
        val accent = prefs.accentColor
        val vis = NowPlayingVisualizerView(ctx).apply {
            accentColor = accent
            style = prefs.screensaverVisualizerStyle
        }
        npVisualizer = vis
        root.addView(vis, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val art = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(0xFF202631.toInt()); cornerRadius = dp(28).toFloat() }
            clipToOutline = true
            elevation = dp(16).toFloat()
            setImageResource(android.R.drawable.ic_media_play)
        }
        val appIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        val app = TextView(ctx).apply {
            text = "NOW PLAYING"; setTextColor(accent); textSize = 15f
            letterSpacing = 0.14f; typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        val appRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
            addView(appIcon, LinearLayout.LayoutParams(dp(40), dp(40)).also { it.rightMargin = dp(12) })
            addView(app)
        }
        val title = TextView(ctx).apply {
            text = ""; setTextColor(Color.WHITE); textSize = 46f
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), 0x99000000.toInt())
            setPadding(0, dp(12), 0, 0)
        }
        val artist = TextView(ctx).apply {
            text = ""; setTextColor(0xFFD5DAE2.toInt()); textSize = 26f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        val elapsed = TextView(ctx).apply {
            text = "0:00"; setTextColor(0xFFC2C8D2.toInt()); textSize = 15f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val duration = TextView(ctx).apply {
            text = "0:00"; setTextColor(0xFFC2C8D2.toInt()); textSize = 15f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
        }
        val progressRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
            addView(elapsed)
            addView(bar, LinearLayout.LayoutParams(0, dp(6), 1f).also { it.leftMargin = dp(14); it.rightMargin = dp(14) })
            addView(duration)
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(appRow); addView(title); addView(artist)
            addView(progressRow, LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(art, LinearLayout.LayoutParams(dp(440), dp(440)).also { it.rightMargin = dp(56) })
            addView(info, LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        npCard = content; npArt = art; npApp = app; npAppIcon = appIcon; npTitle = title; npArtist = artist
        npProgress = bar; npElapsed = elapsed; npDuration = duration; npEq = null

        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER })
    }

    // ---- media reading ----------------------------------------------------

    private fun startMedia() {
        if (listening) { refreshSessions(); return }
        val mgr = (ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager) ?: return
        mediaSessionManager = mgr
        try {
            mgr.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
            listening = true
            refreshSessions()
            main.removeCallbacks(poll)
            main.postDelayed(poll, 2000)
        } catch (_: SecurityException) {
            Log.w(TAG, "media session access denied; now-playing card stays hidden")
        }
    }

    private fun stopMedia() {
        activeController?.unregisterCallback(mediaCallback)
        activeController = null
        if (listening) {
            try { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        }
        listening = false
    }

    private fun refreshSessions() {
        val mgr = mediaSessionManager ?: return
        try {
            bind(mgr.getActiveSessions(listenerComponent).orEmpty())
        } catch (_: Exception) {}
    }

    /**
     * Only sessions that expose a real track in a meaningful transport state count — filters out the
     * Portal's idle Alexa runtime (state=NONE, blank metadata). Mirrors OverlayService.isRealMedia.
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

    private fun bind(controllers: List<MediaController>) {
        val best = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && isRealMedia(it) }
            ?: controllers.firstOrNull { isRealMedia(it) }
        if (activeController?.sessionToken == best?.sessionToken) { render(); return }
        activeController?.unregisterCallback(mediaCallback)
        activeController = best
        best?.registerCallback(mediaCallback, main)
        render()
    }

    private fun render() {
        val card = npCard ?: return
        val c = activeController
        val md = c?.metadata
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        npEq?.playing = playing
        npVisualizer?.playing = playing
        val st = c?.playbackState?.state
        val show = md != null && (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) && c.let { isRealMedia(it) }
        if (!show) {
            if (card.visibility != View.GONE) {
                card.animate().alpha(0f).setDuration(180).withEndAction { card.visibility = View.GONE }.start()
            }
            artUriShowing = null
            return
        }
        if (card.visibility != View.VISIBLE) { card.alpha = 0f; card.visibility = View.VISIBLE; card.animate().alpha(1f).setDuration(220).start() }

        val title = md!!.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel(c.packageName)
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: appLabel(c.packageName)
        npTitle?.text = title
        npArtist?.text = artist
        npApp?.text = appLabel(c.packageName).uppercase(Locale.getDefault())
        npAppIcon?.let { iv ->
            val icon = try { ctx.packageManager.getApplicationIcon(c.packageName) } catch (_: Exception) { null }
            if (icon != null) { iv.setImageDrawable(icon); iv.visibility = View.VISIBLE } else iv.visibility = View.GONE
        }
        applyArtwork(md)
        updateProgress()
    }

    private fun updateProgress() {
        val bar = npProgress ?: return
        val c = activeController
        val state = c?.playbackState
        val duration = c?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (state == null || duration <= 0L) {
            bar.progress = 0; npElapsed?.text = "0:00"; npDuration?.text = "0:00"; return
        }
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            pos += (delta * state.playbackSpeed).toLong()
        }
        pos = pos.coerceIn(0L, duration)
        bar.progress = ((pos * 1000L) / duration).toInt()
        npElapsed?.text = formatClock(pos)
        npDuration?.text = formatClock(duration)
    }

    // ---- artwork ----------------------------------------------------------

    private fun applyArtwork(md: MediaMetadata) {
        (md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))?.let {
            npArt?.setImageBitmap(it); artUriShowing = null; return
        }
        val uriStr = artUri(md)
        if (uriStr.isNullOrBlank()) { npArt?.setImageResource(android.R.drawable.ic_media_play); return }
        if (artUriShowing == uriStr) return
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme == "http" || scheme == "https") {
            fetchRemoteArt(uriStr)
        } else {
            val bmp = loadLocalBitmap(uri)
            if (bmp != null) { npArt?.setImageBitmap(bmp); artUriShowing = uriStr }
            else npArt?.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun fetchRemoteArt(uriStr: String) {
        artworkIo.execute {
            val bmp = runCatching {
                val conn = (URL(uriStr).openConnection() as? HttpURLConnection) ?: return@runCatching null
                try {
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 4000; conn.readTimeout = 4000
                    val bytes = conn.inputStream.use { input ->
                        ByteArrayOutputStream().use { out ->
                            val buf = ByteArray(8 * 1024)
                            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                            out.toByteArray()
                        }
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } finally { runCatching { conn.disconnect() } }
            }.getOrNull()
            if (bmp != null) main.post {
                if (activeController?.metadata?.let { artUri(it) } == uriStr) {
                    npArt?.setImageBitmap(bmp); artUriShowing = uriStr
                }
            }
        }
    }

    private fun artUri(md: MediaMetadata): String? = listOf(
        md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
        md.getString(MediaMetadata.METADATA_KEY_ART_URI),
        md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
    ).firstOrNull { !it.isNullOrBlank() }

    private fun loadLocalBitmap(uri: Uri): Bitmap? = try {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    // ---- helpers ----------------------------------------------------------

    private fun appLabel(pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun formatClock(ms: Long): String {
        val s = (ms / 1000L).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "OverlaysDream"
    }
}
