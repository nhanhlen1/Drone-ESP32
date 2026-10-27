#!/usr/bin/env bash
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
c++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$repo/firmware/test/sensor_stubs" \
  "$repo/firmware/src/sensors/SensorManager.cpp" "$repo/firmware/src/storage/CalibrationStore.cpp" \
  "$repo/firmware/test/sensor_test.cpp" -o "$out/sensor_test"
"$out/sensor_test"
