#!/usr/bin/env python3
"""Đóng gói bản build hiện tại; không tự upload hoặc lấy credential/local config."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

root=Path(__file__).resolve().parents[1]
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
metadata=root/'app/build/outputs/apk/debug/output-metadata.json'
if not apk.is_file() or not metadata.is_file():
    raise SystemExit('Chưa có APK/metadata; chạy scripts/verify.sh và đọc kết quả trước khi đóng gói.')
version=json.loads(metadata.read_text())['elements'][0]['versionName']
if not version or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-_' for c in version):
    raise SystemExit('versionName không hợp lệ để đặt tên artifact')
out=root/'dist'/('ESP32_DRONE_ANDROID_APP_'+version)
out.mkdir(parents=True,exist_ok=True)
shutil.copy2(apk,out/('ESP32-Drone-Controller-'+version+'-debug.apk'))
for name in ['README.md','README_VI.md','CHANGELOG.md','docs','reports','integration','scripts']:
    source=root/name
    if source.is_dir(): shutil.copytree(source,out/name,dirs_exist_ok=True,ignore=shutil.ignore_patterns('__pycache__','captures','*.pyc'))
    elif source.is_file(): shutil.copy2(source,out/name)
excluded={'.gradle','.kotlin','build','.idea','dist','__pycache__','captures'}
with zipfile.ZipFile(out/'android_drone_controller-source.zip','w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(root.rglob('*')):
        relative=p.relative_to(root)
        if p.is_file() and not (set(relative.parts)&excluded) and p.name!='local.properties':
            z.write(p,Path(root.name)/relative)
sums={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir() if p.is_file() and p.name!='SHA256SUMS.json'}
(out/'SHA256SUMS.json').write_text(json.dumps(sums,indent=2)+'\n')
bundle=out.parent/(out.name+'.zip')
with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(out.rglob('*')):
        if p.is_file(): z.write(p,Path(out.name)/p.relative_to(out))
print(out)
print(bundle)
