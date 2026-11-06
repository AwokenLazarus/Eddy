#!/usr/bin/env bash
# Build the Eddy APK.
#
# Needs: the Android SDK (ANDROID_HOME), NDK r26d (NDK), the Knots binaries built by
# scripts/build-knots-android.sh, Python with fontTools and brotli, and Gradle 8.10+ (or the wrapper).
#
#   KNOTS_SRC=~/src/knots FONT_DIR=<dir with inter-var.woff2 and jetbrains-mono-var.woff2> \
#   PEERS_FILE=<optional host:port list> scripts/build-android-app.sh
#
# Output: android/app/build/outputs/apk/release/app-release.apk
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
app=$here/android/app/src/main
: "${KNOTS_SRC:?set KNOTS_SRC to the Knots source tree with build-aarch64 and build-x86_64}"
: "${FONT_DIR:?set FONT_DIR to the folder with the Inter and JetBrains Mono woff2 fonts}"
: "${NDK:=$HOME/android-ndk-r26d}"
strip=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip

# The node: packaged as a native library so Android installs it as an executable file.
for pair in arm64-v8a:aarch64 x86_64:x86_64; do
  abi=${pair%%:*}; arch=${pair##*:}
  src=$KNOTS_SRC/build-$arch/bin/bitcoind
  if [ -f "$src" ]; then
    mkdir -p "$app/jniLibs/$abi"
    "$strip" -o "$app/jniLibs/$abi/libbitcoind.so" "$src"
  else
    echo "note: no $src, skipping $abi" >&2
  fi
done

# Fonts: Android needs TTF, Lightning Stream ships woff2.
mkdir -p "$app/res/font" "$app/assets/licenses"
python3 - "$FONT_DIR" "$app/res/font" <<'PY'
import sys
from fontTools.ttLib import TTFont
src, dst = sys.argv[1], sys.argv[2]
for name, out in (("inter-var.woff2", "inter.ttf"), ("jetbrains-mono-var.woff2", "jetbrains_mono.ttf")):
    f = TTFont(f"{src}/{name}")
    f.flavor = None
    f.save(f"{dst}/{out}")
PY
cp "$FONT_DIR"/*-OFL.txt "$app/assets/licenses/" 2>/dev/null || true

# Bootstrap peers (optional): one host:port per line.
if [ -n "${PEERS_FILE:-}" ]; then cp "$PEERS_FILE" "$app/assets/peers.txt"; else : > "$app/assets/peers.txt"; fi

cd "$here/android"
if [ -x ./gradlew ]; then ./gradlew --no-daemon -q assembleRelease; else gradle --no-daemon -q assembleRelease; fi
ls -la app/build/outputs/apk/release/
