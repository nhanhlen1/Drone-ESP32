#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/flutter_env.sh"
cd "$repo_root/mobile_app"
flutter --suppress-analytics pub get --enforce-lockfile
flutter --suppress-analytics build apk --debug "$@"
