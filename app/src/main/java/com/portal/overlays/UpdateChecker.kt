package com.portal.overlays

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** A newer build advertised by the hosted manifest. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

sealed class UpdateResult {
    data class UpToDate(val installedVersionName: String, val remoteVersionName: String) : UpdateResult()
    data class Available(val info: UpdateInfo, val installedVersionName: String) : UpdateResult()
    data class Failed(val message: String) : UpdateResult()
}

/**
 * Over-the-air update check. Mirrors the approach used by Immortal: a small
 * `version.json` is hosted on GitHub, this fetches it (cache-busted), compares
 * `versionCode` (long) to what's installed, and surfaces the result via either
 * a system notification (silent auto-check on launch) or an in-app dialog
 * (manual "Check for updates" button).
 */
object UpdateChecker {

    @Volatile
    var manifestUrl: String =
        "https://raw.githubusercontent.com/GodricTM/PortalOverlays/main/version.json"

    private const val NOTIF_CHANNEL_ID = "updates"
    private const val NOTIF_ID = 1001

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun checkForUpdate(context: Context, onResult: (UpdateResult) -> Unit) {
        io.execute {
            val installedName = installedVersionName(context)
            val result = runCatching {
                val info = parseManifest(httpGet(cacheBust(manifestUrl, System.currentTimeMillis())))
                Log.i(
                    "PortalOverlaysUpdate",
                    "remote=${info.versionCode} installed=${installedVersionCode(context)}"
                )
                if (shouldUpdate(info.versionCode, installedVersionCode(context)))
                    UpdateResult.Available(info, installedName)
                else
                    UpdateResult.UpToDate(installedName, info.versionName)
            }.getOrElse { UpdateResult.Failed(it.message ?: it.javaClass.simpleName) }
            main.post { onResult(result) }
        }
    }

    /** Silent check on app launch — posts a system notification if newer. */
    fun autoCheck(context: Context) {
        checkForUpdate(context) { result ->
            if (result is UpdateResult.Available) notify(context, result.info)
        }
    }

    /**
     * Download the advertised APK and install it — fully in-app. Uses the silent [InstallDaemon] when
     * it's running, otherwise the one-tap [PackageInstaller] flow (the system "Install" confirmation,
     * handled by [UpdateInstallReceiver]). [status] is invoked on the main thread with progress text.
     */
    fun installUpdate(context: Context, info: UpdateInfo, status: (String) -> Unit) {
        status("Downloading update…")
        io.execute {
            try {
                val apk = File(context.cacheDir, "overlays-update.apk")
                download(info.apkUrl, apk)
                if (InstallDaemon.isAvailable(context)) {
                    main.post { status("Installing silently…") }
                    val ok = InstallDaemon.install(context, apk, "overlays-update")
                    if (ok) { main.post { status("Updated — reopening shortly") }; return@execute }
                    // Daemon was up but the install failed — fall through to the system installer.
                    main.post { status("Opening installer…") }
                    commit(context, apk)
                } else {
                    main.post { status("Opening installer — tap Install") }
                    commit(context, apk)
                }
            } catch (t: Throwable) {
                main.post { status("Update failed: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    /** One-tap self-update via PackageInstaller; result/PENDING_USER_ACTION go to [UpdateInstallReceiver]. */
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, UpdateInstallReceiver::class.java), flags
            )
            session.commit(pending.intentSender)
        }
    }

    private fun download(spec: String, dest: File) {
        open(spec).inputStream.use { input ->
            java.io.FileOutputStream(dest).use { input.copyTo(it) }
        }
    }

    /** Entry point used by the About tab "Check for updates" button. */
    fun check(context: Context) {
        checkForUpdate(context) { result ->
            when (result) {
                is UpdateResult.UpToDate -> showUpToDateDialog(context, result.installedVersionName, result.remoteVersionName)
                is UpdateResult.Available -> showUpdateDialog(context, result.info, result.installedVersionName)
                is UpdateResult.Failed -> showErrorDialog(context, result.message)
            }
        }
    }

    /** Post a heads-up-style notification pointing at the newer build. */
    private fun notify(context: Context, info: UpdateInfo) {
        ensureChannel(context)
        // Open the app's About tab so the user can update in-app, rather than a browser.
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Portal Overlays v${info.versionName} available")
            .setContentText("Tap to update")
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes.ifBlank { "Tap to open Portal Overlays and update." }))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            val ch = NotificationChannel(NOTIF_CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New Portal Overlays releases"
            }
            nm.createNotificationChannel(ch)
        }
    }

    /** Parse a `version.json` manifest (extracted as a pure function for testing). */
    internal fun parseManifest(json: String): UpdateInfo {
        val j = JSONObject(json)
        return UpdateInfo(
            versionCode = j.getLong("versionCode"),
            versionName = j.optString("versionName"),
            apkUrl = j.getString("apkUrl"),
            notes = j.optString("notes")
        )
    }

    /** Whether the remote build is newer than what's installed. */
    internal fun shouldUpdate(remoteVersionCode: Long, installedVersionCode: Long): Boolean =
        remoteVersionCode > installedVersionCode

    /** Append a cache-busting query param, preserving any existing query string. */
    internal fun cacheBust(base: String, t: Long): String =
        base + (if (base.contains("?")) "&" else "?") + "t=" + t

    private fun installedVersionCode(context: Context): Long = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    private fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun httpGet(spec: String): String = open(spec).inputStream.use {
        it.readBytes().toString(Charsets.UTF_8)
    }

    private fun open(spec: String): HttpURLConnection {
        val c = URL(spec).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "PortalOverlays/1.0")
        return c
    }

    private fun showUpdateDialog(context: Context, info: UpdateInfo, current: String) {
        val notes = if (info.notes.length > 1200) info.notes.substring(0, 1200) + "\n…" else info.notes
        AlertDialog.Builder(context)
            .setTitle("Update available: v${info.versionName}")
            .setMessage("Installed: v$current\nLatest:    v${info.versionName}\n\n$notes")
            .setNegativeButton("Later", null)
            .setPositiveButton("Open on GitHub") { _, _ ->
                runCatching {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }
            }
            .show()
    }

    private fun showUpToDateDialog(context: Context, current: String, remote: String) {
        AlertDialog.Builder(context)
            .setTitle("You're up to date")
            .setMessage("Installed: v$current\nLatest:    v$remote")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showErrorDialog(context: Context, message: String) {
        AlertDialog.Builder(context)
            .setTitle("Update check failed")
            .setMessage("Could not reach the update server:\n\n$message")
            .setPositiveButton("OK", null)
            .show()
    }
}
