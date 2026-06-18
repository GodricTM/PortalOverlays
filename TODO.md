# Portal Overlays — TODO

Working tracker for releases and feature work. Newest at top.

## Status snapshot (2026-06-18)
- **v1.1** — shipped ✅ (Portal TV banner, crash fix, permission onboarding)
- **v1.2** — shipped ✅ (in-app updater: silent daemon + one-tap fallback)
- **v1.3** — shipped ✅ (status-strip cluster + lock button)
- **v1.4** — shipped ✅ (nav styles, ticker, app-switcher fallback, weather extras, custom sounds,
  keyboard/ghost-trail fixes)

---

## v1.4 — nav styles + behaviours  (released 2026-06-18)

versionCode 5 / versionName 1.4. Signed APK: `PortalOverlays-v1.4-release.apk`.

Built:
- [x] 8 nav button styles + press-state dimming (Prefs.navStyle; live picker in Nav tab)
- [x] Recents app-switcher fallback for devices with no Overview (Portal Mini)
- [x] Ticker overlay — RSS/Atom or JSON feed URL, self-animated marquee (TickerClient)
- [x] Week number, rain-in-next-hour, sunset/sunrise countdown (Open-Meteo minutely_15 + daily)
- [x] Custom per-kind alert sounds via system ringtone picker; now-playing start compact/expanded
- [x] Keyboard fix — windowSoftInputMode + explicit IME show on field focus

Release checks:
- [x] `version.json` bumped to code 5 / 1.4
- [x] `gh release create v1.4` with asset `PortalOverlays-v1.4-release.apk`
- [x] Raw `version.json` + apkUrl resolve (HTTP 200)
- [ ] **On-device test on Portal Mini** — Recents → app switcher works; each of the 8 nav styles
      renders + presses; ticker scrolls real feed; rain/sun/week show; ringtone picker opens;
      weather-city keyboard appears

### Open question carried from before
- Default nav style chosen = **Pill segments** ("safest all-rounder"); change in-app if preferred.

---

## v1.3 — status cluster + lock  (shipped 2026-06-18)

Code complete, compiles (debug + release), installed on the Portal+ for testing.

Built:
- [x] Streaming indicator — animated dot while audio/video plays (reads media session)
- [x] VPN status dot — green/red, generic "tunnel active" (not WireGuard-specific)
- [x] Wi-Fi signal bars — reflect real RSSI; tap shows device IP
- [x] Subtle separator lines between each strip element
- [x] Lock button — nav-cluster button, locks display via accessibility (no device admin)
- [x] versionCode 4 / versionName 1.3; CHANGELOG entry

Release:
- [x] Rebuild release APK at versionCode 4 (stamped 4, signed)
- [x] Update `version.json` → code 4 / 1.3, commit, push
- [x] `gh release create v1.3` with asset `PortalOverlays-v1.3-release.apk`
- [x] Verify raw `version.json` + apkUrl resolve (HTTP 200)
- [ ] **On-device visual verification** (deferred; build is installed on the Portal+)
      — confirm strip renders with separators + new dots/bars, Wi-Fi tap shows IP, lock button locks

---

## v1.5 — ideas  (planned)

- [ ] **Ticker via ntfy topic** as an alternative source (currently RSS/Atom or JSON URL only)
- [ ] **Custom sounds per ntfy source/kind** (beyond the doorbell/timer/reminder alert kinds)
- [ ] **Per-app split-screen pairing** if Portal multi-window proves workable on any model

---

## Decisions already made
- Ticker source = user RSS/Atom or JSON URL; nothing shows until set (no-fake-data). ntfy-topic
  source deferred to v1.5.
- Custom sounds = system ringtone picker per alert kind (no bundled `res/raw` assets to license).
- Default nav style = Pill segments.
- Recents on no-Overview Portals (Mini) = fall back to an installed-apps switcher grid.
- VPN dot = generic "VPN active" (Android won't reveal which app owns the tunnel without root)

## Infra / gotchas
- Releases cut from `main`; raw `main/version.json` drives the in-app updater — the release
  asset filename MUST match `apkUrl` (e.g. `PortalOverlays-vX.Y-release.apk`)
- Silent-install daemon (`installd.sh`) is started by `enable_portal_permissions`; dies on reboot
  (re-run the helper). App falls back to one-tap PackageInstaller meanwhile.
- `*.sh` pinned to LF via `.gitattributes` (CRLF breaks `/system/bin/sh` on the device)
- Windows ADB kept dropping the Portal after reboot → patched driver INF in
  `Portal+/portal_usb_driver/` adds `VID_2EC6&PID_1800`; install into the driver store so it sticks
- Keystore / `release-signing.properties` / `local.properties` stay gitignored
