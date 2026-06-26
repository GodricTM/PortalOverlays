# Portal Overlays - TODO

Working tracker for release prep and follow-up work. Newest items at top.

## Current status snapshot
- **Widgets page** - core widgets stay on one page; Now Playing lives on its own dedicated tab.
- **Now Playing page** - music controls have a dedicated tab for faster access and clearer separation.
- **Settings page** - weather location and weather units live outside the Weather widget now.
- **Startup defaults** - Clock starts off; Now Playing and the bottom status strip start on.
- **Status strip** - foreground context, weather, network speed, Wi-Fi, week, rain, sunrise/sunset, and optional nav buttons are in place by default.
- **Ticker** - live source presets are available, and the ticker now respects the chosen edge placement.
- **Widget refreshes** - simple on/off changes now use narrower update paths instead of rebuilding every overlay.
- **Strip nav buttons** - the optional Back / Home / Recents buttons have been widened for better touch use.
- **Navigation** - floating Back / Home / Recents and the strip-mounted nav option are already implemented.
- **Notifications** - ntfy banners and mirrored notifications remain available.

## Shipped work
- [x] Agenda / calendar overlay — a new `CalendarClient` polls a public iCal (.ics / webcal) URL
      (keyless, no GMS; Google secret-iCal / Apple / Outlook). Parses VEVENTs with line-unfolding,
      UTC/floating/all-day DTSTART, and a basic RRULE next-occurrence (DAILY/WEEKLY/MONTHLY/YEARLY +
      INTERVAL/UNTIL). Surfaced two ways from one client: a draggable **Agenda widget** (next ~5 events
      with relative times) and a **"Next calendar event" status-strip item**. Settings: Widgets tab
      (Show agenda + Calendar URL) and Status strip tab (Next calendar event toggle).
- [x] Finance ticker — `TickerClient` now recognises `finance:crypto` (CoinGecko keyless simple-price,
      24h change) and `finance:stocks` (Stooq keyless CSV, intraday change), formatted as
      "BTC $64,210 ▲2.3%". Polls once a minute. Surfaced as "Live finance" quick-source buttons on the
      Ticker tab alongside the news presets. Real data only — no fabricated quotes.
- [x] Ticker styles — a Style picker on the Ticker page. The ticker now shares the status strip's
      FULL style catalogue (all 19) by deriving from `stripStyleFor()` — one source of truth, so the
      two never drift. Per-item strip chrome (chips/glyphs/dividers) can't apply to a single scrolling
      line, but the bar fill (solid/gradient), text colour, monospace, bold, and text-scale all carry
      across (e.g. High Contrast gives a taller bold bar; Sky reuses the sun-driven gradient and
      repaints live each tick). Per-style separator glyph between headlines. Changing style (or
      position) rebuilds the ticker in place, preserving the current headline text.
- [x] Status strip "Sky" style — a sun-driven, time-of-day gradient that matches the Immortal
      launcher's Sky background (dawn pinks → midday blue → dusk orange → night). Ports immortal's
      `SkyColors` as ARGB-int `SkyStrip`; driven by today's real sunrise/sunset (now also carried on
      `WeatherClient.Extras`, nominal 06:30/20:00 fallback) and repainted live each 1s tick. 19 styles.
- [x] Status strip styles round 2 — 7 new looks bringing the catalogue to 18: Sunset (amber→magenta
      gradient), Ocean (teal→indigo gradient), Mono Graphite (greyscale gradient), OLED Black
      (true #000), E-ink Paper (warm off-white), Iconic (category glyph before each item, no divider
      lines), and High Contrast (large bold AA-safe text on black). Added a `glyphs`/`textScale`/`bold`
      capability to the StripStyle renderer (monochrome U+FE0E-forced glyphs keyed by segment kind;
      strip height + bottom-ticker offset grow for the large-text style). Picker auto-lists them.
- [x] Status strip styles — 11 selectable looks (Dense Dark, Accented, Three Zones, Segments, Minimal
      Mono, Two Rows, Frosted Glass, Tinted Chips, Aurora, Daylight, HUD Tactical) via a Style picker
      on the Status strip page; default stays Dense Dark, switching rebuilds the strip live
- [x] Fullscreen Now Playing progress bar + elapsed/track length (toggle on the Now Playing page)
- [x] Album name and source-app logo on the fullscreen Now Playing card
- [x] Screen-off (lock display) button next to the Now Playing close control
- [x] Status strip hide / restore handle (takes the ticker with it)
- [x] Overlays auto re-arm when the app is reopened (no more toggle off/on)
- [x] Screenshot button fix — no crash, saves to the gallery via MediaStore on Android 9
- [x] `enable_portal_permissions` grants WRITE_EXTERNAL_STORAGE for screenshots
- [x] Separate control tabs for Widgets, Now Playing, Status strip, Ticker, Settings, Notifications, Navigation, Appearance, and About
- [x] Status strip foreground context label
- [x] Optional Back / Home / Recents buttons inside the bottom strip
- [x] Ticker quick sources and live feed URL entry
- [x] Ticker top-edge and bottom-edge placement rules
- [x] Ticker-only updates for source / position / speed changes
- [x] Multiple full-screen Now Playing visualizers
- [x] Multiple full-card Now Playing layouts
- [x] Dedicated Now Playing tab alongside Widgets
- [x] Dedicated Settings tab for weather location and units
- [x] Wider strip-mounted Back / Home / Recents buttons
- [x] More reliable dragging for the mini Now Playing widget
- [x] Centered and Poster layouts made visually distinct
- [x] Status strip defaults seeded to the requested bottom-edge setup
- [x] Startup defaults flipped so Clock is off and Now Playing is on

## Next up
- [ ] Shuffle / repeat on Now Playing — removed for now: the framework `setShuffleMode`/`setRepeatMode`
      are API 29+, and Spotify exposes no MediaSession custom actions, so there's no public-API way to
      control them on the Portal's Android 9. Revisit via forwarding the media notification's action
      PendingIntents if a reliable mapping is found.
- [ ] Strip styles: remaining idea pool (gradient family, glyphs, high-contrast, e-ink, OLED all
      shipped — see Shipped work). Still open:
      - "Pill cluster" — the whole strip as one rounded floating capsule with inner segment chips
      - Time-of-day reactive: the Sky style now does a full sun-driven background gradient (shipped).
        Still open: a lighter-weight *accent-only* time/weather reactivity (warm at sunset, cool when
        raining) for the other styles, driven by the same weather + sun epochs
      - Accent-follows-app-theme: tint the strip from the foreground app's context colour
      - Subtle animated touches where cheap: pulsing live dot, gentle gradient drift
      - Per-item accent intensity tiers (alert states: energy spike, low battery, rain imminent)
      - Live-state glyphs: drive the weather/sun glyph from actual conditions rather than a static
        category marker (currently the glyph is a fixed per-kind symbol)
- [ ] Strip styles: make Three Zones (centred clock) and Two Rows true structural layouts rather than
      palette variants on the single row, if the demand is there
- [ ] Add more ticker feed presets if we find stable official sources worth keeping
- [ ] Keep tightening overlay spacing and placement for Portal's built-in UI shells
- [ ] Continue visual QA on Portal hardware after each control-surface change

## Notes
- Ticker defaults to BBC News so a fresh install shows live data immediately.
- Bottom ticker placement is intentionally lifted above the bottom status strip when that strip is enabled.
- The strip-mounted nav buttons remain optional and can be left off if the floating nav cluster is preferred.
