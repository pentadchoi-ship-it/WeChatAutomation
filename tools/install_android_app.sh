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
SERVICE="com.perrychoi.wechatmomentscontroller/com.perrychoi.wechatmomentscontroller.WechatAutomationService"

echo "Installing $APK to $DEVICE"
adb -s "$DEVICE" install -r "$APK"
echo "Installed successfully."

ENABLED_SERVICES="$(adb -s "$DEVICE" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
if [[ "$ENABLED_SERVICES" == *"$SERVICE"* ]]; then
  echo "Accessibility service is enabled."
else
  echo "Accessibility service is not enabled. Open the app and enable 朋友圈自动化辅助服务 before testing." >&2
fi
