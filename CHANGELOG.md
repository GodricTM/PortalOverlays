# Changelog

All notable changes to Portal Overlays are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.8] - 2026-07-01

### Added
- **Screensaver remote control** — on the idle screen: tap album art to skip, swipe left/right for
  prev/next, double-tap the clock to wake. Uses the active media session (same as the full card).
- **Screensaver gesture hint** — a one-time toast on first real idle: "Swipe art · double-tap clock"
  (Preview in the app does not consume the hint).
- **Track history** — the full now-playing card logs the last ~20 tracks locally (title, artist,
  timestamp, cached cover art). Tap **History** on the full card for a scrollable list with album
  thumbnails and relative timestamps; no cloud.
- **Album-art crossfade** — cover art fades between tracks instead of hard-swapping.
- **Open-Meteo strip extras** — optional status-strip lines for wind speed, UV index, and severe
  weather alert (WMO thunder/hail codes), from the same poll — no new API key.
- **Finance ticker watchlists** — custom symbols in the feed URL:
  `finance:crypto:BTC,ETH,SOL` or `finance:stocks:AAPL,TSLA,NVDA`.
- **Settings search** — full-panel search in the control deck: ~40 section-level entries, ranked
  results with tab badge / description / keyword chips, browse-by-tab catalog, popular quick chips
  in the rail, and inline top matches while typing. Tap a result to jump to that tab.
- **Seek-jump visualizer pulse** — when playback position jumps >5s (skip/seek from another device),
  the visualizer pulses once — no microphone.
- **Vector transport buttons** — prev / play-pause / next on the full card, edge bar, and strip dock
  use crisp vector icons instead of text glyphs.
- **Strip tap actions** — tap the foreground-app line for Open / App info / Force stop; tap the ntfy
  line for the last message preview.
- **Widget safe zones & soft collision** — draggable widgets nudge away from the strip, ticker, edge
  now-playing, and top pills; widgets also nudge apart when overlapping.
- **Post-boot nav warning** — banner when accessibility could not be restored after reboot and nav
  features are wanted.
- **Labs section** — experimental **React to live audio** moved from Now Playing / Screensaver tabs
  into Settings → Labs (off by default).

### Changed
- **Control deck layout** — each settings tab now fills the full content pane (compact status bar +
  scrollable body). The animated hero banner was removed to give settings more room.
- Control-panel hints on Screensaver, Now Playing, Strip, and Ticker tabs document the new gestures,
  history, weather extras, and finance URL formats.

### Fixed
- **Now-playing cover art** — album art no longer disappears when opening the full card or rebuilding
  the dock; art is bound per view and only crossfades when the track or source actually changes (no
  more constant refresh flicker).

## [1.7] - 2026-06-27

### Added
- **Lock nav position** (issue #2) - a **Lock position** toggle on the Navigation page pins the
  floating nav cluster where you've placed it so it can't be dragged by accident. The buttons keep
  working; turn the lock off to move it again.
- **New high-quality Spectrum visualizer** - a mirrored bar spectrum with a reflection joins Waves /
  Rings / Constellation / Prism, on both the full now-playing card and the screensaver. The visualizer
  animates whenever music is playing (it knows from the media session), so it's lively and smooth.
- **Live-audio reactor (experimental, off by default)** - an optional **React to live audio** toggle
  drives the visualizer from the microphone (it hears the music in the room) via a small Goertzel
  filter bank. The clean route — tapping the audio output mix with `Visualizer` — is blocked on Portal
  (it needs a privileged permission a sideloaded app can't hold), and the mic is shared with Portal's
  always-on assistant, so reaction can be laggy. It's therefore opt-in and clearly labelled; the
  smooth animated visualizer is the default.
- **Fullscreen "Cover" screensaver** - the screensaver's now-playing can now be a **Cover** layout:
  fullscreen album art with the chosen music visualizer filling the screen behind it, the source-app
  icon, track / artist and progress - the same rich look as the in-app full card, on the idle screen.
  Pick **Card** (compact bar over your background) or **Cover** on the Screensaver tab, plus the
  visualizer style and the live-audio toggle. The now-playing card now also shows the **source-app
  icon** (e.g. the Spotify logo) next to the track.
- **Screensaver that survives the screen saver** - a floating overlay is hidden the instant a screen
  saver / Daydream starts, because Android composites the saver on top of every app overlay (the
  highest window type a sideloaded app may use). Portal Overlays now ships its own **screensaver**
  (a `DreamService`) that re-hosts the content people most want on the idle screen: the **Now Playing
  card** with cover art, track/artist and the **bouncing-bars equaliser**, plus a large **clock/date**
  and **battery** readout. Because it *is* the screen saver, it stays on-screen the whole time.
  New **Screensaver** page lets you choose the background — **Black**, a **Photo** you pick, or a
  **Web page** URL (e.g. an Immich Kiosk feed, so you keep your photo source behind the now-playing
  card) — toggle the clock / battery / now-playing / keep-bright options, and set it as the device
  screensaver (system picker button, with an `adb` fallback for Portals that hide the picker). The
  now-playing card reads media sessions directly through the existing notification-listener access and
  filters out the Portal's idle Alexa session, so it only appears while real audio is actually playing.
- **`set_screensaver.bat` helper** - a one-step PC script (mirroring `enable_portal_permissions`) that
  auto-detects the Portal, registers the screensaver, allows the notification listener the now-playing
  card needs, and - if the Immortal launcher is installed - stops it from reclaiming the screensaver
  slot on boot / return-home (it re-asserts its own otherwise). `-Revert` hands the screensaver back;
  `-KeepImmortal` registers without touching Immortal. The battery line auto-hides on mains-powered
  Portals (e.g. the Portal+) that report no battery, rather than showing a misleading "0%".
- **Breaking-news alerts** - urgent ntfy messages now get a full-attention treatment instead of a
  normal banner: a flashing red **BREAKING NEWS** popup with a strobing eyebrow and pulsing dot, a
  chime + haptics, and a spoken read-out of the headline. A message qualifies as "breaking" when it
  arrives with ntfy **priority 5** (`-H "Priority: urgent"`) or a **`breaking`** / **`urgent`** tag
  (`-H "Tags: breaking"`). New **Breaking news** section on the Notifications page toggles the popups,
  toggles the spoken read-out, sets the on-screen time, shows a ready-to-paste `curl` example, and has
  a **Test breaking news** button.
- **Spoken alerts (TTS)** - the headline is read aloud through the device text-to-speech engine,
  preferring the sideloaded **Portal TTS engine** (`com.k2fsa.sherpa.onnx.tts.engine`, Sherpa-ONNX)
  when installed and falling back to any generic engine. Portals with **no** TTS engine installed are
  fully supported: the popup still flashes and chimes, just silently, and the settings page flags that
  no speech engine was detected.

## [1.6] - 2026-06-26

### Added
- **Self-hosted ntfy support** - the Notifications page now has an "ntfy server" field, so you can
  point Portal Overlays at your own ntfy instance (e.g. `https://ntfy.example.com`) instead of the
  public `ntfy.sh` and keep messages on your own server. An optional access token (sent as a Bearer
  token) lets read-protected / private topics work, and plain-`http://` LAN servers are allowed.
  Changing the server, token, or topic now reconnects the listener live. Defaults are unchanged
  (public `ntfy.sh`, no token), so existing setups keep working.
- **Now Playing dock shapes** - the docked Now Playing widget can now be a **Strip** (a floating bar
  with cover art, title, artist and a slim live progress bar plus a play/pause button) or an **Edge
  bar** (a full-width band pinned to the top or bottom of the screen that comes and goes with
  playback), in addition to the original cover-art **Bubble**. Pick the shape under "Docked widget" on
  the Now Playing page; tapping any of them still opens the full card.
- **Edge bar controls & extras** - the Now Playing edge bar shows the source-app logo, a mini
  equaliser that pulses while playing, previous / play-pause / next transport buttons, and (with
  "Show progress & track length" on) elapsed / total time around the progress bar.
- **Status strip network speed no longer jitters** - the live network-speed readout now sits in a
  fixed-width slot, so its per-second updates ("0 B/s" -> "12.3 MB/s") stop shoving the strip items
  after it left and right.
- **Bubble styles & sizes** - the cover-art bubble now has four looks (Rounded, Circle, Square,
  Minimal) and three sizes (Small, Medium, Large), selectable on the Now Playing page when the dock
  is set to Bubble.
- **Auto-hide Now Playing when idle** - the docked Now Playing widget shows only while audio is
  actually playing and fades out as soon as you pause, stop, or close the player, so it no longer
  sits on screen empty. Controlled by "Hide when nothing is playing" (on by default). It ignores
  always-on / stale media sessions (the Portal's built-in Alexa runtime, which idles with empty
  metadata, and players like Spotify that linger in a paused state after they're closed) so they no
  longer keep the widget pinned on screen.
- **Status strip styles** - the status strip now has 19 selectable looks, chosen from a new "Style"
  picker on the Status strip page: Dense Dark (the original), Accented, Three Zones, Segments,
  Minimal Mono, Two Rows, Frosted Glass, Tinted Chips, Aurora, Daylight, HUD Tactical, Sunset, Ocean,
  Mono Graphite, OLED Black, E-ink Paper, Iconic, High Contrast, and Sky (a dynamic sunrise/sunset
  gradient). Each style drives the bar fill (solid or gradient), text and per-item accent colours,
  separators (lines / tinted pills / bordered cells), monospace, and Wi-Fi bar colours. The default
  is unchanged (Dense Dark), and switching rebuilds the strip live. Note: Frosted Glass is a
  translucent band rather than a true backdrop blur (blur needs API 31+; the Portal runs 28-29),
  and Three Zones / Two Rows are palette variants on the single-row layout.
- **Agenda / calendar widget** - a draggable card lists the next few events from a public iCalendar
  (`.ics` / webcal) feed, and the status strip can show a single next-event line ("Standup · in
  25m"). Set the feed URL on the Settings page.
- **Finance ticker sources** - the bottom ticker can now scroll live crypto prices (CoinGecko) or
  stock quotes (Stooq) alongside the existing RSS / Atom / JSON news feeds.
- **Live GitHub release counter** - the About tab now shows the current GitHub release download count
  for the installed version, so users can see a live public usage signal from the release asset.

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
