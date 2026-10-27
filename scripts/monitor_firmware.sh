#!/usr/bin/env sh
set -eu
if [ "$#" -ne 1 ]; then
  echo 'Usage: scripts/monitor_firmware.sh /dev/ttyUSB0' >&2
  exit 2
fi
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir/firmware"
exec pio device monitor --baud 115200 --port "$1"
