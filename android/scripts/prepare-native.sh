#!/usr/bin/env bash
# Copies the Node.js runtime and the Android FFmpeg binaries into the app module.
# They are build inputs, not source: get them once, then run this before building.
#
#   NODEJS_MOBILE_DIR  extracted nodejs-mobile Android build (bin/<abi>/libnode.so, include/)
#   FFMPEG_ANDROID_DIR root containing the GPL Android FFmpeg builds
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
main="$here/../app/src/main"
node="${NODEJS_MOBILE_DIR:-$HOME/.android-build-toolchain/nodejs-mobile}"
ffmpeg="${FFMPEG_ANDROID_DIR:-$HOME/.android-build-toolchain/ffmpeg}"

declare -A ffmpeg_builds=(
  [x86_64]=ffmpeg_android_x86_64_gpl
  [arm64-v8a]=ffmpeg_android_aarch64_gpl
)

for abi in x86_64 arm64-v8a; do
  mkdir -p "$main/jniLibs/$abi"
  cp "$node/bin/$abi/libnode.so" "$main/jniLibs/$abi/libnode.so"
  cp "$ffmpeg/${ffmpeg_builds[$abi]}/bin/ffmpeg" "$main/jniLibs/$abi/libffmpeg_exec.so"
  cp "$ffmpeg/${ffmpeg_builds[$abi]}/bin/ffprobe" "$main/jniLibs/$abi/libffprobe_exec.so"
done

mkdir -p "$main/cpp/nodejs-mobile-include"
cp -R "$node/include/." "$main/cpp/nodejs-mobile-include/"

echo "Node and FFmpeg binaries are in $main/jniLibs; headers are in $main/cpp/nodejs-mobile-include."
