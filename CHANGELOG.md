# Changelog

All notable changes to Portal Overlays are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.5] - 2026-06-22

### Added
- **Fullscreen Now Playing progress** - the full card now shows a playback progress bar with the
  elapsed time and total track length, updated live each second. Toggle it on the Now Playing page
  ("Show progress & track length").
- **Album and source-app logo on Now Playing** - the full card now shows the album name and the
  playing app's icon (e.g. the Spotify logo) next to the source label.
- **Screen-off button on Now Playing** - a lock button sits next to the close (×) on the full card;
  it turns the display off via the accessibility service.
- **Status strip hide / restore** - a small chevron on the status strip collapses it (and the ticker
  that rides with it) to a tiny handle, so you can read whatever is underneath, then tap the handle
  to bring it back.
- **Overlays auto re-arm** - reopening the app now brings the overlays back automatically; you no
  longer have to toggle "running" off and on after the Portal kills the background service.
- **About credit** - the About page now credits the author (Made by GodricTM).
- **Storage grant for screenshots** - `enable_portal_permissions` now grants `WRITE_EXTERNAL_STORAGE`
  so screenshots land in the gallery on pre-Android 10 Portals.
- **Dedicated strip and ticker pages** - Status strip and Ticker now have their own pages in the
  control deck instead of being buried inside Notifications.
- **Foreground context in the status strip** - the strip can now show the active app name, or
  "Home" / "Portal UI" when the launcher or system UI is in front. This is driven by the
  accessibility service and can be toggled in the Status strip tab.
- **Strip-mounted nav buttons** - Back / Home / Recents can now live on the right side of the
  status strip as an option, instead of only in the floating nav cluster.
- **Ticker presets** - the Ticker page now includes live source shortcuts, with BBC News seeded as
  the default source so the ticker works immediately after setup. The preset list has been expanded
  to include BBC, AP, and NPR live feeds.
- **Settings page** - weather location and weather units now live on their own page, so they can be
  changed without enabling the Weather widget first.
- **Now Playing visualizer styles** - Waves, Rings, Constellation, and Prism backgrounds can be
  selected from the dedicated Now Playing page.
- **Now Playing layout styles** - the full-screen player now supports Sidecar, Centered, and Poster
  layouts, including larger art-cover treatments and centered text variants.
- **Now Playing page** - the music controls now live on their own dedicated tab; Widgets now keeps
  the non-music widgets only.
- **Ticker placement cleanup** - top ticker mode now pins to the top edge; bottom ticker mode sits
  above the bottom status strip when that strip is enabled on the bottom edge.
- **Ticker-only updates** - ticker URL, source preset, position, and speed changes now restart just
  the ticker instead of refreshing every overlay window.
- **Strip nav button sizing** - the optional Back / Home / Recents buttons in the status strip now
  use a larger tap target and wider pills so they are easier to read and press.
- **Now Playing drag cleanup** - the mini Now Playing card no longer traps touch events on the album
  art, so dragging the widget is more reliable.
- **Now Playing tab** - the music controls now live on their own dedicated page instead of sharing
  the Widgets page.
- **Now Playing layout separation** - Centered and Poster now render with visibly different
  compositions; Poster uses a side-by-side poster-and-copy layout instead of the same stack.
- **Status strip defaults** - fresh installs now start with the bottom strip on, and the requested
  live strip items enabled by default.
- **Startup defaults** - the Clock widget is off by default, Now Playing is on by default, and the
  status strip stays on by default.

### Fixed
- **Screenshot button no longer crashes** - the floating Screenshot button captured the frame on a
  background thread where a native buffer overrun (stride padding on the Portal GPU) killed the whole
  app. The capture is now bounded to the buffer it actually has, so it can't overrun.
- **Screenshots save to the gallery** - on the Portal's Android 9 the app process never gets the
  shared-storage gid (its targetSdk is 29), so a direct file write failed with "Couldn't write the
  file". Saving now goes through MediaStore (written by the media provider), landing in
  Pictures/Screenshots, with an app-storage fallback so a shot is never lost.

## [1.4] - 2026-06-18

### Added
- **8 navigation button styles** — Pill segments (default), Underline indicator, Ghost pill, Floating
  squares, Dark glass, Icon + label, Colour-coded, and Dot indicator. Pick one in the Navigation
  tab; applies live. Buttons now dim while pressed.
- **App switcher fallback** — on Portals with no system Overview screen (e.g. Portal Mini), the Recents
  button opens a grid of installed apps to switch to, instead of doing nothing. (On Portal+ the
  Recents button opens Portal's native overview.)
- **Ticker overlay** — a thin scrolling strip along the bottom or top. Real data only: point it at an
  RSS/Atom or JSON feed URL. Adjustable scroll speed.
- **Status-strip additions** — ISO week number, rain in the next hour ("🌧 in 40min", from Open-Meteo
  15-minute precipitation), and time until the next sunset/sunrise ("☀ 3h12m").
- **Custom alert sounds** — choose a per-kind notification tone (doorbell / timer / reminder) from the
  system ringtone picker.
- **Now Playing default** — choose whether it starts as a small bubble or opens the full card when
  playback begins.

### Fixed
- **Weather location keyboard** — the soft keyboard now reliably appears when tapping a text field
  (e.g. the Weather city) on Portal's older Android (`adjustResize` + explicit IME show on focus).
- **Overlay ghost trails** — moving an overlay (dragging a widget, or the keyboard panning the HUD)
  no longer smears ghost copies across the screen that stole touches. Overlays no longer pan for the
  IME, and the off-screen-layout flag that defeated damage clearing was removed.
- **Recents on Portal Mini** — the cluster no longer shows a misleading "enable accessibility" message
  when the service is already on; it distinguishes an unsupported system action and falls back to the
  app switcher.

## [1.3] - 2026-06-18

### Added
- **Status strip upgrades** — each element is now separated by a subtle divider, plus three new
  live indicators:
  - **Streaming indicator** — an animated dot that pulses while audio/video is playing (reads the
    active media session).
  - **VPN status dot** — green when a VPN tunnel is active, red when not. (Generic: Android doesn't
    reveal which VPN app owns the tunnel, so it can't be WireGuard-specific.)
  - **Wi-Fi signal** — signal-strength bars reflecting actual quality; tap to show the device IP.
- **Lock button** — an optional nav-cluster button that locks the display immediately (via the
  accessibility service; no device-admin setup needed).

## [1.2] - 2026-06-18

### Added
- **In-app updater.** The "Update available" dialog now downloads and installs the new build
  directly — no more bouncing to a browser. It installs **silently** when the optional shell
  daemon is running, and otherwise falls back to a **one-tap** system installer.
  - `installd.sh` — a shell-user (uid 2000) daemon started over ADB by `enable_portal_permissions`;
    it watches a queue folder and `pm install`s dropped APKs with no dialog. Doesn't survive a
    reboot (re-run the helper to restart it); the app falls back to PackageInstaller meanwhile.
  - `enable_portal_permissions` now also grants `REQUEST_INSTALL_PACKAGES`, starts the daemon, and
    reports whether silent install is live (`-NoDaemon` to skip).
- The update notification now opens the app to update in-app instead of opening GitHub.

## [1.1] - 2026-06-18

### Added
- **Portal TV support.** A proper 320×180 leanback banner (`drawable-xhdpi/banner.png`) plus
  `android:banner` and `touchscreen`/`leanback` feature declarations, so the app now appears
  correctly on Portal TV's home row. Verified compatible across the whole Portal family
  (Portal, Portal+, Portal Mini, Portal Go, Portal TV).
- **First-launch permission walkthrough.** On first run — or any time the "Draw over other apps"
  permission is missing — Overlays now shows an in-app setup card that walks through the overlay,
  accessibility, and notification-access permissions with live status and one-tap buttons to the
  right settings page. Intended for users who didn't run `enable_portal_permissions.bat` from a PC.
- "Enable accessibility (navigation)" quick-setup button in the About tab.

### Fixed
- **Crash when "Draw over other apps" is disabled.** Adding an overlay window without the
  permission threw `BadTokenException` and killed the foreground service (and the app). Every
  overlay is now added through a guarded path: the service stays alive, draws nothing, and surfaces
  a "tap to grant permission" prompt in its notification instead of crashing. This also covers the
  permission being revoked while the service is already running.

## [1.0] - 2026-06-18

### Added
- Initial public release. A floating HUD for sideloaded Meta Portal devices:
  - On-top widgets: clock, weather (Open-Meteo, no API key), battery, sticky note, Now Playing.
  - ntfy.sh push banners and a system-notification mirror (WhatsApp, Messenger, etc.).
  - A configurable status strip and full-attention alert popups.
  - A floating navigation cluster (Back / Home / Recents / Control Center / Screenshot) via an
    accessibility service.
  - A fullscreen Now Playing view with transport controls and a visualizer.
  - Self-updater that checks the GitHub release for newer versions.
- No Google Play Services required.
