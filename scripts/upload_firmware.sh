#!/usr/bin/env sh
set -eu
if [ "$#" -ne 1 ]; then
  echo 'Usage: scripts/upload_firmware.sh /dev/ttyUSB0 (ESC power off; props removed)' >&2
  exit 2
fi
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir/firmware"
exec pio run --target upload --upload-port "$1"
