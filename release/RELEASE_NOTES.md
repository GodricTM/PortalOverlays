# Portal Overlays v1.5

A big Now Playing upgrade, a hide/restore status strip, overlays that come back on their own, and a
fixed screenshot button — plus the dedicated Status strip / Ticker / Settings / Now Playing pages.

## What's in this release

- **Fullscreen Now Playing**: live progress bar with elapsed time and track length, the album name,
  the source-app logo (e.g. Spotify), and a screen-off button next to the close control. The
  progress/length can be toggled on the Now Playing page.
- **Status strip hide / restore**: a small chevron collapses the strip — and the ticker that rides
  with it — to a tiny handle so you can read what's underneath, then tap the handle to bring it back.
- **Overlays auto re-arm**: reopening the app restores the overlays automatically. No more toggling
  "running" off and on after the Portal kills the background service.
- **Screenshot fix**: the floating Screenshot button no longer crashes the app, and screenshots now
  save to the gallery (Pictures/Screenshots) instead of failing with "Couldn't write the file".
- **Dedicated pages**: Status strip, Ticker, Settings, and Now Playing each have their own page in
  the control deck, with live ticker source presets and a foreground-app indicator on the strip.

## Install

```bash
npx -y metavr app install -r PortalOverlays-v1.5-release.apk
npx -y metavr app launch com.portal.overlays
```

After an app update, Android may disable the accessibility service. If the floating nav buttons stop
working, re-run `enable_portal_permissions.bat` from the source repo.

## Permissions

Grant once over ADB, or re-run after reinstalling/updating (now also grants storage so screenshots
reach the gallery on pre-Android 10 Portals):

```powershell
.\enable_portal_permissions.bat
```

## License

MIT.
