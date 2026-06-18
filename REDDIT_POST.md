# Reddit post — Portal Overlays

**Suggested subreddits:** r/sideload, r/MetaPortal, r/oculus (sideloading threads)
**Post type:** Image gallery (upload the 6 screenshots below in order), or Link post to the GitHub release.

---

## Title

Portal Overlays — a free, open-source floating HUD for sideloaded Meta Portals (on-screen Back / Home / Recents, widgets, notifications). No Google Play Services needed.

---

## Body

I sideload apps on my Meta Portal and the one thing that always drove me nuts is that there's **no on-screen Back / Home / Recents** — once you're inside a sideloaded app you can get stuck, and there's no clean way to close out of recent apps. So I built a little floating HUD to fix that, plus a bunch of glanceable widgets. It's free and open-source (MIT).

**It draws on top of any app — including custom launchers like Immortal.**

### The part I actually use every day: the floating nav cluster 🧭

A small draggable cluster of buttons that float over everything:

- **‹ Back** — go back inside any app
- **⌂ Home** — jump straight back to your launcher
- **▢ Recents** — open the recents view so you can **swipe away / close apps you left running**

Those three alone make a sideloaded Portal genuinely usable — you're never trapped in an app, and you can finally close recents without hunting for a gesture. There are two optional extras too: a **Control Center** pull-down and a **Screenshot** button. Drag the cluster anywhere; it remembers where you put it.

(It works through Android's AccessibilityService — there's a one-time ADB command to enable it, documented in the README.)

### Everything else it does

- **On-top widgets** — clock, weather (Open-Meteo, no API key), battery, a sticky note, and a "Now Playing" mini with artwork
- **Push banners** straight from [ntfy.sh](https://ntfy.sh) — ping your Portal from your phone or a script
- **Notification mirroring** — see your phone/app notifications on the Portal
- **Status strip** — time, date, weather, battery, ntfy state, and live network up/down
- **Fullscreen Now Playing** — big artwork, transport controls, animated visualizer
- **Customizable** — accent color, opacity, corner radius, text scale, strip position; every widget is draggable and remembers its spot
- Widgets stay on-screen in **both landscape and vertical** orientations

### No Google required

It targets the Portal's no-GMS environment — nothing depends on Google Play Services.

### Install

Download the APK from the GitHub release and sideload it with [metavr](https://www.npmjs.com/package/metavr):

```bash
npx -y metavr app install -r PortalOverlays-v1.0-release.apk
npx -y metavr app launch com.portal.overlays
```

Then grant the permissions once over ADB (there's a one-click `enable_portal_permissions.bat` in the repo) and flip on **Overlays running**.

**GitHub (source + APK + full setup guide):** https://github.com/GodricTM/PortalOverlays

Happy to answer questions / take feature requests. It's MIT-licensed, so fork away.

---

## Screenshots to attach (in this order)

1. `screenshots/01_widgets.png` — Widgets on top of the launcher
2. `screenshots/03_navigation.png` — **The floating nav cluster (Back / Home / Recents)** ← lead with this one if the gallery shows a cover
3. `screenshots/02_notifications.png` — Notification mirroring + push banners
4. `screenshots/06_immortal_home_with_overlays.png` — Running over the Immortal launcher
5. `screenshots/04_appearance.png` — Customization (accent / opacity / corners)
6. `screenshots/05_about.png` — About / update screen

> Reddit galleries are uploaded manually — these files are in the repo's `screenshots/` folder.
> They're also viewable on GitHub, e.g.
> https://raw.githubusercontent.com/GodricTM/PortalOverlays/main/screenshots/03_navigation.png
