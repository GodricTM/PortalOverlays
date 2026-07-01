package com.portal.overlays

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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

/** True if any TTS engine (the sideloaded Portal TTS engine or a generic one) is installed. */
private fun ttsInstalled(context: android.content.Context): Boolean = Speaker.engineAvailable(context)

private enum class Tab(val glyph: String, val label: String) {
    WIDGETS("W", "Widgets"),
    NOW_PLAYING("NP", "Now Playing"),
    SCREENSAVER("Z", "Screensaver"),
    STRIP("S", "Status strip"),
    TICKER("T", "Ticker"),
    SETTINGS("G", "Settings"),
    NOTIFY("N", "Notifications"),
    NAV("<>", "Navigation"),
    LOOK("A", "Appearance"),
    ABOUT("i", "About")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Deck() }
    }

    override fun onResume() {
        super.onResume()
        // Portal aggressively kills the background overlay service; START_STICKY doesn't reliably
        // bring it back. Re-arm it whenever the app is reopened so the overlays are present without
        // the user having to toggle "running" off and on.
        if (Prefs(this).serviceEnabled) {
            OverlayService.send(this, OverlayService.ACTION_REFRESH)
        }
    }
}

@Composable
private fun Deck() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var tab by remember { mutableStateOf(Tab.WIDGETS) }
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
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
    fun syncWidgets() { if (running) OverlayService.send(context, OverlayService.ACTION_SYNC_WIDGETS) }
    fun syncTicker() { if (running) OverlayService.send(context, OverlayService.ACTION_SYNC_TICKER) }

    // Dismiss the first-run walkthrough. Extracted so a remote's OK/Enter can trigger it too (below).
    val finishOnboarding: () -> Unit = {
        prefs.onboardingDone = true
        showOnboarding = false
        // If overlays are switched on, refresh now that permission may have just been granted.
        if (running) OverlayService.send(context, OverlayService.ACTION_REFRESH)
    }

    Box(
        Modifier
            .fillMaxSize()
            // Portal TV / remote: the walkthrough is a Compose modal over a focusable control deck, so
            // a D-pad can keep moving focus on the deck behind it and never reach the "Done" button.
            // Once the required "Draw over other apps" permission is granted, let the remote's OK/Enter
            // dismiss the finish screen from anywhere on screen.
            .onPreviewKeyEvent { e ->
                if (showOnboarding && canOverlay &&
                    (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)
                ) {
                    if (e.type == KeyEventType.KeyUp) finishOnboarding()
                    true // consume down + up so the deck behind never acts on the press
                } else false
            }
    ) {
        Row(Modifier.fillMaxSize().background(BG)) {
            Rail(
                selected = tab,
                accent = accent,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it; searchOpen = true },
                onSearchOpen = { searchOpen = true },
                onSelect = {
                    tab = it
                    searchOpen = false
                    searchQuery = ""
                },
            )
            TabScaffold(
                tab = if (searchOpen || searchQuery.isNotBlank()) null else tab,
                running = running,
                accent = accent,
                canOverlay = canOverlay,
                accEnabled = accEnabled,
                notifAccess = notifAccess,
                hasTopic = prefs.topic.isNotBlank(),
                onRunning = { on ->
                    running = on; prefs.serviceEnabled = on
                    OverlayService.send(context, if (on) OverlayService.ACTION_REFRESH else OverlayService.ACTION_STOP)
                },
                onRecheck = {
                    canOverlay = Settings.canDrawOverlays(context)
                    accEnabled = NavAccessibilityService.isEnabled
                    notifAccess = notifAccessEnabled(context)
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                if (searchOpen || searchQuery.isNotBlank()) {
                    SettingsSearchPanel(
                        query = searchQuery,
                        accent = accent,
                        onQueryChange = { searchQuery = it },
                        onPick = { tabId ->
                            runCatching { tab = Tab.valueOf(tabId) }
                            searchQuery = ""
                            searchOpen = false
                        },
                        onClose = {
                            searchQuery = ""
                            searchOpen = false
                        },
                    )
                } else {
                when (tab) {
                    Tab.WIDGETS -> WidgetsTab(prefs, accent, ::syncWidgets)
                    Tab.NOW_PLAYING -> NowPlayingTab(prefs, accent, ::syncWidgets)
                    Tab.SCREENSAVER -> ScreensaverTab(context, prefs, accent)
                    Tab.STRIP -> StripTab(prefs, accent, ::refresh)
                    Tab.TICKER -> TickerTab(prefs, accent, ::syncTicker)
                    Tab.SETTINGS -> SettingsTab(prefs, accent, ::refresh)
                    Tab.NOTIFY -> NotifyTab(context, prefs, accent, ::refresh)
                    Tab.NAV -> NavTab(context, prefs, accent, accEnabled, ::refresh)
                    Tab.LOOK -> LookTab(prefs, accent, { accent = it }, ::refresh)
                    Tab.ABOUT -> AboutTab(accent) {
                        updateResult = null
                        UpdateChecker.checkForUpdate(context) { updateResult = it }
                    }
                }
                }
            }
        }

        if (showOnboarding) {
            OnboardingOverlay(
                accent = accent,
                canOverlay = canOverlay, accEnabled = accEnabled, notifAccess = notifAccess,
                onDone = finishOnboarding
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
    // Land D-pad focus on the primary action so a remote can act on the finish screen (Portal TV).
    val doneFocus = remember { FocusRequester() }
    LaunchedEffect(canOverlay) { if (canOverlay) runCatching { doneFocus.requestFocus() } }
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
                modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(doneFocus),
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
            var status by remember { mutableStateOf<String?>(null) }
            val notes = if (result.info.notes.length > 600) result.info.notes.substring(0, 600) + "\n…" else result.info.notes
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (status == null) onDismiss() },
                title = { Text("Update available: v${result.info.versionName}", color = TEXT) },
                text = {
                    Text(
                        buildString {
                            append("Installed: v${result.installedVersionName}\nLatest:    v${result.info.versionName}\n\n$notes")
                            status?.let { append("\n\n$it") }
                        },
                        color = if (status != null) accent else MUTED
                    )
                },
                confirmButton = {
                    if (status == null) androidx.compose.material3.TextButton(onClick = {
                        UpdateChecker.installUpdate(context, result.info) { status = it }
                    }) { Text("Update now", color = accent) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(if (status == null) "Later" else "Close", color = MUTED)
                    }
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

// ---- full-height tab shell ------------------------------------------------

/** Each settings tab fills the content pane: full-width header + scrollable body. */
@Composable
private fun TabScaffold(
    tab: Tab?,
    running: Boolean,
    accent: Color,
    canOverlay: Boolean,
    accEnabled: Boolean,
    notifAccess: Boolean,
    hasTopic: Boolean,
    onRunning: (Boolean) -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(BG)) {
        Row(
            Modifier.fillMaxWidth().background(PANEL).padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tab?.label ?: "Search settings",
                    color = TEXT, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        tab == null -> "Find any tab, section, or keyword"
                        running -> "Overlays live on top of every app"
                        else -> "Overlays stopped"
                    },
                    color = when {
                        tab == null -> MUTED
                        running -> accent
                        else -> MUTED
                    },
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Led("DRAW", canOverlay)
                Spacer(Modifier.width(14.dp))
                Led("NAV", accEnabled)
                Spacer(Modifier.width(14.dp))
                Led("NOTIF", notifAccess)
                Spacer(Modifier.width(14.dp))
                Led("NTFY", hasTopic)
                Spacer(Modifier.width(18.dp))
                Text(
                    if (running) "ON" else "OFF",
                    color = if (running) accent else FAINT,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Switch(checked = running, onCheckedChange = onRunning, colors = switchColors(accent))
            }
        }
        Row(
            Modifier.fillMaxWidth().background(PANEL2).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Portal Overlays", color = FAINT, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(
                "re-check permissions",
                color = MUTED,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onRecheck() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            content()
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ---- settings search ----------------------------------------------------

@Composable
private fun SettingsSearchPanel(
    query: String,
    accent: Color,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val results = remember(query) { SettingsSearch.search(query) }
    val browsing = query.isBlank()

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search tabs, sections, keywords…", color = FAINT, fontSize = 14.sp) },
                singleLine = true,
                textStyle = TextStyle(color = TEXT, fontSize = 16.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PANEL2, unfocusedContainerColor = PANEL2,
                    focusedIndicatorColor = accent, unfocusedIndicatorColor = LINE,
                    cursorColor = accent,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Ghost("Close", Modifier.width(100.dp), onClose)
        }

        if (browsing) {
            Text("Popular searches", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSearch.quickChips.forEach { chip ->
                    SearchChip(chip, accent) { onQueryChange(chip) }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Browse all settings", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
            SettingsSearch.browseByTab().forEach { (tabLabel, entries) ->
                SearchGroup(tabLabel, entries, accent, onPick)
                Spacer(Modifier.height(12.dp))
            }
        } else if (results.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No matches", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Try ntfy, screensaver, edge bar, wind, or labs", color = MUTED, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        } else {
            Text(
                "${results.size} result${if (results.size == 1) "" else "s"} for \"$query\"",
                color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp),
            )
            results.forEach { hit ->
                SearchResultCard(hit, accent) { onPick(hit.tabId) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun SearchGroup(
    tabLabel: String,
    entries: List<SettingsSearch.Result>,
    accent: Color,
    onPick: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PANEL)
            .padding(16.dp),
    ) {
        Text(tabLabel, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(10.dp))
        entries.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPick(entry.tabId) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.section, color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(entry.description, color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Text("→", color = FAINT, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun SearchResultCard(hit: SettingsSearch.Result, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PANEL)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hit.tabLabel.uppercase(),
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("Open tab →", color = FAINT, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(hit.section, color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp))
        Text(hit.description, color = MUTED, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (hit.keywords.isNotEmpty()) {
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                hit.keywords.take(5).forEach { kw ->
                    Text(
                        kw,
                        color = FAINT,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(PANEL2)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: SettingsSearch.Result,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PANEL)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(hit.section, color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(hit.tabLabel, color = MUTED, fontSize = 11.sp, maxLines = 1)
        }
        Text("→", color = accent, fontSize = 16.sp)
    }
}

@Composable
private fun SearchChip(label: String, accent: Color, onClick: () -> Unit) {
    Text(
        label,
        color = TEXT,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PANEL2)
            .border(1.dp, LINE, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// ---- rail -----------------------------------------------------------------

@Composable
private fun Rail(
    selected: Tab,
    accent: Color,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSelect: (Tab) -> Unit,
) {
    val hits = remember(searchQuery) { SettingsSearch.search(searchQuery) }
    Column(
        Modifier.fillMaxHeight().width(240.dp).background(Color(0xFF0A0C0F))
    ) {
        Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Row(Modifier.padding(start = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(10.dp))
            Text("OVERLAYS", color = TEXT, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onSearchOpen() },
            placeholder = { Text("Search settings…", color = FAINT, fontSize = 13.sp) },
            singleLine = true,
            textStyle = TextStyle(color = TEXT, fontSize = 14.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = PANEL2, unfocusedContainerColor = PANEL2,
                focusedIndicatorColor = accent, unfocusedIndicatorColor = LINE,
                cursorColor = accent
            )
        )
        if (searchQuery.isBlank()) {
            Text("Popular", color = FAINT, fontSize = 11.sp, modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 4.dp))
            Row(
                Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SettingsSearch.quickChips.take(4).forEach { chip ->
                    SearchChip(chip, accent) { onSearchQueryChange(chip); onSearchOpen() }
                }
            }
        } else if (hits.isNotEmpty()) {
            Text(
                "${hits.size} match${if (hits.size == 1) "" else "es"} →",
                color = accent,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
            )
            hits.take(3).forEach { hit ->
                SearchHitRow(hit, accent, Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
                    runCatching { onSelect(Tab.valueOf(hit.tabId)) }
                    onSearchQueryChange("")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
private fun WidgetsTab(prefs: Prefs, accent: Color, syncWidgets: () -> Unit) {
    Section("Clock", "Time, optional seconds and date.") {
        var on by remember { mutableStateOf(prefs.clockEnabled) }
        Toggle("Show clock", on, accent) { on = it; prefs.clockEnabled = it; syncWidgets() }
        if (on) {
            var h24 by remember { mutableStateOf(prefs.clock24h) }
            var secs by remember { mutableStateOf(prefs.clockSeconds) }
            var date by remember { mutableStateOf(prefs.clockShowDate) }
            Toggle("24-hour time", h24, accent) { h24 = it; prefs.clock24h = it; syncWidgets() }
            Toggle("Show seconds", secs, accent) { secs = it; prefs.clockSeconds = it; syncWidgets() }
            Toggle("Show date", date, accent) { date = it; prefs.clockShowDate = it; syncWidgets() }
        }
    }
    Section("Weather", "Live conditions from Open-Meteo (no API key).") {
        var on by remember { mutableStateOf(prefs.weatherEnabled) }
        var f by remember { mutableStateOf(prefs.weatherFahrenheit) }
        Toggle("Show weather", on, accent) { on = it; prefs.weatherEnabled = it; syncWidgets() }
        if (on) {
            Toggle("Use Fahrenheit", f, accent) { f = it; prefs.weatherFahrenheit = it; syncWidgets() }
            Text("Set the weather location in Settings.", color = MUTED, fontSize = 13.sp)
        }
    }
    Section("Battery", "Charge level and charging state.") {
        var on by remember { mutableStateOf(prefs.batteryEnabled) }
        Toggle("Show battery", on, accent) { on = it; prefs.batteryEnabled = it; syncWidgets() }
    }
    Section("Sticky note", "A pinned note that floats on top.") {
        var on by remember { mutableStateOf(prefs.noteEnabled) }
        var txt by remember { mutableStateOf(prefs.noteText) }
        Toggle("Show note", on, accent) { on = it; prefs.noteEnabled = it; syncWidgets() }
        if (on) Field("Note text", txt, "Type your note") { txt = it; prefs.noteText = it; syncWidgets() }
    }
    Section("Agenda", "Your next events from a public calendar (iCal / .ics link). No Google sign-in needed.") {
        var on by remember { mutableStateOf(prefs.agendaEnabled) }
        var ical by remember { mutableStateOf(prefs.calendarUrl) }
        Toggle("Show agenda", on, accent) { on = it; prefs.agendaEnabled = it; syncWidgets() }
        Field("Calendar URL (.ics / webcal)", ical, "https://calendar.google.com/.../basic.ics") {
            ical = it; prefs.calendarUrl = it; syncWidgets()
        }
        Hint("Google Calendar → Settings → your calendar → \"Secret address in iCal format\". Apple/Outlook expose a public iCal link too.")
    }
    Hint("Drag any widget to reposition it. Positions are remembered. Widgets snap clear of the status strip, ticker, and edge now-playing bar.")
}

@Composable
private fun NowPlayingTab(prefs: Prefs, accent: Color, syncWidgets: () -> Unit) {
    Section("Music  Now playing", "Album art, track details and transport controls from active media sessions.") {
        val context = LocalContext.current
        if (!notifAccessEnabled(context)) Code(
            "metavr adb shell cmd notification allow_listener com.portal.overlays/com.portal.overlays.NotifyListenerService"
        )
        NowPlayingControls(prefs, accent, syncWidgets)
        Text(
            "Open the full card to use History (last ~20 tracks) and see album-art crossfade on track changes.",
            color = MUTED, fontSize = 13.sp
        )
    }
}

@Composable
private fun ScreensaverTab(context: android.content.Context, prefs: Prefs, accent: Color) {
    Section(
        "Survive the screen saver",
        "A floating overlay is hidden the moment the screen saver starts — Android draws the saver on top. " +
            "This screensaver re-hosts the now-playing card, the bouncing-bars equaliser and a clock so they " +
            "stay on-screen. Pick it as the device screensaver below."
    ) {
        var bg by remember { mutableStateOf(prefs.screensaverBackground) }
        var webUrl by remember { mutableStateOf(prefs.screensaverWebUrl) }
        var photoUri by remember { mutableStateOf(prefs.screensaverPhotoUri) }
        var showClock by remember { mutableStateOf(prefs.screensaverShowClock) }
        var showBattery by remember { mutableStateOf(prefs.screensaverShowBattery) }
        var showNp by remember { mutableStateOf(prefs.screensaverShowNowPlaying) }
        var keepBright by remember { mutableStateOf(prefs.screensaverKeepBright) }

        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                photoUri = uri.toString(); prefs.screensaverPhotoUri = photoUri
            }
        }

        Text("Background", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
        val backgrounds = Prefs.SCREENSAVER_BACKGROUNDS
        Segmented(
            backgrounds.map { it.second },
            backgrounds.indexOfFirst { it.first == bg }.coerceAtLeast(0),
            accent
        ) { bg = backgrounds[it].first; prefs.screensaverBackground = bg }
        Text(
            "Black is simplest. Photo shows a still image you pick. Web page embeds any URL — e.g. an " +
                "Immich Kiosk feed — so you keep your photo source behind the now-playing card.",
            color = MUTED, fontSize = 13.sp
        )

        if (bg == "web") {
            Field("Web page URL", webUrl, "https://immich.example.com/kiosk") {
                webUrl = it; prefs.screensaverWebUrl = it
            }
        }
        if (bg == "photo") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (photoUri.isBlank()) "No photo chosen" else "Photo selected",
                    color = if (photoUri.isBlank()) FAINT else OK, fontSize = 14.sp, modifier = Modifier.weight(1f)
                )
                Ghost("Choose photo") {
                    runCatching { photoPicker.launch(arrayOf("image/*")) }
                        .onFailure { OverlayService.sendBanner(context, "No picker", "This Portal has no file picker; use a Web page or Black background.") }
                }
            }
        }

        Toggle("Show clock & date", showClock, accent) { showClock = it; prefs.screensaverShowClock = it }
        Toggle("Show battery", showBattery, accent) { showBattery = it; prefs.screensaverShowBattery = it }
        Toggle("Show now playing", showNp, accent) { showNp = it; prefs.screensaverShowNowPlaying = it }
        Text(
            "On the idle screen: tap album art to skip, swipe left/right for prev/next, double-tap the clock to wake. " +
                "First real idle shows a one-time hint toast.",
            color = MUTED, fontSize = 13.sp
        )
        if (showNp) {
            var npLayout by remember { mutableStateOf(prefs.screensaverNowPlayingLayout) }
            var vizStyle by remember { mutableStateOf(prefs.screensaverVisualizerStyle) }
            Text("Now-playing style", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            val layouts = Prefs.SCREENSAVER_NP_LAYOUTS
            Segmented(layouts.map { it.second }, layouts.indexOfFirst { it.first == npLayout }.coerceAtLeast(0), accent) {
                npLayout = layouts[it].first; prefs.screensaverNowPlayingLayout = npLayout
            }
            Text(
                "Card = a compact bar over your background. Cover = fullscreen album art with a big " +
                    "music visualizer behind it (replaces the background while playing).",
                color = MUTED, fontSize = 13.sp
            )
            if (npLayout == "cover") {
                Text("Visualizer", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                val vizzes = Prefs.NOW_PLAYING_VISUALIZERS
                Segmented(vizzes.map { it.second }, vizzes.indexOfFirst { it.first == vizStyle }.coerceAtLeast(0), accent) {
                    vizStyle = vizzes[it].first; prefs.screensaverVisualizerStyle = vizStyle
                }
            }
        }
        Toggle("Keep screen bright", keepBright, accent) { keepBright = it; prefs.screensaverKeepBright = it }
        if (!notifAccessEnabled(context)) Text(
            "Tip: the now-playing card needs notification access (enable it on the Now Playing tab).",
            color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Primary("Preview screensaver", accent) {
            runCatching {
                context.startActivity(Intent(context, ScreensaverPreviewActivity::class.java))
            }
        }
        Text("Shows exactly what the screen saver will — play something to see the now-playing. Tap to close.",
            color = MUTED, fontSize = 13.sp)
    }

    Section("Make it the screensaver", "Easiest: run set_screensaver.bat from a PC (handles everything below). Or do it here.") {
        Primary("Open screensaver settings", accent) {
            runCatching { context.startActivity(Intent("android.settings.DREAM_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { OverlayService.sendBanner(context, "No settings screen", "This Portal hides the screensaver picker — use the adb command instead.") }
        }
        Text(
            "If the picker isn't available on your Portal, set it over adb (this also replaces the " +
                "Immortal screensaver — point the Background above at your Immich Kiosk URL to keep that feed):",
            color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        Code(
            "adb shell settings put secure screensaver_components " +
                "com.portal.overlays/.NowPlayingDreamService\n" +
                "adb shell settings put secure screensaver_enabled 1"
        )
        Text(
            "Already running the Immortal launcher? It re-asserts its own screensaver on boot and on " +
                "every return home, which would evict this one. Stop that first by revoking its " +
                "secure-settings access, then run the commands above:",
            color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
        )
        Code("adb shell pm revoke com.immortal.launcher android.permission.WRITE_SECURE_SETTINGS")
    }
}

@Composable
private fun SettingsTab(prefs: Prefs, accent: Color, refresh: () -> Unit) {
    Section("Weather location", "Set the city used by the Weather widget and the status strip.") {
        var city by remember { mutableStateOf(prefs.weatherCity) }
        Field("City", city, "e.g. New York", onCommit = { refresh() }) {
            city = it; prefs.weatherCity = it
        }
        Text("This feeds both Weather and any strip weather indicators.", color = MUTED, fontSize = 13.sp)
    }
    Section("Weather units", "Choose the unit system used for Weather.") {
        var f by remember { mutableStateOf(prefs.weatherFahrenheit) }
        Toggle("Use Fahrenheit", f, accent) { f = it; prefs.weatherFahrenheit = it; refresh() }
    }
    LabsSection(prefs, accent, refresh)
}

@Composable
private fun LabsSection(prefs: Prefs, accent: Color, refresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Section(
        "Labs",
        "Experimental options that usually make things worse on Portal. Leave collapsed unless you know why you're here."
    ) {
        Ghost(if (open) "Hide labs" else "Show experimental options", Modifier.fillMaxWidth()) { open = !open }
        if (open) {
            var npReactive by remember { mutableStateOf(prefs.nowPlayingSoundReactive) }
            var ssReactive by remember { mutableStateOf(prefs.screensaverSoundReactive) }
            Text(
                "The visualizer already animates smoothly whenever music is playing. Live-audio reaction " +
                    "uses the microphone because Portal blocks output-mix capture for sideloaded apps, and " +
                    "the mic is shared with the always-on assistant — so bars often look laggy or broken. " +
                    "See docs/portal-audio-capture.md in the source repo.",
                color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            Toggle("Now playing: react to live audio", npReactive, accent) {
                npReactive = it; prefs.nowPlayingSoundReactive = it
                if (it) micLauncher.launch("android.permission.RECORD_AUDIO")
                refresh()
            }
            Toggle("Screensaver: react to live audio", ssReactive, accent) {
                ssReactive = it; prefs.screensaverSoundReactive = it
                if (it) micLauncher.launch("android.permission.RECORD_AUDIO")
            }
        }
    }
}

@Composable
private fun NowPlayingControls(prefs: Prefs, accent: Color, refresh: () -> Unit) {
    var on by remember { mutableStateOf(prefs.nowPlayingEnabled) }
    Toggle("Show now playing", on, accent) { on = it; prefs.nowPlayingEnabled = it; refresh() }
    if (on) {
        var dock by remember { mutableStateOf(prefs.nowPlayingDockStyle) }
        var autoHide by remember { mutableStateOf(prefs.nowPlayingAutoHide) }
        var expanded by remember { mutableStateOf(prefs.nowPlayingStartExpanded) }
        var style by remember { mutableStateOf(prefs.nowPlayingVisualizerStyle) }
        var layout by remember { mutableStateOf(prefs.nowPlayingLayoutStyle) }
        var progress by remember { mutableStateOf(prefs.nowPlayingShowProgress) }
        Text("Docked widget", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        val docks = Prefs.NOW_PLAYING_DOCKS
        Segmented(
            docks.map { it.second },
            docks.indexOfFirst { it.first == dock }.coerceAtLeast(0),
            accent
        ) {
            dock = docks[it].first; prefs.nowPlayingDockStyle = dock; refresh()
        }
        Text(
            "Bubble = a small cover-art button. Strip = a floating bar with title, artist & progress. " +
                "Edge bar = a full-width band at the top or bottom that comes and goes with playback.",
            color = MUTED, fontSize = 13.sp
        )
        if (dock == "bubble") {
            var bubbleStyle by remember { mutableStateOf(prefs.nowPlayingBubbleStyle) }
            var bubbleSize by remember { mutableStateOf(prefs.nowPlayingBubbleSize) }
            Text("Bubble style", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            val bstyles = Prefs.NOW_PLAYING_BUBBLE_STYLES
            Segmented(
                bstyles.map { it.second },
                bstyles.indexOfFirst { it.first == bubbleStyle }.coerceAtLeast(0),
                accent
            ) {
                bubbleStyle = bstyles[it].first; prefs.nowPlayingBubbleStyle = bubbleStyle; refresh()
            }
            Text("Bubble size", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            val sizes = Prefs.NOW_PLAYING_SIZES
            Segmented(
                sizes.map { it.second },
                sizes.indexOfFirst { it.first == bubbleSize }.coerceAtLeast(0),
                accent
            ) {
                bubbleSize = sizes[it].first; prefs.nowPlayingBubbleSize = bubbleSize; refresh()
            }
        }
        if (dock == "edge") {
            var edgePos by remember { mutableStateOf(prefs.nowPlayingEdgePosition) }
            Text("Edge bar position", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            Segmented(listOf("Top", "Bottom"), if (edgePos == "bottom") 1 else 0, accent) {
                edgePos = if (it == 1) "bottom" else "top"; prefs.nowPlayingEdgePosition = edgePos; refresh()
            }
        }
        Toggle("Hide when nothing is playing", autoHide, accent) {
            autoHide = it; prefs.nowPlayingAutoHide = it; refresh()
        }
        Toggle("Open full card when playing", expanded, accent) {
            expanded = it; prefs.nowPlayingStartExpanded = it; refresh()
        }
        Toggle("Show progress & track length", progress, accent) {
            progress = it; prefs.nowPlayingShowProgress = it; refresh()
        }
        val context = LocalContext.current
        Text("Visualizer", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        val visualizers = Prefs.NOW_PLAYING_VISUALIZERS
        Segmented(
            visualizers.map { it.second },
            visualizers.indexOfFirst { it.first == style }.coerceAtLeast(0),
            accent
        ) {
            style = visualizers[it].first
            prefs.nowPlayingVisualizerStyle = style
            refresh()
        }
        val layouts = Prefs.NOW_PLAYING_LAYOUTS
        Segmented(
            layouts.map { it.second },
            layouts.indexOfFirst { it.first == layout }.coerceAtLeast(0),
            accent
        ) {
            layout = layouts[it].first
            prefs.nowPlayingLayoutStyle = layout
            refresh()
        }
        Text("Off = starts as a small bubble you tap to expand.", color = MUTED, fontSize = 13.sp)
        Primary("Preview now playing", accent) {
            OverlayService.send(context, OverlayService.ACTION_PREVIEW_NOW_PLAYING)
        }
        Text("Opens the full now-playing card so you can try visualizer styles and layouts. Needs " +
            "\"Draw over other apps\". Tap × to close.", color = MUTED, fontSize = 13.sp)
    }
}

@Composable
private fun TickerTab(prefs: Prefs, accent: Color, syncTicker: () -> Unit) {
    Section("Ticker", "A thin scrolling strip for live RSS, Atom or JSON headlines.") {
        var on by remember { mutableStateOf(prefs.tickerEnabled) }
        var url by remember { mutableStateOf(prefs.tickerUrl) }
        var speed by remember { mutableStateOf(prefs.tickerSpeed.toFloat()) }
        var top by remember { mutableStateOf(prefs.tickerPosition == "top") }
        Toggle("Show ticker", on, accent) { on = it; prefs.tickerEnabled = it; syncTicker() }
        if (on) {
            Segmented(listOf("Bottom", "Top"), if (top) 1 else 0, accent) {
                top = it == 1; prefs.tickerPosition = if (top) "top" else "bottom"; syncTicker()
            }
            var style by remember { mutableStateOf(prefs.tickerStyle) }
            Text("Style", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            TickerStylePicker(style, accent) { style = it; prefs.tickerStyle = it; syncTicker() }
        }
        Field("Feed URL (RSS/Atom or JSON)", url, "https://... .xml or .json") {
            url = it; prefs.tickerUrl = it; syncTicker()
        }
        Text(
            "Custom finance watchlists: finance:crypto:BTC,ETH,SOL or finance:stocks:AAPL,TSLA,NVDA",
            color = MUTED, fontSize = 13.sp
        )
        Text("Quick sources", color = MUTED, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Prefs.TICKER_SOURCES.forEach { (label, source) ->
            Ghost("Use $label", Modifier.fillMaxWidth()) {
                on = true
                url = source
                prefs.tickerEnabled = true
                prefs.tickerUrl = source
                syncTicker()
            }
        }
        Text("Live finance", color = MUTED, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Prefs.TICKER_FINANCE.forEach { (label, source) ->
            Ghost("Use $label", Modifier.fillMaxWidth()) {
                on = true
                url = source
                prefs.tickerEnabled = true
                prefs.tickerUrl = source
                syncTicker()
            }
        }
        SliderRow("Scroll speed", "${speed.toInt()} px/s", speed, 20f..200f, accent) {
            speed = it; prefs.tickerSpeed = it.toInt(); syncTicker()
        }
    }
}

@Composable
private fun StripTab(prefs: Prefs, accent: Color, refresh: () -> Unit) {
    Section("Status strip", "A thin live-info bar along one edge.") {
        var on by remember { mutableStateOf(prefs.stripEnabled) }
        var top by remember { mutableStateOf(prefs.stripPosition == "top") }
        Toggle("Show strip", on, accent) { on = it; prefs.stripEnabled = it; refresh() }
        if (on) {
            Segmented(listOf("Bottom", "Top"), if (top) 1 else 0, accent) {
                top = it == 1; prefs.stripPosition = if (top) "top" else "bottom"; refresh()
            }
            var style by remember { mutableStateOf(prefs.stripStyle) }
            Text("Style", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            StripStylePicker(style, accent) { style = it; prefs.stripStyle = it; refresh() }
            var c by remember { mutableStateOf(prefs.stripShowClock) }
            var d by remember { mutableStateOf(prefs.stripShowDate) }
            var w by remember { mutableStateOf(prefs.stripShowWeather) }
            var b by remember { mutableStateOf(prefs.stripShowBattery) }
            var net by remember { mutableStateOf(prefs.stripShowNetwork) }
            var stream by remember { mutableStateOf(prefs.stripShowStreaming) }
            var vpn by remember { mutableStateOf(prefs.stripShowVpn) }
            var wifi by remember { mutableStateOf(prefs.stripShowWifi) }
            var week by remember { mutableStateOf(prefs.stripShowWeek) }
            var rain by remember { mutableStateOf(prefs.stripShowRain) }
            var sun by remember { mutableStateOf(prefs.stripShowSun) }
            var wind by remember { mutableStateOf(prefs.stripShowWind) }
            var uv by remember { mutableStateOf(prefs.stripShowUv) }
            var alert by remember { mutableStateOf(prefs.stripShowWeatherAlert) }
            var agenda by remember { mutableStateOf(prefs.stripShowAgenda) }
            var n by remember { mutableStateOf(prefs.stripShowNtfy) }
            var ctx by remember { mutableStateOf(prefs.stripShowContext) }
            var nav by remember { mutableStateOf(prefs.stripShowNavButtons) }
            Toggle("Clock", c, accent) { c = it; prefs.stripShowClock = it; refresh() }
            Toggle("Date", d, accent) { d = it; prefs.stripShowDate = it; refresh() }
            Toggle("Foreground app / Portal UI", ctx, accent) { ctx = it; prefs.stripShowContext = it; refresh() }
            Text("Tap the foreground-app label on the strip to open, inspect, or force-stop the app.",
                color = MUTED, fontSize = 13.sp)
            Toggle("Back / Home / Recents on strip", nav, accent) { nav = it; prefs.stripShowNavButtons = it; refresh() }
            Toggle("Weather", w, accent) { w = it; prefs.stripShowWeather = it; refresh() }
            Toggle("Battery", b, accent) { b = it; prefs.stripShowBattery = it; refresh() }
            Toggle("Network speed", net, accent) { net = it; prefs.stripShowNetwork = it; refresh() }
            Toggle("Streaming indicator", stream, accent) { stream = it; prefs.stripShowStreaming = it; refresh() }
            Toggle("VPN status dot", vpn, accent) { vpn = it; prefs.stripShowVpn = it; refresh() }
            Toggle("Wi-Fi signal (tap for IP)", wifi, accent) { wifi = it; prefs.stripShowWifi = it; refresh() }
            Toggle("Week number", week, accent) { week = it; prefs.stripShowWeek = it; refresh() }
            Toggle("Rain in the next hour", rain, accent) { rain = it; prefs.stripShowRain = it; refresh() }
            Toggle("Time to sunset / sunrise", sun, accent) { sun = it; prefs.stripShowSun = it; refresh() }
            Toggle("Wind speed", wind, accent) { wind = it; prefs.stripShowWind = it; refresh() }
            Toggle("UV index", uv, accent) { uv = it; prefs.stripShowUv = it; refresh() }
            Toggle("Severe weather alert", alert, accent) { alert = it; prefs.stripShowWeatherAlert = it; refresh() }
            Toggle("Next calendar event", agenda, accent) { agenda = it; prefs.stripShowAgenda = it; refresh() }
            if ((rain || sun) && prefs.weatherCity.isBlank()) {
                Text("Set a Weather city in the Widgets tab - rain and sun times need a location.",
                    color = MUTED, fontSize = 13.sp)
            }
            if (agenda && prefs.calendarUrl.isBlank()) {
                Text("Add a Calendar URL in the Widgets tab - the next-event item needs an iCal link.",
                    color = MUTED, fontSize = 13.sp)
            }
            Toggle("ntfy status", n, accent) { n = it; prefs.stripShowNtfy = it; refresh() }
            Text("Tap the ntfy line on the strip to preview the last message.",
                color = MUTED, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NotifyTab(context: android.content.Context, prefs: Prefs, accent: Color, refresh: () -> Unit) {
    Section("ntfy server", "Use the public ntfy.sh, or point this at your own self-hosted instance to keep messages private.") {
        var server by remember { mutableStateOf(prefs.ntfyServer) }
        var token by remember { mutableStateOf(prefs.ntfyToken) }
        Field("Server URL", server, "https://ntfy.sh", onCommit = { refresh() }) {
            server = it; prefs.ntfyServer = it
        }
        Field("Access token (optional)", token, "tk_… for a protected topic", onCommit = { refresh() }) {
            token = it; prefs.ntfyToken = it
        }
        Text(
            "For a self-hosted server enter its URL (e.g. https://ntfy.example.com). Add an access " +
                "token only if the topic is read-protected; leave it blank for public topics.",
            color = MUTED, fontSize = 13.sp
        )
    }
    Section("ntfy topic", "Every message published to this topic pops a banner on top of any app.") {
        var topic by remember { mutableStateOf(prefs.topic) }
        Field("Topic", topic, "e.g. portal-denis-7f3a", onCommit = { refresh() }) {
            topic = it; prefs.topic = it
        }
        Spacer(Modifier.height(6.dp))
        val host = prefs.ntfyServer.ifBlank { "https://ntfy.sh" }
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        Code("curl -d \"Kitchen timer done\" $host/${topic.ifBlank { "your-topic" }}")
    }
    Section("Banners", "How incoming messages appear.") {
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
    Section("🔴  Breaking news", "Urgent ntfy messages (priority 5, or a \"breaking\"/\"urgent\" tag) flash a full-attention popup and read the headline aloud.") {
        var on by remember { mutableStateOf(prefs.breakingNewsEnabled) }
        var speak by remember { mutableStateOf(prefs.breakingNewsSpeak) }
        var secs by remember { mutableStateOf(prefs.breakingNewsSeconds.toFloat()) }
        var vol by remember { mutableStateOf(prefs.breakingNewsVolume.toFloat()) }
        Toggle("Enable breaking-news popups", on, accent) { on = it; prefs.breakingNewsEnabled = it }
        if (on) {
            Toggle("Read the headline aloud (TTS)", speak, accent) { speak = it; prefs.breakingNewsSpeak = it }
            if (speak && !ttsInstalled(context)) Text(
                "No speech engine detected — the popup still flashes and chimes, just silently. " +
                    "Install the Portal TTS engine (com.k2fsa.sherpa.onnx.tts.engine) to enable spoken alerts.",
                color = MUTED, fontSize = 13.sp
            )
            if (speak) SliderRow("TTS volume", "${vol.toInt()}%", vol, 0f..100f, accent) {
                vol = it; prefs.breakingNewsVolume = it.toInt()
            }
            SliderRow("On-screen time", "${secs.toInt()}s", secs, 4f..30f, accent) {
                secs = it; prefs.breakingNewsSeconds = it.toInt()
            }
            val host = prefs.ntfyServer.ifBlank { "https://ntfy.sh" }
                .removePrefix("https://").removePrefix("http://").trimEnd('/')
            Code("curl -H \"Priority: urgent\" -H \"Tags: breaking\" -d \"Your headline\" $host/${prefs.topic.ifBlank { "your-topic" }}")
            Spacer(Modifier.height(6.dp))
            Primary("Test breaking news", accent) {
                OverlayService.sendBreaking(
                    context,
                    "This is a test of the breaking-news alert system",
                    "Triggered from the Overlays settings panel."
                )
            }
        }
    }
    Section("Mirror app notifications", "Show other apps' notifications (WhatsApp, Messenger, email...) as banners.") {
        var on by remember { mutableStateOf(prefs.mirrorNotifications) }
        var skip by remember { mutableStateOf(prefs.mirrorSkipOngoing) }
        if (!notifAccessEnabled(context)) Code(
            "metavr adb shell cmd notification allow_listener com.portal.overlays/com.portal.overlays.NotifyListenerService"
        )
        Toggle("Mirror notifications", on, accent) { on = it; prefs.mirrorNotifications = it }
        if (on) Toggle("Skip ongoing (music, etc.)", skip, accent) { skip = it; prefs.mirrorSkipOngoing = it }
    }
    Section("Alert popups", "Full-attention overlays with a Dismiss button.") {
        var vib by remember { mutableStateOf(prefs.alertVibrate) }
        var snd by remember { mutableStateOf(prefs.alertSound) }
        Toggle("Vibrate on alert", vib, accent) { vib = it; prefs.alertVibrate = it }
        Toggle("Play a sound on alert", snd, accent) { snd = it; prefs.alertSound = it }
        if (snd) {
            SoundRow(context, prefs, accent, "Doorbell sound", OverlayService.KIND_DOORBELL)
            SoundRow(context, prefs, accent, "Timer sound", OverlayService.KIND_TIMER)
            SoundRow(context, prefs, accent, "Reminder sound", OverlayService.KIND_REMINDER)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Ghost("Doorbell", Modifier.weight(1f)) { OverlayService.send(context, OverlayService.ACTION_ALERT, OverlayService.KIND_DOORBELL) }
            Spacer(Modifier.width(10.dp))
            Ghost("Timer", Modifier.weight(1f)) { OverlayService.send(context, OverlayService.ACTION_ALERT, OverlayService.KIND_TIMER) }
            Spacer(Modifier.width(10.dp))
            Ghost("Reminder", Modifier.weight(1f)) { OverlayService.send(context, OverlayService.ACTION_ALERT, OverlayService.KIND_REMINDER) }
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
        var lock by remember { mutableStateOf(prefs.navLock) }
        var vert by remember { mutableStateOf(prefs.navVertical) }
        Toggle("Show navigation cluster", on, accent) { on = it; prefs.navEnabled = it; refresh() }
        if (on) {
            Toggle("Back  ‹", back, accent) { back = it; prefs.navBack = it; refresh() }
            Toggle("Home  ⌂", home, accent) { home = it; prefs.navHome = it; refresh() }
            Toggle("Recents  ▢", rec, accent) { rec = it; prefs.navRecents = it; refresh() }
            if (rec) Text(
                "Recents opens Portal's own overview (the Facebook recents UI). On smaller Portals with " +
                "no overview screen (e.g. Portal Mini) it opens a quick app switcher instead.",
                color = MUTED, fontSize = 13.sp)
            Toggle("Control Center  ⌄", cc, accent) { cc = it; prefs.navControlCenter = it; refresh() }
            Toggle("Screenshot  📸", shot, accent) { shot = it; prefs.navScreenshot = it; refresh() }
            Toggle("Lock screen  🔒", lock, accent) { lock = it; prefs.navLock = it; refresh() }
            Segmented(listOf("Horizontal", "Vertical"), if (vert) 1 else 0, accent) {
                vert = it == 1; prefs.navVertical = it == 1; refresh()
            }
            var posLocked by remember { mutableStateOf(prefs.navLocked) }
            Toggle("Lock position", posLocked, accent) { posLocked = it; prefs.navLocked = it; refresh() }
            Text(
                "Pins the cluster where you've placed it so it can't be dragged by accident. The buttons " +
                    "still work — turn this off to move it again.",
                color = MUTED, fontSize = 13.sp
            )
        }
    }
    Section("🎛️  Button style", "Eight looks for the cluster. Tap one to apply it live.") {
        var style by remember { mutableStateOf(prefs.navStyle) }
        NavStylePicker(style, accent) { style = it; prefs.navStyle = it; refresh() }
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
    var releaseDownloads by remember { mutableStateOf<Long?>(null) }
    val releaseTag = "v1.5"
    LaunchedEffect(Unit) {
        UpdateChecker.fetchReleaseDownloadStats(releaseTag) { stats ->
            releaseDownloads = stats?.downloadCount
        }
    }
    Section("Portal Overlays", "A floating HUD for Meta Portal.") {
        Text(
            "Draws banners, widgets, alerts and a status strip on top of any app — including the " +
            "Immortal launcher — and listens to ntfy.sh (or your own self-hosted server) for push " +
            "notifications. Built for sideloaded Portal devices (no Google services required).",
            color = MUTED, fontSize = 15.sp
        )
    }
    Section("Updates", "Compare against the latest release on GitHub.") {
        Primary("Check for updates", accent) { onCheck() }
        Spacer(Modifier.height(10.dp))
        Text(
            when (val count = releaseDownloads) {
                null -> "Live GitHub release downloads: loading…"
                else -> "Live GitHub release downloads for $releaseTag: $count"
            },
            color = MUTED,
            fontSize = 14.sp
        )
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
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PANEL)
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onCommit: (() -> Unit)? = null,
    onChange: (String) -> Unit
) {
    // On Portal's older Android the soft keyboard often won't auto-raise inside a scrolled form, so we
    // request it explicitly when the field gains focus — via Compose's controller AND the platform
    // InputMethodManager (posted, so the input connection is established first), which is what actually
    // sticks on Portal.
    val keyboard = LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    var wasFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, color = MUTED) },
        placeholder = { Text(placeholder, color = FAINT) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            onCommit?.invoke()
        }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .onFocusChanged { state ->
                if (wasFocused && !state.isFocused) onCommit?.invoke()
                wasFocused = state.isFocused
                if (state.isFocused) {
                    keyboard?.show()
                    view.post {
                        val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as? android.view.inputmethod.InputMethodManager
                        imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = PANEL2, unfocusedContainerColor = PANEL2,
            focusedTextColor = TEXT, unfocusedTextColor = TEXT,
            focusedIndicatorColor = LINE, unfocusedIndicatorColor = LINE, cursorColor = TEXT
        )
    )
}

@Composable
private fun SoundRow(
    context: android.content.Context, prefs: Prefs, accent: Color, label: String, kind: String
) {
    var uriStr by remember { mutableStateOf(prefs.soundUri(kind)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            uriStr = picked?.toString() ?: ""
            prefs.setSoundUri(kind, uriStr)
        }
    }
    val title = remember(uriStr) {
        if (uriStr.isBlank()) "Default notification tone"
        else runCatching { RingtoneManager.getRingtone(context, Uri.parse(uriStr))?.getTitle(context) }.getOrNull() ?: "Custom"
    }
    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TEXT, fontSize = 15.sp)
            Text(title, color = MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Ghost("Choose") {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, label)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                if (uriStr.isNotBlank()) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(uriStr))
            }
            runCatching { launcher.launch(intent) }
                .onFailure { OverlayService.sendBanner(context, "No sound picker", "This Portal has no ringtone picker; the default tone is used.") }
        }
    }
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
private fun StripStylePicker(selected: String, accent: Color, onSelect: (String) -> Unit) {
    val hints = mapOf(
        "default" to "Compact, divided — the original look",
        "accented" to "Coloured highlights per item",
        "three-zones" to "Clock-forward, calmer accents",
        "segments" to "Each item in a bordered cell",
        "mono" to "Monospace, low-key",
        "two-rows" to "Denser palette variant",
        "frosted" to "Light translucent band (no true blur on Portal)",
        "chips" to "Each item a tinted pill",
        "aurora" to "Bold purple→teal gradient bar",
        "daylight" to "Clean light theme for bright rooms",
        "hud" to "Sci-fi mono, cyan/amber",
        "sunset" to "Warm amber→magenta gradient",
        "ocean" to "Cool teal→indigo gradient",
        "graphite" to "Quiet greyscale, monospace",
        "oled" to "True black, OLED-friendly",
        "paper" to "Warm e-ink paper look",
        "iconic" to "Icon before each item, no lines",
        "hicontrast" to "Large bold text, AA-safe colours",
        "sky" to "Sun-driven sky gradient (matches Immortal)",
    )
    Column {
        Prefs.STRIP_STYLES.forEach { (id, label) ->
            val active = id == selected
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) accent.copy(alpha = 0.16f) else PANEL2)
                    .border(if (active) 2.dp else 0.dp, if (active) accent else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onSelect(id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = TEXT, fontSize = 16.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    Text(hints[id] ?: "", color = MUTED, fontSize = 12.sp)
                }
                if (active) Text("✓", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TickerStylePicker(selected: String, accent: Color, onSelect: (String) -> Unit) {
    // The ticker shares the strip's style catalogue; hints describe how each reads on a scrolling line
    // (per-item strip chrome like chips/glyphs/dividers doesn't apply — only the bar fill + text do).
    val hints = mapOf(
        "default" to "Dark bar, light text — the original look",
        "accented" to "Dark bar, light text",
        "three-zones" to "Near-black bar",
        "segments" to "Dark bar",
        "mono" to "Monospace, low-key",
        "two-rows" to "Dark bar",
        "frosted" to "Light translucent band",
        "chips" to "Near-black bar, dim text",
        "aurora" to "Purple→teal gradient bar",
        "daylight" to "Clean light theme for bright rooms",
        "hud" to "Sci-fi mono, cyan",
        "sunset" to "Warm amber→magenta gradient",
        "ocean" to "Cool teal→indigo gradient",
        "graphite" to "Greyscale gradient, monospace",
        "oled" to "True black, OLED-friendly",
        "paper" to "Warm e-ink paper look",
        "iconic" to "Dark bar, light text",
        "hicontrast" to "Large bold text on black",
        "sky" to "Sun-driven sky gradient (matches Immortal)",
    )
    Column {
        Prefs.STRIP_STYLES.forEach { (id, label) ->
            val active = id == selected
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) accent.copy(alpha = 0.16f) else PANEL2)
                    .border(if (active) 2.dp else 0.dp, if (active) accent else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onSelect(id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = TEXT, fontSize = 16.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    Text(hints[id] ?: "", color = MUTED, fontSize = 12.sp)
                }
                if (active) Text("✓", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NavStylePicker(selected: String, accent: Color, onSelect: (String) -> Unit) {
    val hints = mapOf(
        "pill" to "Native-feeling all-rounder",
        "ghost" to "Invisible, for ambient/clock mode",
        "squares" to "Coarse touch from a distance",
        "underline" to "When buttons double as screen switchers",
        "glass" to "Best over other content",
        "label" to "Shared use — names under each icon",
        "color" to "Fastest muscle memory",
        "dot" to "Most minimal, full-screen ambient",
    )
    Column {
        Prefs.NAV_STYLES.forEach { (id, label) ->
            val active = id == selected
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) accent.copy(alpha = 0.16f) else PANEL2)
                    .border(if (active) 2.dp else 0.dp, if (active) accent else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onSelect(id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = TEXT, fontSize = 16.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    Text(hints[id] ?: "", color = MUTED, fontSize = 12.sp)
                }
                if (active) Text("✓", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
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
