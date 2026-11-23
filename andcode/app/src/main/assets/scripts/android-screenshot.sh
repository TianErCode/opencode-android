#!/bin/sh



set -e


if ! command -v adb >/dev/null 2>&1; then
    echo "android: installing adb once from the package mirrors (android-tools-adb)" >&2
    apk add --no-cache android-tools-adb >&2 || {
        echo "android: could not install android-tools-adb. Open the rescue terminal and run:" >&2
        echo "         apk add android-tools-adb" >&2
        exit 1
    }
fi
adb get-state >/dev/null 2>&1 || {
    echo "android: adb has no connected device. Enable wireless debugging and connect via OpenCode -> Settings -> Local runtime -> ADB." >&2
    exit 1
}
OUTPUT="${1:-/tmp/screenshot.png}"
adb shell screencap -p /sdcard/_oc_screenshot.png
adb pull /sdcard/_oc_screenshot.png "$OUTPUT" >/dev/null 2>&1
adb shell rm /sdcard/_oc_screenshot.png >/dev/null 2>&1
echo "$OUTPUT"
