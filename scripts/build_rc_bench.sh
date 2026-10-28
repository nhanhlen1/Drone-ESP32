#!/usr/bin/env bash
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# Reuse the installed JDK, Android SDK, trusted CA and proxy; no Flutter build.
source "$repo/scripts/flutter_env.sh"
cd "$repo/android_rc_bench"
if [[ ! -f local.properties ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi
if [[ $# -eq 0 ]]; then set -- testDebugUnitTest lintDebug assembleDebug; fi
exec ./gradlew --no-daemon "$@"
