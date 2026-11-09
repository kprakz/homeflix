#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
node_version="${NODEJS_MOBILE_VERSION:-24.18.0-0}"
ffmpeg_version="${FFMPEG_ANDROID_VERSION:-8.1.3-r1}"
cache="${HOMEFLIX_NATIVE_CACHE:-$HOME/.cache/homeflix-native}"
node_dir="$cache/nodejs-mobile-$node_version"
ffmpeg_dir="$cache/ffmpeg-android-$ffmpeg_version"

mkdir -p "$cache" "$ffmpeg_dir"

if [[ ! -f "$node_dir/bin/arm64-v8a/libnode.so" || ! -f "$node_dir/include/node/node.h" ]]; then
  archive="$cache/nodejs-mobile-android-$node_version.zip"
  curl -fL --retry 3 --retry-delay 2 -o "$archive" \
    "https://github.com/gmaclennan/nodejs-mobile/releases/download/v$node_version/nodejs-mobile-android-$node_version.zip"
  rm -rf "$node_dir"
  mkdir -p "$node_dir"
  unzip -q "$archive" -d "$node_dir"
  rm -f "$archive"
fi

for abi in aarch64 x86_64; do
  name="ffmpeg_android_${abi}_gpl"
  if [[ ! -x "$ffmpeg_dir/$name/bin/ffmpeg" ]]; then
    archive="$cache/$name-$ffmpeg_version.tar.gz"
    curl -fL --retry 3 --retry-delay 2 -o "$archive" \
      "https://github.com/yearsyan/ffmpeg-android-build/releases/download/v$ffmpeg_version/$name.tar.gz"
    tar -xzf "$archive" -C "$ffmpeg_dir"
    rm -f "$archive"
  fi
done

NODEJS_MOBILE_DIR="$node_dir" FFMPEG_ANDROID_DIR="$ffmpeg_dir" "$here/prepare-native.sh"
