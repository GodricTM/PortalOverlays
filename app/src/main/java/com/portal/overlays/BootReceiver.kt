package com.portal.overlays

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Relaunches the overlay service after a reboot if the user had it enabled,
 * and re-asserts the accessibility service setting (Portal wipes
 * enabled_accessibility_services on every boot, so the AccessibilityServiceManager
 * has nothing to bind until the value is written again).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent?.action != "com.htc.intent.action.QUICKBOOT_POWERON") return

        try {
            val resolver = context.contentResolver
            val our = "${context.packageName}/${context.packageName}.NavAccessibilityService"
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val flattened = current.split(':').filter { it.isNotBlank() }
            if (our !in flattened) {
                val updated = (flattened + our).joinToString(":")
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } catch (_: SecurityException) {
            // WRITE_SECURE_SETTINGS is signature/privileged; adb shell has it but a regular
            // app does not. The setting still gets restored by enable_portal_permissions.ps1
            // on the next adb session, so this is a best-effort attempt.
        }

        if (Prefs(context).serviceEnabled) {
            OverlayService.send(context, OverlayService.ACTION_REFRESH)
        }
    }
}
