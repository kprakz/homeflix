// Copies ffmpeg.exe and ffprobe.exe into vendor/ffmpeg so the installer can ship them.
// Uses the ffmpeg already on this PC's PATH (install it with: winget install Gyan.FFmpeg.Essentials).

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dest = path.join(root, 'vendor', 'ffmpeg');

let ffmpeg;
try {
  ffmpeg = execFileSync('where', ['ffmpeg'], { encoding: 'utf8' }).split(/\r?\n/)[0].trim();
} catch {
  console.error('ffmpeg was not found on PATH. Install it with:\n  winget install Gyan.FFmpeg.Essentials\nthen open a new terminal and run this again.');
  process.exit(1);
}

const binDir = path.dirname(ffmpeg);
fs.mkdirSync(dest, { recursive: true });
for (const name of ['ffmpeg.exe', 'ffprobe.exe']) {
  const from = path.join(binDir, name);
  const to = path.join(dest, name);
  if (!fs.existsSync(from)) {
    console.error(`${name} is missing next to ${ffmpeg}`);
    process.exit(1);
  }
  if (!fs.existsSync(to) || fs.statSync(to).size !== fs.statSync(from).size) fs.copyFileSync(from, to);
}

// FFmpeg is GPL software: ship its license and say where its source code is.
const license = [path.join(binDir, '..', 'LICENSE'), path.join(binDir, '..', 'LICENSE.txt')].find((f) => fs.existsSync(f));
if (license) fs.copyFileSync(license, path.join(dest, 'LICENSE.txt'));
const version = execFileSync(ffmpeg, ['-version'], { encoding: 'utf8' }).split('\n')[0].trim();
fs.writeFileSync(
  path.join(dest, 'SOURCE.txt'),
  `HOMEFLIX includes unmodified FFmpeg programs (ffmpeg.exe, ffprobe.exe), run as separate programs.\r\n\r\n` +
    `${version}\r\n\r\nFFmpeg is licensed under the GNU GPL; see LICENSE.txt.\r\n` +
    `Source code: https://ffmpeg.org/download.html#releases (and https://github.com/FFmpeg/FFmpeg)\r\n` +
    `Windows build and its configuration: https://www.gyan.dev/ffmpeg/builds/\r\n`
);
console.log(`Bundled ${version}\n  from ${binDir}\n  into ${dest}`);
