#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/tools/android_env.sh" >/dev/null

cd "$ROOT_DIR/android-app"
./gradlew assembleDebug >/tmp/wechat-moments-install-build.log

DEVICES="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
FIRST_DEVICE="$(printf '%s\n' "$DEVICES" | sed -n '1p')"
if [ -z "$FIRST_DEVICE" ]; then
  echo "No authorized Android device found. Enable USB debugging and approve this computer on the phone." >&2
  exit 1
fi

DEVICE="${1:-$FIRST_DEVICE}"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "Installing $APK to $DEVICE"
adb -s "$DEVICE" install -r "$APK"
echo "Installed successfully."
