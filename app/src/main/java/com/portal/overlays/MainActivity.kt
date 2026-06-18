package com.portal.overlays

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

// ---- control-deck palette -------------------------------------------------
private val BG = Color(0xFF0C0E12)
private val PANEL = Color(0xFF15181E)
private val PANEL2 = Color(0xFF1B1F27)
private val LINE = Color(0xFF262B34)
private val TEXT = Color(0xFFF2F4F7)
private val MUTED = Color(0xFF9AA1AD)
private val FAINT = Color(0xFF6B7280)
private val OK = Color(0xFF34C759)
private val OFF = Color(0xFF5A6172)

private fun notifAccessEnabled(context: android.content.Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.split(":").any { it.startsWith(context.packageName + "/") }
}

private enum class Tab(val glyph: String, val label: String) {
    WIDGETS("◵", "Widgets"), NOTIFY("✶", "Notifications"),
    NAV("‹›", "Navigation"), LOOK("◐", "Appearance"), ABOUT("ⓘ", "About")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Deck() }
    }
}

@Composable
private fun Deck() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var tab by remember { mutableStateOf(Tab.WIDGETS) }
    var running by remember { mutableStateOf(prefs.serviceEnabled) }
    var accent by remember { mutableStateOf(Color(prefs.accentColor)) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accEnabled by remember { mutableStateOf(NavAccessibilityService.isEnabled) }
    var notifAccess by remember { mutableStateOf(notifAccessEnabled(context)) }
    var updateResult by remember { mutableStateOf<UpdateResult?>(null) }
    // Show the first-run walkthrough until it's been dismissed once — and always re-surface it if the
    // crash-critical overlay permission isn't granted (e.g. the user never ran the .bat/PowerShell helper).
    var showOnboarding by remember { mutableStateOf(!prefs.onboardingDone || !Settings.canDrawOverlays(context)) }

    // Silent background check on launch — surfaces a system notification if newer
    LaunchedEffect(Unit) { UpdateChecker.autoCheck(context) }

    // Re-read permission state every time we return to the foreground — typically right after the user
    // grants something on a system settings page and presses Back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canOverlay = Settings.canDrawOverlays(context)
                accEnabled = NavAccessibilityService.isEnabled
                notifAccess = notifAccessEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun refresh() { if (running) OverlayService.send(context, OverlayService.ACTION_REFRESH) }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().background(BG)) {
        Rail(tab, accent) { tab = it }
        Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 36.dp, top = 24.dp, bottom = 24.dp)) {
            StatusHeader(
                running = running, accent = accent,
                canOverlay = canOverlay, accEnabled = accEnabled, hasTopic = prefs.topic.isNotBlank(),
                notifAccess = notifAccess,
                onRunning = { on ->
                    running = on; prefs.serviceEnabled = on
                    OverlayService.send(context, if (on) OverlayService.ACTION_REFRESH else OverlayService.ACTION_STOP)
                },
                onRecheck = {
                    canOverlay = Settings.canDrawOverlays(context)
                    accEnabled = NavAccessibilityService.isEnabled
                    notifAccess = notifAccessEnabled(context)
                }
            )
            Spacer(Modifier.height(14.dp))
            AnimatedBanner(accent)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (tab) {
                    Tab.WIDGETS -> WidgetsTab(prefs, accent, ::refresh)
                    Tab.NOTIFY -> NotifyTab(context, prefs, accent, ::refresh)
                    Tab.NAV -> NavTab(context, prefs, accent, accEnabled, ::refresh)
                    Tab.LOOK -> LookTab(prefs, accent, { accent = it }, ::refresh)
                    Tab.ABOUT -> AboutTab(accent) {
                        updateResult = null
                        UpdateChecker.checkForUpdate(context) { updateResult = it }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

        if (showOnboarding) {
            OnboardingOverlay(
                accent = accent,
                canOverlay = canOverlay, accEnabled = accEnabled, notifAccess = notifAccess,
                onDone = {
                    prefs.onboardingDone = true
                    showOnboarding = false
                    // If overlays are switched on, refresh now that permission may have just been granted.
                    if (running) OverlayService.send(context, OverlayService.ACTION_REFRESH)
                }
            )
        }
    }

    updateResult?.let { result ->
        UpdateResultDialog(result = result, accent = accent, onDismiss = { updateResult = null })
    }
}

// ---- first-run permission walkthrough ------------------------------------

private fun openOverlaySettings(context: android.content.Context) = runCatching {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}.onFailure {
    // Some Portal builds gate the per-app overlay page; fall back to the global list.
    runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openNotificationListenerSettings(context: android.content.Context) = runCatching {
    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun OnboardingOverlay(
    accent: Color, canOverlay: Boolean, accEnabled: Boolean, notifAccess: Boolean, onDone: () -> Unit
) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(Color(0xF2070809))
            // Consume taps on the scrim so they don't reach the control deck behind it.
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.62f).clip(RoundedCornerShape(22.dp)).background(PANEL).padding(34.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Finish setup", color = TEXT, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "Overlays needs a few permissions to draw on top of other apps. These are normally granted " +
                "by enable_portal_permissions.bat from a PC — if you didn't run it, enable them here.",
                color = MUTED, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp)
            )

            PermStep(
                index = "1", title = "Draw over other apps", required = true, granted = canOverlay, accent = accent,
                detail = "Required. Without this the overlays can't appear and the service stays idle.",
                onEnable = { openOverlaySettings(context) }
            )
            Spacer(Modifier.height(12.dp))
            PermStep(
                index = "2", title = "Accessibility service", required = false, granted = accEnabled, accent = accent,
                detail = "Powers the floating Back / Home / Recents navigation cluster. Portal only lets this be " +
                    "granted from a PC — run enable_portal_permissions, or use the adb command on the Navigation tab.",
                adbOnly = true,
                onEnable = {}
            )
            Spacer(Modifier.height(12.dp))
            PermStep(
                index = "3", title = "Notification access", required = false, granted = notifAccess, accent = accent,
                detail = "Lets Overlays mirror other apps' notifications and show Now Playing.",
                onEnable = { openNotificationListenerSettings(context) }
            )

            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canOverlay) accent else PANEL2,
                    contentColor = if (canOverlay) Color.White else MUTED
                )
            ) {
                Text(if (canOverlay) "Done" else "Skip for now", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!canOverlay) Text(
                "You can grant \"Draw over other apps\" later from the About tab — overlays stay off until then.",
                color = FAINT, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun PermStep(
    index: String, title: String, required: Boolean, granted: Boolean, accent: Color,
    detail: String, onEnable: () -> Unit, adbOnly: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PANEL2).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (granted) OK else accent.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (granted) "✓" else index, color = if (granted) Color.White else accent,
                fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TEXT, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (required) {
                    Spacer(Modifier.width(8.dp))
                    Text("REQUIRED", color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(detail, color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(14.dp))
        when {
            granted -> Text("Enabled", color = OK, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            adbOnly -> Text("PC only", color = FAINT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            else -> Button(
                onClick = onEnable, modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
            ) { Text("Enable", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun UpdateResultDialog(result: UpdateResult, accent: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    when (result) {
        is UpdateResult.Available -> {
            val notes = if (result.info.notes.length > 600) result.info.notes.substring(0, 600) + "\n…" else result.info.notes
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update available: v${result.info.versionName}", color = TEXT) },
                text = {
                    Text(
                        "Installed: v${result.installedVersionName}\nLatest:    v${result.info.versionName}\n\n$notes",
                        color = MUTED
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        onDismiss()
                        runCatching {
                            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(result.info.apkUrl))
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                        }
                    }) { Text("Open on GitHub", color = accent) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Later", color = MUTED) }
                },
                containerColor = PANEL
            )
        }
        is UpdateResult.UpToDate -> {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("You're up to date", color = TEXT) },
                text = { Text("Installed: v${result.installedVersionName}\nLatest:    v${result.remoteVersionName}", color = MUTED) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK", color = accent) }
                },
                containerColor = PANEL
            )
        }
        is UpdateResult.Failed -> {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update check failed", color = TEXT) },
                text = { Text("Could not reach the update server:\n\n${result.message}", color = MUTED) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK", color = accent) }
                },
                containerColor = PANEL
            )
        }
    }
}

// ---- rail -----------------------------------------------------------------

@Composable
private fun Rail(selected: Tab, accent: Color, onSelect: (Tab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(232.dp).background(Color(0xFF0A0C0F)).padding(vertical = 26.dp)
    ) {
        Row(Modifier.padding(start = 22.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(10.dp))
            Text("OVERLAYS", color = TEXT, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Tab.values().forEach { t ->
            val active = t == selected
            Row(
                Modifier.fillMaxWidth().height(58.dp)
                    .background(if (active) PANEL else Color.Transparent)
                    .clickable { onSelect(t) }
                    .padding(start = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(3.dp).height(26.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (active) accent else Color.Transparent))
                Spacer(Modifier.width(14.dp))
                Text(t.glyph, color = if (active) accent else MUTED, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(13.dp))
                Text(t.label, color = if (active) TEXT else MUTED, fontSize = 19.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ---- status header (signature) -------------------------------------------

@Composable
private fun StatusHeader(
    running: Boolean, accent: Color, canOverlay: Boolean, accEnabled: Boolean, hasTopic: Boolean,
    notifAccess: Boolean, onRunning: (Boolean) -> Unit, onRecheck: () -> Unit
) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PANEL).padding(24.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Portal Overlays", color = TEXT, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (running) "On-top overlays are live" else "Overlays are stopped",
                        color = if (running) accent else MUTED, fontSize = 15.sp, fontFamily = FontFamily.Monospace
                    )
                }
                Text(if (running) "RUNNING" else "OFF", color = if (running) accent else FAINT,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 14.dp))
                Switch(checked = running, onCheckedChange = onRunning, colors = switchColors(accent))
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Led("DRAW ON TOP", canOverlay)
                Spacer(Modifier.width(24.dp))
                Led("ACCESSIBILITY", accEnabled)
                Spacer(Modifier.width(24.dp))
                Led("NOTIF ACCESS", notifAccess)
                Spacer(Modifier.width(24.dp))
                Led("NTFY TOPIC", hasTopic)
                Spacer(Modifier.weight(1f))
                Text("re-check", color = MUTED, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRecheck() }
                        .background(PANEL2).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

// ---- animated hero banner (drifting waves + stacked-layers motif) --------

@Composable
private fun AnimatedBanner(accent: Color) {
    val t = rememberInfiniteTransition(label = "banner")
    val phase by t.animateFloat(
        0f, (2f * PI).toFloat(),
        infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "phase"
    )
    val bob by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "bob"
    )
    Box(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(18.dp)).background(PANEL)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent, accent.copy(alpha = 0.14f))))
            // three drifting sine waves
            for (i in 0..2) {
                val amp = h * 0.09f * (1f + i * 0.4f)
                val yBase = h * (0.56f + i * 0.05f)
                val path = Path().apply {
                    moveTo(0f, yBase)
                    var x = 0f
                    while (x <= w) {
                        val y = yBase + amp * sin(((x / w) * (2f + i) * 2f * PI.toFloat() + phase + i).toDouble()).toFloat()
                        lineTo(x, y); x += 6f
                    }
                }
                drawPath(path, color = accent.copy(alpha = 0.42f - i * 0.11f), style = Stroke(width = (3f - i).dp.toPx()))
            }
            // stacked-layers motif drifting on the right (echoes the app icon)
            val cx = w * 0.84f
            for (i in 0..2) {
                val lw = w * 0.11f; val lh = h * 0.15f
                val yy = h * 0.30f + i * lh * 1.5f + (1f - i) * bob * 5.dp.toPx()
                drawRoundRect(
                    color = accent.copy(alpha = 0.32f - i * 0.08f),
                    topLeft = Offset(cx - lw / 2f, yy), size = Size(lw, lh),
                    cornerRadius = CornerRadius(8f, 8f)
                )
            }
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 26.dp)) {
            Text("LIVE HUD", color = accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("Overlays on top of everything", color = TEXT, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Led(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) OK else OFF))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (on) TEXT else FAINT, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

// ---- tabs -----------------------------------------------------------------

@Composable
private fun WidgetsTab(prefs: Prefs, accent: Color, refresh: () -> Unit) {
    Section("🕐  Clock", "Time, optional seconds and date.") {
        var on by remember { mutableStateOf(prefs.clockEnabled) }
        Toggle("Show clock", on, accent) { on = it; prefs.clockEnabled = it; refresh() }
        if (on) {
            var h24 by remember { mutableStateOf(prefs.clock24h) }
            var secs by remember { mutableStateOf(prefs.clockSeconds) }
            var date by remember { mutableStateOf(prefs.clockShowDate) }
            Toggle("24-hour time", h24, accent) { h24 = it; prefs.clock24h = it; refresh() }
            Toggle("Show seconds", secs, accent) { secs = it; prefs.clockSeconds = it; refresh() }
            Toggle("Show date", date, accent) { date = it; prefs.clockShowDate = it; refresh() }
        }
    }
    Section("🌤️  Weather", "Live conditions from Open-Meteo (no API key).") {
        var on by remember { mutableStateOf(prefs.weatherEnabled) }
        var city by remember { mutableStateOf(prefs.weatherCity) }
        var f by remember { mutableStateOf(prefs.weatherFahrenheit) }
        Toggle("Show weather", on, accent) { on = it; prefs.weatherEnabled = it; refresh() }
        if (on) {
            Field("City", city, "e.g. Bucharest") { city = it; prefs.weatherCity = it; refresh() }
            Toggle("Use Fahrenheit", f, accent) { f = it; prefs.weatherFahrenheit = it; refresh() }
        }
    }
    Section("🔋  Battery", "Charge level and charging state.") {
        var on by remember { mutableStateOf(prefs.batteryEnabled) }
        Toggle("Show battery", on, accent) { on = it; prefs.batteryEnabled = it; refresh() }
    }
    Section("Now playing", "Album art, track details and transport controls from active media sessions.") {
        val context = LocalContext.current
        var on by remember { mutableStateOf(prefs.nowPlayingEnabled) }
        if (!notifAccessEnabled(context)) Code(
            "metavr adb shell cmd notification allow_listener com.portal.overlays/com.portal.overlays.NotifyListenerService"
        )
        Toggle("Show now playing", on, accent) { on = it; prefs.nowPlayingEnabled = it; refresh() }
    }
    Section("📝  Sticky note", "A pinned note that floats on top.") {
        var on by remember { mutableStateOf(prefs.noteEnabled) }
        var txt by remember { mutableStateOf(prefs.noteText) }
        Toggle("Show note", on, accent) { on = it; prefs.noteEnabled = it; refresh() }
        if (on) Field("Note text", txt, "Type your note") { txt = it; prefs.noteText = it; refresh() }
    }
    Hint("Drag any widget to reposition it. Positions are remembered.")
}

@Composable
private fun NotifyTab(context: android.content.Context, prefs: Prefs, accent: Color, refresh: () -> Unit) {
    Section("🔔  ntfy.sh topic", "Every message published to this topic pops a banner on top of any app.") {
        var topic by remember { mutableStateOf(prefs.topic) }
        Field("Topic", topic, "e.g. portal-denis-7f3a") { topic = it; prefs.topic = it; refresh() }
        Spacer(Modifier.height(6.dp))
        Code("curl -d \"Kitchen timer done\" ntfy.sh/${topic.ifBlank { "your-topic" }}")
    }
    Section("💬  Banners", "How incoming messages appear.") {
        var secs by remember { mutableStateOf(prefs.bannerSeconds.toFloat()) }
        var bottom by remember { mutableStateOf(prefs.bannerPosition == "bottom") }
        Segmented(listOf("Top", "Bottom"), if (bottom) 1 else 0, accent) {
            bottom = it == 1; prefs.bannerPosition = if (bottom) "bottom" else "top"; refresh()
        }
        SliderRow("Auto-dismiss", "${secs.toInt()}s", secs, 2f..30f, accent) {
            secs = it; prefs.bannerSeconds = it.toInt(); refresh()
        }
        Primary("Test banner", accent) { OverlayService.send(context, OverlayService.ACTION_TEST_BANNER) }
    }
    Section("📲  Mirror app notifications", "Show other apps' notifications (WhatsApp, Messenger, email…) as banners.") {
        var on by remember { mutableStateOf(prefs.mirrorNotifications) }
        var skip by remember { mutableStateOf(prefs.mirrorSkipOngoing) }
        if (!notifAccessEnabled(context)) Code(
            "metavr adb shell cmd notification allow_listener com.portal.overlays/com.portal.overlays.NotifyListenerService"
        )
        Toggle("Mirror notifications", on, accent) { on = it; prefs.mirrorNotifications = it }
        if (on) Toggle("Skip ongoing (music, etc.)", skip, accent) { skip = it; prefs.mirrorSkipOngoing = it }
    }
    Section("🚨  Alert popups", "Full-attention overlays with a Dismiss button.") {
        var vib by remember { mutableStateOf(prefs.alertVibrate) }
        Toggle("Vibrate on alert", vib, accent) { vib = it; prefs.alertVibrate = it }
        Row {
            Ghost("🔔 Doorbell", Modifier.weight(1f)) { OverlayService.send(context, OverlayService.ACTION_ALERT, OverlayService.KIND_DOORBELL) }
            Spacer(Modifier.width(10.dp))
            Ghost("⏰ Timer", Modifier.weight(1f)) { OverlayService.send(context, OverlayService.ACTION_ALERT, OverlayService.KIND_TIMER) }
            Spacer(Modifier.width(10.dp))
            Ghost("📌 Reminder", Modifier.weight(1f)) { OverlayService.send(context, OverlayService.ACTION_ALERT, OverlayService.KIND_REMINDER) }
        }
    }
    Section("📊  Status strip", "A thin live-info bar along one edge.") {
        var on by remember { mutableStateOf(prefs.stripEnabled) }
        var top by remember { mutableStateOf(prefs.stripPosition == "top") }
        Toggle("Show strip", on, accent) { on = it; prefs.stripEnabled = it; refresh() }
        if (on) {
            Segmented(listOf("Bottom", "Top"), if (top) 1 else 0, accent) {
                top = it == 1; prefs.stripPosition = if (top) "top" else "bottom"; refresh()
            }
            var c by remember { mutableStateOf(prefs.stripShowClock) }
            var d by remember { mutableStateOf(prefs.stripShowDate) }
            var w by remember { mutableStateOf(prefs.stripShowWeather) }
            var b by remember { mutableStateOf(prefs.stripShowBattery) }
            var net by remember { mutableStateOf(prefs.stripShowNetwork) }
            var n by remember { mutableStateOf(prefs.stripShowNtfy) }
            Toggle("Clock", c, accent) { c = it; prefs.stripShowClock = it; refresh() }
            Toggle("Date", d, accent) { d = it; prefs.stripShowDate = it; refresh() }
            Toggle("Weather", w, accent) { w = it; prefs.stripShowWeather = it; refresh() }
            Toggle("Battery", b, accent) { b = it; prefs.stripShowBattery = it; refresh() }
            Toggle("Network speed", net, accent) { net = it; prefs.stripShowNetwork = it; refresh() }
            Toggle("ntfy status", n, accent) { n = it; prefs.stripShowNtfy = it; refresh() }
        }
    }
}

@Composable
private fun NavTab(context: android.content.Context, prefs: Prefs, accent: Color, accEnabled: Boolean, refresh: () -> Unit) {
    if (!accEnabled) Hint(
        "Navigation buttons need the accessibility service. Enable it from a computer:\n" +
        "metavr adb shell settings put secure enabled_accessibility_services com.portal.overlays/com.portal.overlays.NavAccessibilityService\n" +
        "metavr adb shell settings put secure accessibility_enabled 1"
    )
    Section("🧭  Floating navigation", "Draggable buttons that act on any app.") {
        var on by remember { mutableStateOf(prefs.navEnabled) }
        var back by remember { mutableStateOf(prefs.navBack) }
        var home by remember { mutableStateOf(prefs.navHome) }
        var rec by remember { mutableStateOf(prefs.navRecents) }
        var cc by remember { mutableStateOf(prefs.navControlCenter) }
        var shot by remember { mutableStateOf(prefs.navScreenshot) }
        var vert by remember { mutableStateOf(prefs.navVertical) }
        Toggle("Show navigation cluster", on, accent) { on = it; prefs.navEnabled = it; refresh() }
        if (on) {
            Toggle("Back  ‹", back, accent) { back = it; prefs.navBack = it; refresh() }
            Toggle("Home  ⌂", home, accent) { home = it; prefs.navHome = it; refresh() }
            Toggle("Recents  ▢", rec, accent) { rec = it; prefs.navRecents = it; refresh() }
            Toggle("Control Center  ⌄", cc, accent) { cc = it; prefs.navControlCenter = it; refresh() }
            Toggle("Screenshot  📸", shot, accent) { shot = it; prefs.navScreenshot = it; refresh() }
            Segmented(listOf("Horizontal", "Vertical"), if (vert) 1 else 0, accent) {
                vert = it == 1; prefs.navVertical = it == 1; refresh()
            }
        }
    }
    Section("📸  Screenshot", "Tap the 📸 button to capture the screen after a countdown.") {
        var delay by remember { mutableStateOf(prefs.screenshotDelay.toFloat()) }
        SliderRow("Countdown", if (delay.toInt() == 0) "instant" else "${delay.toInt()}s", delay, 0f..10f, accent) {
            delay = it; prefs.screenshotDelay = it.toInt()
        }
        Text("Saved to Pictures/Screenshots. The first capture asks for one-time permission.",
            color = MUTED, fontSize = 13.sp)
    }
    Hint("Control Center pulls down Portal's built-in panel via an accessibility swipe — the system panel itself can't be restyled by a sideloaded app.")
}

@Composable
private fun LookTab(prefs: Prefs, accent: Color, onAccent: (Color) -> Unit, refresh: () -> Unit) {
    Section("🎨  Accent colour", "Used across banners, navigation and this panel.") {
        Row {
            Prefs.ACCENT_PRESETS.forEach { c ->
                val col = Color(c)
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(col)
                        .border(3.dp, if (col == accent) TEXT else Color.Transparent, CircleShape)
                        .clickable { prefs.accentColor = c; onAccent(col); refresh() }
                )
                Spacer(Modifier.width(14.dp))
            }
        }
    }
    Section("🪟  Surface", "Opacity and shape of overlay cards.") {
        var op by remember { mutableStateOf(prefs.overlayOpacity.toFloat()) }
        var rad by remember { mutableStateOf(prefs.cornerRadius.toFloat()) }
        var scale by remember { mutableStateOf(prefs.textScale.toFloat()) }
        SliderRow("Opacity", "${op.toInt()}%", op, 30f..100f, accent) { op = it; prefs.overlayOpacity = it.toInt(); refresh() }
        SliderRow("Corner radius", "${rad.toInt()}dp", rad, 0f..40f, accent) { rad = it; prefs.cornerRadius = it.toInt(); refresh() }
        SliderRow("Text size", "${scale.toInt()}%", scale, 80f..160f, accent) { scale = it; prefs.textScale = it.toInt(); refresh() }
    }
    Section("📐  Layout", "Reset where overlays sit on screen.") {
        Ghost("Reset all widget positions", Modifier.fillMaxWidth()) { prefs.resetPositions(); refresh() }
    }
}

@Composable
private fun AboutTab(accent: Color, onCheck: () -> Unit = {}) {
    val ctx = LocalContext.current
    Section("Portal Overlays", "A floating HUD for Meta Portal.") {
        Text(
            "Draws banners, widgets, alerts and a status strip on top of any app — including the " +
            "Immortal launcher — and listens to ntfy.sh for push notifications. Built for sideloaded " +
            "Portal devices (no Google services required).",
            color = MUTED, fontSize = 15.sp
        )
    }
    Section("Updates", "Compare against the latest release on GitHub.") {
        Primary("Check for updates", accent) { onCheck() }
    }
    Section("Quick setup", "Tap to open the matching system settings page.") {
        Ghost("Allow draw over other apps", Modifier.fillMaxWidth()) {
            runCatching {
                val i = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}")
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        }
        Spacer(Modifier.height(6.dp))
        Ghost("Enable notification listener", Modifier.fillMaxWidth()) {
            runCatching {
                val i = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        }
    }
    Section("One-time permissions (adb)", "Or grant from a computer over adb.") {
        Code("metavr adb shell appops set com.portal.overlays SYSTEM_ALERT_WINDOW allow")
        Spacer(Modifier.height(8.dp))
        Code("metavr adb shell settings put secure enabled_accessibility_services com.portal.overlays/com.portal.overlays.NavAccessibilityService")
        Spacer(Modifier.height(8.dp))
        Code("metavr adb shell settings put secure accessibility_enabled 1")
        Spacer(Modifier.height(8.dp))
        Code("metavr adb shell cmd notification allow_listener com.portal.overlays/com.portal.overlays.NotifyListenerService")
    }
    Section("Credits", "") {
        Text("Open-Meteo for weather · ntfy.sh for push · made for the Portal sideloading community.",
            color = MUTED, fontSize = 14.sp)
    }
}

// ---- reusable pieces ------------------------------------------------------

@Composable
private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp)).background(PANEL).padding(22.dp)) {
        Column {
            Text(title, color = TEXT, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TEXT, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors(accent))
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, color = MUTED) },
        placeholder = { Text(placeholder, color = FAINT) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = PANEL2, unfocusedContainerColor = PANEL2,
            focusedTextColor = TEXT, unfocusedTextColor = TEXT,
            focusedIndicatorColor = LINE, unfocusedIndicatorColor = LINE, cursorColor = TEXT
        )
    )
}

@Composable
private fun Code(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0C0F)).padding(14.dp)) {
        Text(text, color = Color(0xFFB7C0CC), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(14.dp)).background(PANEL2).padding(16.dp)) {
        Text(text, color = MUTED, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, accent: Color, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(label, color = TEXT, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(value, color = accent, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(value = v, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = LINE))
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(PANEL2).padding(4.dp)) {
        options.forEachIndexed { i, opt ->
            val active = i == selected
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (active) accent else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) { Text(opt, color = if (active) Color.White else MUTED, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun Primary(label: String, accent: Color, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 6.dp),
        shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Ghost(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(52.dp).padding(top = 6.dp),
        shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = PANEL2, contentColor = TEXT)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun switchColors(accent: Color) = SwitchDefaults.colors(
    checkedThumbColor = Color.White, checkedTrackColor = accent, checkedBorderColor = accent,
    uncheckedThumbColor = MUTED, uncheckedTrackColor = PANEL2, uncheckedBorderColor = LINE
)
