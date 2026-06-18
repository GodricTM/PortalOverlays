# Portal Overlays v1.4

Nav styles, ticker, weather extras, custom alert sounds, and Portal-specific fixes for Recents,
keyboard input, and overlay ghost trails.

## What's in this release

- **Eight nav styles**: Pill segments (default), Underline indicator, Ghost pill, Floating squares,
  Dark glass, Icon + label, Colour-coded, and Dot indicator.
- **Recents fallback**: on Portal models with no native Overview screen, Recents opens an installed-app
  switcher grid instead of doing nothing.
- **Ticker overlay**: paste an RSS, Atom, or JSON feed URL and show a thin scrolling strip on the top
  or bottom edge. No placeholder data is shown.
- **Status-strip extras**: ISO week number, rain in the next hour, and time until the next sunset or
  sunrise using Open-Meteo.
- **Custom alert sounds**: choose notification tones for doorbell, timer, and reminder alerts.
- **Now Playing start mode**: start playback overlays as a compact bubble or open the full card.
- **Keyboard fix**: Portal text fields now explicitly raise the soft keyboard on focus.
- **Overlay ghost-trail fix**: moving overlays and keyboard activity no longer smear stale overlay
  frames that steal touches.

## Install

```bash
npx -y metavr app install -r PortalOverlays-v1.4-release.apk
npx -y metavr app launch com.portal.overlays
```

After an app update, Android may disable the accessibility service. If the floating nav buttons stop
working, re-run `enable_portal_permissions.bat` from the source repo.

## Permissions

Grant once over ADB, or re-run after reinstalling/updating:

```powershell
.\enable_portal_permissions.bat
```

## License

MIT.
