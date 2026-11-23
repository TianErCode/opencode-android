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
CMD="${1:-}"
[ -n "$CMD" ] && shift
case "$CMD" in
    install)
        [ -n "${1:-}" ] || { echo "usage: android-app install <apk_path>" >&2; exit 2; }
        adb install -r "$1"
        ;;
    launch)
        [ -n "${1:-}" ] || { echo "usage: android-app launch <package>" >&2; exit 2; }
        adb shell monkey -p "$1" -c android.intent.category.LAUNCHER 1
        ;;
    stop)
        [ -n "${1:-}" ] || { echo "usage: android-app stop <package>" >&2; exit 2; }
        adb shell am force-stop "$1"
        ;;
    clear)
        [ -n "${1:-}" ] || { echo "usage: android-app clear <package>" >&2; exit 2; }
        adb shell pm clear "$1"
        ;;
    logcat)
        PKG="${1:-}"
        LINES="${2:-200}"
        [ -n "$PKG" ] || { echo "usage: android-app logcat <package> [lines]" >&2; exit 2; }
        PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
        if [ -n "$PID" ]; then
            adb logcat -d -t "$LINES" --pid="$PID"
        else
            adb logcat -d -t "$LINES" | grep -F "$PKG" || true
        fi
        ;;
    list)
        adb shell pm list packages -3 | sed 's/^package://'
        ;;
    *)
        echo "usage: android-app {install|launch|stop|clear|logcat|list} ..." >&2
        exit 2
        ;;
esac
