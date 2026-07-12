package com.portal.overlays

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.AlarmClock
import android.provider.Settings
import java.util.concurrent.ConcurrentHashMap

/** Tap targets on the live status strip and what they should do. */
object StripLaunch {
    enum class Segment(val prefKey: String, val label: String) {
        CLOCK("clock", "Clock"),
        DATE("date", "Date"),
        WEATHER("weather", "Weather"),
        BATTERY("battery", "Battery"),
        NETWORK("network", "Network speed"),
        CONTEXT("context", "Foreground app"),
        AGENDA("agenda", "Next event"),
        NTFY("ntfy", "ntfy status"),
        WEEK("week", "Week number"),
        RAIN("rain", "Rain"),
        SUN("sun", "Sunset / sunrise"),
        WIND("wind", "Wind"),
        UV("uv", "UV index"),
        ALERT("alert", "Weather alert"),
    }

    data class Preset(
        val id: String,
        val label: String,
        val component: ComponentName? = null,
        val special: Special? = null,
    )

    enum class Special {
        NONE,
        FOREGROUND_MENU,
        PINNED_MENU,
        NTFY_PREVIEW,
        WIFI_INFO,
        ALARM_CLOCK,
    }

    val PRESETS =
        listOf(
            Preset("none", "Do nothing", special = Special.NONE),
            Preset("immortal.home", "Immortal home", ComponentName("com.immortal.launcher", "com.immortal.launcher.HomeActivity")),
            Preset("immortal.settings", "Immortal settings", ComponentName("com.immortal.launcher", "com.immortal.launcher.ImmortalSettingsActivity")),
            Preset("immortal.clock", "Immortal clock", ComponentName("com.immortal.launcher", "com.immortal.launcher.ClockSettingsActivity")),
            Preset("immortal.sunrise", "Sunrise alarm", ComponentName("com.immortal.launcher", "com.immortal.launcher.SunriseSettingsActivity")),
            Preset("immortal.timers", "Timers / countdown", ComponentName("com.immortal.launcher", "com.immortal.launcher.CountdownSettingsActivity")),
            Preset("immortal.sleep", "Sleep settings", ComponentName("com.immortal.launcher", "com.immortal.launcher.SleepSettingsActivity")),
            Preset("overlays", "Portal Overlays", ComponentName("com.portal.overlays", "com.portal.overlays.MainActivity")),
            Preset("lyrics", "Portal Lyrics", ComponentName("com.portal.lyrics", "com.portal.lyrics.MainActivity")),
            Preset("menu.foreground", "Foreground app menu", special = Special.FOREGROUND_MENU),
            Preset("menu.pinned", "Pinned apps", special = Special.PINNED_MENU),
            Preset("banner.ntfy", "Last ntfy message", special = Special.NTFY_PREVIEW),
            Preset("banner.wifi", "Wi-Fi details", special = Special.WIFI_INFO),
            Preset("alarms", "Alarms", special = Special.ALARM_CLOCK),
        )

    private val presetById = PRESETS.associateBy { it.id }

    private val defaultActions =
        mapOf(
            Segment.CLOCK to "immortal.clock",
            Segment.WEATHER to "immortal.home",
            Segment.CONTEXT to "menu.foreground",
            Segment.AGENDA to "immortal.home",
            Segment.NTFY to "banner.ntfy",
        )

    fun actionFor(prefs: Prefs, segment: Segment): String =
        prefs.stripSegmentAction(segment.prefKey).ifBlank { defaultActions[segment].orEmpty().ifBlank { "none" } }

    fun presetFor(action: String): Preset? = presetById[action]

    fun labelFor(context: Context, action: String): String {
        if (action.isBlank() || action == "none") return "Do nothing"
        presetFor(action)?.let { return it.label }
        if (action.startsWith("app:")) {
            val pkg = action.removePrefix("app:")
            return appLabel(context, pkg).ifBlank { pkg }
        }
        return action
    }

    fun launch(context: Context, action: String, handlers: Handlers): Boolean {
        if (action.isBlank() || action == "none") return false
        presetFor(action)?.let { preset ->
            return when (preset.special) {
                Special.NONE -> launchComponent(context, preset.component)
                Special.FOREGROUND_MENU -> {
                    handlers.showForegroundMenu()
                    true
                }
                Special.PINNED_MENU -> {
                    handlers.showPinnedMenu()
                    true
                }
                Special.NTFY_PREVIEW -> {
                    handlers.showNtfyPreview()
                    true
                }
                Special.WIFI_INFO -> {
                    handlers.showWifiInfo()
                    true
                }
                Special.ALARM_CLOCK -> launchAlarmClock(context)
                null -> launchComponent(context, preset.component)
            }
        }
        if (action.startsWith("app:")) {
            return launchPackage(context, action.removePrefix("app:"))
        }
        return false
    }

    fun launchSegment(context: Context, prefs: Prefs, segment: Segment, handlers: Handlers): Boolean {
        var action = actionFor(prefs, segment)
        if (segment == Segment.CONTEXT &&
            action == "menu.foreground" &&
            UiContextState.currentKind() == UiContextState.WindowKind.HOME &&
            prefs.stripPinnedApps.isNotEmpty()) {
            action = "menu.pinned"
        }
        return launch(context, action, handlers)
    }

    interface Handlers {
        fun showForegroundMenu()
        fun showPinnedMenu()
        fun showNtfyPreview()
        fun showWifiInfo()
    }

    fun launchComponent(context: Context, component: ComponentName?): Boolean {
        if (component == null) return false
        val intent =
            Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun launchPackage(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            .getOrDefault(false)
    }

    private fun launchAlarmClock(context: Context): Boolean {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            return runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
        return launchComponent(context, ComponentName("com.immortal.launcher", "com.immortal.launcher.SunriseSettingsActivity"))
    }

    fun launchAppInfo(context: Context, pkg: String): Boolean =
        runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$pkg")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                true
            }
            .getOrDefault(false)

    fun installedLaunchables(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                AppEntry(pkg, label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    data class AppEntry(val packageName: String, val label: String)

    private fun appLabel(context: Context, pkg: String): String =
        runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
            }
            .getOrNull()
            .orEmpty()
}

/** Subtle strip tint sampled from the foreground app's icon. */
object AppAccent {
    private val cache = ConcurrentHashMap<String, Int>()
    private var lastPkg = ""

    fun colorFor(context: Context, packageName: String): Int? {
        if (packageName.isBlank() || packageName == context.packageName) return null
        cache[packageName]?.let { return it }
        val drawable =
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
                ?: return null
        val color = extract(drawable) ?: return null
        cache[packageName] = color
        if (cache.size > 40) cache.keys.take(10).forEach { cache.remove(it) }
        return color
    }

    fun foregroundPackage(): String {
        val pkg = UiContextState.currentPackageName()
        if (pkg.isBlank()) return lastPkg
        if (UiContextState.currentKind() == UiContextState.WindowKind.APP) lastPkg = pkg
        return if (UiContextState.currentKind() == UiContextState.WindowKind.APP) pkg else ""
    }

    fun blend(base: Int, accent: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 0.45f)
        return Color.rgb(
            (Color.red(base) * (1 - a) + Color.red(accent) * a).toInt().coerceIn(0, 255),
            (Color.green(base) * (1 - a) + Color.green(accent) * a).toInt().coerceIn(0, 255),
            (Color.blue(base) * (1 - a) + Color.blue(accent) * a).toInt().coerceIn(0, 255),
        )
    }

    private fun extract(drawable: Drawable): Int? {
        val bitmap = drawableToBitmap(drawable, 48) ?: return null
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        val step = 4
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val a = Color.alpha(c)
                if (a < 128) {
                    x += step
                    continue
                }
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max - min < 24) {
                    x += step
                    continue
                }
                rSum += r
                gSum += g
                bSum += b
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return null
        return Color.rgb(
            (rSum / count).toInt().coerceIn(0, 255),
            (gSum / count).toInt().coerceIn(0, 255),
            (bSum / count).toInt().coerceIn(0, 255),
        )
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }
}
