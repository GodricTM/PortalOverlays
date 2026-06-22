package com.portal.overlays

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Invisible activity that asks for the one-time MediaProjection consent ("Start capturing?") and
 * hands the result token to [OverlayService], which keeps it alive so later screenshots don't
 * prompt again. A normal app can't run `screencap`; MediaProjection is the sanctioned route and
 * needs no Google services.
 */
class ScreenshotActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_PROJECTION_RESULT)
                    .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                startService(i)
            } else {
                OverlayService.sendBanner(
                    this,
                    "Screenshot canceled",
                    "Screen-capture permission was not granted."
                )
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object { private const val REQ = 7001 }
}
