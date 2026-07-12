# Portal Overlays v1.9 release

The bottom bar gets a mini app dock, smarter tap actions, clearer hide/restore, pinned-app reorder,
and an automatic in-app update popup when a new GitHub release is published.

## What's in this release

- **Automatic update popup** — opening the app checks GitHub for a newer build and shows an in-app
  dialog with release notes and **Update now**. **Later** skips that version until something newer
  ships. Toggle **Automatic update prompts** in **About → Updates**.
- **Pinned app icons on the bar** — add up to 8 shortcuts in **Bottom bar → Pinned apps**; their
  icons appear on the **right side of the strip** (before nav / hide). Tap to launch; long-press for
  Open / App info / Move / Unpin. Reorder with drag **≡** or ↑ ↓ — order matches left → right on
  the bar. The active app gets an accent ring. Toggle **Show pinned icons on strip** to hide the dock
  without removing pins.
- **Strip tap actions** — assign a preset or any installed app to each strip segment (clock → Immortal
  clock, weather → Immortal home, alarms, Portal Lyrics, etc.). **Bottom bar → Strip tap actions**.
- **Accent follows foreground app** — optional subtle strip tint sampled from the active app's icon
  colour (off when strip style is Sky).
- **Bottom bar UX** — settings tab renamed from "Status strip" to **Bottom bar**; first-run hint
  explains how to hide it; settings search adds `bottom bar`, `subtitles`, and related keywords.
  **New installs default to top** strip position so subtitles stay clear (existing prefs unchanged).
- **Hide / restore polish** — collapse control is an **eye-off icon** (not ▴/▾ text). When minimized,
  a small **expand chevron** pill appears; **Show bar if hidden** at the top of the Bottom bar tab
  brings the strip back if the pill is gone.

## Install

```bash
npx -y metavr app install -r PortalOverlays-v1.9-release.apk
npx -y metavr app launch com.portal.overlays
```

Stable alias (always latest):

```bash
npx -y metavr app install -r https://github.com/GodricTM/PortalOverlays/releases/latest/download/PortalOverlays.apk
```

After an app update, Android may disable the accessibility service. If the floating nav buttons stop
working, re-run `enable_portal_permissions.bat` from the source repo.

## In-app updates

Portal Overlays checks `version.json` on GitHub (`versionCode` compare). **About → Check for updates**
downloads and installs when a newer build is published. With **Automatic update prompts** enabled, a
popup appears on launch when an update is available. Immortal's app store uses the same `versionCode`
in its catalog entry — bump both when shipping.

## Permissions

Grant once over ADB, or re-run after reinstalling/updating:

```powershell
.\enable_portal_permissions.bat
```

## License

MIT.
