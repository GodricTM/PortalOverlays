# Portal Overlays v1.8 release

The screensaver becomes a remote control, the now-playing card gains track history and smoother art,
and the control deck gets a full settings search — plus status-strip weather extras, finance
watchlists, and a batch of Now Playing and strip polish.

## What's in this release

- **Screensaver as a remote** — on the idle/dream screen: **tap** album art to skip, **swipe**
  left/right for prev/next, **double-tap** the clock to wake the device. A one-time hint toast
  ("Swipe art · double-tap clock") shows the first time the real screen saver starts.
- **Track history** — the full now-playing card logs the last ~20 tracks locally (cover thumbnail,
  title, artist, relative time). Tap **History** for the list; stored on-device, no cloud.
- **Smoother album art** — cover art **crossfades** between tracks on the full card, dock, and
  screensaver, and stays stable during playback (no refresh flicker).
- **Settings search** — a full-panel search in the control deck: ~40 section-level entries with
  ranked results (tab badge, description, keyword chips), a browse-by-tab catalog, popular quick
  chips, and inline top matches while typing. Tap a result to jump straight to that tab.
- **Status-strip weather extras** — optional wind speed, UV index, and severe-weather alert lines
  from the same keyless Open-Meteo poll (no new API key).
- **Finance ticker watchlists** — custom symbols in the ticker feed URL:
  `finance:crypto:BTC,ETH,SOL` or `finance:stocks:AAPL,TSLA,NVDA`.
- **Now Playing polish** — crisp **vector transport buttons** (prev / play-pause / next) on the full
  card, edge bar, and strip dock, and a **seek-jump visualizer pulse** when playback position jumps
  more than 5s (e.g. a skip from your phone) — no microphone.
- **Strip tap actions** — tap the foreground-app line for Open / App info / Force stop; tap the ntfy
  line for the last message preview.
- **Widget safe zones** — draggable widgets nudge away from the strip, ticker, and edge now-playing,
  and nudge apart when they overlap.
- **Post-boot nav warning** — a banner if the accessibility service couldn't be restored after a
  reboot and nav features are enabled.
- **Labs** — the experimental **React to live audio** option moved to **Settings → Labs** (off by
  default). See `docs/portal-audio-capture.md` for why true output-mix capture isn't viable on Portal.

## Install

```bash
npx -y metavr app install -r PortalOverlays-v1.8-release.apk
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
