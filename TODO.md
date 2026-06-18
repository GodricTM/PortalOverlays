# Portal Overlays — TODO

Working tracker for releases and feature work. Newest at top.

## Status snapshot (2026-06-18)
- **v1.1** — shipped ✅ (Portal TV banner, crash fix, permission onboarding)
- **v1.2** — shipped ✅ (in-app updater: silent daemon + one-tap fallback)
- **v1.3** — shipped ✅ (status-strip cluster + lock button)

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

## v1.4 — nav styles + behaviours  (planned)

- [ ] **8 nav button styles** (tap to feel press states):
      Ghost pill · Pill segments · Floating squares · Underline indicator ·
      Dark glass · Icon + label · Colour-coded · Dot indicator
- [ ] **Compact vs expanded** Now-Playing default (start as bubble vs full card)
- [ ] **Custom sounds per source** — doorbell→doorbell sound, server alert→klaxon
      (bundle in `res/raw`; map per alert kind / ntfy source)
- [ ] **Ticker overlay** — thin scrolling text strip along the bottom; real data only
      (user-supplied RSS/JSON URL or ntfy topic — no placeholder headlines)
- [ ] **Long-press Recents → split screen** — EXPERIMENTAL; Portal multi-window support unverified
      (accessibility `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN`)

### Open decisions for v1.4
- Ticker: default source/format — RSS vs JSON vs ntfy topic
- Custom sounds: which bundled sounds; allow picking a device sound?
- Nav styles: which style is the default

---

## Decisions already made
- VPN dot = generic "VPN active" (Android won't reveal which app owns the tunnel without root)
- Ticker data = user RSS/JSON or ntfy topic (honors no-fake-data)
- Sequencing = v1.3 (status + lock) → v1.4 (nav styles + behaviours)

## Infra / gotchas
- Releases cut from `main`; raw `main/version.json` drives the in-app updater — the release
  asset filename MUST match `apkUrl` (e.g. `PortalOverlays-vX.Y-release.apk`)
- Silent-install daemon (`installd.sh`) is started by `enable_portal_permissions`; dies on reboot
  (re-run the helper). App falls back to one-tap PackageInstaller meanwhile.
- `*.sh` pinned to LF via `.gitattributes` (CRLF breaks `/system/bin/sh` on the device)
- Windows ADB kept dropping the Portal after reboot → patched driver INF in
  `Portal+/portal_usb_driver/` adds `VID_2EC6&PID_1800`; install into the driver store so it sticks
- Keystore / `release-signing.properties` / `local.properties` stay gitignored
