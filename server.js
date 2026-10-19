// HOMEFLIX — stream the movies on this laptop to an iPad (or any browser) on the same Wi-Fi.
// No dependencies: Node's standard library plus ffmpeg/ffprobe for files Safari can't play directly.

const http = require('http');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { spawn } = require('child_process');

const ROOT = __dirname;
let CONFIG = { port: 8080, folders: [], minSizeMB: 5, cacheDir: 'cache', ffmpeg: 'ffmpeg', ffprobe: 'ffprobe' };
let CACHE_DIR = path.resolve(ROOT, CONFIG.cacheDir);
const VIDEO_EXT = new Set(['.mp4', '.m4v', '.mov', '.mkv', '.avi', '.webm', '.wmv', '.flv', '.ts', '.m2ts', '.mpg', '.mpeg', '.3gp']);
const DIRECT_CONTAINERS = new Set(['.mp4', '.m4v', '.mov']);
const SAFARI_AUDIO = new Set(['aac', 'mp3', 'ac3', 'eac3', 'alac', 'flac']);
const MP4_AUDIO_COPY = new Set(['aac', 'mp3', 'ac3', 'eac3', 'alac']);
const TEXT_SUBS = new Set(['subrip', 'ass', 'ssa', 'mov_text', 'webvtt', 'text']);

// The desktop app calls this with its own settings; `node server.js` uses config.json.
function configure(options) {
  CONFIG = { ...CONFIG, ...options };
  CACHE_DIR = path.resolve(ROOT, CONFIG.cacheDir);
  fs.mkdirSync(CACHE_DIR, { recursive: true });
}

function loadConfig() {
  const defaults = {
    port: 8080,
    folders: [path.join(os.homedir(), 'Videos'), path.join(os.homedir(), 'Downloads')],
    minSizeMB: 5,
    cacheDir: 'cache',
    ffmpeg: 'ffmpeg',
    ffprobe: 'ffprobe',
  };
  const file = path.join(ROOT, 'config.json');
  if (!fs.existsSync(file)) return defaults;
  try {
    return { ...defaults, ...JSON.parse(fs.readFileSync(file, 'utf8')) };
  } catch (err) {
    console.error(`config.json is not valid JSON (${err.message}); using defaults.`);
    return defaults;
  }
}

// ---------- library ----------

const library = new Map(); // id -> { id, path, name, title, year, size, mtime, folder }

function idFor(file) {
  return crypto.createHash('sha1').update(file.toLowerCase()).digest('hex').slice(0, 16);
}

// "Insidious.Chapter.2.2013.1080p.10bit.BluRay.x265.mkv" -> { title: "Insidious Chapter 2", year: "2013" }
function prettyName(file) {
  let base = path.basename(file, path.extname(file)).replace(/[._]+/g, ' ').replace(/\s+/g, ' ').trim();
  const yearMatch = base.match(/^(.+?)[\s(\[]+((?:19|20)\d{2})(?:[\s)\]]|$)/);
  if (yearMatch && yearMatch[1].length > 1) return { title: yearMatch[1].trim(), year: yearMatch[2] };
  const junk = base.search(/\b(480p|720p|1080p|2160p|4k|bluray|brrip|webrip|web dl|hdrip|dvdrip|x264|x265|hevc|10bit)\b/i);
  if (junk > 1) base = base.slice(0, junk).trim();
  return { title: base, year: null };
}

async function walk(dir, depth, out) {
  if (depth > 6) return;
  let entries;
  try {
    entries = await fsp.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    if (entry.name.startsWith('.') || entry.name.startsWith('$')) continue;
    const full = path.join(dir, entry.name);
    if (full.toLowerCase() === CACHE_DIR.toLowerCase()) continue;
    if (entry.isDirectory()) await walk(full, depth + 1, out);
    else if (entry.isFile() && VIDEO_EXT.has(path.extname(entry.name).toLowerCase())) out.push(full);
  }
}

async function scanLibrary() {
  const files = [];
  for (const folder of CONFIG.folders) await walk(path.resolve(folder), 0, files);
  library.clear();
  for (const file of files) {
    let stat;
    try {
      stat = await fsp.stat(file);
    } catch {
      continue;
    }
    if (stat.size < CONFIG.minSizeMB * 1024 * 1024) continue;
    const id = idFor(file);
    const folder = CONFIG.folders.find((f) => file.toLowerCase().startsWith(path.resolve(f).toLowerCase())) || '';
    // A file written to in the last 2 minutes is most likely still downloading (e.g. from Telegram).
    const downloading = Date.now() - stat.mtimeMs < 2 * 60 * 1000;
    library.set(id, { id, path: file, ...prettyName(file), size: stat.size, mtime: stat.mtimeMs, folder: path.basename(folder), downloading });
  }
  return [...library.values()].sort((a, b) => b.mtime - a.mtime);
}

// ---------- probing ----------

const probes = new Map(); // id -> Promise<info>

function run(cmd, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { windowsHide: true });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => (stdout += d));
    child.stderr.on('data', (d) => (stderr += d));
    child.on('error', reject);
    child.on('close', (code) => (code === 0 ? resolve(stdout) : reject(new Error(stderr.trim().split('\n').pop() || `${cmd} exited ${code}`))));
  });
}

function probe(item) {
  const key = `${item.id}-${item.size}-${item.mtime}`; // re-probe once a download grows
  if (!probes.has(key)) {
    const p = run(CONFIG.ffprobe, ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', item.path]).then((out) => {
      const data = JSON.parse(out);
      const streams = data.streams || [];
      const video = streams.find((s) => s.codec_type === 'video' && !(s.disposition && s.disposition.attached_pic));
      const audio = streams.find((s) => s.codec_type === 'audio');
      const subs = streams
        .filter((s) => s.codec_type === 'subtitle' && TEXT_SUBS.has(s.codec_name))
        .map((s, i) => ({ key: `e${s.index}`, stream: s.index, n: i, lang: (s.tags && s.tags.language) || 'und', label: (s.tags && s.tags.title) || (s.tags && s.tags.language) || `Subtitle ${i + 1}` }));
      for (const side of sidecarSubs(item.path)) subs.push(side);
      return { duration: Number(data.format && data.format.duration) || 0, video, audio, subs, mode: playMode(item.path, video, audio) };
    });
    p.catch(() => probes.delete(key));
    probes.set(key, p);
  }
  return probes.get(key);
}

// Subtitle files next to the movie: "Movie.srt", "Movie.en.srt", "Movie.English.vtt"
function sidecarSubs(file) {
  const dir = path.dirname(file);
  const stem = path.basename(file, path.extname(file)).toLowerCase();
  let names = [];
  try {
    names = fs.readdirSync(dir);
  } catch {}
  return names
    .filter((n) => /\.(srt|vtt)$/i.test(n) && n.toLowerCase().startsWith(stem))
    .map((n, i) => {
      const extra = n.slice(stem.length).replace(/\.(srt|vtt)$/i, '').replace(/^[ ._-]+/, '');
      return { key: `f${i}`, file: path.join(dir, n), lang: extra.slice(0, 3).toLowerCase() || 'und', label: extra || 'Subtitles' };
    });
}

// direct: Safari plays the file as-is. remux: copy video into MP4. transcode: re-encode video (slow).
function playMode(file, video, audio) {
  if (!video) return 'transcode';
  const videoOk = videoCopyable(video);
  const audioOk = !audio || SAFARI_AUDIO.has(audio.codec_name);
  const tagOk = video.codec_name !== 'hevc' || video.codec_tag_string === 'hvc1';
  if (DIRECT_CONTAINERS.has(path.extname(file).toLowerCase()) && videoOk && audioOk && tagOk) return 'direct';
  return videoOk ? 'remux' : 'transcode';
}

function videoCopyable(video) {
  if (video.codec_name === 'hevc') return true;
  if (video.codec_name === 'h264') return !/10|12|422|444/.test(video.pix_fmt || '') && !/High 10|4:2:2|4:4:4/.test(video.profile || '');
  return false;
}

// ---------- preparing (remux / transcode into the cache) ----------

const jobs = new Map(); // id -> { state, progress, error }

function cachedMp4(item) {
  // Include size+mtime so a replaced file gets prepared again.
  return path.join(CACHE_DIR, `${item.id}-${item.size}-${Math.floor(item.mtime)}.mp4`);
}

function jobStatus(item) {
  if (fs.existsSync(cachedMp4(item))) return { state: 'ready', progress: 1 };
  return jobs.get(item.id) || { state: 'idle', progress: 0 };
}

async function prepare(item) {
  const existing = jobStatus(item);
  if (existing.state === 'ready' || existing.state === 'working') return existing;
  if (item.downloading) return { state: 'error', progress: 0, error: 'This file is still downloading. Try again once it finishes.' };
  const job = { state: 'working', progress: 0, error: null };
  jobs.set(item.id, job);

  try {
    const info = await probe(item);
    const out = cachedMp4(item);
    const tmp = `${out}.part`;
    // Map the real video stream: some files carry cover art as an extra "video" stream.
    const args = ['-hide_banner', '-v', 'error', '-y', '-i', item.path, '-map', `0:${info.video.index}`, '-map', info.audio ? `0:${info.audio.index}` : '0:a:0?'];
    if (info.mode === 'transcode') {
      args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '21', '-pix_fmt', 'yuv420p');
    } else {
      args.push('-c:v', 'copy');
      if (info.video.codec_name === 'hevc') args.push('-tag:v', 'hvc1');
    }
    // Copying audio Safari already plays is ~70x faster than re-encoding it; otherwise make stereo AAC.
    if (info.audio && MP4_AUDIO_COPY.has(info.audio.codec_name)) args.push('-c:a', 'copy');
    else args.push('-c:a', 'aac', '-ac', '2', '-b:a', '192k');
    args.push('-movflags', '+faststart', '-f', 'mp4', '-progress', 'pipe:1', '-nostats', tmp);

    console.log(`Preparing "${item.title}" (${info.mode})…`);
    const child = spawn(CONFIG.ffmpeg, args, { windowsHide: true });
    let stderr = '';
    child.stderr.on('data', (d) => (stderr += d));
    child.stdout.on('data', (d) => {
      const m = String(d).match(/out_time_us=(\d+)/g);
      if (m && info.duration) {
        const us = Number(m[m.length - 1].split('=')[1]);
        job.progress = Math.min(0.99, us / 1e6 / info.duration);
      }
    });
    child.on('error', (err) => Object.assign(job, { state: 'error', error: `Could not start ffmpeg: ${err.message}` }));
    child.on('close', async (code) => {
      if (code === 0) {
        await fsp.rename(tmp, out);
        Object.assign(job, { state: 'ready', progress: 1 });
        console.log(`Ready: "${item.title}"`);
      } else {
        fsp.unlink(tmp).catch(() => {});
        Object.assign(job, { state: 'error', error: stderr.trim().split('\n').pop() || `ffmpeg exited ${code}` });
        console.error(`Failed to prepare "${item.title}": ${job.error}`);
      }
    });
  } catch (err) {
    Object.assign(job, { state: 'error', error: err.message });
  }
  return job;
}

// ---------- subtitles & thumbnails (cached) ----------

const pending = new Map(); // cache file -> Promise

function once(file, make) {
  if (fs.existsSync(file)) return Promise.resolve(file);
  if (!pending.has(file)) {
    const p = make().then(() => file).finally(() => pending.delete(file));
    pending.set(file, p);
  }
  return pending.get(file);
}

async function subtitleFile(item, key) {
  const info = await probe(item);
  const sub = info.subs.find((s) => s.key === key);
  if (!sub) return null;
  const out = path.join(CACHE_DIR, `${item.id}-${Math.floor(item.mtime)}-${key}.vtt`);
  const input = sub.file || item.path;
  const map = sub.file ? '0:s:0' : `0:${sub.stream}`;
  return once(out, () => run(CONFIG.ffmpeg, ['-hide_banner', '-v', 'error', '-y', '-i', input, '-map', map, '-f', 'webvtt', out]));
}

// Thumbnails decode real video frames, so only make two at a time.
let thumbSlots = 2;
const thumbQueue = [];
function withThumbSlot(fn) {
  return new Promise((resolve, reject) => {
    const go = () => {
      thumbSlots--;
      fn().then(resolve, reject).finally(() => {
        thumbSlots++;
        if (thumbQueue.length) thumbQueue.shift()();
      });
    };
    thumbSlots > 0 ? go() : thumbQueue.push(go);
  });
}

// hero=true makes a wide backdrop for the landing page banner.
async function thumbnail(item, hero = false) {
  const out = path.join(CACHE_DIR, `${item.id}-${Math.floor(item.mtime)}${hero ? '-hero' : ''}.jpg`);
  return once(out, () =>
    withThumbSlot(async () => {
      const info = await probe(item);
      const at = info.duration ? Math.min(info.duration * 0.2, 600) : 5;
      const scale = hero ? 'scale=1600:-2' : 'scale=480:-2';
      await run(CONFIG.ffmpeg, ['-hide_banner', '-v', 'error', '-y', '-ss', at.toFixed(1), '-i', item.path, '-frames:v', '1', '-vf', scale, '-q:v', hero ? '3' : '4', out]);
    })
  );
}

// ---------- http ----------

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json' };
const VIDEO_MIME = { '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.mov': 'video/quicktime' };

function send(res, status, body, type = 'application/json') {
  const data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(data);
}

// Safari requires byte-range support to play (and seek) video.
async function streamFile(req, res, file, type) {
  let stat;
  try {
    stat = await fsp.stat(file);
  } catch {
    return send(res, 404, { error: 'File not found' });
  }
  const size = stat.size;
  const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
  const headers = { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-cache' };
  if (!range) {
    res.writeHead(200, { ...headers, 'Content-Length': size });
    if (req.method === 'HEAD') return res.end();
    return fs.createReadStream(file).pipe(res);
  }
  let start = range[1] === '' ? size - Number(range[2]) : Number(range[1]);
  let end = range[1] !== '' && range[2] !== '' ? Number(range[2]) : size - 1;
  end = Math.min(end, size - 1);
  if (start < 0 || start > end || start >= size) {
    res.writeHead(416, { 'Content-Range': `bytes */${size}` });
    return res.end();
  }
  res.writeHead(206, { ...headers, 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 });
  if (req.method === 'HEAD') return res.end();
  const stream = fs.createReadStream(file, { start, end });
  stream.pipe(res);
  res.on('close', () => stream.destroy());
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const parts = url.pathname.split('/').filter(Boolean);
  // /api/<action>/<id>  or  /video|thumb|subs/<id>/...
  const item = library.get(parts[0] === 'api' ? parts[2] : parts[1]) || null;

  if (parts[0] === 'api') {
    if (parts[1] === 'library') return send(res, 200, (await scanLibrary()).map(({ path: _p, ...rest }) => ({ ...rest, ...jobStatus(rest) })));
    if (!item) return send(res, 404, { error: 'Unknown movie — refresh the library.' });
    if (parts[1] === 'info') {
      const info = await probe(item);
      return send(res, 200, { ...publicItem(item), duration: info.duration, mode: info.mode, subs: info.subs.map(({ key, lang, label }) => ({ key, lang, label })), ...jobStatus(item) });
    }
    if (parts[1] === 'prepare' && req.method === 'POST') return send(res, 200, await prepare(item));
    if (parts[1] === 'status') return send(res, 200, jobStatus(item));
    return send(res, 404, { error: 'Not found' });
  }

  if (parts[0] === 'video' && item) {
    const info = await probe(item);
    if (info.mode === 'direct') return streamFile(req, res, item.path, VIDEO_MIME[path.extname(item.path).toLowerCase()] || 'video/mp4');
    if (jobStatus(item).state !== 'ready') return send(res, 409, { error: 'This movie is still being prepared.' });
    return streamFile(req, res, cachedMp4(item), 'video/mp4');
  }
  if (parts[0] === 'subs' && item && parts[2]) {
    const file = await subtitleFile(item, parts[2].replace(/\.vtt$/, ''));
    if (!file) return send(res, 404, { error: 'No such subtitle' });
    return send(res, 200, await fsp.readFile(file), 'text/vtt; charset=utf-8');
  }
  if (parts[0] === 'thumb' && item) {
    if (item.downloading) return send(res, 404, { error: 'Still downloading' });
    const file = await thumbnail(item, url.searchParams.get('size') === 'hero');
    res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'max-age=604800' });
    return fs.createReadStream(file).pipe(res);
  }

  // Static files from ./public
  const rel = url.pathname === '/' || parts[0] === 'watch' ? 'index.html' : decodeURIComponent(url.pathname).replace(/^\/+/, '');
  const file = path.join(ROOT, 'public', path.normalize(rel));
  if (!file.startsWith(path.join(ROOT, 'public'))) return send(res, 403, { error: 'Forbidden' });
  try {
    return send(res, 200, await fsp.readFile(file), MIME[path.extname(file)] || 'application/octet-stream');
  } catch {
    return send(res, 404, 'Not found', 'text/plain');
  }
}

function publicItem({ path: _p, ...rest }) {
  return rest;
}

// Home-network IPv4 addresses, real Wi-Fi/Ethernet first. Virtual adapters (Hyper-V, WSL, VirtualBox,
// VPNs) can't be reached from a phone, so they go last.
function lanAddresses() {
  const virtual = /vEthernet|Virtual|VMware|WSL|Hyper-V|Loopback|Bluetooth|Tailscale|ZeroTier|VPN|TAP/i;
  return Object.entries(os.networkInterfaces())
    .flatMap(([name, addrs]) => (addrs || []).map((a) => ({ name, ...a })))
    .filter((a) => a.family === 'IPv4' && !a.internal && !a.address.startsWith('169.254.'))
    .sort((a, b) => virtual.test(a.name) - virtual.test(b.name) || /^192\.168\./.test(b.address) - /^192\.168\./.test(a.address))
    .map((a) => a.address);
}

// Delete prepared copies, thumbnails and subtitles. Files still being written are left alone.
async function clearCache() {
  let freed = 0;
  for (const name of await fsp.readdir(CACHE_DIR).catch(() => [])) {
    if (name.endsWith('.part')) continue;
    const file = path.join(CACHE_DIR, name);
    try {
      freed += (await fsp.stat(file)).size;
      await fsp.unlink(file);
    } catch {}
  }
  for (const [id, job] of jobs) if (job.state !== 'working') jobs.delete(id);
  return freed;
}

async function cacheSize() {
  let total = 0;
  for (const name of await fsp.readdir(CACHE_DIR).catch(() => [])) total += (await fsp.stat(path.join(CACHE_DIR, name)).catch(() => ({ size: 0 }))).size;
  return total;
}

let server = null;

function start() {
  return new Promise((resolve, reject) => {
    server = http.createServer((req, res) => {
      handle(req, res).catch((err) => {
        console.error(`${req.method} ${req.url}: ${err.message}`);
        if (!res.headersSent) send(res, 500, { error: err.message });
        else res.destroy();
      });
    });
    server.once('error', reject);
    server.listen(CONFIG.port, '0.0.0.0', () => {
      server.off('error', reject);
      resolve(server.address().port);
    });
  });
}

function stop() {
  return new Promise((resolve) => {
    if (!server) return resolve();
    server.closeAllConnections();
    server.close(() => resolve());
    server = null;
  });
}

module.exports = { configure, start, stop, scanLibrary, lanAddresses, clearCache, cacheSize, VIDEO_EXT };

if (require.main === module) {
  configure(loadConfig());
  start()
    .then(async (port) => {
      const movies = await scanLibrary();
      console.log('\n  HOMEFLIX is running.\n');
      console.log(`  Library: ${movies.length} video(s) from ${CONFIG.folders.join(', ')}`);
      console.log('  On your iPad (same Wi-Fi), open Safari and go to:\n');
      for (const ip of lanAddresses()) console.log(`     http://${ip}:${port}`);
      console.log(`\n  On this laptop: http://localhost:${port}`);
      console.log('  Press Ctrl+C to stop.\n');
    })
    .catch((err) => {
      if (err.code === 'EADDRINUSE') console.error(`Port ${CONFIG.port} is already in use. Is HOMEFLIX already running? Or set "port" in config.json.`);
      else console.error(err);
      process.exit(1);
    });
}
