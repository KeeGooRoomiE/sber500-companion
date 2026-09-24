#!/usr/bin/env bash
# Local Android build + optional install on connected device/emulator.
# Usage:
#   ./scripts/build-android.sh          — debug APK only
#   ./scripts/build-android.sh --install — debug APK + adb install
#   ./scripts/build-android.sh --release — release APK (requires signing)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"
APK_DEBUG="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"

MODE="debug"
INSTALL=false

for arg in "$@"; do
  case $arg in
    --install) INSTALL=true ;;
    --release) MODE="release" ;;
  esac
done

# ── sanity checks ──────────────────────────────────────────────────────────────
if [ ! -f "$ANDROID_DIR/gradlew" ]; then
  echo "❌  gradlew not found in android/. Run from repo root."
  exit 1
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  # Android Studio default locations
  for candidate in \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    "/usr/local/lib/android/sdk"; do
    if [ -d "$candidate" ]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "❌  ANDROID_HOME not set and SDK not found in default locations."
  exit 1
fi

echo "sdk.dir=$ANDROID_HOME" > "$ANDROID_DIR/local.properties"

# ── build ──────────────────────────────────────────────────────────────────────
cd "$ANDROID_DIR"
chmod +x gradlew

if [ "$MODE" = "release" ]; then
  echo "🔨  Building release APK…"
  ./gradlew assembleRelease --no-daemon --quiet
  APK=$(find "$ANDROID_DIR/app/build/outputs/apk/release" -name "*.apk" | head -1)
else
  echo "🔨  Building debug APK…"
  ./gradlew assembleDebug --no-daemon --quiet
  APK="$APK_DEBUG"
fi

echo "✅  APK: $APK"

# ── install ────────────────────────────────────────────────────────────────────
if [ "$INSTALL" = true ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
  if [ ! -f "$ADB" ]; then
    echo "❌  adb not found at $ADB"
    exit 1
  fi

  DEVICES=$("$ADB" devices | grep -v "^List" | grep -v "^$" | wc -l | tr -d ' ')
  if [ "$DEVICES" -eq 0 ]; then
    echo "❌  No devices/emulators connected. Start emulator first."
    exit 1
  fi

  echo "📲  Installing on device…"
  "$ADB" install -r "$APK"
  echo "✅  Installed. Launch: adb shell am start -n ru.keegoo.companion/.ui.MainActivity"
fi
