#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [[ -z "$SDK" && -n "${HOME:-}" && -d "$HOME/Android/Sdk" ]]; then
  SDK="$HOME/Android/Sdk"
elif [[ -z "$SDK" && -n "${HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then
  SDK="$HOME/Library/Android/sdk"
fi

if [[ -z "$SDK" ]]; then
  printf 'Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK path.\n' >&2
  printf 'Example: ANDROID_HOME="$HOME/Android/Sdk" ./build-apk.sh\n' >&2
  exit 1
fi

BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-}"
if [[ -z "$BUILD_TOOLS" ]]; then
  for dir in "$SDK"/build-tools/35.0.0 "$SDK"/build-tools/35.* "$SDK"/build-tools/*; do
    if [[ -d "$dir" && -x "$dir/aapt2" && -x "$dir/d8" && -x "$dir/zipalign" && -x "$dir/apksigner" ]]; then
      BUILD_TOOLS="$dir"
      break
    fi
  done
fi

if [[ -z "$BUILD_TOOLS" ]]; then
  printf 'Android build-tools not found in %s/build-tools. Install build-tools 35.0.0.\n' "$SDK" >&2
  exit 1
fi

PLATFORM_JAR="${ANDROID_PLATFORM_JAR:-$SDK/platforms/android-35/android.jar}"
OUT="$APP_DIR/build"
APK_OUT="$OUT/outputs/velvet-run-64.apk"

if [[ ! -f "$PLATFORM_JAR" ]]; then
  printf 'Android platform not found: %s\n' "$PLATFORM_JAR" >&2
  printf 'Install platform android-35 or set ANDROID_PLATFORM_JAR to a valid android.jar.\n' >&2
  exit 1
fi

rm -rf "$OUT/classes" "$OUT/gen" "$OUT/dex" "$OUT/classes.jar" "$OUT/compiled.zip" "$OUT/blackjack-unsigned.apk" "$OUT/blackjack-unsigned-dex.apk" "$OUT/blackjack-aligned.apk"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex" "$OUT/outputs"

if [[ "${SKIP_TESTS:-0}" != "1" ]]; then
  "$APP_DIR/test-game.sh"
fi

"$BUILD_TOOLS/aapt2" compile --dir "$APP_DIR/res" -o "$OUT/compiled.zip"
"$BUILD_TOOLS/aapt2" link \
  -I "$PLATFORM_JAR" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  -R "$OUT/compiled.zip" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  -o "$OUT/blackjack-unsigned.apk"

mapfile -d '' SOURCES < <(find "$APP_DIR/src" "$OUT/gen" -name '*.java' -print0)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$PLATFORM_JAR" -d "$OUT/classes" "${SOURCES[@]}"

(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
"$BUILD_TOOLS/d8" --lib "$PLATFORM_JAR" --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/blackjack-unsigned.apk" "$OUT/blackjack-unsigned-dex.apk"
(cd "$OUT/dex" && jar uf "$OUT/blackjack-unsigned-dex.apk" classes.dex)

"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/blackjack-unsigned-dex.apk" "$OUT/blackjack-aligned.apk"

KEYSTORE="${BLACKJACK_KEYSTORE:-$APP_DIR/debug.keystore}"
KEY_ALIAS="${BLACKJACK_KEY_ALIAS:-blackjack}"
KEYSTORE_PASS="${BLACKJACK_KEYSTORE_PASSWORD:-android}"
KEY_PASS="${BLACKJACK_KEY_PASSWORD:-$KEYSTORE_PASS}"

if [[ ! -f "$KEYSTORE" && -z "${BLACKJACK_KEYSTORE:-}" ]]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass "$KEYSTORE_PASS" \
    -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Velvet Run 64,O=Velvet Run 64,C=IT"
fi

if [[ ! -f "$KEYSTORE" ]]; then
  printf 'Keystore not found: %s\n' "$KEYSTORE" >&2
  exit 1
fi

"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEYSTORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$APK_OUT" \
  "$OUT/blackjack-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose "$APK_OUT"
printf 'APK generated: %s\n' "$APK_OUT"
