#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$APP_DIR/build/test-game"

rm -rf "$OUT"
mkdir -p "$OUT"

javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$OUT" \
  "$APP_DIR/src/com/opencode/blackjack/Card.java" \
  "$APP_DIR/src/com/opencode/blackjack/Player.java" \
  "$APP_DIR/src/com/opencode/blackjack/BlackjackGame.java" \
  "$APP_DIR/tests/BlackjackGameSmokeTest.java"

java -cp "$OUT" com.opencode.blackjack.BlackjackGameSmokeTest
