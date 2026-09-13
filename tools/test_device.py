#!/usr/bin/env python3
"""Install the built preview on the running CI emulator and exercise real Android UI/export.
A disposable CI-only signing key is used; user delivery is signed privately later.
"""
import os, subprocess, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'out/device'; OUT.mkdir(parents=True,exist_ok=True)
SDK=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
TOOLS=SDK/'build-tools/35.0.0'; JAR=SDK/'platforms/android-35/android.jar'
def run(*args, **kwargs):return subprocess.run(list(map(str,args)),check=True,**kwargs)
key=OUT/'ci-only.keystore'
run('keytool','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','test','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Disposable CI Test')
def sign(src,dst):
 run(TOOLS/'apksigner','sign','--ks',key,'--ks-key-alias','test','--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',dst,src)
sign(ROOT/'out/preview/Bichen-0.3.0-test.3-unsigned.apk',OUT/'device-app.apk')
manifest=OUT/'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="bichen.devicecheck"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Bichen device checks" android:debuggable="true"/><instrumentation android:name="bichen.devicecheck.Smoke" android:targetPackage="io.github.xgl34222220.bichen.preview" android:functionalTest="true"/></manifest>''')
classes=OUT/'classes';classes.mkdir(exist_ok=True)
run('javac','-source','8','-target','8','-encoding','UTF-8','-bootclasspath',str(JAR)+os.pathsep+str(TOOLS/'core-lambda-stubs.jar'),'-d',classes,ROOT/'tests/device/Smoke.java')
with zipfile.ZipFile(OUT/'classes.jar','w') as z:
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
dex=OUT/'dex';dex.mkdir(exist_ok=True)
run(TOOLS/'d8','--min-api','26','--lib',JAR,'--output',dex,OUT/'classes.jar')
run(TOOLS/'aapt','package','-f','-M',manifest,'-I',JAR,'-F',OUT/'test-unsigned.apk')
with zipfile.ZipFile(OUT/'test-unsigned.apk','a') as z:
 for p in dex.glob('*.dex'):z.write(p,p.name)
run(TOOLS/'zipalign','-f','4',OUT/'test-unsigned.apk',OUT/'test-aligned.apk')
sign(OUT/'test-aligned.apk',OUT/'device-test.apk')
run('adb','install','-r',OUT/'device-app.apk');run('adb','install','-r',OUT/'device-test.apk')
run('adb','shell','settings','put','system','system_locales','zh-CN')
proc=run('adb','shell','am','instrument','-w','bichen.devicecheck/.Smoke',capture_output=True,text=True,timeout=150)
(OUT/'device-results.txt').write_text(proc.stdout+'\n'+proc.stderr);print(proc.stdout)
run('adb','root');run('adb','wait-for-device')
run('adb','pull','/data/user/0/io.github.xgl34222220.bichen.preview/files',OUT/'screenshots')
assert 'BICHEN_DEVICE_PASS' in proc.stdout and 'BICHEN_DEVICE_FAIL' not in proc.stdout
