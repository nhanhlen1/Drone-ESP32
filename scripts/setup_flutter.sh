#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/flutter_env.sh"
mkdir -p "$DRONE_TOOLING_ROOT"
if [[ ! -d "$DRONE_TOOLING_ROOT/flutter/.git" ]]; then
  git clone --depth 1 --branch 3.35.7 https://github.com/flutter/flutter.git "$DRONE_TOOLING_ROOT/flutter"
fi
[[ "$(git -C "$DRONE_TOOLING_ROOT/flutter" rev-parse HEAD)" == adc901062556672b4138e18a4dc62a4be8f4b3c2 ]] || {
  echo 'Unexpected Flutter revision. Preserve local SDK work before selecting the pinned SDK.' >&2; exit 1;
}
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  python3 - <<'JDKPY'
import hashlib,os,pathlib,urllib.request,tarfile
root=pathlib.Path(os.environ['DRONE_TOOLING_ROOT'])
name='OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz'
url='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/'+name
archive=root/name
urllib.request.urlretrieve(url,archive)
# SHA256 published by Adoptium beside the official release archive.
expected='810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f'
if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:
    raise RuntimeError('JDK archive integrity check failed')
with tarfile.open(archive) as t:t.extractall(root,filter='data')
JDKPY
fi
"$JAVA_HOME/bin/javac" -version
flutter --suppress-analytics --version
if [[ ! -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]; then
  python3 - <<'PY'
import hashlib,os,pathlib,urllib.request,zipfile
out=pathlib.Path(os.environ['ANDROID_HOME'])/'cmdline-tools'
out.mkdir(parents=True,exist_ok=True)
archive=pathlib.Path(os.environ['DRONE_TOOLING_ROOT'])/'android-cmdline-tools-19.zip'
url='https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip'
urllib.request.urlretrieve(url,archive)
# SHA1 published by Google's HTTPS repository2-1.xml for cmdline-tools;19.0.
expected='5fdcc763663eefb86a5b8879697aa6088b041e70'
if hashlib.sha1(archive.read_bytes()).hexdigest()!=expected:
    raise RuntimeError('SDK archive integrity check failed')
stage=out/'stage-19'
stage.mkdir(exist_ok=True)
with zipfile.ZipFile(archive) as z:z.extractall(stage)
(stage/'cmdline-tools').rename(out/'19.0')
stage.rmdir()
for executable in (out/'19.0'/'bin').iterdir(): executable.chmod(0o755)
if not (out/'latest').exists(): (out/'latest').symlink_to('19.0',target_is_directory=True)
PY
fi
# Standard Android SDK licenses are accepted for this authorized development setup.
python3 - <<'PY'
import os,subprocess
root=os.environ['ANDROID_HOME']
cmd=[root+'/cmdline-tools/19.0/bin/sdkmanager','--sdk_root='+root]
subprocess.run(cmd+['--licenses'],input='y\n'*100,text=True,check=True)
subprocess.run(cmd+['platform-tools','platforms;android-36','build-tools;35.0.0','ndk;27.0.12077973','cmake;3.22.1'],check=True)
PY
cd "$repo_root/mobile_app"
flutter --suppress-analytics pub get --enforce-lockfile
