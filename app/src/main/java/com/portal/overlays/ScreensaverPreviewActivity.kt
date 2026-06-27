package com.portal.overlays

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * Full-screen in-app preview of the screen saver — launched by the **Preview** button on the
 * Screensaver tab. Hosts the same [ScreensaverScene] the real [NowPlayingDreamService] does, so what
 * you see here is exactly what the idle screen will show. A tap (the scene's catcher) or Back exits.
 */
class ScreensaverPreviewActivity : Activity() {

    private var scene: ScreensaverScene? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val s = ScreensaverScene(this) { finish() }
        scene = s
        setContentView(s.createView())
    }

    override fun onResume() {
        super.onResume()
        scene?.start()
    }

    override fun onPause() {
        scene?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        scene?.stop()
        scene = null
        super.onDestroy()
    }
}
