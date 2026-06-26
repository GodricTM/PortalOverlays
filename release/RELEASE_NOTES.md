# Portal Overlays v1.6 release

A big Now Playing update — three dock shapes that come and go with playback — plus more status-strip
styles, an agenda/calendar widget, finance ticker sources, and a steadier status strip.

## What's in this release

- **Now Playing dock shapes**: choose how the docked widget looks — a small cover-art **Bubble**
  (four styles: Rounded, Circle, Square, Minimal; three sizes), a floating **Strip** (cover art,
  title, artist, slim progress bar, play/pause), or a full-width **Edge bar** pinned to the top or
  bottom. Tapping any of them still opens the full card.
- **Edge bar controls & extras**: the edge bar shows the source-app logo, a mini equaliser that
  pulses while playing, previous / play-pause / next transport buttons, and (with "Show progress &
  track length" on) elapsed / total time around the progress bar.
- **Auto-hide when idle**: every Now Playing dock now shows only while audio is actually playing and
  fades out the moment you pause, stop, or close the player. It ignores always-on / stale media
  sessions — the Portal's built-in Alexa runtime and players left paused after they're closed — so
  they no longer keep the widget pinned on screen. Toggle with "Hide when nothing is playing".
- **19 status-strip styles**: Dense Dark, Accented, Three Zones, Segments, Minimal Mono, Two Rows,
  Frosted Glass, Tinted Chips, Aurora, Daylight, HUD Tactical, Sunset, Ocean, Mono Graphite, OLED
  Black, E-ink Paper, Iconic, High Contrast, and a dynamic Sky gradient.
- **Agenda / calendar widget**: a draggable card listing the next few events from a public iCalendar
  (`.ics` / webcal) feed, plus an optional next-event line on the status strip.
- **Finance ticker sources**: the bottom ticker can scroll live crypto prices (CoinGecko) or stock
  quotes (Stooq) alongside the existing news feeds.
- **Steadier status strip**: the live network-speed readout now sits in a fixed-width slot, so its
  per-second updates no longer shove the rest of the strip left and right.

## Install

```bash
npx -y metavr app install -r PortalOverlays-v1.6-release.apk
npx -y metavr app launch com.portal.overlays
```

After an app update, Android may disable the accessibility service. If the floating nav buttons stop
working, re-run `enable_portal_permissions.bat` from the source repo.

## Permissions

Grant once over ADB, or re-run after reinstalling/updating (also grants storage so screenshots reach
the gallery on pre-Android 10 Portals):

```powershell
.\enable_portal_permissions.bat
```

## License

MIT.
