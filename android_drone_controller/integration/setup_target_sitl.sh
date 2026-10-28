#!/usr/bin/env bash
# Mô phỏng upstream cùng base revision firmware; không nạp hay liên lạc FC thật.
set -euo pipefail
root="${DRONE_TOOLING_ROOT:-/workspace/tooling}"
source_dir="$root/ardupilot-target-sitl"
venv="$root/mavlink-venv"
revision=afeb9c4941543d97f3e04fecf257b8f598f49a6a
if [[ ! -d "$source_dir/.git" ]]; then
  mkdir -p "$source_dir"
  git init "$source_dir"
  git -C "$source_dir" remote add origin https://github.com/ArduPilot/ardupilot.git
  git -C "$source_dir" fetch --depth 1 origin "$revision"
  git -C "$source_dir" checkout --detach FETCH_HEAD
fi
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$revision" ]] || {
  echo 'Sai revision SITL đích; giữ nguyên source để kiểm tra.' >&2; exit 1;
}
git -C "$source_dir" diff --quiet --ignore-submodules=all HEAD -- || {
  echo 'Source SITL đích có thay đổi; không ghi đè.' >&2; exit 1;
}
if [[ ! -x "$venv/bin/python" ]]; then python3 -m venv "$venv"; fi
"$venv/bin/python" -m pip install pymavlink==2.4.49 empy==3.3.4 future==1.0.0 pexpect==4.9.0 dronecan==1.0.26 setuptools==80.9.0
cd "$source_dir"
git submodule update --init --depth 1 modules/waf modules/mavlink modules/gtest \
  modules/DroneCAN/DSDL modules/DroneCAN/pydronecan modules/DroneCAN/dronecan_dsdlc modules/DroneCAN/libcanard modules/littlefs
# Base4.8 generator phụ thuộc cả mã Python lẫn C headers từ nested pin này.
git -C modules/mavlink submodule update --init --recursive --depth 1 pymavlink
export PATH="$venv/bin:$PATH"
# Dùng lại cache chỉ khi board và các tùy chọn compile cần thiết đều khớp.
# AP_Networking/Lua không cần; HAL SITL TCP serial vẫn hoạt động.
if ! python - <<'CACHE'
import ast,pathlib,sys
try:
    p=pathlib.Path('build/c4che/sitl_cache.py')
    values={n.targets[0].id:ast.literal_eval(n.value) for n in ast.parse(p.read_text()).body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name)}
    opts=values.get('OPTIONS',{})
    ok=(values.get('BOARD')=='sitl' and opts.get('disable_scripting') is True
        and opts.get('disable_networking') is True and opts.get('submodule_update') is False)
    sys.exit(0 if ok else 1)
except (OSError,ValueError,SyntaxError):sys.exit(1)
CACHE
then
  python ./waf configure --board sitl --disable-scripting --disable-networking --no-submodule-update
fi
python ./waf copter -j6
git rev-parse HEAD
sha256sum "$source_dir/build/sitl/bin/arducopter"
