#!/usr/bin/env python3
"""Build an isolated preview without overwriting the installed production app.

The unsigned, aligned output is signed locally with a private preview key. CI
never receives that key. Root module identity and contents intentionally stay
unchanged: stop the old app's VPN and automatic updates before testing.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'io.github.xgl34222220.bichen'
PREVIEW = BASE + '.preview'
VERSION = '0.3.0-test.3'
CODE = 303

def run(*args: str | Path, cwd: Path) -> None:
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main() -> None:
    out = ROOT / 'out' / 'preview'
    out.mkdir(parents=True, exist_ok=True)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    with tempfile.TemporaryDirectory(prefix='bichen-preview-') as temp:
        stage = Path(temp) / 'Bichen'
        shutil.copytree(ROOT, stage, ignore=shutil.ignore_patterns('.git', 'out', 'downloads', 'build', '__pycache__', '*.keystore', '*.jks', '*.p12', '*.idsig'))
        main_dir = stage / 'android-app/app/src/main'
        manifest = main_dir / 'AndroidManifest.xml'
        value = manifest.read_text()
        assert f'package="{BASE}"' in value
        value = value.replace(f'package="{BASE}"', f'package="{PREVIEW}"')
        value = value.replace('android:label="辟尘"', 'android:label="辟尘·测试"')
        value = re.sub(r'android:versionCode="[^"]+"', f'android:versionCode="{CODE}"', value)
        value = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{VERSION}"', value)
        manifest.write_text(value)
        # Java package and intent actions are isolated together. Android uses
        # getPackageName() for self-bypass and launch intents in the VPN code.
        for path in list((main_dir / 'java').rglob('*.java')) + list((stage / 'tests').glob('*.java')):
            text = path.read_text().replace(BASE, PREVIEW)
            if path.name == 'MainActivity.java':
                text = text.replace('"辟尘"', '"辟尘·测试"')
                text = text.replace('"少一点打扰，多一点清净"', '"测试版 · 请先停止旧版保护与自动更新"')
            path.write_text(text)
        test_script = stage / 'tools/test_java.py'
        test_script.write_text(test_script.read_text().replace(BASE, PREVIEW))
        builder = stage / 'tools/build_app.py'
        value = re.sub(r'^VERSION = .*$', f'VERSION = "{VERSION}"', builder.read_text(), flags=re.M)
        value = re.sub(r'^VERSION_CODE = .*$', f'VERSION_CODE = {CODE}', value, flags=re.M)
        builder.write_text(value)
        run(sys.executable, 'tools/package.py', '--prepare-only', cwd=stage)
        run(sys.executable, 'tools/build_app.py', '--out', stage / 'out/compile-check.apk', cwd=stage)
        run(sys.executable, 'tools/test_java.py', cwd=stage)
        run(sys.executable, '-m', 'unittest', 'discover', '-s', 'tests', '-p', '*test.py', '-v', cwd=stage)
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
        tools = sdk / 'build-tools/35.0.0'
        unsigned = out / f'Bichen-{VERSION}-unsigned.apk'
        shutil.copyfile(stage / 'android-app/build/aligned.apk', unsigned)
        run(tools / 'zipalign', '-c', '-p', '4', unsigned, cwd=stage)
        badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(unsigned)], text=True)
        assert f"package: name='{PREVIEW}'" in badging
        assert f"versionName='{VERSION}'" in badging and f"versionCode='{CODE}'" in badging
        assert f"launchable-activity: name='{PREVIEW}.MainActivity'" in badging
        (out / 'apk-badging.txt').write_text(badging)
        with zipfile.ZipFile(unsigned) as apk:
            assert apk.testzip() is None
            module = apk.read('assets/bichen-module.zip')
            info = json.loads(apk.read('assets/module-info.json'))
            assert hashlib.sha256(module).hexdigest() == info['sha256']
            assert info['version'] == '0.3.0-beta.1'
            assert info['sha256'] == '2a830195fca8fa3558f42c696aa02441b136069719157cc1c8abf81d903cd413'
            dex = apk.read('classes.dex')
            for name in ('DnsResponseFilter', 'NetworkEpoch', 'RuleUpdateGate', 'RuleProfiles'):
                assert f'Lio/github/xgl34222220/bichen/preview/{name};'.encode() in dex
        (out / 'Bichen-0.3.0-beta.1-module.zip').write_bytes(module)
        shutil.copyfile(tools / 'lib/apksigner.jar', out / 'apksigner.jar')
        meta = {'sourceCommit': revision, 'versionName': VERSION, 'versionCode': CODE, 'packageName': PREVIEW,
                'moduleVersion': info['version'], 'moduleSha256': info['sha256'],
                'unsignedApkSha256': digest(unsigned), 'signingToolSha256': digest(out / 'apksigner.jar'),
                'note': 'Unsigned handoff. Sign locally with the private preview key; do not distribute as a production update.'}
        (out / 'build-info.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
        # Exact source used for compilation; no APKs, tool binaries, or keys.
        source = out / f'Bichen-{VERSION}-source.zip'
        with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for path in sorted(stage.rglob('*')):
                rel = path.relative_to(stage)
                if not path.is_file() or any(p in {'out', 'build', '.git', '__pycache__', 'downloads'} for p in rel.parts):
                    continue
                if path.suffix in {'.keystore', '.jks', '.p12', '.pyc', '.ttf', '.otf'}:
                    continue
                z.write(path, str(Path('Bichen') / rel))
        files = [unsigned, out / 'Bichen-0.3.0-beta.1-module.zip', source, out / 'apksigner.jar', out / 'build-info.json', out / 'apk-badging.txt']
        (out / 'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in files))
        print(json.dumps(meta, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
