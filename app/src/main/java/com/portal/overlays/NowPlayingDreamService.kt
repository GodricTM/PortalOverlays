package com.portal.overlays

import android.service.dreams.DreamService

/**
 * The Portal Overlays screen saver. A floating [OverlayService] window uses TYPE_APPLICATION_OVERLAY,
 * which the system composites *under* a running screen saver/dream — so those overlays vanish the
 * moment the screen saver kicks in. The only surface that survives a dream is the dream itself, so
 * this re-hosts the now-playing card/cover + clock on the idle screen.
 *
 * All the actual content lives in [ScreensaverScene], shared with [ScreensaverPreviewActivity] so the
 * in-app Preview button shows exactly what the screen saver will.
 */
class NowPlayingDreamService : DreamService() {

    private var scene: ScreensaverScene? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true            // receive the tap that dismisses the dream
        isFullscreen = true
        val s = ScreensaverScene(this, onExit = { finish() }, showGestureHint = true)
        scene = s
        isScreenBright = s.keepBright()
        setContentView(s.createView())
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        scene?.start()
    }

    override fun onDreamingStopped() {
        scene?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scene?.stop()
        scene = null
        super.onDetachedFromWindow()
    }
}
