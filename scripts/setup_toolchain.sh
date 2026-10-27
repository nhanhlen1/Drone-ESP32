#!/usr/bin/env bash
# Official-source fallback when the PlatformIO registry is unavailable.
# Installs the same pinned SDK/toolchain and libraries through supported local-package APIs.
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tools_root="${DRONE_TOOLS_ROOT:-/workspace/tooling}"
mkdir -p "$tools_root"
if [[ ! -x "$tools_root/pio-venv/bin/python" ]]; then
  python3 -m venv "$tools_root/pio-venv"
fi
"$tools_root/pio-venv/bin/pip" --disable-pip-version-check --cache-dir "$tools_root/pip-cache" install --quiet platformio==6.1.18 esptool==4.5.1 scons==4.8.1
export PLATFORMIO_CORE_DIR="$tools_root/platformio"
"$tools_root/pio-venv/bin/python" - "$tools_root" "$repo" <<'PY'
import hashlib,json,pathlib,subprocess,sys,tarfile,zipfile
import requests
from platformio.package.meta import PackageSpec
from platformio.package.manager.platform import PlatformPackageManager
from platformio.package.manager.tool import ToolPackageManager
from platformio.package.manager.library import LibraryPackageManager
root,repo=map(pathlib.Path,sys.argv[1:]);vendor=root/'vendor';archives=root/'archives'
vendor.mkdir(exist_ok=True);archives.mkdir(exist_ok=True)
def git_checkout(path,url,commit):
    if path.exists():
        found=subprocess.check_output(['git','-C',str(path),'rev-parse','HEAD'],text=True).strip()
        if found!=commit: raise RuntimeError(f'{path}: unexpected revision; preserving existing files')
        return
    subprocess.run(['git','init','-q',str(path)],check=True)
    subprocess.run(['git','-C',str(path),'remote','add','origin',url],check=True)
    subprocess.run(['git','-C',str(path),'fetch','-q','--depth','1','origin',commit],check=True)
    subprocess.run(['git','-C',str(path),'checkout','-q','--detach','FETCH_HEAD'],check=True)
def archive(name,url,digest):
    path=archives/name
    if not path.exists():
        temp=path.with_suffix(path.suffix+'.partial')
        with requests.get(url,stream=True,timeout=120) as response:
            response.raise_for_status()
            with temp.open('wb') as output:
                for data in response.iter_content(1024*1024):output.write(data)
        temp.replace(path)
    actual=hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
    if actual!=digest:raise RuntimeError(f'{name}: SHA256 mismatch; refusing artifact')
    return path
# Published checksums from Espressif's official package_esp32_index.json (2.0.17):
# https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
sdk=archive('esp32-2.0.17.zip','https://github.com/espressif/arduino-esp32/releases/download/2.0.17/esp32-2.0.17.zip','1f8658d4b18a8001ce782142ad08164af2991d70b83a147c3437a6ee30a9b225')
gcc=archive('xtensa.tar.gz','https://github.com/espressif/crosstool-NG/releases/download/esp-2021r2-patch5/xtensa-esp32-elf-gcc8_4_0-esp-2021r2-patch5-linux-amd64.tar.gz','8ef14e0409c2011b41e504a30f70d3e35287313a795d1f2462ad2cd0e2052d37')
if not (vendor/'esp32-2.0.17').exists():
    with zipfile.ZipFile(sdk) as z:
        for entry in z.namelist():
            if not (vendor/entry).resolve().is_relative_to(vendor.resolve()):raise RuntimeError('Unsafe zip path')
        z.extractall(vendor)
if not (vendor/'xtensa-esp32-elf').exists():
    with tarfile.open(gcc) as t:t.extractall(vendor,filter='data')
platform=root/'espressif32-platform'
git_checkout(platform,'https://github.com/platformio/platform-espressif32.git','268f561df4793d631d02e4ff5e67b6b11d1be4ec')
libs=[
 ('sparkfun','SparkFun 9DoF IMU Breakout - ICM 20948 - Arduino Library','ICM-20948','sparkfun/SparkFun_ICM-20948_ArduinoLibrary','32cf0614bccf0d8cff32fbc4260097ac10e5b714'),
 ('adafruit','Adafruit BMP280 Library','BMP280','adafruit/Adafruit_BMP280_Library','ddcaf6d232e545179af9caf906fa7a56305fcf9d'),
 ('adafruit','Adafruit BusIO','BusIO','adafruit/Adafruit_BusIO','15fbda592d1b237b0a92cfb91841adb01a34efd9'),
 ('adafruit','Adafruit Unified Sensor','UnifiedSensor','adafruit/Adafruit_Sensor','7b2473b6b24ae340f41685b5f5b2b90ad896db04'),
 ('mikalhart','TinyGPSPlus','TinyGPSPlus','mikalhart/TinyGPSPlus','0a205759da22a1c54c5f5285480fe6132592a4e2'),
 ('links2004','WebSockets','WebSockets','Links2004/arduinoWebSockets','7a4c416082b80dfc2c0da10731effc8d3a69abc6'),
 ('bblanchon','ArduinoJson','ArduinoJson','bblanchon/ArduinoJson','40ee05c065ce248192c47eb37af7a70a6935dfa6')]
for owner,name,directory,remote,commit in libs:git_checkout(vendor/directory,'https://github.com/'+remote+'.git',commit)
def package(path,name,version,extra=None):
    path.mkdir(exist_ok=True)
    data={'name':name,'version':version}
    if extra:data.update(extra)
    (path/'package.json').write_text(json.dumps(data,indent=2))
# PlatformIO uses a packaging version distinct from upstream Arduino's version.
# Only local package metadata changes; archive content was verified above.
sdk_manifest=vendor/'esp32-2.0.17'/'package.json'
data=json.loads(sdk_manifest.read_text());data.update(version='3.20017.0',upstreamVersion='2.0.17',upstreamArchiveSha256='1f8658d4b18a8001ce782142ad08164af2991d70b83a147c3437a6ee30a9b225');sdk_manifest.write_text(json.dumps(data,indent=2))
package(vendor/'xtensa-esp32-elf','toolchain-xtensa-esp32','8.4.0+2021r2-patch5')
package(vendor/'scons','tool-scons','4.40801.0',{'description':'SCons4.8.1 installed from PyPI'})
(vendor/'scons'/'scons.py').write_text('import SCons.Script\nSCons.Script.main()\n')
package(vendor/'esptool','tool-esptoolpy','1.40501.0',{'description':'esptool4.5.1 installed from PyPI'})
(vendor/'esptool'/'esptool.py').write_text('import os,sys\nsys.path=[p for p in sys.path if os.path.realpath(p)!=os.path.dirname(os.path.realpath(__file__))]\nimport esptool\nesptool._main()\n')
# Local-source specifications retain their real file:// provenance and owner/name
# qualifiers, allowing ordinary platformio.ini requirements to resolve offline.
pm=PlatformPackageManager()
ps=PackageSpec(owner='platformio',name='espressif32',uri=platform.as_uri())
if not pm.get_package(PackageSpec(owner='platformio',name='espressif32',requirements='6.9.0')):pm.install(ps,skip_dependencies=True)
tm=ToolPackageManager()
for owner,name,directory,version in [
 ('espressif','toolchain-xtensa-esp32','xtensa-esp32-elf','8.4.0+2021r2-patch5'),
 ('platformio','framework-arduinoespressif32','esp32-2.0.17','3.20017.0'),
 ('platformio','tool-esptoolpy','esptool','1.40501.0'),
 ('platformio','tool-scons','scons','4.40801.0')]:
    spec=PackageSpec(owner=owner,name=name,requirements=version)
    if not tm.get_package(spec):tm.install(PackageSpec(owner=owner,name=name,uri=(vendor/directory).as_uri()),skip_dependencies=True)
lm=LibraryPackageManager(str(repo/'firmware'/'.pio'/'libdeps'/'esp32dev'))
for owner,name,directory,remote,commit in libs:
    if not lm.get_package(PackageSpec(owner=owner,name=name)):
        lm.install(PackageSpec(owner=owner,name=name,uri=(vendor/directory).as_uri()),skip_dependencies=True)
print('Verified ESP32 toolchain installed. Build with:')
print(f'PLATFORMIO_CORE_DIR={root}/platformio {root}/pio-venv/bin/pio run -d {repo}/firmware -j 4')
PY
