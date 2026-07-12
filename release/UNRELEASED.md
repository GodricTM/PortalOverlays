# Portal Overlays — unreleased

**Not published yet.** This file tracks what is built on `main` but not tagged as a GitHub release.

## Release checklist

1. Copy highlights into `release/RELEASE_NOTES.md`
2. Bump `versionCode` + `versionName` in `app/build.gradle.kts`
3. Update root `version.json`:
   - `versionCode` / `versionName`
   - `apkUrl` → versioned asset, e.g. `…/releases/download/vX.Y/PortalOverlays-vX.Y-release.apk`
   - `notes` one-liner for the in-app updater dialog
4. Build signed release: `.\gradlew.bat :app:assembleRelease`
5. Tag `vX.Y`, push, `gh release create` with `PortalOverlays-vX.Y-release.apk`
6. CI attaches stable `PortalOverlays.apk` to the release (see `.github/workflows/stable-apk.yml`)
7. **Immortal catalog** (`immortal/catalog.json` + `app/src/main/assets/catalog.json`):
   - Keep `apkUrl` on **`releases/latest/download/PortalOverlays.apk`** (stable alias)
   - **Bump `versionCode`** to match the new build — Immortal store update detection compares this
     against the installed app; the URL alone does not change between releases

_Nothing pending for GitHub tag — v1.9 (versionCode 14) shipped 2026-07-12._
