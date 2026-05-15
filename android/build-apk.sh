#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/tmp/opencode/android-sdk}"
BUILD_TOOLS="$SDK/build-tools/35.0.0"
PLATFORM_JAR="${ANDROID_PLATFORM_JAR:-$SDK/platforms/android-35/android.jar}"
OUT="$APP_DIR/build"
APK_OUT="$OUT/outputs/blackjack-royal.apk"

if [[ ! -f "$PLATFORM_JAR" ]]; then
  printf 'Android platform not found: %s\n' "$PLATFORM_JAR" >&2
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
    -dname "CN=Blackjack Royal,O=OpenCode,C=IT"
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
