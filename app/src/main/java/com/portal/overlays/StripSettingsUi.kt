package com.portal.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Panel = Color(0xFF15181E)
private val Panel2 = Color(0xFF1B1F27)
private val TextMain = Color(0xFFF2F4F7)
private val Muted = Color(0xFF9AA1AD)

@Composable
fun StripAccentAndPinsSection(prefs: Prefs, accent: Color, refresh: () -> Unit) {
    val context = LocalContext.current
    var follow by remember { mutableStateOf(prefs.stripAccentFollowApp) }
    Toggle("Accent follows foreground app", follow, accent) {
        follow = it
        prefs.stripAccentFollowApp = it
        refresh()
    }
    Text(
        "Subtly tints the strip from the active app's icon colour.",
        color = Muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(14.dp))
    PinnedAppsSection(context, prefs, accent, refresh)
}

@Composable
private fun PinnedAppsSection(
    context: android.content.Context,
    prefs: Prefs,
    accent: Color,
    refresh: () -> Unit,
) {
    var pinned by remember { mutableStateOf(prefs.stripPinnedAppList()) }
    var showIcons by remember { mutableStateOf(prefs.stripShowPinnedIcons) }
    var pickerOpen by remember { mutableStateOf(false) }
    Text("Pinned apps", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "App icons appear on the right side of the bottom bar — tap to launch, long-press for " +
            "App info / Unpin / Move. Order here is left → right on the strip.",
        color = Muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Toggle("Show pinned icons on strip", showIcons, accent) {
        showIcons = it
        prefs.stripShowPinnedIcons = it
        refresh()
    }
    if (pinned.isEmpty()) {
        Text("No pinned apps yet.", color = Muted, fontSize = 14.sp)
    } else {
        pinned.forEachIndexed { index, pkg ->
            PinnedAppRow(
                pkg = pkg,
                index = index,
                count = pinned.size,
                accent = accent,
                onNudge = { delta ->
                    val moved = prefs.moveStripPinnedApp(pkg, delta)
                    if (moved) {
                        pinned = prefs.stripPinnedAppList()
                        refresh()
                    }
                    moved
                },
                onRemove = {
                    pinned = pinned.filterNot { it == pkg }
                    prefs.setStripPinnedAppList(pinned)
                    refresh()
                },
            )
            Spacer(Modifier.height(6.dp))
        }
    }
    if (pinned.size < 8) {
        Ghost("Add pinned app", Modifier.fillMaxWidth()) { pickerOpen = true }
    }
    if (pickerOpen) {
        AppPickerDialog(
            accent = accent,
            exclude = pinned.toSet(),
            onDismiss = { pickerOpen = false },
            onPick = { entry ->
                pinned = (pinned + entry.packageName).distinct().take(8)
                prefs.setStripPinnedAppList(pinned)
                pickerOpen = false
                refresh()
            },
        )
    }
}

@Composable
private fun PinnedAppRow(
    pkg: String,
    index: Int,
    count: Int,
    accent: Color,
    onNudge: (delta: Int) -> Boolean,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val label =
        remember(pkg) {
            StripLaunch.installedLaunchables(context).find { it.packageName == pkg }?.label ?: pkg
        }
    val rowHeight = 56.dp
    var dragAccum by remember(pkg) { mutableStateOf(0f) }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel2)
            .pointerInput(pkg) {
                val threshold = with(density) { rowHeight.toPx() * 0.55f }
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragAccum = 0f },
                    onDragEnd = { dragAccum = 0f },
                    onDragCancel = { dragAccum = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        dragAccum += amount.y
                        while (dragAccum > threshold) {
                            if (!onNudge(+1)) break
                            dragAccum -= threshold
                        }
                        while (dragAccum < -threshold) {
                            if (!onNudge(-1)) break
                            dragAccum += threshold
                        }
                    },
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "≡",
            color = Muted,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(label, color = TextMain, fontSize = 16.sp)
            Text(pkg, color = Muted, fontSize = 12.sp)
        }
        Text(
            "↑",
            color = if (index > 0) accent else Muted.copy(alpha = 0.35f),
            fontSize = 18.sp,
            modifier =
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = index > 0) {
                    onNudge(-1)
                }.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            "↓",
            color = if (index < count - 1) accent else Muted.copy(alpha = 0.35f),
            fontSize = 18.sp,
            modifier =
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = index < count - 1) {
                    onNudge(+1)
                }.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            "Remove",
            color = Color(0xFFE5484D),
            fontSize = 14.sp,
            modifier =
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRemove() }.padding(8.dp),
        )
    }
}

@Composable
fun StripTapActionsSection(prefs: Prefs, accent: Color, refresh: () -> Unit) {
    val context = LocalContext.current
    Text("Strip tap actions", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Choose what opens when you tap a strip segment. Defaults: clock → Immortal clock, " +
            "weather → Immortal home, foreground app → app menu (or pinned apps on Home).",
        color = Muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    StripLaunch.Segment.entries.forEach { segment ->
        var action by remember(segment.prefKey) { mutableStateOf(StripLaunch.actionFor(prefs, segment)) }
        var pickerOpen by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Panel2)
                .clickable { pickerOpen = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(segment.label, color = TextMain, fontSize = 16.sp)
                Text(StripLaunch.labelFor(context, action), color = Muted, fontSize = 13.sp)
            }
            Text("Change", color = accent, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        if (pickerOpen) {
            StripActionPickerDialog(
                accent = accent,
                current = action,
                onDismiss = { pickerOpen = false },
                onPick = { picked ->
                    action = picked
                    prefs.setStripSegmentAction(segment.prefKey, picked)
                    pickerOpen = false
                    refresh()
                },
            )
        }
    }
}

@Composable
private fun StripActionPickerDialog(
    accent: Color,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var appPicker by remember { mutableStateOf(false) }
    ActionPickerScaffold(accent, "Strip tap action", onDismiss) {
        StripLaunch.PRESETS.forEach { preset ->
            PickerRow(preset.label, current == preset.id, accent) {
                onPick(preset.id)
            }
        }
        PickerRow("Choose any installed app…", current.startsWith("app:"), accent) { appPicker = true }
        if (appPicker) {
            AppPickerDialog(
                accent = accent,
                exclude = emptySet(),
                onDismiss = { appPicker = false },
                onPick = { entry -> onPick("app:${entry.packageName}") },
            )
        }
    }
}

@Composable
private fun AppPickerDialog(
    accent: Color,
    exclude: Set<String>,
    onDismiss: () -> Unit,
    onPick: (StripLaunch.AppEntry) -> Unit,
) {
    val context = LocalContext.current
    val apps =
        remember {
            StripLaunch.installedLaunchables(context).filter { it.packageName !in exclude }
        }
    var filter by remember { mutableStateOf("") }
    val shown =
        remember(filter, apps) {
            val q = filter.trim().lowercase()
            if (q.isBlank()) apps
            else apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
    ActionPickerScaffold(accent, "Choose app", onDismiss) {
        Field("Search apps", filter, "Type a name…") { filter = it }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
            shown.take(80).forEach { entry ->
                PickerRow("${entry.label}\n${entry.packageName}", false, accent) { onPick(entry) }
                Spacer(Modifier.height(4.dp))
            }
            if (shown.isEmpty()) {
                Text("No matching apps.", color = Muted, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ActionPickerScaffold(
    accent: Color,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxScrim(onDismiss) {
        Column(
            Modifier.fillMaxWidth(0.72f)
                .clip(RoundedCornerShape(18.dp))
                .background(Panel)
                .padding(22.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Close", color = Muted, fontSize = 15.sp, modifier = Modifier.clickable { onDismiss() })
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun BoxScrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC070809))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onDismiss()
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {},
        ) {
            content()
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Panel2)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextMain, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = accent, fontSize = 16.sp)
    }
}
