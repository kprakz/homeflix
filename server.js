// HOMEFLIX — stream the movies on this laptop to an iPad (or any browser) on the same Wi-Fi.
// No dependencies: Node's standard library plus ffmpeg/ffprobe for files Safari can't play directly.

const http = require('http');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const dgram = require('dgram');
const { spawn } = require('child_process');

const ROOT = __dirname;
const APP_VERSION = (() => { try { return require('./package.json').version; } catch { return '0.0.0'; } })();
const API_VERSION = 1;
let CONFIG = { port: 8080, folders: [], minSizeMB: 5, cacheDir: 'cache', ffmpeg: 'ffmpeg', ffprobe: 'ffprobe' };
let CACHE_DIR = path.resolve(ROOT, CONFIG.cacheDir);
const VIDEO_EXT = new Set(['.mp4', '.m4v', '.mov', '.mkv', '.avi', '.webm', '.wmv', '.flv', '.ts', '.m2ts', '.mpg', '.mpeg', '.3gp']);
const DIRECT_CONTAINERS = new Set(['.mp4', '.m4v', '.mov']);
// Audio every browser plays (iPhone, Android, TVs). Dolby AC3/E-AC3, DTS etc. are silent on many
// Android and TV browsers, so those are converted to stereo AAC.
const UNIVERSAL_AUDIO = new Set(['aac', 'mp3']);
const TEXT_SUBS = new Set(['subrip', 'ass', 'ssa', 'mov_text', 'webvtt', 'text']);

// The desktop app calls this with its own settings; `node server.js` uses config.json.
// (config.json may give "pin": "1234" in plain text; it's kept hashed.)
function configure(options) {
  CONFIG = { ...CONFIG, ...options };
  if (typeof CONFIG.pin === 'string' || typeof CONFIG.pin === 'number') CONFIG.pin = /^\d{4,8}$/.test(String(CONFIG.pin)) ? hashPin(CONFIG.pin) : null;
  if (!CONFIG.secret) CONFIG.secret = crypto.randomBytes(16).toString('hex');
  CACHE_DIR = path.resolve(ROOT, CONFIG.cacheDir);
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  loadDevices();
}

function loadConfig() {
  const defaults = {
    port: 8080,
    // Nothing is shared until the owner names folders in config.json (or picks them in the app):
    // pre-selecting Videos/Downloads would serve files they never agreed to share.
    folders: [],
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

let library = new Map(); // id ->{ id, path, name, title, year, size, mtime, folder }

function idFor(file) {
  return crypto.createHash('sha1').update(file.toLowerCase()).digest('hex').slice(0, 16);
}

// "Insidious.Chapter.2.2013.1080p.10bit.BluRay.x265.mkv" -> { title: "Insidious Chapter 2", year: "2013" }
function prettyName(file) {
  let base = path.basename(file, path.extname(file)).replace(/[._]+/g, ' ').replace(/\s+/g, ' ').trim();
  // Leading channel and site tags from Telegram/torrent names: "@WMR ", "[TamilMV] ", "www site com - "
  base = base.replace(/^(?:(?:@\S+|\[[^\]]*\]|www \S+ (?:com|net|org|in|me|to)\b)\s*-?\s*)+/i, '').trim() || base;
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

// Requests arriving together share one scan, and the new list replaces the old one only when it's
// complete, so a lookup never sees a half-built library.
let scanning = null;
function scanLibrary() {
  if (!scanning) scanning = buildLibrary().finally(() => (scanning = null));
  return scanning;
}

// "Show S01E05" -> { show: "Show", season: 1, ep: 5 }. Shared by the web and Android clients so both
// group episodes the same way.
const SERIES_RE = /^(.*?)[\s-]*S(\d{1,2})\s*E(\d{1,3})\b/i;
function seriesInfo(title) {
  const m = String(title || '').match(SERIES_RE);
  return m ? { show: m[1].trim(), season: Number(m[2]), ep: Number(m[3]) } : {};
}

async function buildLibrary() {
  const files = [];
  for (const folder of CONFIG.folders) await walk(path.resolve(folder), 0, files);
  const next = new Map();
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
    const { title, year } = prettyName(file);
    next.set(id, {
      id, path: file, title, year, ...seriesInfo(title),
      size: stat.size, mtime: stat.mtimeMs, folder: path.basename(folder), downloading,
    });
  }
  library = next;
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

// ---------- track names ----------
// Files tag tracks with ISO 639 codes ("tam", "en") and often stuff site names into the title
// ("www.1TamilMV.ing", "t.me/…"). Show "Tamil", "English 5.1", "English SDH" instead.
const LANGUAGES = {
  tam: 'Tamil', ta: 'Tamil', tel: 'Telugu', te: 'Telugu', hin: 'Hindi', hi: 'Hindi', mal: 'Malayalam', ml: 'Malayalam',
  kan: 'Kannada', kn: 'Kannada', ben: 'Bengali', bn: 'Bengali', mar: 'Marathi', mr: 'Marathi', guj: 'Gujarati', gu: 'Gujarati',
  pan: 'Punjabi', pa: 'Punjabi', urd: 'Urdu', ur: 'Urdu', ori: 'Odia', ory: 'Odia', or: 'Odia', asm: 'Assamese', sin: 'Sinhala',
  nep: 'Nepali', eng: 'English', en: 'English', spa: 'Spanish', es: 'Spanish', fre: 'French', fra: 'French', fr: 'French',
  ger: 'German', deu: 'German', de: 'German', ita: 'Italian', it: 'Italian', por: 'Portuguese', pt: 'Portuguese',
  rus: 'Russian', ru: 'Russian', jpn: 'Japanese', ja: 'Japanese', kor: 'Korean', ko: 'Korean', chi: 'Chinese', zho: 'Chinese',
  zh: 'Chinese', ara: 'Arabic', ar: 'Arabic', tur: 'Turkish', tr: 'Turkish', tha: 'Thai', th: 'Thai', ind: 'Indonesian',
  may: 'Malay', msa: 'Malay', vie: 'Vietnamese', vi: 'Vietnamese', fil: 'Filipino', tgl: 'Tagalog', per: 'Persian', fas: 'Persian',
  heb: 'Hebrew', pol: 'Polish', dut: 'Dutch', nld: 'Dutch', swe: 'Swedish', nor: 'Norwegian', dan: 'Danish', fin: 'Finnish',
  gre: 'Greek', ell: 'Greek', cze: 'Czech', ces: 'Czech', hun: 'Hungarian', rum: 'Romanian', ron: 'Romanian', ukr: 'Ukrainian',
};

function trackLabel(s, fallback) {
  const tags = s.tags || {};
  const code = String(tags.language || '').toLowerCase();
  const lang = LANGUAGES[code] || '';
  let title = String(tags.title || '').trim();
  // Site names, Telegram handles and links say nothing about the track.
  if (/www\.|https?:|t\.me|@|\.(com|net|org|in|ing|me|to|cc|tv)\b/i.test(title)) title = '';
  // "ENG SDH" -> "SDH": drop the language itself, which the name already says.
  if (lang) title = title.replace(new RegExp(`\\b(${code}|${lang})\\b`, 'ig'), '').replace(/^[\s\-–|:,]+|[\s\-–|:,]+$/g, '').trim();
  const channels = s.channels === 6 ? ' 5.1' : s.channels === 8 ? ' 7.1' : '';
  const name = [lang, title].filter(Boolean).join(' ') || fallback;
  return s.codec_type === 'audio' ? name + channels : name;
}

function probe(item) {
  const key = `${item.id}-${item.size}-${item.mtime}`; // re-probe once a download grows
  if (!probes.has(key)) {
    const p = run(CONFIG.ffprobe, ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', item.path]).then((out) => {
      const data = JSON.parse(out);
      const streams = data.streams || [];
      const video = streams.find((s) => s.codec_type === 'video' && !(s.disposition && s.disposition.attached_pic));
      // Every audio track, in file order. Track 0 is what plays unless a device asks for another.
      const audios = streams
        .filter((s) => s.codec_type === 'audio')
        .map((s, n) => ({ n, index: s.index, codec: s.codec_name, lang: String((s.tags && s.tags.language) || 'und').toLowerCase(), label: trackLabel(s, `Audio ${n + 1}`) }));
      // Same name twice ("English", "English"): number them so they can be told apart.
      for (const a of audios) if (audios.filter((b) => b.label === a.label).length > 1) a.label += ` (${a.n + 1})`;
      const audio = streams.find((s) => s.codec_type === 'audio');
      const subs = streams
        .filter((s) => s.codec_type === 'subtitle' && TEXT_SUBS.has(s.codec_name))
        .map((s, i) => ({ key: `e${s.index}`, stream: s.index, n: i, lang: (s.tags && s.tags.language) || 'und', label: trackLabel(s, `Subtitles ${i + 1}`) }));
      for (const side of sidecarSubs(item.path)) subs.push(side);
      return {
        duration: Number(data.format && data.format.duration) || 0,
        video,
        audio,
        audios,
        subs,
        mode: playMode(item.path, video, audio),
        videoCodec: video ? video.codec_name : null,
        tenBit: !!video && /1[02]/.test(video.pix_fmt || ''),
        height: video ? video.height : 0,
      };
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

// direct: the file plays as-is. remux: copy the video into an MP4 (seconds).
// transcode: the video itself must be re-encoded, which happens as an HLS stream (see below).
// HEVC plays only on some devices; the player asks for the HLS stream when it can't.
function playMode(file, video, audio) {
  if (!video) return 'transcode';
  const videoOk = videoCopyable(video);
  const audioOk = !audio || UNIVERSAL_AUDIO.has(audio.codec_name);
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

const jobs = new Map(); // "id:audio" -> { state, progress, error }

// One copy per audio language, made when a device asks for that language. Browsers on Android and
// TVs can't switch between audio tracks in a file, so each copy carries just one.
function cachedMp4(item, audio = 0) {
  // Include size+mtime so a replaced file gets prepared again; "u" marks the universal-audio format
  // (and is the first audio track, as copies were before languages could be chosen).
  return path.join(CACHE_DIR, `${item.id}-${item.size}-${Math.floor(item.mtime)}-${audio ? `a${audio}` : 'u'}.mp4`);
}

function jobStatus(item, audio = 0) {
  if (fs.existsSync(cachedMp4(item, audio))) return { state: 'ready', progress: 1 };
  const key = `${item.id}:${audio}`;
  const job = jobs.get(key);
  if (job && job.state === 'ready') jobs.delete(key); // its copy was removed to free space
  return jobs.get(key) || { state: 'idle', progress: 0 };
}

// ?audio=N from a request: which audio track (0 = the file's first).
function audioParam(url) {
  const n = Number(url.searchParams.get('audio'));
  return Number.isInteger(n) && n > 0 && n < 64 ? n : 0;
}

// ---------- disk space ----------
// Converted copies are as big as the movie. Keep 2 GB free on the laptop by deleting the copies
// watched longest ago (their mtime is bumped whenever they're streamed).
const RESERVE = 2 * 1024 ** 3;

async function freeBytes() {
  try {
    const s = await fsp.statfs(CACHE_DIR);
    return s.bavail * s.bsize;
  } catch {
    return Infinity;
  }
}

async function makeRoom(need, keep) {
  let free = await freeBytes();
  if (free >= need + RESERVE) return true;
  const busy = new Set([...hls.values()].filter((st) => st.job).map((st) => st.dir));
  const candidates = [];
  for (const e of await fsp.readdir(CACHE_DIR, { withFileTypes: true }).catch(() => [])) {
    const p = path.join(CACHE_DIR, e.name);
    const isCopy = e.isDirectory() ? e.name.startsWith('hls-') : e.name.endsWith('.mp4');
    if (!isCopy || p === keep || busy.has(p)) continue;
    const st = await fsp.stat(p).catch(() => null);
    if (st) candidates.push({ p, used: st.mtimeMs, dir: e.isDirectory() });
  }
  candidates.sort((a, b) => a.used - b.used);
  const removed = new Set();
  for (const c of candidates) {
    if (free >= need + RESERVE) break;
    const size = c.dir ? await dirSize(c.p) : (await fsp.stat(c.p).catch(() => ({ size: 0 }))).size;
    await fsp.rm(c.p, { recursive: true, force: true }).catch(() => {});
    removed.add(c.p);
    free += size;
    console.log(`Freed ${(size / 1e9).toFixed(1)} GB by removing an old converted copy`);
  }
  for (const [id, st] of hls) if (removed.has(st.dir)) hls.delete(id);
  return free >= need + RESERVE;
}

function touch(file) {
  const now = new Date();
  fs.utimes(file, now, now, () => {});
}

async function prepare(item, audio = 0) {
  const existing = jobStatus(item, audio);
  if (existing.state === 'ready' || existing.state === 'working') return existing;
  if (item.downloading) return { state: 'error', progress: 0, error: 'This file is still downloading. Try again once it finishes.' };
  if (!(await makeRoom(item.size * 1.02))) {
    return { state: 'error', progress: 0, code: 'nospace', error: `Not enough free space on the laptop to prepare this (needs about ${Math.ceil((item.size * 1.02 + RESERVE) / 1e9)} GB free).` };
  }
  const job = { state: 'working', progress: 0, error: null };
  jobs.set(`${item.id}:${audio}`, job);

  try {
    const info = await probe(item);
    if (info.mode === 'transcode') throw new Error('This video needs converting while it plays; the player uses the compatible stream for it.');
    const track = info.audios[audio] || info.audios[0];
    const out = cachedMp4(item, audio);
    const tmp = `${out}.part`;
    // Map the real video stream: some files carry cover art as an extra "video" stream.
    const args = ['-hide_banner', '-v', 'error', '-y', '-i', item.path, '-map', `0:${info.video.index}`, '-map', track ? `0:${track.index}` : '0:a:0?'];
    args.push('-c:v', 'copy');
    if (info.video.codec_name === 'hevc') args.push('-tag:v', 'hvc1');
    // Copying audio every device plays is far faster than re-encoding it; otherwise make stereo AAC.
    if (track && UNIVERSAL_AUDIO.has(track.codec)) args.push('-c:a', 'copy');
    else args.push('-c:a', 'aac', '-ac', '2', '-b:a', '192k');
    args.push('-movflags', '+faststart', '-f', 'mp4', '-progress', 'pipe:1', '-nostats', tmp);

    console.log(`Preparing "${item.title}"${track && info.audios.length > 1 ? ` in ${track.label}` : ''} (${info.mode})…`);
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

// ---------- HLS: H.264 for devices that can't play the original video ----------
// Many Android phones, TV browsers and PCs can't decode HEVC (x265), especially 10-bit. For those,
// the movie is converted to H.264 while it plays, as 4-second HLS segments. The playlist lists every
// segment up front, so the seek bar covers the whole movie; a segment that isn't made yet is waited
// for, and seeking far ahead restarts the converter at that point. Finished segments stay cached.

const SEG = 4; // seconds per segment
const AHEAD = 45; // segments the converter may be behind a request before it's restarted there
const hls = new Map(); // id -> { item, dir, total, done: Set<number>, job, profile, error }

let encoderList = null;
// Hardware encoders are ~3x faster than the CPU and keep the laptop cool; use the first that works.
function workingEncoders() {
  if (!encoderList) {
    encoderList = (async () => {
      const found = [];
      for (const enc of ['h264_qsv', 'h264_nvenc', 'h264_amf']) {
        const pix = enc === 'h264_qsv' ? 'nv12' : 'yuv420p';
        try {
          await run(CONFIG.ffmpeg, ['-hide_banner', '-v', 'error', '-f', 'lavfi', '-i', 'color=size=640x360:duration=0.5', '-pix_fmt', pix, '-c:v', enc, '-f', 'null', '-']);
          found.push(enc);
        } catch {}
      }
      console.log(`H.264 encoders: ${[...found, 'libx264'].join(', ')}`);
      return found;
    })();
  }
  return encoderList;
}

// Ways to convert, fastest first. If one fails on a file, the next is tried.
async function hlsProfiles(info) {
  const encoders = await workingEncoders();
  const fit = "'min(iw,1920)'"; // phones and TVs handle 1080p H.264 best
  const profiles = [];
  if (encoders.includes('h264_qsv')) {
    const enc = ['-c:v', 'h264_qsv', '-preset', 'veryfast', '-global_quality', '23', '-forced_idr', '1'];
    if (['hevc', 'h264'].includes(info.videoCodec)) profiles.push({ name: 'Intel Quick Sync', pre: ['-hwaccel', 'qsv', '-hwaccel_output_format', 'qsv'], vf: `scale_qsv=w=${fit}:h=-1:format=nv12`, enc });
    profiles.push({ name: 'Intel Quick Sync', pre: [], vf: `scale=${fit}:-2,format=nv12`, enc });
  }
  if (encoders.includes('h264_nvenc')) profiles.push({ name: 'NVIDIA NVENC', pre: [], vf: `scale=${fit}:-2,format=yuv420p`, enc: ['-c:v', 'h264_nvenc', '-preset', 'p4', '-rc', 'vbr', '-cq', '23', '-b:v', '0', '-forced-idr', '1'] });
  if (encoders.includes('h264_amf')) profiles.push({ name: 'AMD AMF', pre: [], vf: `scale=${fit}:-2,format=yuv420p`, enc: ['-c:v', 'h264_amf', '-quality', 'balanced', '-rc', 'cqp', '-qp_i', '22', '-qp_p', '24', '-header_insertion_mode', 'idr'] });
  profiles.push({ name: 'CPU', pre: [], vf: `scale=${fit}:-2,format=yuv420p`, enc: ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '22', '-forced-idr', '1'] });
  return profiles;
}

// One converted stream per movie and audio language.
function hlsState(item, info, audio = 0) {
  const dir = path.join(CACHE_DIR, `hls-${item.id}-${item.size}-${Math.floor(item.mtime)}${audio ? `-a${audio}` : ''}`);
  const key = `${item.id}:${audio}`;
  let st = hls.get(key);
  if (!st || st.dir !== dir) {
    if (st) stopHls(st);
    let done = [];
    try {
      done = JSON.parse(fs.readFileSync(path.join(dir, 'segments.json'), 'utf8'));
    } catch {}
    st = { item, audio, dir, total: Math.max(1, Math.ceil(info.duration / SEG)), duration: info.duration, done: new Set(done), job: null, profile: 0, error: null, starting: null };
    hls.set(key, st);
  }
  return st;
}

// key: the ?k= the playlist was asked with, passed on to its segments (AirPlay sends no cookies).
function hlsPlaylist(st, key) {
  const q = key ? `?k=${encodeURIComponent(key)}` : '';
  const lines = ['#EXTM3U', '#EXT-X-VERSION:3', `#EXT-X-TARGETDURATION:${SEG + 1}`, '#EXT-X-MEDIA-SEQUENCE:0', '#EXT-X-PLAYLIST-TYPE:VOD'];
  for (let i = 0; i < st.total; i++) {
    const len = i === st.total - 1 ? st.duration - SEG * i : SEG;
    lines.push(`#EXTINF:${Math.max(0.1, len).toFixed(3)},`, `${i}.ts${q}`);
  }
  lines.push('#EXT-X-ENDLIST', '');
  return lines.join('\n');
}

const segFile = (st, n) => path.join(st.dir, `seg${String(n).padStart(5, '0')}.ts`);

function stopHls(st) {
  const job = st.job;
  if (!job) return;
  job.killed = true;
  clearInterval(job.timer);
  job.child.kill();
  st.job = null;
}

// ffmpeg lists a segment in its own playlist once the segment is complete.
function readProgress(st, job) {
  let text = '';
  try {
    text = fs.readFileSync(job.list, 'utf8');
  } catch {
    return;
  }
  let changed = false;
  for (const m of text.matchAll(/seg(\d+)\.ts/g)) {
    const n = Number(m[1]);
    if (!st.done.has(n)) {
      st.done.add(n);
      changed = true;
    }
    job.produced = Math.max(job.produced, n - job.from + 1);
    job.next = Math.max(job.next, n + 1);
  }
  if (changed) fs.writeFile(path.join(st.dir, 'segments.json'), JSON.stringify([...st.done]), () => {});
  // Ran into segments an earlier run already made: stop, and pick up again where needed.
  if (st.job === job && job.next > job.from && st.done.has(job.next)) stopHls(st);
}

async function startHls(st, info, from) {
  stopHls(st);
  // Room for the rest of the movie at roughly 0.35 MB per second of 1080p H.264, else at least 1 GB.
  const need = (st.total - st.done.size) * SEG * 0.35e6;
  if (!(await makeRoom(need, st.dir)) && (await freeBytes()) < 1024 ** 3) {
    st.error = 'The laptop is out of disk space. Free some space and try again.';
    return;
  }
  fs.mkdirSync(st.dir, { recursive: true });
  const profiles = await hlsProfiles(info);
  const profile = profiles[Math.min(st.profile, profiles.length - 1)];
  const list = path.join(st.dir, 'run.m3u8');
  try {
    fs.unlinkSync(list);
  } catch {}
  const at = String(from * SEG);
  const args = ['-hide_banner', '-v', 'error', '-y', ...profile.pre, '-ss', at, '-i', st.item.path, '-map', `0:${info.video.index}`];
  const track = info.audios[st.audio] || info.audios[0];
  if (track) args.push('-map', `0:${track.index}`);
  args.push('-vf', profile.vf, ...profile.enc, '-force_key_frames', `expr:gte(t,n_forced*${SEG})`);
  args.push('-c:a', 'aac', '-ac', '2', '-b:a', '160k', '-output_ts_offset', at);
  args.push('-f', 'hls', '-hls_time', String(SEG), '-hls_list_size', '0', '-start_number', String(from), '-hls_segment_filename', path.join(st.dir, 'seg%05d.ts'), list);

  console.log(`Converting "${st.item.title}" for this device from ${Math.round(from * SEG / 60)} min (${profile.name})…`);
  const job = { child: spawn(CONFIG.ffmpeg, args, { windowsHide: true }), from, next: from, produced: 0, list, profile: profile.name, killed: false };
  let stderr = '';
  job.child.stderr.on('data', (d) => (stderr += d));
  job.timer = setInterval(() => readProgress(st, job), 400);
  job.child.on('error', (err) => (stderr += err.message));
  job.child.on('close', (code) => {
    clearInterval(job.timer);
    readProgress(st, job);
    if (st.job === job) st.job = null;
    if (job.killed || code === 0) return;
    // Failed before making anything: this way of converting doesn't suit the file, try the next one.
    if (job.produced === 0 && st.profile < profiles.length - 1) {
      console.error(`${profile.name} couldn't convert "${st.item.title}", trying another way: ${stderr.trim().split('\n').pop()}`);
      st.profile++;
      startHls(st, info, from);
    } else if (job.produced === 0) {
      st.error = stderr.trim().split('\n').pop() || `ffmpeg exited ${code}`;
      console.error(`Couldn't convert "${st.item.title}": ${st.error}`);
    }
  });
  st.job = job;
  st.error = null;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function hlsSegment(req, res, item, n, audio) {
  const info = await probe(item);
  const st = hlsState(item, info, audio);
  if (!(n >= 0 && n < st.total)) return send(res, 404, { error: 'No such segment' });
  const deadline = Date.now() + 45000;
  while (!st.done.has(n)) {
    if (st.error && !st.job) return send(res, 500, { error: st.error });
    const job = st.job;
    const inReach = job && n >= job.from && n <= job.next + AHEAD;
    if (!inReach && !st.starting) st.starting = startHls(st, info, n).finally(() => (st.starting = null));
    if (st.starting) await st.starting;
    if (Date.now() > deadline || req.destroyed) return send(res, 503, { error: 'Still converting, try again' });
    await sleep(250);
  }
  if (n % 15 === 0) touch(st.dir); // recently watched: keep it when making room
  return streamFile(req, res, segFile(st, n), 'video/mp2t');
}

async function dirSize(dir) {
  let total = 0;
  for (const e of await fsp.readdir(dir, { withFileTypes: true }).catch(() => [])) {
    const p = path.join(dir, e.name);
    total += e.isDirectory() ? await dirSize(p) : (await fsp.stat(p).catch(() => ({ size: 0 }))).size;
  }
  return total;
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

// ---------- who may watch ----------

// Only devices on the home network (and this computer) get in. Someone on the internet reaching the
// port — a router port-forward, a server with a public address — is turned away.
function isLocalAddress(ip) {
  ip = String(ip || '').toLowerCase().replace(/^::ffff:/, '');
  if (ip === '::1') return true;
  const v4 = /^(\d+)\.(\d+)\.\d+\.\d+$/.exec(ip);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
  }
  return /^f[cd][0-9a-f]{2}:/.test(ip) || /^fe[89ab][0-9a-f]:/.test(ip); // IPv6 private and link-local
}

// The page must be opened by address (192.168.1.20:8080, localhost) or a home name (laptop.local), not
// through some website's domain: that stops a web page on a phone from reading the library through it.
function isLocalHost(host) {
  const name = String(host || '').toLowerCase().replace(/:\d+$/, '').replace(/^\[|\]$/g, '');
  return !name || name === 'localhost' || /^[\d.]+$/.test(name) || name.includes(':') || !name.includes('.') ||
    /\.(local|lan|home|internal|localhost|home\.arpa)$/.test(name);
}

// Optional PIN. CONFIG.pin is { salt, hash } (scrypt) or null; CONFIG.secret signs the device cookie,
// so changing or removing the PIN signs every device out.
const PIN_COOKIE = 'homeflix_key';
function hashPin(pin, salt = crypto.randomBytes(16).toString('hex')) {
  return { salt, hash: crypto.scryptSync(String(pin), salt, 32).toString('hex') };
}
function deviceKey() {
  return CONFIG.pin ? crypto.createHmac('sha256', String(CONFIG.secret || '')).update(CONFIG.pin.hash).digest('hex').slice(0, 32) : null;
}
function keyFrom(req, url) {
  const cookie = /(?:^|;\s*)homeflix_key=([0-9a-f]+)/.exec(req.headers.cookie || '');
  // Apps that aren't browsers send the key as a header instead of a cookie/query string.
  const header = String(req.headers['x-homeflix-key'] || '').toLowerCase();
  return /^[0-9a-f]{16,64}$/.test(header) ? header : url.searchParams.get('k') || (cookie && cookie[1]) || '';
}
function hasKey(req, url) {
  const want = deviceKey();
  if (!want) return true;
  const got = Buffer.from(keyFrom(req, url));
  return got.length === want.length && crypto.timingSafeEqual(got, Buffer.from(want));
}

// Wrong PINs: 5 free tries, then each try waits longer (30 s, 1 min, 2 min … up to an hour).
const pinTries = new Map(); // ip -> { fails, until }
function pinWait(ip) {
  const t = pinTries.get(ip);
  return t && t.until > Date.now() ? Math.ceil((t.until - Date.now()) / 1000) : 0;
}
function checkPin(ip, pin) {
  const t = pinTries.get(ip) || { fails: 0, until: 0 };
  const ok = /^\d{4,8}$/.test(String(pin)) && crypto.timingSafeEqual(Buffer.from(hashPin(pin, CONFIG.pin.salt).hash, 'hex'), Buffer.from(CONFIG.pin.hash, 'hex'));
  if (ok) pinTries.delete(ip);
  else {
    t.fails++;
    if (t.fails >= 5) t.until = Date.now() + Math.min(3600, 30 * 2 ** (t.fails - 5)) * 1000;
    pinTries.set(ip, t);
  }
  return ok;
}

function readBody(req, limit = 1024) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', (d) => { body += d; if (body.length > limit) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(body)); } catch { resolve({}); } });
    req.on('error', () => resolve({}));
  });
}

const keyCookie = (key) => `${PIN_COOKIE}=${key}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax`;

const NOT_HOME = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>HOMEFLIX</title>
<body style="margin:0;display:grid;place-items:center;min-height:100vh;background:#141414;color:#fff;font:17px/1.5 system-ui,sans-serif;text-align:center;padding:24px">
<div><h1 style="color:#e50914;letter-spacing:.04em">HOMEFLIX</h1><p>HOMEFLIX only works on the home network it runs on.<br>Connect to the same Wi-Fi as the computer and open its address again.</p></div>`;

// Returns true when the request may go on; otherwise answers it.
async function gate(req, res, url, parts) {
  const ip = req.socket.remoteAddress;
  if (!isLocalAddress(ip)) {
    res.writeHead(403, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(NOT_HOME);
    return false;
  }
  if (!isLocalHost(req.headers.host)) {
    send(res, 403, 'Open HOMEFLIX by its address, like http://192.168.1.20:8080', 'text/plain; charset=utf-8');
    return false;
  }
  if (parts[0] === 'api' && parts[1] === 'unlock' && req.method === 'POST') {
    if (!CONFIG.pin) return send(res, 200, { ok: true }), false;
    const wait = pinWait(ip);
    if (wait) return send(res, 429, { error: `Too many tries. Wait ${wait} seconds.`, wait }), false;
    const { pin } = await readBody(req);
    if (!checkPin(ip, pin)) {
      const next = pinWait(ip);
      return send(res, 401, { error: next ? `Wrong PIN. Wait ${next} seconds before trying again.` : 'Wrong PIN. Try again.', wait: next }), false;
    }
    const key = deviceKey();
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'Set-Cookie': keyCookie(key) });
    res.end(JSON.stringify({ ok: true, key }));
    return false;
  }
  // Public, read-only description of this server: used by the Android app's connect screen and by
  // anything that wants to check the version before talking to the rest of the API.
  if (parts[0] === 'api' && parts[1] === 'hello') {
    return send(res, 200, {
      app: 'homeflix', name: 'HOMEFLIX', hostname: deviceHostname(), version: APP_VERSION, api: API_VERSION,
      pin: !!CONFIG.pin, port: CONFIG.port,
    }), false;
  }
  const ok = hasKey(req, url);
  if (parts[0] === 'api' && parts[1] === 'auth') return send(res, 200, { pin: !!CONFIG.pin, ok, hostname: deviceHostname(), version: APP_VERSION, api: API_VERSION }), false;
  // A valid ?k= (from the desktop app's "Watch on this laptop", or AirPlay) also signs this browser in.
  if (ok && CONFIG.pin && url.searchParams.has('k') && !/homeflix_key=/.test(req.headers.cookie || '')) res.setHeader('Set-Cookie', keyCookie(deviceKey()));
  // The page itself, its icons and scripts load without the PIN so it can ask for it; movies don't.
  const open = !['api', 'video', 'thumb', 'subs', 'hls'].includes(parts[0]);
  if (ok || open) return true;
  send(res, 401, { error: 'PIN needed', pin: true });
  return false;
}

// ---------- connected devices ----------
// Server Mode shows who is connected, what they opened and how much they transferred. One record
// per IP address (the same device reconnecting keeps its stats), usage is bucketed per day and the
// buckets, totals and watch history survive restarts in devices.json next to the config. Nothing
// ever leaves the network.
const dns = require('dns');
const ACTIVITY_ACTIVE_MS = 60_000;
const ACTIVITY_KEEP_MS = 10 * 60_000;
const STATS_KEEP_DAYS = 800;
const HISTORY_KEEP = 200;     // remembered watches per device
const HISTORY_REPORT = 10;    // watches sent to the app each poll
const SESSION_GAP_MS = 30 * 60_000;   // same title inside half an hour counts as one watch
const SESSION_MAX_MS = 6 * 3_600_000; // never let one watch stretch longer than this
const SAVE_DEBOUNCE_MS = 30_000;
const activity = new Map(); // ip -> record
const openResponses = new Set(); // live responses, so usage shows while a movie is still playing
let devicesLoaded = false;
let devicesSaveTimer = null;

function devicesFile() {
  return path.resolve(CONFIG.dataDir || ROOT, 'devices.json');
}

function dateKey(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function bodyBytes(chunk) {
  return typeof chunk === 'string' || Buffer.isBuffer(chunk) || ArrayBuffer.isView(chunk) ? Buffer.byteLength(chunk) : 0;
}

function deviceName(ua) {
  const s = String(ua || '');
  const os =
    /iPhone/.test(s) ? 'iPhone' :
    /iPad/.test(s) ? 'iPad' :
    /Android/.test(s) ? 'Android' :
    /Windows/.test(s) ? 'Windows' :
    /Macintosh|Mac OS X/.test(s) ? 'Mac' :
    /Linux/.test(s) ? 'Linux' :
    /AppleTV/.test(s) ? 'Apple TV' :
    /SMART-TV|SmartTV|Tizen|Web0S|WebOS/i.test(s) ? 'Smart TV' : '';
  const app =
    /ExoPlayerLib/i.test(s) ? 'HOMEFLIX app' :
    /okhttp/i.test(s) ? 'HOMEFLIX app' :
    /Edg\//.test(s) ? 'Edge' :
    /OPR\//.test(s) ? 'Opera' :
    /Chrome\//.test(s) ? 'Chrome' :
    /Firefox\//.test(s) ? 'Firefox' :
    /Safari\//.test(s) ? 'Safari' : 'Browser';
  return os ? `${app} on ${os}` : app;
}

// So devices stay recognisable even when the router hands out changing addresses: one record per
// IP, plus the network name for that address found with a reverse DNS lookup when there is one.
function isLanIp(ip) {
  return /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip);
}

function lookupHostname(rec) {
  if (rec.hostLooked || !isLanIp(rec.ip)) return;
  rec.hostLooked = true;
  dns.reverse(rec.ip).then((names) => {
    const host = (names || []).find((n) => n && !n.includes('.in-addr.arpa'));
    if (host) {
      rec.host = host.replace(/\.local\.?$/, '').split('.')[0];
      rec.name = rec.host;
      scheduleSave();
    }
  }).catch(() => {});
}

function freshRecord(ip, now) {
  return {
    ip, uas: {}, host: null, client: null, name: null, firstSeen: now, lastSeen: now,
    total: { bytes: 0, seconds: 0, requests: 0 },
    days: {}, history: [],
    open: 0, watchingOpen: 0, mode: 'idle', title: null, session: null,
  };
}

function recordFor(ip, ua) {
  const now = Date.now();
  let rec = activity.get(ip);
  if (!rec) {
    rec = freshRecord(ip, now);
    activity.set(ip, rec);
    lookupHostname(rec);
  }
  if (ua) {
    rec.uas[ua] = (rec.uas[ua] || 0) + 1;
    // Prefer a name that actually describes the device ("Chrome on Windows") over the generic
    // fallback ("Browser") that one-off probes and unknown clients produce, then break ties by
    // how often that user agent has been seen.
    let best = null;
    let bestScore = -1;
    for (const [agent, count] of Object.entries(rec.uas)) {
      const label = deviceName(agent);
      const score = (label === 'Browser' ? 0 : 1) * 1e9 + count;
      if (score > bestScore || (score === bestScore && agent === ua)) { best = label; bestScore = score; }
    }
    rec.client = best;
    if (!rec.host) rec.name = rec.client;
  }
  return rec;
}

function loadDevices() {
  if (devicesLoaded) return;
  devicesLoaded = true;
  let array = [];
  try {
    array = JSON.parse(fs.readFileSync(devicesFile(), 'utf8'));
  } catch {}
  if (!Array.isArray(array)) return;
  for (const item of array) {
    if (!item || typeof item.ip !== 'string' || !item.ip) continue;
    const now = Date.now();
    const rec = Object.assign(freshRecord(item.ip, now), {
      uas: item.uas && typeof item.uas === 'object' ? item.uas : {},
      host: typeof item.host === 'string' ? item.host : null,
      name: typeof item.name === 'string' ? item.name : null,
      firstSeen: Number(item.firstSeen) || now,
      lastSeen: Number(item.lastSeen) || now,
      total: item.total && typeof item.total === 'object' ? item.total : freshRecord(item.ip, now).total,
      days: item.days && typeof item.days === 'object' ? item.days : {},
      history: Array.isArray(item.history) ? item.history : [],
    });
    if (rec.host) rec.hostLooked = true;
    trimRecord(rec);
    activity.set(rec.ip, rec);
  }
}

function dayBucket(rec, key) {
  return rec.days[key] || (rec.days[key] = { bytes: 0, seconds: 0, requests: 0 });
}

// Inclusive sum over the daily buckets whose date key falls in [from, to] (YYYY-MM-DD strings).
function rangeTotals(rec, from, to) {
  const out = { bytes: 0, seconds: 0, requests: 0 };
  for (const [key, bucket] of Object.entries(rec.days)) {
    if (key >= from && key <= to) {
      out.bytes += bucket.bytes || 0;
      out.seconds += bucket.seconds || 0;
      out.requests += bucket.requests || 0;
    }
  }
  return out;
}

function trimRecord(rec) {
  const keys = Object.keys(rec.days).sort();
  if (keys.length > STATS_KEEP_DAYS) {
    for (const key of keys.slice(0, keys.length - STATS_KEEP_DAYS)) delete rec.days[key];
  }
  if (rec.history.length > HISTORY_KEEP) rec.history.length = HISTORY_KEEP;
}

function saveDevices() {
  devicesSaveTimer = null;
  loadDevices();
  for (const rec of activity.values()) {
    flushSession(rec);
    trimRecord(rec);
  }
  const array = [...activity.values()]
    .sort((a, b) => b.lastSeen - a.lastSeen)
    .map((rec) => ({
      ip: rec.ip, uas: rec.uas, host: rec.host, name: rec.name,
      firstSeen: rec.firstSeen, lastSeen: rec.lastSeen,
      total: rec.total, days: rec.days, history: rec.history,
    }));
  const file = devicesFile();
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const temp = `${file}.tmp`;
    fs.writeFileSync(temp, JSON.stringify(array));
    fs.renameSync(temp, file);
  } catch (err) {
    console.error(`devices.json: ${err.message}`);
  }
}

function scheduleSave() {
  if (!devicesSaveTimer && devicesLoaded) {
    devicesSaveTimer = setTimeout(saveDevices, SAVE_DEBOUNCE_MS);
    devicesSaveTimer.unref?.();
  }
}

// A "watch" starts when a device opens a title and keeps growing while the player keeps fetching
// from it; half an hour without the device touching that title ends the entry.
function flushSession(rec) {
  const session = rec.session;
  if (!session) return;
  rec.session = null;
  const seconds = Math.min(SESSION_MAX_MS, Math.max(0, session.lastAt - session.startedAt));
  if (seconds >= 2000 || session.bytes >= 512 * 1024) {
    rec.history.unshift({
      ts: session.startedAt, id: session.id, title: session.title,
      seconds: Math.round(seconds / 1000), bytes: session.bytes,
    });
    if (rec.history.length > HISTORY_KEEP) rec.history.length = HISTORY_KEEP;
    scheduleSave();
  }
}

function watchItem(rec, item) {
  if (!item) return;
  const now = Date.now();
  if (rec.session) {
    if (rec.session.id === item.id) {
      if (now - rec.session.startedAt > SESSION_MAX_MS || now - rec.session.lastAt > SESSION_GAP_MS) flushSession(rec);
      else {
        rec.session.lastAt = now;
        return;
      }
    } else {
      flushSession(rec);
    }
  }
  rec.session = { id: item.id, title: watchTitle(item) || item.id, startedAt: now, lastAt: now, bytes: 0 };
}

function reachItem(rec, item) {
  if (!item) return;
  if (rec.session && rec.session.id === item.id) rec.session.lastAt = Date.now();
}

function beginActivity(req, res) {
  const ip = String(req.socket.remoteAddress || '').replace(/^::ffff:/, '');
  const ua = String(req.headers['user-agent'] || '');
  const rec = recordFor(ip, ua || null);
  const now = Date.now();
  rec.lastSeen = now;
  rec.total.requests++;
  dayBucket(rec, dateKey(now)).requests++;
  rec.open++;

  const state = { client: rec, itemId: null, itemBytes: 0, startedAt: now, isWatch: false, isDirect: false, isSegment: false, touchedUsage: false };
  openResponses.add(state);
  let touch = now;
  const write = res.write;
  const end = res.end;
  res.write = function (chunk, ...args) {
    const n = bodyBytes(chunk);
    if (n) {
      rec.total.bytes += n;
      dayBucket(rec, dateKey(Date.now())).bytes += n;
      state.itemBytes += n;
      state.touchedUsage = true;
      const t = Date.now();
      if (t - touch > 1000) {
        rec.lastSeen = t;
        touch = t;
      }
    }
    return write.call(this, chunk, ...args);
  };
  res.end = function (chunk, ...args) {
    const n = bodyBytes(chunk);
    if (n) {
      rec.total.bytes += n;
      dayBucket(rec, dateKey(Date.now())).bytes += n;
      state.itemBytes += n;
      state.touchedUsage = true;
    }
    return end.call(this, chunk, ...args);
  };

  let closed = false;
  const done = () => {
    if (closed) return;
    closed = true;
    openResponses.delete(state);
    const t = Date.now();
    if (state.isWatch) rec.watchingOpen = Math.max(0, rec.watchingOpen - 1);
    if (state.isDirect) {
      const secs = Math.max(0, Math.round((t - state.startedAt) / 1000));
      if (secs) {
        rec.total.seconds += secs;
        dayBucket(rec, dateKey(t)).seconds += secs;
        state.touchedUsage = true;
      }
    } else if (state.isSegment && res.statusCode < 400) {
      rec.total.seconds += SEG;
      dayBucket(rec, dateKey(t)).seconds += SEG;
    }
    if (rec.session && state.itemId && rec.session.id === state.itemId) {
      rec.session.bytes += state.itemBytes;
      rec.session.lastAt = t;
    }
    rec.open = Math.max(0, rec.open - 1);
    rec.lastSeen = t;
    if (state.touchedUsage) scheduleSave();
  };
  res.once('finish', done);
  res.once('close', done);
  return state;
}

function watchTitle(item) {
  if (!item) return null;
  return item.show ? `${item.show}${item.epLabel ? ` ${item.epLabel}` : ''}` : item.title;
}

function noteAccess(state, parts, item) {
  if (!state) return;
  const rec = state.client;
  const route = parts[0] || '';
  const last = parts[parts.length - 1] || '';
  if (route === 'video' || route === 'hls') {
    rec.mode = 'watching';
    rec.title = watchTitle(item);
    rec.watchingOpen++;
    state.isWatch = true;
    if (route === 'video') {
      state.isDirect = true;
      if (item) {
        state.itemId = item.id;
        watchItem(rec, item);
      }
    } else if (/\.m3u8$/.test(last)) {
      if (item) {
        state.itemId = item.id;
        watchItem(rec, item);
      }
    } else if (item) {
      state.itemId = item.id;
      reachItem(rec, item);
      state.isSegment = /^\d+\.ts$/.test(last);
    }
  } else if (route === 'subs' && item) {
    rec.mode = 'watching';
    rec.title = watchTitle(item);
    state.itemId = item.id;
    reachItem(rec, item);
  } else if (route === 'api' && (parts[1] === 'info' || parts[1] === 'prepare' || parts[1] === 'hls')) {
    rec.mode = 'opening';
    rec.title = watchTitle(item);
    if (item) {
      state.itemId = item.id;
      reachItem(rec, item);
    }
  } else if (route === 'api' && parts[1] === 'library') {
    rec.mode = 'browsing';
    rec.title = null;
  } else if (route === 'thumb' || route === '' || route === 'watch') {
    if (rec.watchingOpen === 0) {
      rec.mode = 'browsing';
      rec.title = null;
    }
  }
  rec.lastSeen = Date.now();
}

setInterval(() => {
  const now = Date.now();
  for (const rec of activity.values()) {
    if (rec.session && now - rec.session.lastAt > SESSION_GAP_MS) flushSession(rec);
  }
}, 60_000).unref();

// Current and recent devices, newest activity first. `active` stays true while a response is open
// (a movie being streamed) or while the device has talked to the server in the last minute.
function activitySnapshot(range) {
  const now = Date.now();
  const todayKey = dateKey(now);
  const monthStart = `${todayKey.slice(0, 7)}-01`;
  const yearStart = `${todayKey.slice(0, 4)}-01-01`;
  for (const [ip, rec] of activity) {
    if (rec.session && now - rec.session.lastAt > SESSION_GAP_MS) flushSession(rec);
    if (rec.open === 0 && now - rec.lastSeen > ACTIVITY_KEEP_MS && !rec.total.bytes && !rec.history.length) activity.delete(ip);
  }
  return [...activity.values()]
    .map((rec) => {
      // A movie that is still streaming has not "finished", so add its elapsed time to the usage
      // shown now instead of waiting for the response to close (which could be hours).
      let liveSeconds = 0;
      for (const st of openResponses) {
        if (st.client === rec && st.isDirect) liveSeconds += Math.max(0, now - st.startedAt);
      }
      liveSeconds = Math.round(liveSeconds / 1000);
      const today = rangeTotals(rec, todayKey, todayKey);
      const month = rangeTotals(rec, monthStart, todayKey);
      const year = rangeTotals(rec, yearStart, todayKey);
      today.seconds += liveSeconds;
      month.seconds += liveSeconds;
      year.seconds += liveSeconds;
      const out = {
        id: rec.ip,
        name: rec.host || rec.client || rec.name || rec.ip,
        host: rec.host || null,
        client: rec.client || null,
        address: rec.ip,
        firstSeen: rec.firstSeen,
        active: rec.open > 0 || now - rec.lastSeen <= ACTIVITY_ACTIVE_MS,
        activity: rec.mode,
        title: rec.title,
        bytes: rec.total.bytes,
        seconds: rec.total.seconds + liveSeconds,
        requests: rec.total.requests,
        today,
        thisMonth: month,
        thisYear: year,
        lastSeen: Math.max(0, Math.round((now - rec.lastSeen) / 1000)),
        history: rec.history.slice(0, HISTORY_REPORT),
      };
      if (range) {
        out.range = rangeTotals(rec, range.from, range.to);
        if (todayKey >= range.from && todayKey <= range.to) out.range.seconds += liveSeconds;
      }
      return out;
    })
    .sort((a, b) => Number(b.active) - Number(a.active) || a.lastSeen - b.lastSeen);
}

// Exposed through the embedded control server, which is only reachable from this device.
// `query` may carry "from=YYYY-MM-DD&to=YYYY-MM-DD" for a custom usage range.
function activityForControl(query) {
  let range = null;
  try {
    const params = new URLSearchParams(query || '');
    const from = params.get('from');
    const to = params.get('to');
    if (from && to && /^\d{4}-\d{2}-\d{2}/.test(from) && /^\d{4}-\d{2}-\d{2}/.test(to)) {
      range = from <= to ? { from, to } : { from: to, to: from };
    }
  } catch {}
  return activitySnapshot(range);
}



async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const parts = url.pathname.split('/').filter(Boolean);
  if (!(await gate(req, res, url, parts))) return;
  const request = beginActivity(req, res);
  // /api/<action>/<id>  or  /video|thumb|subs|hls/<id>/...
  const id = parts[0] === 'api' ? parts[2] : parts[1];
  // Unknown movie (just added, or a device resuming right after a restart): look again before giving up.
  const movieRoute = ['api', 'video', 'thumb', 'subs', 'hls'].includes(parts[0]) && parts[1] !== 'library';
  if (movieRoute && id && !library.has(id)) await scanLibrary();
  const item = library.get(id) || null;
  noteAccess(request, parts, item);


  if (url.pathname === '/vendor/hls.min.js') {
    res.writeHead(200, { 'Content-Type': 'text/javascript', 'Cache-Control': 'max-age=86400' });
    return fs.createReadStream(require.resolve('hls.js/dist/hls.min.js')).pipe(res);
  }

  if (parts[0] === 'api') {
    if (parts[1] === 'library') return send(res, 200, (await scanLibrary()).map(({ path: _p, ...rest }) => ({ ...rest, ...jobStatus(rest) })));
    if (!item) return send(res, 404, { error: 'Unknown movie — refresh the library.' });
    const info = await probe(item);
    const audio = Math.min(audioParam(url), Math.max(0, info.audios.length - 1));
    if (parts[1] === 'info') {
      return send(res, 200, {
        ...publicItem(item), duration: info.duration, mode: info.mode, videoCodec: info.videoCodec, tenBit: info.tenBit, height: info.height,
        audios: info.audios.map(({ n, lang, label }) => ({ n, lang, label })),
        subs: info.subs.map(({ key, lang, label }) => ({ key, lang, label })),
        ...jobStatus(item),
      });
    }
    if (parts[1] === 'prepare' && req.method === 'POST') return send(res, 200, await prepare(item, audio));
    if (parts[1] === 'status') return send(res, 200, jobStatus(item, audio));
    if (parts[1] === 'hls') {
      const st = hls.get(`${item.id}:${audio}`);
      return send(res, 200, st ? { total: st.total, done: st.done.size, converting: !!st.job, encoder: st.job && st.job.profile, error: st.error } : { total: 0, done: 0, converting: false });
    }
    return send(res, 404, { error: 'Not found' });
  }

  if (parts[0] === 'video' && item) {
    const info = await probe(item);
    const audio = Math.min(audioParam(url), Math.max(0, info.audios.length - 1));
    // The original file carries the first language; others come from their own copy.
    if (info.mode === 'direct' && audio === 0) return streamFile(req, res, item.path, VIDEO_MIME[path.extname(item.path).toLowerCase()] || 'video/mp4');
    if (jobStatus(item, audio).state !== 'ready') return send(res, 409, { error: 'This movie is still being prepared.' });
    if (!req.headers.range || /^bytes=0-/.test(req.headers.range)) touch(cachedMp4(item, audio));
    return streamFile(req, res, cachedMp4(item, audio), 'video/mp4');
  }
  // /hls/<id>/index.m3u8 (first language) or /hls/<id>/<audio>/index.m3u8; segments are relative to it.
  if (parts[0] === 'hls' && item && parts[2]) {
    const info = await probe(item);
    const withAudio = /^\d+$/.test(parts[2]) && parts[3];
    const audio = withAudio ? Math.min(Number(parts[2]), Math.max(0, info.audios.length - 1)) : 0;
    const file = withAudio ? parts[3] : parts[2];
    if (file === 'index.m3u8') return send(res, 200, hlsPlaylist(hlsState(item, info, audio), CONFIG.pin && url.searchParams.get('k')), 'application/vnd.apple.mpegurl');
    const n = /^(\d+)\.ts$/.exec(file);
    if (n) return hlsSegment(req, res, item, Number(n[1]), audio);
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
  // (utun/awdl/llw/bridge/anpi: macOS VPN, AirDrop, Wi-Fi low-latency, bridges and internal links)
  const virtual = /vEthernet|Virtual|VMware|WSL|Hyper-V|Loopback|Bluetooth|Tailscale|ZeroTier|VPN|TAP|^utun|^awdl|^llw|^bridge|^anpi|^ap\d/i;
  return Object.entries(os.networkInterfaces())
    .flatMap(([name, addrs]) => (addrs || []).map((a) => ({ name, ...a })))
    .filter((a) => a.family === 'IPv4' && !a.internal && !a.address.startsWith('169.254.'))
    .sort((a, b) => virtual.test(a.name) - virtual.test(b.name) || /^192\.168\./.test(b.address) - /^192\.168\./.test(a.address))
    .map((a) => a.address);
}

// Delete prepared copies, converted streams, thumbnails and subtitles. Anything being written is left alone.
async function clearCache() {
  const busy = new Set([...hls.values()].filter((st) => st.job).map((st) => st.dir));
  let freed = 0;
  for (const entry of await fsp.readdir(CACHE_DIR, { withFileTypes: true }).catch(() => [])) {
    const file = path.join(CACHE_DIR, entry.name);
    if (entry.name.endsWith('.part') || busy.has(file)) continue;
    try {
      freed += entry.isDirectory() ? await dirSize(file) : (await fsp.stat(file)).size;
      await fsp.rm(file, { recursive: true, force: true });
    } catch {}
  }
  for (const [id, job] of jobs) if (job.state !== 'working') jobs.delete(id);
  for (const [id, st] of hls) if (!st.job) hls.delete(id);
  return freed;
}

function cacheSize() {
  return dirSize(CACHE_DIR);
}

// ---------- mDNS: let the Android app find this computer ----------
// Advertises _homeflix._tcp.local on the LAN. Implemented here with Node's dgram (no dependency) so the
// server keeps its "standard library only" promise. If anything goes wrong (another mDNS daemon holds
// port 5353, multicast unavailable) discovery is simply unavailable; the address/QR still work.
const MDNS_ADDR = '224.0.0.251';
const MDNS_PORT = 5353;
let mdnsSocket = null;
let mdnsChild = null;

// The name clients show for this server. Android's embedded Node reports "localhost", which is
// useless to a phone trying to tell servers apart, so treat it (and blanks) as "no name".
function deviceHostname() {
  const raw = String(os.hostname() || '').split('.')[0].trim();
  return raw && !/^localhost$/i.test(raw) ? raw : null;
}

function mdnsHost() {
  const raw = String(deviceHostname() || 'homeflix').split('.')[0] || 'homeflix';
  return raw.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/^-+|-+$/g, '').slice(0, 63) || 'homeflix';
}

function dnsName(name) {
  const parts = [];
  for (const label of String(name).split('.').filter(Boolean)) {
    const bytes = Buffer.from(label, 'utf8');
    parts.push(Buffer.from([Math.min(bytes.length, 63)]), bytes.subarray(0, 63));
  }
  parts.push(Buffer.from([0]));
  return Buffer.concat(parts);
}

function mdnsRecords(ttl = null) {
  const host = mdnsHost();
  const label = deviceHostname();
  const service = '_homeflix._tcp.local';
  const instance = `${label ? `HOMEFLIX ${host}` : 'HOMEFLIX'}._homeflix._tcp.local`;
  const target = `${host}.local`;
  const records = [];
  const record = (name, type, recordTtl, rdata, unique) => {
    const head = Buffer.alloc(10);
    head.writeUInt16BE(type, 0);
    head.writeUInt16BE(unique ? 0x8001 : 0x0001, 2);
    head.writeUInt32BE(Math.max(0, ttl == null ? recordTtl : ttl), 4);
    head.writeUInt16BE(rdata.length, 8);
    records.push(Buffer.concat([dnsName(name), head, rdata]));
  };
  record(service, 12, 4500, dnsName(instance), false);
  const srv = Buffer.alloc(6);
  srv.writeUInt16BE(0, 0); // priority
  srv.writeUInt16BE(0, 2); // weight
  srv.writeUInt16BE(Number(CONFIG.port) || 8080, 4);
  record(instance, 33, 120, Buffer.concat([srv, dnsName(target)]), true);
  const txtParts = [`version=${APP_VERSION}`, `api=${API_VERSION}`, 'path=/'];
  if (label) txtParts.unshift(`name=${label}`);
  const txt = txtParts
    .map((s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([b.length]), b]); });
  record(instance, 16, 4500, Buffer.concat(txt), true);
  for (const ip of lanAddresses()) {
    const bytes = ip.split('.').map(Number);
    if (bytes.length === 4 && bytes.every((n) => Number.isInteger(n) && n >= 0 && n <= 255)) {
      record(target, 1, 120, Buffer.from(bytes), true);
    }
  }
  return { records, target, service };
}

function mdnsPacket(ttl = null) {
  const { records } = mdnsRecords(ttl);
  const header = Buffer.alloc(12);
  header.writeUInt16BE(0, 0); // id
  header.writeUInt16BE(0x8400, 2); // response + authoritative
  header.writeUInt16BE(0, 4); // questions
  header.writeUInt16BE(records.length, 6); // answers
  return Buffer.concat([header, ...records]);
}

function readDnsName(buf, offset) {
  const labels = [];
  let next = offset;
  let jumped = false;
  for (let guard = 0; guard < 128 && offset < buf.length; guard++) {
    const len = buf[offset];
    if (len === 0) {
      if (!jumped) next = offset + 1;
      break;
    }
    if ((len & 0xc0) === 0xc0) {
      if (offset + 1 >= buf.length) break;
      const pointer = ((len & 0x3f) << 8) | buf[offset + 1];
      if (!jumped) next = offset + 2;
      jumped = true;
      offset = pointer;
      continue;
    }
    offset++;
    labels.push(buf.toString('utf8', offset, offset + len));
    offset += len;
    if (!jumped) next = offset;
  }
  return { name: labels.join('.').toLowerCase(), next };
}

function onMdnsMessage(msg, rinfo) {
  if (!mdnsSocket || msg.length < 12) return;
  const questions = msg.readUInt16BE(4);
  const { target, service } = mdnsRecords();
  const host = `${mdnsHost()}.local`;
  let offset = 12;
  let asked = false;
  let unicast = false;
  for (let i = 0; i < questions && offset < msg.length; i++) {
    const parsed = readDnsName(msg, offset);
    offset = parsed.next;
    if (offset + 4 > msg.length) break;
    const qclass = msg.readUInt16BE(offset + 2);
    offset += 4;
    const name = parsed.name;
    if (name === service || name.endsWith(`.${service}`) || name === target || name === host ||
        name === '_services._dns-sd._udp.local') {
      asked = true;
      if (qclass & 0x8000) unicast = true; // QU: reply directly to the asker
    }
  }
  if (!asked) return;
  const packet = mdnsPacket();
  if (unicast) mdnsSocket.send(packet, 0, packet.length, rinfo.port, rinfo.address, () => {});
  else mdnsSocket.send(packet, 0, packet.length, MDNS_PORT, MDNS_ADDR, () => {});
}

// macOS keeps port 5353 for mDNSResponder, so ask it to publish the service instead of fighting for
// the socket. Linux/Windows use the built-in responder; if the port is taken (Avahi) the helper below
// takes over.
function startMdnsHelper() {
  if (mdnsChild) return;
  const label = deviceHostname();
  const instance = label ? `HOMEFLIX ${mdnsHost()}` : 'HOMEFLIX';
  const txt = [`version=${APP_VERSION}`, `api=${API_VERSION}`, 'path=/'];
  if (label) txt.unshift(`name=${label}`);
  const candidates = process.platform === 'darwin'
    ? [{ cmd: 'dns-sd', args: ['-R', instance, '_homeflix._tcp', '.', String(CONFIG.port), ...txt] }]
    : process.platform === 'linux'
      ? [{ cmd: 'avahi-publish-service', args: [instance, '_homeflix._tcp', String(CONFIG.port), ...txt] }]
      : [{ cmd: 'dns-sd', args: ['-R', instance, '_homeflix._tcp', '.', String(CONFIG.port), ...txt] }];
  const next = () => {
    const option = candidates.shift();
    if (!option || mdnsChild) return;
    let child;
    try {
      child = spawn(option.cmd, option.args, { stdio: 'ignore', windowsHide: true });
    } catch {
      next();
      return;
    }
    mdnsChild = child;
    child.on('error', () => {
      if (mdnsChild === child) mdnsChild = null;
      next();
    });
    child.on('exit', () => {
      if (mdnsChild === child) mdnsChild = null;
    });
  };
  next();
}

function startMdns() {
  if (mdnsSocket || mdnsChild) return;
  if (process.platform === 'darwin') {
    startMdnsHelper();
    return;
  }
  let sock;
  try {
    sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  } catch (err) {
    console.error(`mDNS discovery unavailable: ${err.message}`);
    return;
  }
  sock.on('error', (err) => {
    console.error(`mDNS discovery unavailable: ${err.message}`);
    if (mdnsSocket === sock) mdnsSocket = null;
    try { sock.close(); } catch {}
    startMdnsHelper();
  });
  sock.on('message', (msg, rinfo) => { try { onMdnsMessage(msg, rinfo); } catch {} });
  sock.bind(MDNS_PORT, () => {
    if (mdnsSocket !== sock) return;
    try {
      sock.addMembership(MDNS_ADDR);
      sock.setMulticastTTL(255);
      sock.setMulticastLoopback(true);
    } catch (err) {
      console.error(`mDNS discovery unavailable: ${err.message}`);
    }
    const announce = () => {
      if (mdnsSocket !== sock) return;
      const packet = mdnsPacket();
      sock.send(packet, 0, packet.length, MDNS_PORT, MDNS_ADDR, () => {});
    };
    announce();
    setTimeout(announce, 1000);
    setTimeout(announce, 3000);
  });
  mdnsSocket = sock;
}

function stopMdns() {
  if (mdnsChild) {
    try { mdnsChild.kill(); } catch {}
    mdnsChild = null;
  }
  const sock = mdnsSocket;
  if (!sock) return;
  mdnsSocket = null;
  try {
    const packet = mdnsPacket(0); // goodbye: TTL 0
    sock.send(packet, 0, packet.length, MDNS_PORT, MDNS_ADDR, () => {});
  } catch {}
  try { sock.close(); } catch {}
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
      CONFIG.port = server.address().port;
      startMdns();
      resolve(server.address().port);
    });
  });
}

function stop() {
  try {
    for (const rec of activity.values()) flushSession(rec);
    saveDevices();
  } catch {}
  return new Promise((resolve) => {
    stopMdns();
    for (const st of hls.values()) stopHls(st);
    if (!server) return resolve();
    server.closeAllConnections();
    server.close(() => resolve());
    server = null;
  });
}

module.exports = { configure, start, stop, scanLibrary, lanAddresses, clearCache, cacheSize, hashPin, deviceKey, isLocalAddress, isLocalHost, VIDEO_EXT, activityForControl };

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
