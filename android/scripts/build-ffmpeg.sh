#!/usr/bin/env bash
# Builds ffmpeg and ffprobe for Android (arm64) from FFmpeg's own source release, with the NDK.
# Only what HOMEFLIX's phone server needs: read every common format, convert audio to AAC, make
# thumbnails and subtitles, and convert video with the phone's own video chip (Android MediaCodec,
# through the NDK: no Java needed). No software video encoders (the CPU is what makes phones hot),
# so the build stays LGPL and small.
#
#   ANDROID_NDK   path to the NDK (GitHub's Ubuntu runners: $ANDROID_NDK_LATEST_HOME)
#   OUT           where ffmpeg and ffprobe go (default: android/build/ffmpeg/arm64-v8a)
set -euo pipefail

VERSION=9.0.2
SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
API=29
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${OUT:-$here/../build/ffmpeg/arm64-v8a}"
work="${WORK:-$(mktemp -d)}"
ndk="${ANDROID_NDK:?set ANDROID_NDK to the NDK folder}"
tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64"

cd "$work"
curl -fsSL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.xz"
echo "$SHA256  ffmpeg.tar.xz" | sha256sum -c -
tar -xJf ffmpeg.tar.xz
cd "ffmpeg-$VERSION"

./configure \
  --prefix="$work/install" \
  --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
  --cc="$tc/bin/aarch64-linux-android$API-clang" --cxx="$tc/bin/aarch64-linux-android$API-clang++" \
  --ar="$tc/bin/llvm-ar" --nm="$tc/bin/llvm-nm" --ranlib="$tc/bin/llvm-ranlib" --strip="$tc/bin/llvm-strip" \
  --sysroot="$tc/sysroot" \
  --enable-pic --enable-static --disable-shared \
  --disable-doc --disable-debug --disable-ffplay \
  --disable-autodetect --disable-network --disable-avdevice --disable-devices --disable-hwaccels \
  --enable-jni --enable-mediacodec \
  --disable-encoders --enable-encoder=aac,mjpeg,png,webvtt,mov_text,ass,ssa,subrip,h264_mediacodec \
  --disable-muxers --enable-muxer=mp4,mov,ipod,mpegts,hls,segment,webvtt,image2,mjpeg,null \
  --disable-filters --enable-filter=scale,format,aformat,aresample,anull,null,copy,setpts,asetpts,pan,volume \
  --extra-ldflags="-Wl,-z,max-page-size=16384"
make -j"$(nproc)"
mkdir -p "$out"
cp ffmpeg "$out/ffmpeg"
cp ffprobe "$out/ffprobe"
"$tc/bin/llvm-strip" "$out/ffmpeg" "$out/ffprobe"
cp COPYING.LGPLv2.1 "$out/LICENSE.txt"
printf 'FFmpeg %s, built from https://ffmpeg.org/releases/ffmpeg-%s.tar.xz (sha256 %s)\nLicensed under the LGPL v2.1; see LICENSE.txt. Source: https://ffmpeg.org/download.html\n' "$VERSION" "$VERSION" "$SHA256" > "$out/SOURCE.txt"
ls -la "$out"
