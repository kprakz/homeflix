// HOMEFLIX desktop app: runs the streaming server in the background, lives in the system tray,
// and gives a small window to pick movie folders and see the address to open on other devices.

const { app, BrowserWindow, Tray, Menu, dialog, ipcMain, shell, clipboard, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { execFile } = require('child_process');
const QRCode = require('qrcode');
const homeflix = require('../server');

const ICON = path.join(__dirname, 'icon.png');
const TRAY_ICON = path.join(__dirname, 'tray.png');
const SETTINGS_FILE = path.join(app.getPath('userData'), 'settings.json');
const CACHE_DIR = path.join(process.env.LOCALAPPDATA || app.getPath('userData'), 'HOMEFLIX', 'cache');
const FFMPEG_DIR = app.isPackaged ? path.join(process.resourcesPath, 'ffmpeg') : path.join(__dirname, '..', 'vendor', 'ffmpeg');

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
    ffmpeg: path.join(FFMPEG_DIR, 'ffmpeg.exe'),
    ffprobe: path.join(FFMPEG_DIR, 'ffprobe.exe'),
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
  app.setAppUserModelId('com.homeflix.app');
  loadSettings();
  applyConfig();
  await startServer();
  createTray();
  if (!process.argv.includes('--hidden')) showWindow();
}

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
      tray.displayBalloon({ iconType: 'info', title: 'HOMEFLIX is still running', content: 'Your movies stay available. Right-click the HOMEFLIX icon here to quit.' });
    }
  });
  win.on('closed', () => (win = null));
}

app.on('before-quit', () => {
  quitting = true;
  homeflix.stop();
});
app.on('window-all-closed', () => {}); // stay in the tray

// A phone can't connect when Windows treats the Wi-Fi as Public (the firewall blocks it).
let networkCheck = { at: 0, value: null };
function networkIsPublic() {
  if (Date.now() - networkCheck.at < 15000) return Promise.resolve(networkCheck.value);
  return new Promise((resolve) => {
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', '(Get-NetConnectionProfile | ForEach-Object { "$($_.NetworkCategory)" }) -join ","'], { windowsHide: true, timeout: 8000 }, (err, stdout) => {
      const value = err ? null : stdout.split(',').map((s) => s.trim()).includes('Public');
      networkCheck = { at: Date.now(), value };
      resolve(value);
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
    networkPublic: await networkIsPublic(),
    ffmpegFound: fs.existsSync(path.join(FFMPEG_DIR, 'ffmpeg.exe')),
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
ipcMain.handle('open-wifi-settings', () => shell.openExternal('ms-settings:network-status'));
ipcMain.handle('copy', (_e, text) => clipboard.writeText(String(text)));

ipcMain.handle('set-open-at-login', (_e, on) => {
  settings.openAtLogin = !!on;
  saveSettings();
  app.setLoginItemSettings({ openAtLogin: settings.openAtLogin, args: ['--hidden'] });
  return state();
});

ipcMain.handle('clear-cache', async () => {
  await homeflix.clearCache();
  return state();
});
