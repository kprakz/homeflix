// HOMEFLIX desktop app (Windows and macOS): runs the streaming server in the background, lives in
// the system tray / menu bar, and gives a small window to pick movie folders and see the address to
// open on other devices.

const { app, BrowserWindow, Tray, Menu, Notification, dialog, ipcMain, shell, clipboard, nativeImage } = require('electron');
const path = require('path');
const os = require('os');
const fs = require('fs');
const { execFile } = require('child_process');
const QRCode = require('qrcode');
const homeflix = require('../server');

const IS_WIN = process.platform === 'win32';
const IS_MAC = process.platform === 'darwin';
const ICON = path.join(__dirname, 'icon.png');
// macOS menu-bar icons are "template" images (black + transparency) that the system tints.
const TRAY_ICON = path.join(__dirname, IS_MAC ? 'trayTemplate.png' : 'tray.png');
const SETTINGS_FILE = path.join(app.getPath('userData'), 'settings.json');
// Converted copies can be many GB: keep them where each system expects caches.
const CACHE_DIR = IS_WIN
  ? path.join(process.env.LOCALAPPDATA || app.getPath('userData'), 'HOMEFLIX', 'cache')
  : IS_MAC
    ? path.join(os.homedir(), 'Library', 'Caches', 'HOMEFLIX')
    : path.join(app.getPath('userData'), 'cache');
const FFMPEG_DIR = app.isPackaged ? path.join(process.resourcesPath, 'ffmpeg') : path.join(__dirname, '..', 'vendor', 'ffmpeg');
const EXE = IS_WIN ? '.exe' : '';

let settings = { folders: [], port: 8080, openAtLogin: false };
let port = null;
let serverError = null;
let win = null;
let tray = null;
let quitting = false;
let toldAboutTray = false;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
  app.whenReady().then(boot);
}

function loadSettings() {
  try {
    settings = { ...settings, ...JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')) };
  } catch {}
  settings.folders = settings.folders.filter((f) => fs.existsSync(f));
}

function saveSettings() {
  fs.mkdirSync(path.dirname(SETTINGS_FILE), { recursive: true });
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2));
}

function applyConfig() {
  homeflix.configure({
    folders: settings.folders,
    cacheDir: CACHE_DIR,
    ffmpeg: path.join(FFMPEG_DIR, 'ffmpeg' + EXE),
    ffprobe: path.join(FFMPEG_DIR, 'ffprobe' + EXE),
  });
}

// Keep the saved port so bookmarks on the iPad keep working; step up if something else holds it.
async function startServer() {
  for (let p = settings.port; p < settings.port + 20; p++) {
    homeflix.configure({ port: p });
    try {
      port = await homeflix.start();
      serverError = null;
      if (p !== settings.port) {
        settings.port = p;
        saveSettings();
      }
      return;
    } catch (err) {
      if (err.code !== 'EADDRINUSE') {
        serverError = err.message;
        return;
      }
    }
  }
  serverError = `Ports ${settings.port}–${settings.port + 19} are all in use.`;
}

async function boot() {
  if (IS_WIN) app.setAppUserModelId('com.homeflix.app');
  loadSettings();
  applyConfig();
  await startServer();
  createTray();
  // Started at sign-in: stay in the tray / menu bar. (Windows passes --hidden; macOS reports it.)
  const atLogin = process.argv.includes('--hidden') || (IS_MAC && app.getLoginItemSettings().wasOpenedAtLogin);
  if (!atLogin) showWindow();
}

// macOS: clicking the Dock icon brings the window back.
app.on('activate', () => showWindow());

function address() {
  const ip = homeflix.lanAddresses()[0];
  return port ? `http://${ip || 'localhost'}:${port}` : null;
}

function createTray() {
  tray = new Tray(nativeImage.createFromPath(TRAY_ICON));
  tray.setToolTip('HOMEFLIX');
  tray.on('click', showWindow);
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: 'Open HOMEFLIX', click: showWindow },
      { label: 'Watch on this laptop', click: () => port && shell.openExternal(`http://localhost:${port}`) },
      { type: 'separator' },
      { label: 'Quit HOMEFLIX', click: () => { quitting = true; app.quit(); } },
    ])
  );
}

function showWindow() {
  if (win) {
    if (win.isMinimized()) win.restore();
    win.show();
    win.focus();
    return;
  }
  win = new BrowserWindow({
    width: 960,
    height: 760,
    minWidth: 640,
    minHeight: 560,
    title: 'HOMEFLIX',
    icon: ICON,
    backgroundColor: '#141414',
    autoHideMenuBar: true,
    show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true },
  });
  win.loadFile(path.join(__dirname, 'control.html'));
  win.once('ready-to-show', () => win.show());
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });
  // Closing the window keeps the server running in the tray; Quit is in the tray menu.
  win.on('close', (e) => {
    if (quitting) return;
    e.preventDefault();
    win.hide();
    if (!toldAboutTray) {
      toldAboutTray = true;
      const title = 'HOMEFLIX is still running';
      if (IS_WIN) tray.displayBalloon({ iconType: 'info', title, content: 'Your movies stay available. Right-click the HOMEFLIX icon here to quit.' });
      else if (Notification.isSupported()) new Notification({ title, body: 'Your movies stay available. Quit from the HOMEFLIX icon in the menu bar.', silent: true }).show();
    }
  });
  win.on('closed', () => (win = null));
}

app.on('before-quit', () => {
  quitting = true;
  homeflix.stop();
});
app.on('window-all-closed', () => {}); // stay in the tray

// Whether phones can reach HOMEFLIX depends on Windows Firewall rules for this exe, per network type
// (Public / Private). When Windows first asks, it only ticks the network type in use at that moment,
// so read the actual rules rather than guessing from the network type.
const psQuote = (s) => `'${String(s).replace(/'/g, "''")}'`;
let networkCheck = { at: 0, value: null };
function networkStatus() {
  if (!IS_WIN) return Promise.resolve(null); // macOS asks once ("accept incoming connections?") by itself
  if (Date.now() - networkCheck.at < 30000) return Promise.resolve(networkCheck.value);
  const script = [
    `$exe = ${psQuote(process.execPath)}`,
    '$cats = @(Get-NetConnectionProfile | ForEach-Object { "$($_.NetworkCategory)" })',
    '$rules = @(Get-NetFirewallApplicationFilter -Program $exe -ErrorAction SilentlyContinue | Get-NetFirewallRule -ErrorAction SilentlyContinue |',
    '  Where-Object { "$($_.Enabled)" -eq "True" -and "$($_.Direction)" -eq "Inbound" } | ForEach-Object { @{ action = "$($_.Action)"; profile = "$($_.Profile)" } })',
    '@{ categories = $cats; rules = $rules } | ConvertTo-Json -Compress -Depth 3',
  ].join('\n');
  return new Promise((resolve) => {
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, timeout: 15000 }, (err, stdout) => {
      let value = null;
      try {
        const data = JSON.parse(stdout);
        const cats = [].concat(data.categories || []).map((c) => (c === 'DomainAuthenticated' ? 'Domain' : c));
        const rules = [].concat(data.rules || []);
        const covers = (r, cat) => r.profile === 'Any' || r.profile.split(/,\s*/).includes(cat);
        const allowed = (cat) => rules.some((r) => r.action === 'Allow' && covers(r, cat)) && !rules.some((r) => r.action === 'Block' && covers(r, cat));
        const category = cats.includes('Public') ? 'Public' : cats[0] || null;
        value = { category, allowedHere: category ? allowed(category) : true, allowedPrivate: allowed('Private'), allowedPublic: allowed('Public') };
      } catch {}
      if (err) value = null;
      networkCheck = { at: Date.now(), value };
      resolve(value);
    });
  });
}

// Adds a firewall rule letting HOMEFLIX accept connections on Private (home) networks, and removes any
// rule blocking it. Windows shows its admin prompt; nothing changes if the user says no.
function allowOnHomeNetworks() {
  const exe = psQuote(process.execPath);
  const script = [
    `Get-NetFirewallApplicationFilter -Program ${exe} -ErrorAction SilentlyContinue | Get-NetFirewallRule | Where-Object { "$($_.Action)" -eq "Block" -and "$($_.Direction)" -eq "Inbound" } | Remove-NetFirewallRule`,
    `New-NetFirewallRule -DisplayName 'HOMEFLIX (home networks)' -Description 'Lets phones and TVs on your home Wi-Fi watch movies from HOMEFLIX.' -Direction Inbound -Action Allow -Program ${exe} -Protocol TCP -Profile Private | Out-Null`,
  ].join('\n');
  const encoded = Buffer.from(script, 'utf16le').toString('base64');
  const launcher = `try { $p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','${encoded}'; exit $p.ExitCode } catch { exit 1223 }`;
  return new Promise((resolve) => {
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', launcher], { windowsHide: true, timeout: 180000 }, (err) => {
      networkCheck.at = 0;
      resolve(err ? (err.code === 1223 ? 'cancelled' : 'failed') : 'ok');
    });
  });
}

async function state() {
  const movies = await homeflix.scanLibrary();
  const url = address();
  return {
    version: app.getVersion(),
    running: !!port,
    serverError,
    url,
    localUrl: port ? `http://localhost:${port}` : null,
    qr: url ? await QRCode.toDataURL(url, { margin: 1, width: 360, color: { dark: '#000000', light: '#ffffff' } }) : null,
    folders: settings.folders.map((f) => ({ path: f, count: movies.filter((m) => m.path.toLowerCase().startsWith(path.join(f, path.sep).toLowerCase())).length })),
    movieCount: movies.length,
    openAtLogin: settings.openAtLogin,
    cacheBytes: await homeflix.cacheSize(),
    network: await networkStatus(),
    ffmpegFound: fs.existsSync(path.join(FFMPEG_DIR, 'ffmpeg' + EXE)),
    platform: process.platform,
  };
}

ipcMain.handle('state', state);

ipcMain.handle('add-folder', async () => {
  const result = await dialog.showOpenDialog(win, {
    title: 'Choose the folder where your movies are',
    buttonLabel: 'Use this folder',
    properties: ['openDirectory', 'multiSelections'],
  });
  if (!result.canceled) {
    for (const folder of result.filePaths) {
      const already = settings.folders.some((f) => f.toLowerCase() === folder.toLowerCase());
      if (!already) settings.folders.push(folder);
    }
    saveSettings();
    applyConfig();
  }
  return state();
});

ipcMain.handle('remove-folder', async (_e, folder) => {
  settings.folders = settings.folders.filter((f) => f !== folder);
  saveSettings();
  applyConfig();
  return state();
});

ipcMain.handle('open-folder', (_e, folder) => shell.openPath(folder));
ipcMain.handle('open-browser', () => port && shell.openExternal(`http://localhost:${port}`));
ipcMain.handle('open-wifi-settings', () => {
  networkCheck.at = 0; // look again when the user comes back
  return shell.openExternal(IS_WIN ? 'ms-settings:network-status' : 'x-apple.systempreferences:com.apple.Network-Settings.extension');
});
ipcMain.handle('allow-firewall', async () => ({ result: await allowOnHomeNetworks(), state: await state() }));
ipcMain.handle('copy', (_e, text) => clipboard.writeText(String(text)));

ipcMain.handle('set-open-at-login', (_e, on) => {
  settings.openAtLogin = !!on;
  saveSettings();
  app.setLoginItemSettings(IS_WIN ? { openAtLogin: settings.openAtLogin, args: ['--hidden'] } : { openAtLogin: settings.openAtLogin });
  return state();
});

ipcMain.handle('clear-cache', async () => {
  await homeflix.clearCache();
  return state();
});
