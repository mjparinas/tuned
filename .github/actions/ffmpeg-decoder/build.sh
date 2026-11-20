#!/bin/bash
# Builds Media3's FFmpeg audio decoder for the given Media3 version, with only the Dolby and DTS decoders, and copies
# the AAR to $2. FFmpeg is LGPL 2.1 and built without --enable-gpl or --enable-nonfree.
set -euo pipefail
MEDIA3="$1"
OUT="$2"
WORK="${RUNNER_TEMP:-/tmp}/media3-ffmpeg"
rm -rf "$WORK"
git clone --quiet --depth 1 --branch "$MEDIA3" https://github.com/androidx/media.git "$WORK"
MODULE="$WORK/libraries/decoder_ffmpeg/src/main"
# The FFmpeg release Media3's README recommends.
git clone --quiet --depth 1 --branch release/6.0 https://github.com/FFmpeg/FFmpeg.git "$MODULE/jni/ffmpeg"
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
# Dolby Digital, Dolby Digital Plus, Dolby TrueHD and DTS: what phones and many boxes can't decode themselves.
(cd "$MODULE/jni" && ./build_ffmpeg.sh "$MODULE" "$NDK" linux-x86_64 21 ac3 eac3 truehd mlp dca)
# Three tries: this downloads Media3's own Gradle plugins, and a plugin host that doesn't answer once shouldn't fail CI.
for try in 1 2 3; do
  if (cd "$WORK" && ./gradlew --quiet :lib-decoder-ffmpeg:assembleRelease -Pandroid.ndkPath="$NDK"); then break; fi
  if [ "$try" = 3 ]; then exit 1; fi
  echo "::warning::Media3's Gradle build didn't finish (try $try of 3); trying again."
  sleep $((try * 30))
done
AAR=$(find "$WORK/libraries/decoder_ffmpeg" -name '*release*.aar' | head -n 1)
mkdir -p "$(dirname "$OUT")"
cp "$AAR" "$OUT"
ls -l "$OUT"
