#!/usr/bin/env bash
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$repo/build"
g++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie \
 -I"$repo/firmware/test/esc_stubs" -I"$repo/firmware/include" -I"$repo/firmware/src" \
 "$repo/firmware/src/motors/EscDriver.cpp" "$repo/firmware/test/esc_test.cpp" -o "$repo/build/esc_test"
"$repo/build/esc_test"
if g++ -std=c++17 -DDRONE_ENABLE_REAL_ESC=1 -I"$repo/firmware/test/esc_stubs" \
 -I"$repo/firmware/include" -I"$repo/firmware/src" -c "$repo/firmware/src/motors/EscDriver.cpp" \
 -o "$repo/build/forbidden_esc.o" 2>"$repo/build/esc_guard.log"; then
 echo 'ERROR: unverified real ESC configuration unexpectedly compiled' >&2
 exit 1
fi
rg -q 'Real ESC output forbidden' "$repo/build/esc_guard.log"
echo 'Real ESC compile interlock rejects unverified hardware: PASS'
