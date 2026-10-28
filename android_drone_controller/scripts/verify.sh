#!/usr/bin/env bash
set -euo pipefail
project="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f /workspace/tooling/onboarding/android-env.sh ]]; then
  source /workspace/tooling/onboarding/android-env.sh
fi
cd "$project"
if [[ $# -eq 0 ]]; then set -- testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest; fi
exec bash ./gradlew --no-daemon --max-workers=4 "$@"
