package com.portal.overlays

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Receives the result of a [PackageInstaller] self-update commit (the one-tap fallback used when
 * the silent [InstallDaemon] isn't running). On STATUS_PENDING_USER_ACTION it launches the system
 * "Install" confirmation; success/failure are surfaced as a toast.
 *
 * Invoked via an explicit PendingIntent from [UpdateChecker], so no intent-filter is needed.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "Portal Overlays updated", Toast.LENGTH_LONG).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "Update failed: ${msg ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
