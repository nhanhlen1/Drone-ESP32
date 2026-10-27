#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build_dir="${TMPDIR:-/tmp}/esp32-drone-core-tests"
mkdir -p "$build_dir"
"${CXX:-g++}" -std=c++11 -O1 -g -Wall -Wextra -Wpedantic -Werror -fsanitize=undefined,address -fno-omit-frame-pointer -Ifirmware/include firmware/test/core_test.cpp -o "$build_dir/core_test"
"$build_dir/core_test"
