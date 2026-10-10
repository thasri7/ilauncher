# Remaining tasks (continue locally)

Branch: `claude/gracious-ramanujan-tbgazm`. Build: `./gradlew :app:assembleRelease` (run `chmod +x gradlew` first if needed).
APK upload: copy `app/build/outputs/apk/release/app-release.apk` to `apk/iLauncher.apk`, then `git add -f apk/iLauncher.apk`.

## Done and working
- Hub (BlackBerry Hub style): `launcher/HubStore.kt`, `HubActions.kt`, `HubActivity.kt`, Hub tile, Hub in Start menu
- Today page, universal search (apps, shortcuts, settings, contacts, files, calendar, Hub, answers), real currency rates
- Per-app icon overrides + icon shapes in `IconCache.kt` (prefs: `iconOverrides`, `iconShape`, `labelOverrides`, `lockedApps` in `TilePreferences.kt`)
- `namedApps()` in `LauncherActivity` applies renamed app labels
- `HubActions.replyTo(sbn, text)` / `canReply(sbn)` for replying from tile menus

## Task 9 — finish (draft code ready)
Draft is in `docs/drafts/task9_launcher_draft.kt.txt`. Paste it into `LauncherActivity` and fix the compile errors. It may call helpers with other names than the real ones, e.g. `prompt`, `ui.toggleRow`, `widgets.label/icon/delete`, `tile.stackIndex`, `shortcutIndex`, `promptGestureHelper`, `nextSpace`.
1. **App menu rows**: call `addAppCustomRows(card, key, name)` from the drawer, text-page and tile app menus. The rows are Rename, Change icon (icon-pack grid or a photo), and Lock.
2. **App lock**: in `launchApp`, if `key in prefs.lockedApps && key !in unlockedApps`, then `authenticate(name, "Open") { unlockedApps += key; launch }`. Clear `unlockedApps` in `onStop`.
3. **Icon shape setting**: add chips in Settings › Colours & icons (system/circle/squircle/rounded/teardrop). Set `prefs.iconShape`, then call `reloadApps()`.
4. **Gestures to anything**: store gesture prefs as action specs (`app:<key>`, `shortcut:<pkg>|<id>|<label>`, built-ins). Use `chooseAction` in Settings › Gestures, and `runAction(spec)` in the gesture handler.
5. **Tile notifications with reply**: in the tile long-press menu, list `NotificationHub.active` items for that app. Add an inline reply box when `HubActions.canReply(sbn)`, which sends via `HubActions.replyTo`.
6. **Widget stacks**:
   - Store extra widget ids in `tile.extras["stack"]`, plus a `stackIndex` field on TileItem.
   - The widget holder shows the current id, with swipe or dots to switch between them.
   - Add "Add widget to this tile" to the widget tile menu, and handle `stackTarget` in the widget-bind result.

## Task 10
1. **Focus mode / bedtime**: hide chosen apps, grey out tiles, daily app limits from UsageStats, and a schedule.
2. **Daily wallpaper**: **OFF by default**. When on, it changes the **home screen only** (`WallpaperManager.FLAG_SYSTEM`) and never touches the lock screen. Daily JobScheduler job.
3. **Smart tiles / rules**: headphones → music tile up front; work hours or a chosen Wi-Fi → switch space; low battery → calm theme / pause live tiles.
4. **Weekly auto backup**: user picks a folder (SAF `OpenDocumentTree`, keep the persisted permission). A weekly JobScheduler job writes the existing backup JSON there, keeping the last 4 backups.
5. **Landscape / tablet layout**: more grid columns when width ≥ 600dp, the drawer as a side panel, and no forced portrait.

## Wrap-up
- `./gradlew :app:testDebugUnitTest lint`
- Build the release APK, then force-add `apk/iLauncher.apk`, commit and push.
