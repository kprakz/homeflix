# AGENTS.md — HOMEFLIX project rules for agent sessions

## Memory / continuity (IMPORTANT)
- At session start, read `DEVLOG.md` in this repo. It contains the project map, current feature state, and where the last session left off.
- At session end, **update `DEVLOG.md`**: refresh "CURRENT STATE", append a numbered "Session log" entry and its "Appendix — Session end notes". This is how the next session resumes after a broken chat.

## Project facts
- Repo root: `~/Desktop/homeflix` (this directory). Branch `main`, remote `origin`.
- Components: `server.js` (media server), `electron/` (desktop app), `public/` (web player), `android/` (Compose app incl. on-device Server Mode).
- Version source of truth: root `package.json`. Android derives version from it.
- `config.json` holds local settings — never commit (already git-ignored).

## Conventions
- Friendly, plain-English UI copy (see README tone). Compose style: `Ink`/`Panel` surfaces, `PremiumButton` family, green `Color(0xFF43D17A)` for active states, `HomeflixRed` accent.
- Edge-to-edge handled with `enableEdgeToEdge()` + `safeDrawingPadding`/`windowInsetsPadding`, not `WindowCompat.setDecorFitsSystemWindows`.
- Everything stays local-network-only; connected-device usage and watch history are persisted locally in `devices.json` (never sent off the network).
- The Android app carries a synced copy of the server: `android/app/src/main/server/android-server.js` (and assets) — keep behavior in sync with `server.js` changes when relevant.

## Verification before claiming done
- Kotlin: `cd android && ./gradlew :app:compileDebugKotlin` (assembleDebug for full build).
- Server JS: `node --check server.js`.
- Manual tests use `~/homeflix-test/media` library and/or a USB-debugging-connected phone (adb install debug APK, exercise Server Mode).

## Deploy to test phone
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk   # or via ./gradlew :app:installDebug
```
