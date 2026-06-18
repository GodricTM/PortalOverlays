# Changelog

All notable changes to Portal Overlays are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
