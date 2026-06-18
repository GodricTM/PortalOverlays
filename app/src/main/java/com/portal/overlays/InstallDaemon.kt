package com.portal.overlays

import android.content.Context
import java.io.File

/**
 * Client for the optional shell-privileged install daemon (`installd.sh`), started once over ADB
 * by `enable_portal_permissions`. The daemon runs as the shell user (uid 2000), so it can
 * `pm install` **silently** — no system installer dialog. We hand it an APK by dropping it in a
 * watched queue folder; it installs and renames `<name>.apk` → `.apk.done` / `.apk.failed`.
 *
 * When the daemon isn't running (it doesn't survive a reboot — a non-root shell helper can't),
 * callers fall back to the one-tap [android.content.pm.PackageInstaller] flow in [UpdateChecker].
 *
 * Mirrors Immortal's daemon protocol so the same `installd.sh` works for either app (point it at
 * this app's queue dir). Self-contained: no dependency on Immortal being installed.
 */
object InstallDaemon {

    /** `/sdcard/Android/data/com.portal.overlays/files/installq` — writable by us, watched by the daemon. */
    private fun queueDir(context: Context) = File(context.getExternalFilesDir(null), "installq")

    /** Heartbeat freshness window (pure, for testing). The daemon writes unix-time every ~2s. */
    internal fun heartbeatFresh(tsSeconds: Long, nowSeconds: Long): Boolean =
        (nowSeconds - tsSeconds) in 0..20

    /** True if the daemon is alive (fresh `.heartbeat`). */
    fun isAvailable(context: Context): Boolean {
        val ts = runCatching { File(queueDir(context), ".heartbeat").readText().trim().toLong() }
            .getOrDefault(0L)
        return heartbeatFresh(ts, System.currentTimeMillis() / 1000)
    }

    /**
     * Queue [apk] for the daemon and block (call from a background thread) until it reports a
     * result or [timeoutMs] elapses. Returns true on success.
     */
    fun install(context: Context, apk: File, name: String, timeoutMs: Long = 180_000): Boolean =
        install(queueDir(context), apk, name, timeoutMs)

    /** Queue protocol against an explicit dir — extracted so it's testable without a Context. */
    internal fun install(queueDir: File, apk: File, name: String, timeoutMs: Long = 180_000): Boolean {
        val d = queueDir.apply { mkdirs() }
        val target = File(d, "$name.apk")
        val done = File(d, "$name.apk.done")
        val failed = File(d, "$name.apk.failed")
        val log = File(d, "$name.apk.log")
        listOf(target, done, failed, log).forEach { runCatching { it.delete() } }

        // Write to a temp name, then atomically rename in — the daemon only ever sees a whole APK.
        val part = File(d, "$name.part")
        runCatching { apk.copyTo(part, overwrite = true) }.getOrElse { part.delete(); return false }
        if (!part.renameTo(target)) { part.delete(); return false }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (done.exists()) { listOf(done, log).forEach { runCatching { it.delete() } }; return true }
            if (failed.exists()) { listOf(failed, log).forEach { runCatching { it.delete() } }; return false }
            Thread.sleep(800)
        }
        return false
    }
}
