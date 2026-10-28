#!/usr/bin/env bash
set -euo pipefail
mkdir -p /workspace/tooling/onboarding
cat > /workspace/tooling/onboarding/android-env.sh <<'DRONE_ANDROID_ENV'
#!/usr/bin/env bash
# Source in each task. Keep all SDKs and caches outside repository.
export DRONE_TOOLING_ROOT=/workspace/tooling
export JAVA_HOME="$DRONE_TOOLING_ROOT/jdk-21.0.9+10"
export ANDROID_HOME="$DRONE_TOOLING_ROOT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$DRONE_TOOLING_ROOT/android-user"
export GRADLE_USER_HOME="$DRONE_TOOLING_ROOT/gradle"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/19.0/bin:$PATH"
export CI=true
# Java does not consume HTTPS_PROXY. Use its supported proxy/trust settings.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
  drone_java_proxy="$(python3 - <<'PY'
import os,urllib.parse
p=urllib.parse.urlsplit(os.environ['HTTPS_PROXY'])
if p.username or p.password: raise SystemExit('Proxy authentication must be supplied by the platform, not copied into scripts.')
if p.hostname: print(f'-Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port or 80} -Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port or 80}')
PY
)" || return 1
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $drone_java_proxy"
  unset drone_java_proxy
fi
if [[ -f /etc/ssl/certs/java/cacerts ]]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
DRONE_ANDROID_ENV
mkdir -p /workspace/tooling
source /workspace/tooling/onboarding/android-env.sh
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
 curl --fail --location --retry 2 --connect-timeout 15 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz' -o /workspace/tooling/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz
 python3 - <<'PY'
import hashlib,pathlib,tarfile
p=pathlib.Path('/workspace/tooling/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz')
assert hashlib.sha256(p.read_bytes()).hexdigest()=='810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f','JDK integrity mismatch'
with tarfile.open(p) as t:t.extractall('/workspace/tooling',filter='data')
PY
fi
if [[ ! -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]; then
 curl --fail --location --retry 2 --connect-timeout 15 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' -o /workspace/tooling/android-cmdline-tools-19.zip
 python3 - <<'PY'
import hashlib,pathlib,zipfile
p=pathlib.Path('/workspace/tooling/android-cmdline-tools-19.zip')
assert hashlib.sha1(p.read_bytes()).hexdigest()=='5fdcc763663eefb86a5b8879697aa6088b041e70','SDK tools integrity mismatch'
root=pathlib.Path('/workspace/tooling/android-sdk/cmdline-tools');root.mkdir(parents=True,exist_ok=True)
stage=root/'stage-19';stage.mkdir(exist_ok=True)
with zipfile.ZipFile(p) as z:z.extractall(stage)
(stage/'cmdline-tools').rename(root/'19.0');stage.rmdir()
for executable in (root/'19.0'/'bin').iterdir():executable.chmod(0o755)
PY
fi
python3 - <<'PY'
import os,subprocess
root=os.environ['ANDROID_HOME'];cmd=[root+'/cmdline-tools/19.0/bin/sdkmanager','--sdk_root='+root]
subprocess.run(cmd+['--licenses'],input='y\n'*100,text=True,check=True)
subprocess.run(cmd+['platform-tools','platforms;android-36','build-tools;35.0.0'],check=True)
PY
java -version
javac -version

# The application checkout is the task checkout; do not create a Git worktree.
project=/workspace/Drone-ESP32/android_drone_controller
[[ -f "$project/settings.gradle.kts" ]] || { echo 'Thiếu project android_drone_controller trong snapshot/checkout.' >&2; exit 1; }
bash "$project/integration/setup_sitl.sh"
bash "$project/integration/setup_target_sitl.sh"
export DRONE_SITL_BINARY=/workspace/tooling/ardupilot-target-sitl/build/sitl/bin/arducopter
export DRONE_SITL_PARAM_PROFILE=modern
bash "$project/scripts/verify.sh"
cd "$project"
python3 -m unittest discover -s integration -p 'test_*.py' -v
