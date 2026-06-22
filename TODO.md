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
- [ ] Add more ticker feed presets if we find stable official sources worth keeping
- [ ] Keep tightening overlay spacing and placement for Portal's built-in UI shells
- [ ] Continue visual QA on Portal hardware after each control-surface change

## Notes
- Ticker defaults to BBC News so a fresh install shows live data immediately.
- Bottom ticker placement is intentionally lifted above the bottom status strip when that strip is enabled.
- The strip-mounted nav buttons remain optional and can be left off if the floating nav cluster is preferred.
