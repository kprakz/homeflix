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
// Audio every browser plays (iPhone, Android, TVs). Dolby AC3/E-AC3, DTS etc. are silent on many
// Android and TV browsers, so those are converted to stereo AAC.
const UNIVERSAL_AUDIO = new Set(['aac', 'mp3']);
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
    next.set(id, { id, path: file, ...prettyName(file), size: stat.size, mtime: stat.mtimeMs, folder: path.basename(folder), downloading });
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

function hlsPlaylist(st) {
  const lines = ['#EXTM3U', '#EXT-X-VERSION:3', `#EXT-X-TARGETDURATION:${SEG + 1}`, '#EXT-X-MEDIA-SEQUENCE:0', '#EXT-X-PLAYLIST-TYPE:VOD'];
  for (let i = 0; i < st.total; i++) {
    const len = i === st.total - 1 ? st.duration - SEG * i : SEG;
    lines.push(`#EXTINF:${Math.max(0.1, len).toFixed(3)},`, `${i}.ts`);
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

async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const parts = url.pathname.split('/').filter(Boolean);
  // /api/<action>/<id>  or  /video|thumb|subs|hls/<id>/...
  const id = parts[0] === 'api' ? parts[2] : parts[1];
  // Unknown movie (just added, or a device resuming right after a restart): look again before giving up.
  const movieRoute = ['api', 'video', 'thumb', 'subs', 'hls'].includes(parts[0]) && parts[1] !== 'library';
  if (movieRoute && id && !library.has(id)) await scanLibrary();
  const item = library.get(id) || null;

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
    if (file === 'index.m3u8') return send(res, 200, hlsPlaylist(hlsState(item, info, audio)), 'application/vnd.apple.mpegurl');
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
    for (const st of hls.values()) stopHls(st);
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
