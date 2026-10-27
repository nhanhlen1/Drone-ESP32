#!/usr/bin/env bash
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
gps_src="${TINYGPSPLUS_SRC:-$repo/firmware/.pio/libdeps/esp32dev/TinyGPSPlus/src}"
if [[ ! -f "$gps_src/TinyGPS++.cpp" ]]; then
  echo 'TinyGPSPlus source missing. Run PlatformIO dependency installation or set TINYGPSPLUS_SRC to the pinned 1.1.0 src directory.' >&2
  exit 2
fi
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
# Upstream parser intentionally falls through checksum delimiters. Keep project warnings fatal.
c++ -std=c++17 -DARDUINO=10819 -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$repo/firmware/test/sensor_stubs" -I"$gps_src" -c "$gps_src/TinyGPS++.cpp" -o "$out/parser.o"
c++ -std=c++17 -Wall -Wextra -Werror -DARDUINO=10819 -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$repo/firmware/test/sensor_stubs" -I"$gps_src" \
  "$repo/firmware/src/gps/GpsManager.cpp" "$out/parser.o" "$repo/firmware/test/gps_test.cpp" \
  -o "$out/gps_test"
"$out/gps_test"
