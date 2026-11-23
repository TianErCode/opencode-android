#!/usr/bin/env bash












set -euo pipefail

IDLE_MINUTES="${IDLE_MINUTES:-30}"
CHAT_MINUTES="${CHAT_MINUTES:-5}"

snapshot() {
  local label="$1"
  local stamp
  stamp="$(date +%Y-%m-%dT%H:%M:%S)"
  {
    echo "=== $label @ $stamp ==="
    echo "-- battery --"
    dumpsys battery | grep -E 'level|status|temperature' || true
    echo "-- process tree RSS (kB) --"
    top -m 20 -s rss -n 1 2>/dev/null | grep -E 'opencode|proot' || true
    echo "-- runtime disk --"
    du -sh /data/data/com.tianercode.opencode/files/runtime 2>/dev/null || true
    du -sh /data/data/com.tianercode.opencode/files/runtime/logs 2>/dev/null || true
    echo
  } | tee -a "opencode-benchmark-${stamp%%[+ ]*}.log"
}

echo "Starting OpenCode benchmark."
echo "Idle: ${IDLE_MINUTES} min  Chat: ${CHAT_MINUTES} min"
snapshot "before-idle"

echo "Keep the app backgrounded with the local runtime running."
echo "Sleeping for ${IDLE_MINUTES} minutes..."
sleep "$((IDLE_MINUTES * 60))"

snapshot "after-idle"

echo "Run a chat workload for ${CHAT_MINUTES} minutes now."
echo "Sleeping for ${CHAT_MINUTES} minutes..."
sleep "$((CHAT_MINUTES * 60))"

snapshot "after-chat"
echo "Done. Collect opencode-benchmark-*.log and copy values into the device matrix doc"
