# HOMEFLIX

Watch the movies on your Windows PC or Mac from your iPad, iPhone, Android phone or TV, over your home Wi-Fi. No account, no internet upload, no subscription.

**Website and download: [kprakz.github.io/homeflix](https://kprakz.github.io/homeflix/)**

---

## Install on a Mac

1. **[Download HOMEFLIX for Mac](https://kprakz.github.io/homeflix/#download)**. Choose **Apple Silicon** for Macs with an M1–M4 chip, or **Intel**. (Apple menu → About This Mac shows which you have.) Requires macOS 12 or later.
2. Open the downloaded `.dmg` and drag **HOMEFLIX** onto **Applications**.
3. Open HOMEFLIX from Applications. The first time, macOS says it can't verify the app. Click **Done**, open System Settings → **Privacy & Security**, scroll down and click **Open Anyway**. You only do this once. (HOMEFLIX isn't notarized by Apple, which needs a paid developer account.)
4. Click **Choose your movies folder**.
5. When macOS asks whether HOMEFLIX may **accept incoming network connections**, click **Allow**. Your phone and TV can't connect otherwise.

HOMEFLIX then lives in the menu bar at the top right of the screen. Everything else below works the same as on Windows.

## Install on Windows (5 minutes, one time)

### 1. Download and run the installer
**[⬇ Download HOMEFLIX for Windows](https://kprakz.github.io/homeflix/#download)**, then double-click the downloaded `HOMEFLIX-Setup-<version>.exe`.

Requires Windows 10 or 11 (64-bit).

> **"Windows protected your PC"?** This shows up for new apps that aren't from a big publisher. Click **More info**, then **Run anyway**.

Follow the installer. It puts HOMEFLIX on your Desktop and in the Start menu, and opens it when it finishes. No admin password is needed.

### 2. Choose your movies folder
Click **Choose your movies folder** and pick the folder where your movies are, for example `Downloads` or `Videos`. Sub-folders are included, and you can add more folders any time.

### 3. Let your phone through Windows Firewall (if HOMEFLIX asks)
HOMEFLIX checks Windows Firewall and shows a box at the top of its window if something needs doing:
- **Your Wi-Fi is set to Public:** click **Open network settings** → your Wi-Fi → **Properties** → **Private network**. Only do this for your **home** Wi-Fi, never for café, hotel or airport Wi-Fi.
- **HOMEFLIX isn't allowed on home networks yet:** click **Allow on home networks** and say **Yes** when Windows asks.

> The first time HOMEFLIX starts, Windows may ask *"Allow HOMEFLIX to communicate on these networks?"* It ticks whichever network type you're on at that moment. If your Wi-Fi was Public then, HOMEFLIX ends up allowed only on Public networks, and stops working once you switch the Wi-Fi to Private. The **Allow on home networks** button fixes that.

---

## Watch

1. Connect your iPhone, iPad, Android phone or TV to the **same Wi-Fi** as the laptop.
2. **Scan the QR code** in the HOMEFLIX window with the camera, or type the address shown (for example `http://192.168.1.57:8080`) into the browser.
3. Pick a movie and press Play.

### iPhone and iPad
Open the address in **Safari**. Tap **Share → Add to Home Screen** to get a HOMEFLIX app icon.

### Android phones and tablets
Open the address in **Chrome**. Tap **⋮ → Add to Home screen** (or **Install app**) to get a HOMEFLIX app icon. Videos go full-screen in landscape, and the phone's back button works as you'd expect.

### Smart TVs
Open your TV's web browser, such as Samsung Internet, LG Web Browser, or a browser app on Android TV / Google TV / Fire TV, and type the address.

| Remote button | What it does |
|---|---|
| Arrows / OK | Move around and choose |
| Back | Go back, or leave the movie |
| OK (while watching) | Pause / play |
| Left / Right (while watching) | Skip back / forward 10 seconds |
| Up / Down (while watching) | Show the controls: subtitles, skip, play/pause |

If HOMEFLIX doesn't switch to TV controls on its own, add `?tv=1` to the end of the address once, for example `http://192.168.1.57:8080/?tv=1`. Your TV remembers it.

From an iPhone or iPad you can also **AirPlay** to an Apple TV or an AirPlay-compatible TV.

### Audio language and subtitles
Movies with several audio languages (for example Tamil, Telugu, Hindi and English in one file) get a language button in the player:
- **iPhone, iPad, Android, laptop:** tap **🔊 Tamil** (or whichever language is playing) at the top right of the player. On iPhone/iPad, leave full screen first.
- **TV:** press **Up**, then choose **Audio**.

The movie continues at the same point in the new language. Each device remembers your choice for that movie, and the last language you picked becomes your default. Choose English once, and other multi-language movies start in English on that device.

Subtitles inside the movie, or a `.srt` / `.vtt` file next to it with the same name, appear in the player's subtitle menu (on a TV: **Up → Subtitles**).

**Tips**
- Each device remembers where you stopped and shows those movies under **Continue Watching**. To watch from the beginning instead, choose **Start over**: next to **Resume** on the home screen and in **More Info**, at the top of the player, or in the TV controls.
- **Too quiet?** Many films are mixed quietly for cinemas, and phones and TVs can't go above 100% the way VLC can. Turn on **Volume boost** in the player (top right; on a TV, press Up). It makes the sound about twice as loud on that device without distorting it, and the device remembers it. Turn it off for the original sound.
- TV shows named like `Show S01E05` are grouped into series, and the next episode plays automatically.

## Good to know

- **Keep the laptop awake and plugged in** while you watch. Nothing plays on the laptop itself: the movie plays on your device.
- **Closing the window doesn't stop HOMEFLIX.** It keeps running in the system tray, near the clock. Right-click the red **H** icon → **Quit HOMEFLIX** to stop it.
- **Start with Windows** in Settings makes HOMEFLIX start quietly when you sign in.
- **MKV files** are repackaged once into a format phones and TVs play. This usually takes seconds, or a few minutes for movies with Dolby audio.
- **Devices that can't play HEVC (x265)**, which includes many Android phones and TVs, get a version converted while you watch. It starts in a few seconds, and HOMEFLIX uses the laptop's Intel, NVIDIA or AMD graphics chip for this when it can. If you see sound but no picture, tap **Picture missing?** in the player.
- **Disk space:** converted copies are stored on the laptop. When space runs low, HOMEFLIX deletes the ones watched longest ago, keeping 2 GB free. **Settings → Free up space** removes them all, and they're made again when needed.
- **Files still downloading** show a *Downloading* badge and become playable when finished.

## Privacy

HOMEFLIX works only inside your own network. Nothing is uploaded, and there are no accounts or tracking. Anyone on the same Wi-Fi can open the page, so use it on networks you trust.

## Uninstall

Settings → Apps → Installed apps → **HOMEFLIX** → Uninstall. This also removes HOMEFLIX's converted copies. Your own movie files are never touched.

## Troubleshooting

| Problem | Fix |
|---|---|
| The page won't open on my phone | Same Wi-Fi? Then follow the box at the top of the HOMEFLIX window. To check by hand: Windows Security → Firewall & network protection → Allow an app through firewall → Change settings → **homeflix.exe** should have **Private** ticked, and your home Wi-Fi should be set to Private. |
| It only works when my Wi-Fi is Public | HOMEFLIX was allowed on Public networks only. Click **Allow on home networks** in HOMEFLIX (or tick **Private** for homeflix.exe as above), then set your Wi-Fi to Private. |
| The address changed | Your router gave the laptop a new address. Scan the new QR code in the HOMEFLIX window. |
| A movie shows "Downloading" | It's still being written to disk. Wait for the download to finish and reload the page. |
| Sound but no picture | Tap **Picture missing?** in the player. On a TV, press Up and choose it. |
| My TV shows the phone layout / no remote controls | Add `?tv=1` to the end of the address once. |
| "Not enough free space" | The laptop's drive is nearly full. Free some space. HOMEFLIX streams a converted version in the meantime. |
| No thumbnails / MKV won't play | Reinstall HOMEFLIX (its video tools are missing). |

---

## For developers

Requirements: Node.js 20+, and ffmpeg on PATH (`winget install Gyan.FFmpeg.Essentials`).

```bash
npm install
npm run ffmpeg      # copy ffmpeg/ffprobe into vendor/ffmpeg
npm start           # run the desktop app
npm run dist        # Windows: build dist/HOMEFLIX-Setup-<version>.exe
```

**Website and download map.** The site in `docs/` is served by GitHub Pages and by a Vercel project, which also runs the download counter in `api/`:
- `api/download.js` counts a download for the visitor's country (Vercel's `x-vercel-ip-country` header; no IP addresses or personal data are stored), once per browser, then redirects to the installer on GitHub. It only redirects to HOMEFLIX's own release files.
- `api/downloads.js` returns the totals per country and platform for the map.
- Counts live in Upstash Redis (free). In the Vercel project: **Storage → Marketplace → Upstash (Redis)** → create a free database → connect it to the project → redeploy. Until then the map says counts are coming and the download buttons link straight to GitHub.
- The map's square grid (`docs/world-grid.json`) is made by `npm run world-grid`.

**Mac builds** are made by GitHub Actions (`.github/workflows/mac.yml`) on Apple Silicon and Intel Macs whenever a release is published. The workflow starts the built app with a test movie, checks it lists, streams and prepares it, and attaches `HOMEFLIX-<version>-mac-arm64.dmg` and `-mac-x64.dmg` to the release. To build on a Mac yourself: `npm install --no-save ffmpeg-static ffprobe-static && npm run dist:mac`.

`npm run serve` runs only the streaming server (`server.js`), configured by `config.json` (copy `config.example.json` to start). `npm run icons` redraws the icons.

| Path | What it is |
|---|---|
| `server.js` | Streaming server: library scan, byte-range streaming, MKV→MP4 remux, on-the-fly H.264 HLS (hardware encoders when available), thumbnails, subtitles, disk-space management |
| `public/index.html` | The Netflix-style web player that phones and TVs open (per-device format choice, TV remote navigation) |
| `electron/` | Desktop app: tray, setup window (`control.html`), settings |
| `build/` | Installer icons and NSIS uninstall script |
| `vendor/ffmpeg/` | Bundled ffmpeg (not committed; created by `npm run ffmpeg`) |

App settings live in `%APPDATA%\HOMEFLIX\settings.json` (Mac: `~/Library/Application Support/HOMEFLIX/settings.json`). Converted copies live in `%LOCALAPPDATA%\HOMEFLIX\cache` (Mac: `~/Library/Caches/HOMEFLIX`).

HOMEFLIX ships unmodified FFmpeg programs, which are GPL-licensed. See `vendor/ffmpeg/LICENSE.txt` and `SOURCE.txt`, which are installed to `resources\ffmpeg`.
