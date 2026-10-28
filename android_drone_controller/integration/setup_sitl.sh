#!/usr/bin/env bash
# Reference SITL only. Never uploads or touches the real FC.
set -euo pipefail
root="${DRONE_TOOLING_ROOT:-/workspace/tooling}"
source_dir="$root/ardupilot-sitl"
venv="$root/mavlink-venv"
if [[ ! -d "$source_dir/.git" ]]; then
  git clone --depth 1 --branch Copter-4.6.3 https://github.com/ArduPilot/ardupilot.git "$source_dir"
fi
[[ "$(git -C "$source_dir" rev-parse HEAD)" == 92b0cd788ec29406f26c6f9c31d5ceedbd1cc538 ]] || {
  echo 'Sai revision SITL; giữ nguyên source hiện tại để kiểm tra.' >&2; exit 1;
}
if [[ ! -x "$venv/bin/python" ]]; then python3 -m venv "$venv"; fi
"$venv/bin/python" -m pip install pymavlink==2.4.49 empy==3.3.4 future==1.0.0 pexpect==4.9.0 dronecan==1.0.26 setuptools==80.9.0
cd "$source_dir"
git submodule update --init --depth 1 modules/waf modules/mavlink modules/gtest \
  modules/DroneCAN/DSDL modules/DroneCAN/dronecan_dsdlc modules/DroneCAN/libcanard modules/DroneCAN/pydronecan
export PATH="$venv/bin:$PATH"
# HAL SITL TCP serial remains enabled; AP_Networking peripheral + Lua are not needed.
# Waf configure regenerates dependencies and can force a full rebuild. Reuse an
# exact matching cache; waf still checks source dependencies on every build.
if ! python - <<'CACHE'
import ast,pathlib,sys
try:
    p=pathlib.Path('build/c4che/sitl_cache.py')
    values={n.targets[0].id:ast.literal_eval(n.value) for n in ast.parse(p.read_text()).body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name)}
    opts=values.get('OPTIONS',{})
    ok=values.get('BOARD')=='sitl' and opts.get('disable_scripting') is True and opts.get('disable_networking') is True
    sys.exit(0 if ok else 1)
except (OSError,ValueError,SyntaxError):sys.exit(1)
CACHE
then
    python ./waf configure --board sitl --disable-scripting --disable-networking --no-submodule-update
fi
python ./waf copter -j6
sha256sum "$source_dir/build/sitl/bin/arducopter"
