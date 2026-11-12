#!/usr/bin/env bash
# Gets the native pieces the Android app is built with, into android/build/native:
#   - the Node.js runtime from the official nodejs-mobile release (checksum pinned)
#   - ffmpeg and ffprobe built by scripts/build-ffmpeg.sh (FFMPEG_DIR, default android/build/ffmpeg/arm64-v8a)
# They're build inputs, not source, so they're not in git.
set -euo pipefail

NODE_VERSION=18.20.4
NODE_SHA256=bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af
ABI=arm64-v8a
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
native="$here/../build/native"
ffmpeg_dir="${FFMPEG_DIR:-$here/../build/ffmpeg/$ABI}"

mkdir -p "$native"
node_dir="$native/nodejs-mobile"
if [[ ! -f "$node_dir/bin/$ABI/libnode.so" ]]; then
  zip="$native/nodejs-mobile.zip"
  curl -fsSL -o "$zip" "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v$NODE_VERSION/nodejs-mobile-v$NODE_VERSION-android.zip"
  echo "$NODE_SHA256  $zip" | sha256sum -c -
  rm -rf "$node_dir"
  mkdir -p "$node_dir"
  # Everything but the other chips' libraries (the headers have subfolders).
  unzip -q "$zip" -x 'bin/armeabi-v7a/*' 'bin/x86_64/*' -d "$node_dir"
  rm -f "$zip"
fi

for tool in ffmpeg ffprobe; do
  [[ -f "$ffmpeg_dir/$tool" ]] || { echo "Missing $ffmpeg_dir/$tool: run scripts/build-ffmpeg.sh, or download the android-ffmpeg build from GitHub Actions." >&2; exit 1; }
done

libs="$native/jniLibs/$ABI"
mkdir -p "$libs"
cp "$node_dir/bin/$ABI/libnode.so" "$libs/libnode.so"
cp "$ffmpeg_dir/ffmpeg" "$libs/libffmpeg.so"
cp "$ffmpeg_dir/ffprobe" "$libs/libffprobe.so"
echo "Native pieces ready in $libs"
