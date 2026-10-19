# HOMEFLIX

Watch the movies on your Windows laptop from your iPad, iPhone, Android phone or TV, over your home Wi-Fi. No account, no internet upload, no subscription.

---

## Install (5 minutes, one time)

### 1. Download and run the installer
**[⬇ Download HOMEFLIX for Windows](https://github.com/kprakz/homeflix/releases/latest)**. On that page, under *Assets*, click **`HOMEFLIX-Setup-1.0.0.exe`**, then double-click the downloaded file.

Requires Windows 10 or 11 (64-bit).

> **"Windows protected your PC"?** This shows up for new apps that aren't from a big publisher. Click **More info**, then **Run anyway**.

Follow the installer. It puts HOMEFLIX on your Desktop and in the Start menu, and opens it when it finishes. No admin password is needed.

### 2. Choose your movies folder
Click **Choose your movies folder** and pick the folder where your movies are, for example `Downloads` or `Videos`. Sub-folders are included, and you can add more folders any time.

### 3. Allow HOMEFLIX on your network
The first time HOMEFLIX starts, Windows may ask *"Allow HOMEFLIX to communicate on these networks?"* Make sure **Private networks** is ticked, then click **Allow**.

### 4. Set your home Wi-Fi to Private (if HOMEFLIX asks)
If HOMEFLIX shows a yellow box saying your Wi-Fi is set to **Public**, your phone can't connect yet. To fix it:
1. Click **Open network settings** in HOMEFLIX.
2. Click your Wi-Fi's **Properties**.
3. Choose **Private network**.

Only do this for your **home** Wi-Fi, never for café, hotel or airport Wi-Fi.

---

## Watch

1. Connect your iPad, phone or TV to the **same Wi-Fi** as the laptop.
2. **Scan the QR code** in the HOMEFLIX window with the camera, or type the address shown (for example `http://192.168.1.57:8080`) into Safari or Chrome.
3. Pick a movie and press Play.

**Tips**
- On iPhone/iPad, tap **Share → Add to Home Screen** to get a HOMEFLIX icon.
- Each device remembers where you stopped and shows those movies under **Continue Watching**.
- TV shows named like `Show S01E05` are grouped into series, and the next episode plays automatically.
- Subtitles inside the movie, or a `.srt` file next to it with the same name, appear in the player's subtitle menu.
- To watch on a TV, **AirPlay** from an iPhone or iPad works with every movie. Some TV browsers can't play HEVC (x265) files.

## Good to know

- **Keep the laptop awake and plugged in** while you watch. Nothing plays on the laptop itself: the movie plays on your device.
- **Closing the window doesn't stop HOMEFLIX.** It keeps running in the system tray, near the clock. Right-click the red **H** icon → **Quit HOMEFLIX** to stop it.
- **Start with Windows** in Settings makes HOMEFLIX start quietly when you sign in.
- **MKV files** are converted once into a format Apple devices play, which usually takes seconds. These copies use disk space. **Settings → Free up space** removes them at any time, and HOMEFLIX makes them again when needed.
- **Files still downloading** show a *Downloading* badge and become playable when finished.

## Privacy

HOMEFLIX works only inside your own network. Nothing is uploaded, and there are no accounts or tracking. Anyone on the same Wi-Fi can open the page, so use it on networks you trust.

## Uninstall

Settings → Apps → Installed apps → **HOMEFLIX** → Uninstall. This also removes HOMEFLIX's converted copies. Your own movie files are never touched.

## Troubleshooting

| Problem | Fix |
|---|---|
| The page won't open on my phone | Same Wi-Fi? Wi-Fi set to **Private**? HOMEFLIX allowed in the firewall? (Windows Security → Firewall & network protection → Allow an app through firewall → tick **HOMEFLIX** for Private.) |
| The address changed | Your router gave the laptop a new address. Scan the new QR code in the HOMEFLIX window. |
| A movie shows "Downloading" | It's still being written to disk. Wait for the download to finish and reload the page. |
| A movie won't play on my TV | The TV browser probably can't play HEVC. Use AirPlay from an iPhone/iPad instead. |
| No thumbnails / MKV won't play | Reinstall HOMEFLIX (its video tools are missing). |

---

## For developers

Requirements: Node.js 20+, and ffmpeg on PATH (`winget install Gyan.FFmpeg.Essentials`).

```bash
npm install
npm run ffmpeg      # copy ffmpeg.exe/ffprobe.exe into vendor/ffmpeg
npm start           # run the desktop app
npm run dist        # build dist/HOMEFLIX-Setup-<version>.exe
```

`npm run serve` runs only the streaming server (`server.js`), configured by `config.json` (copy `config.example.json` to start). `npm run icons` redraws the icons.

| Path | What it is |
|---|---|
| `server.js` | Streaming server: library scan, byte-range streaming, MKV→MP4 remux, thumbnails, subtitles |
| `public/index.html` | The Netflix-style web player that phones and TVs open |
| `electron/` | Desktop app: tray, setup window (`control.html`), settings |
| `build/` | Installer icons and NSIS uninstall script |
| `vendor/ffmpeg/` | Bundled ffmpeg (not committed; created by `npm run ffmpeg`) |

App settings live in `%APPDATA%\HOMEFLIX\settings.json`. Converted copies live in `%LOCALAPPDATA%\HOMEFLIX\cache`.

HOMEFLIX ships unmodified FFmpeg programs, which are GPL-licensed. See `vendor/ffmpeg/LICENSE.txt` and `SOURCE.txt`, which are installed to `resources\ffmpeg`.
