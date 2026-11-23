#!/usr/bin/env bash




set -euo pipefail

cd "$(dirname "$0")/.."

OUTPUT="THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt"

./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain \
  | grep -oE '[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[0-9][a-zA-Z0-9_.-]*' \
  | sort -u -t: -k1,2 \
  | sort -u \
  > "$OUTPUT"

echo "Wrote $(wc -l < "$OUTPUT" | tr -d ' ') resolved dependency coordinates to $OUTPUT"
