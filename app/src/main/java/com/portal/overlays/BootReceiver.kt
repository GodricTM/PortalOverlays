package com.portal.overlays

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relaunches the overlay service after a reboot if the user had it enabled,
 * and re-asserts the accessibility service setting (Portal wipes
 * enabled_accessibility_services on every boot, so the AccessibilityServiceManager
 * has nothing to bind until the value is written again).
 *
 * The write needs WRITE_SECURE_SETTINGS — see [PortalPermissions]. Portal can also wipe the
 * setting *after* BOOT_COMPLETED is delivered, so OverlayService retries this on its own whenever
 * it notices the service is unbound; this receiver is the first attempt, not the only one.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent?.action != "com.htc.intent.action.QUICKBOOT_POWERON") return

        val prefs = Prefs(context)
        if (PortalPermissions.restoreAccessibilityService(context)) {
            prefs.accessibilityBootRestoreFailed = false
        } else {
            prefs.accessibilityBootRestoreFailed = true
            prefs.navWarningDismissed = false
        }

        if (prefs.serviceEnabled) {
            OverlayService.send(context, OverlayService.ACTION_REFRESH)
        }
    }
}
