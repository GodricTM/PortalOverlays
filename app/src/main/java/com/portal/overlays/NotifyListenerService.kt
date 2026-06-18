package com.portal.overlays

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Mirrors other apps' notifications (WhatsApp, Messenger, email, …) as floating banners on top of
 * whatever's on screen. Enable the listener from a computer:
 *   metavr adb shell cmd notification allow_listener \
 *     com.portal.overlays/com.portal.overlays.NotifyListenerService
 * Then turn on "Mirror app notifications" in the app's Notifications tab.
 */
class NotifyListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val prefs = Prefs(this)
        if (!prefs.serviceEnabled) return
        if (prefs.nowPlayingEnabled && sbn.packageName != packageName && sbn.isOngoing) {
            OverlayService.send(this, OverlayService.ACTION_MEDIA_REFRESH)
        }
        if (!prefs.mirrorNotifications) return
        if (sbn.packageName == packageName) return            // never mirror our own banner/service
        if (prefs.mirrorSkipOngoing && sbn.isOngoing) return  // skip music players, foreground services

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val app = appLabel(sbn.packageName)
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString(": ")
        OverlayService.sendBanner(this, app, body)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
