#!/usr/bin/env bash
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
json_src="${ARDUINOJSON_SRC:-$repo/firmware/.pio/libdeps/esp32dev/ArduinoJson/src}"
if [[ ! -f "$json_src/ArduinoJson.h" ]]; then
  echo 'ArduinoJson 6.21.5 source needed. Run PlatformIO dependency installation, or set ARDUINOJSON_SRC to trusted v6.21.5 src directory.' >&2
  exit 1
fi
mkdir -p "$repo/build"
g++ -std=c++17 -Wall -Wextra -Werror \
  -I"$repo/firmware/test/network_stubs" -I"$json_src" -I"$repo/firmware/include" -I"$repo/firmware/src" \
  "$repo/firmware/src/network/Network.cpp" "$repo/firmware/test/network_test.cpp" \
  -o "$repo/build/network_test"
"$repo/build/network_test"
