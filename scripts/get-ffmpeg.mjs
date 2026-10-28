// Copies ffmpeg and ffprobe into vendor/ffmpeg so the installer can ship them.
// - FFMPEG_SRC_DIR set: takes ffmpeg and ffprobe from that folder (the Mac and Linux builds download
//   native static builds for each chip there; see .github/workflows/mac.yml and linux.yml).
// - Windows: uses the ffmpeg on this PC's PATH (install it with: winget install Gyan.FFmpeg.Essentials).
// - macOS / Linux otherwise: uses the ffmpeg-static and ffprobe-static npm packages
//   (npm install --no-save ffmpeg-static ffprobe-static). Note ffprobe-static's Mac build is Intel-only.

import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dest = path.join(root, 'vendor', 'ffmpeg');
const isWin = process.platform === 'win32';
const exe = isWin ? '.exe' : '';

function fail(msg) {
  console.error(msg);
  process.exit(1);
}

// Where to copy ffmpeg and ffprobe from, plus a license file and a note on the build's origin.
function findSources() {
  const fromDir = process.env.FFMPEG_SRC_DIR;
  if (fromDir) {
    const license = ['LICENSE', 'LICENSE.txt', 'LICENSE.md'].map((f) => path.join(fromDir, f)).find((f) => fs.existsSync(f));
    return {
      ffmpeg: path.join(fromDir, 'ffmpeg' + exe),
      ffprobe: path.join(fromDir, 'ffprobe' + exe),
      license,
      origin: process.env.FFMPEG_ORIGIN || `Build from ${fromDir}`,
    };
  }
  if (isWin) {
    let ffmpeg;
    try {
      ffmpeg = execFileSync('where', ['ffmpeg'], { encoding: 'utf8' }).split(/\r?\n/)[0].trim();
    } catch {
      fail('ffmpeg was not found on PATH. Install it with:\n  winget install Gyan.FFmpeg.Essentials\nthen open a new terminal and run this again.');
    }
    const binDir = path.dirname(ffmpeg);
    return {
      ffmpeg,
      ffprobe: path.join(binDir, 'ffprobe.exe'),
      license: [path.join(binDir, '..', 'LICENSE'), path.join(binDir, '..', 'LICENSE.txt')].find((f) => fs.existsSync(f)),
      origin: 'Windows build and its configuration: https://www.gyan.dev/ffmpeg/builds/',
    };
  }
  const require = createRequire(path.join(root, 'package.json'));
  let ffmpeg, ffprobe;
  try {
    ffmpeg = require('ffmpeg-static');
    ffprobe = require('ffprobe-static').path;
  } catch {
    fail('Install the ffmpeg builds first:\n  npm install --no-save ffmpeg-static ffprobe-static');
  }
  const pkgDir = path.dirname(require.resolve('ffmpeg-static/package.json'));
  const license = fs.readdirSync(pkgDir).filter((f) => /license/i.test(f)).map((f) => path.join(pkgDir, f)).find((f) => /ffmpeg/i.test(f)) || null;
  return { ffmpeg, ffprobe, license, origin: 'Builds from https://github.com/eugeneware/ffmpeg-static and https://github.com/joshwnj/ffprobe-static' };
}

const src = findSources();
fs.mkdirSync(dest, { recursive: true });
for (const [name, from] of [['ffmpeg', src.ffmpeg], ['ffprobe', src.ffprobe]]) {
  if (!from || !fs.existsSync(from)) fail(`${name} not found (looked at ${from})`);
  const to = path.join(dest, name + exe);
  if (!fs.existsSync(to) || fs.statSync(to).size !== fs.statSync(from).size) fs.copyFileSync(from, to);
  if (!isWin) fs.chmodSync(to, 0o755);
}

// FFmpeg is GPL software: ship its license and say where its source code is.
if (src.license) fs.copyFileSync(src.license, path.join(dest, 'LICENSE.txt'));
const version = execFileSync(path.join(dest, 'ffmpeg' + exe), ['-version'], { encoding: 'utf8' }).split('\n')[0].trim();
const nl = isWin ? '\r\n' : '\n';
fs.writeFileSync(
  path.join(dest, 'SOURCE.txt'),
  [
    `HOMEFLIX includes unmodified FFmpeg programs (ffmpeg, ffprobe), run as separate programs.`,
    '',
    version,
    '',
    'FFmpeg is licensed under the GNU GPL; see LICENSE.txt.',
    'Source code: https://ffmpeg.org/download.html#releases (and https://github.com/FFmpeg/FFmpeg)',
    src.origin,
    '',
  ].join(nl)
);
console.log(`Bundled ${version}\n  from ${path.dirname(src.ffmpeg)}\n  into ${dest}`);
