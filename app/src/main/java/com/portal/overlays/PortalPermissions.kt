package com.portal.overlays

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Portal clears `enabled_accessibility_services` on every boot, which unbinds the nav service and
 * kills Back / Home / Recents until someone re-runs enable_portal_permissions.ps1 from a PC.
 *
 * WRITE_SECURE_SETTINGS is `signature|privileged|development`, and the `development` flag means a
 * sideloaded build can hold it after a single one-time grant over adb:
 *
 *     adb shell pm grant com.portal.overlays android.permission.WRITE_SECURE_SETTINGS
 *
 * That grant survives reboots, so once it is in place the app repairs itself and the PC is only
 * ever needed once. Without it every write throws SecurityException and we fall back to the
 * on-screen warning.
 */
object PortalPermissions {

    /** The flattened component name the AccessibilityServiceManager expects. */
    fun accessibilityComponent(context: Context): String =
        "${context.packageName}/${context.packageName}.NavAccessibilityService"

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Re-adds our accessibility service to the secure setting, preserving every other service
     * already listed — Portal's own Aloha and Immortal services live in the same colon-separated
     * value, and clobbering them breaks the system UI.
     *
     * Returns true if the setting now lists us (including when it already did), false if the write
     * was refused. Never throws.
     */
    fun restoreAccessibilityService(context: Context): Boolean {
        val our = accessibilityComponent(context)
        return try {
            val resolver = context.contentResolver
            val current = Settings.Secure
                .getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
            val listed = current.split(':').filter { it.isNotBlank() }
            if (our !in listed) {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (listed + our).joinToString(":")
                )
            }
            // Portal can leave the master toggle off even when the list survives.
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
