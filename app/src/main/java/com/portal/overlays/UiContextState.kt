package com.portal.overlays

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.util.Locale

/**
 * Shared foreground/window context tracked by the accessibility service and rendered in the
 * status strip. This is intentionally coarse: the goal is to show what kind of UI is active, not
 * to mirror the full accessibility tree.
 */
object UiContextState {
    enum class WindowKind { UNKNOWN, HOME, PORTAL_UI, APP, IME }

    @Volatile private var signature = ""
    @Volatile private var packageName = ""
    @Volatile private var className = ""
    @Volatile private var windowText = ""
    @Volatile private var kind: WindowKind = WindowKind.UNKNOWN

    fun update(event: AccessibilityEvent?, activePackage: String?): Boolean {
        event ?: return false

        val pkg = activePackage?.trim().orEmpty().ifBlank { event.packageName?.toString().orEmpty().trim() }
        val cls = event.className?.toString().orEmpty().trim()
        val text = buildString {
            event.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { append(it) }
            val body = event.text?.joinToString(" ")?.trim().orEmpty()
            if (body.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(body)
            }
        }.trim().take(160)

        val nextSignature = listOf(pkg, cls, text).joinToString("\u0000")
        if (nextSignature == signature) return false

        signature = nextSignature
        packageName = pkg
        className = cls
        windowText = text
        kind = when {
            looksLikeIme(pkg, cls, text) -> WindowKind.IME
            looksLikeSystemUi(pkg, cls, text) -> WindowKind.PORTAL_UI
            looksLikeLauncher(pkg, cls, text) -> WindowKind.HOME
            pkg.isNotBlank() -> WindowKind.APP
            else -> WindowKind.UNKNOWN
        }
        return true
    }

    /** Package name of the foreground app, or blank when unknown / launcher shell. */
    fun currentPackageName(): String = packageName

    fun displayLabel(context: Context): String {
        val pkg = packageName
        val cls = className
        val text = windowText
        if (pkg.isBlank() && cls.isBlank() && text.isBlank()) return "Unknown"

        if (kind == WindowKind.PORTAL_UI) return "Portal UI"
        if (kind == WindowKind.HOME) return "Home"
        if (pkg == context.packageName) return appLabel(context, pkg)

        return appLabel(context, pkg).ifBlank { pkg.ifBlank { "App" } }
    }

    fun currentKind(): WindowKind = kind

    private fun looksLikeLauncher(pkg: String, cls: String, text: String): Boolean {
        val p = pkg.lowercase(Locale.US)
        val c = cls.lowercase(Locale.US)
        val t = text.lowercase(Locale.US)
        return p == "com.immortal.launcher" ||
            p == "com.facebook.alohaapps.launcher" ||
            c.contains("launcher") ||
            t.contains("home screen") ||
            t == "home"
    }

    private fun looksLikeSystemUi(pkg: String, cls: String, text: String): Boolean {
        val p = pkg.lowercase(Locale.US)
        val c = cls.lowercase(Locale.US)
        val t = text.lowercase(Locale.US)
        return p.contains("systemui") ||
            c.contains("statusbar") ||
            c.contains("quicksettings") ||
            c.contains("notificationshade") ||
            c.contains("controlcenter") ||
            c.contains("panel") ||
            t.contains("quick settings") ||
            t.contains("notification shade") ||
            t.contains("control center") ||
            t.contains("status bar")
    }

    private fun looksLikeIme(pkg: String, cls: String, text: String): Boolean {
        val p = pkg.lowercase(Locale.US)
        val c = cls.lowercase(Locale.US)
        val t = text.lowercase(Locale.US)
        return p.contains("inputmethod") ||
            p.contains("ime") ||
            c.contains("inputmethod") ||
            c.contains("keyboard") ||
            t.contains("keyboard") ||
            t.contains("typing")
    }

    private fun appLabel(context: Context, pkg: String): String = try {
        if (pkg.isBlank()) "" else {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
        }
    } catch (_: Exception) {
        pkg
    }
}
