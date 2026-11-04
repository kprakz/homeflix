# HOMEFLIX for Android

The native Android app for the HOMEFLIX computer. It is a Kotlin + Jetpack Compose app that talks to
the same `server.js` as the web player, so the laptop/desktop app does not need a separate mode.

The same app can also run that `server.js` on the phone: choose **Share this phone's videos** on the
connect screen. The embedded Node.js runtime and bundled FFmpeg serve the phone's own movie folders to
other HOMEFLIX devices on the same Wi-Fi, using the normal client to watch locally.

- Android 7.0 (API 24) and newer, phones, tablets and Android TV / Google TV / Fire TV
- Finds the computer with mDNS, a QR code, or a typed address
- Resume positions, start over, series grouping, auto-play next episode, audio tracks, subtitles,
  remembered volume boost
- Direct play, one-time remux, and live HLS conversion when the device cannot play the file
- HEVC fallback: if the phone has no decoder (or shows sound but no picture), it asks the computer
  for the H.264 stream and remembers that for next time
- Optional on-device server mode with the same API as the desktop app

## Build

Requirements: JDK 17 or newer, Android SDK 36 (`platforms;android-36`, `build-tools;36.0.0`),
NDK `28.0.13004108` and CMake `3.22.1`.

Server mode links the Node.js runtime from
[nodejs-mobile](https://github.com/gmaclennan/nodejs-mobile) and the GPL Android FFmpeg builds. These
large binaries are build inputs and are not committed. Download the pinned versions and copy them in:

```bash
cd android
./scripts/fetch-native.sh
```

To use local builds instead, set `NODEJS_MOBILE_DIR` and `FFMPEG_ANDROID_DIR` and run
`./scripts/prepare-native.sh`.

`NODEJS_MOBILE_DIR` must contain `bin/x86_64/libnode.so`, `bin/arm64-v8a/libnode.so` and `include/`.
`FFMPEG_ANDROID_DIR` must contain `ffmpeg_android_x86_64_gpl/` and `ffmpeg_android_aarch64_gpl/`
with `bin/ffmpeg` and `bin/ffprobe`.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The version name and code come from the root `package.json`, so one version bump covers the desktop
app and Android. `versionCode` is `major * 10000 + minor * 100 + patch` (1.4.2 → 10402).

Useful checks:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## Server mode on the phone

Tap **Share this phone's videos** on the connect screen, then **Allow access to files** and grant
all-files access. The server starts automatically and shows the LAN address and PIN. Other devices
connect to it exactly like they connect to the desktop app.

You choose what is shared: the default list is `Movies`, `Download` and the app's own external
`Movies` folder, but **Add folder** opens the system folder picker (internal storage or SD card) and
each folder can be removed again. Subfolders are scanned. Changes apply immediately while the server
is running. The PIN works like the desktop app: **Change PIN** accepts 4 to 8 digits and **Turn off
PIN** disables it. Changing or turning off the PIN makes every device enter the new state again.

The canonical `server.js` and `public/` are copied into the APK at build time, so there is only one
server implementation. `libnode.so`, `libffmpeg_exec.so` and `libffprobe_exec.so` are packaged for
`x86_64` and `arm64-v8a`.

The server runs in a `dataSync` foreground service with a multicast lock, so mDNS discovery, video
streaming and thumbnails keep working while the screen is off. Android may stop it after long
background periods. **Stop server** stops sharing but leaves the embedded Node runtime loaded, so
**Start server** works again immediately without restarting the app; force stop HOMEFLIX to unload
the runtime. The release APK grows by roughly 130 MB because it includes Node and FFmpeg for two
ABIs.

## Release signing

Create a keystore once and keep it safe (losing it means you cannot update the Play listing):

```bash
keytool -genkeypair -v -keystore homeflix-release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias homeflix
```

Then either copy `keystore.properties.example` to `keystore.properties` and fill it in, or set:

```bash
export ANDROID_KEYSTORE_PATH=/absolute/path/to/homeflix-release.jks
export ANDROID_KEYSTORE_PASSWORD=...
export ANDROID_KEY_ALIAS=homeflix
export ANDROID_KEY_PASSWORD=...
```

Build a signed APK (GitHub Releases) and an app bundle (Google Play):

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

Outputs:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

Without a keystore the release build is unsigned; the CI workflow falls back to a debug APK when the
signing secrets are not configured.

## GitHub Actions

`.github/workflows/android.yml` runs on every push/PR that touches the app, and attaches installers
when a GitHub release is published or when started by hand with a tag.

Repository secrets used for a signed release:

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 homeflix-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Usually `homeflix` |
| `ANDROID_KEY_PASSWORD` | Key password |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Optional: Play Console service account JSON |

The workflow uploads the APK/AAB to the GitHub release. If the Play service account is configured, it
also uploads the AAB to the **internal** track as a draft; promote it in Play Console when ready.

## Google Play

1. Create the app in Play Console with package name `com.homeflix.app`.
2. Create a service account with release permissions and download its JSON key.
3. Add the JSON as `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` in the repository secrets.
4. Publish a GitHub release (or run the workflow with a tag). The AAB is attached to the release and
   uploaded to internal testing.
5. Complete the store listing, content rating, data safety form (no collection, LAN only) and
   testing requirements, then promote the release.

The app declares the internet and local-network permissions it needs. It does not collect analytics.
It talks to a user-owned computer over plain HTTP on the private LAN; the server rejects requests
from non-private addresses, and the app never sends the library anywhere else.

## Where things are

- `data/` – API client, models, DataStore (server, key, resume positions, audio language, HEVC block)
- `discovery/` – mDNS (`NsdManager`) and CameraX + ZXing QR scanning
- `ui/` – connect, PIN, library, continue watching, TV show grouping and detail sheets
- `player/` – Media3/ExoPlayer screen, source selection, audio/subtitles, volume boost, fallback,
  auto-next and start-over
- `server/` – on-device Node service, JNI bridge, foreground notification and server screen
- `util/` – series grouping, resume rules, format helpers
- `scripts/fetch-native.sh` – downloads pinned Node/FFmpeg builds and runs `prepare-native.sh`
- `scripts/prepare-native.sh` – copies Node/FFmpeg binaries and headers into the app module

The API version is `1`. The app checks `/api/hello` before connecting. Older servers without
`/api/hello` are still supported through `/api/auth` and the PIN/no-PIN `/api/unlock` flow.
