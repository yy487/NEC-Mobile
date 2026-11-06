"""Build the small, separately installed Android feature-check instrumentation."""
from pathlib import Path
import os
import subprocess
import argparse
import shutil

parser=argparse.ArgumentParser()
parser.add_argument('--jdk',type=Path)
args=parser.parse_args()

root=Path(__file__).resolve().parents[1]
sdk=root/'.cache/android-sdk'
tools=sdk/'build-tools/36.0.0'
android=sdk/'platforms/android-36/android.jar'
jdk=(args.jdk or Path(shutil.which('javac') or '').resolve().parent.parent)/'bin'
work=root/'work/update-0.2.4/instrumentation'
work.mkdir(parents=True,exist_ok=True)
classes=work/'classes'
classes.mkdir(exist_ok=True)
app_classes=max((p/'classes' for p in (root/'work').glob('apk-*') if (p/'classes/org/necmobile/Native.class').is_file()),key=lambda p:p.stat().st_mtime)
def run(*args): subprocess.run([str(x) for x in args],cwd=root,check=True)
run(tools/'aapt2.exe','link','-o',work/'base.apk','-I',android,'--manifest',root/'tests/android/AndroidManifest.xml')
sources=sorted((root/'tests/android').glob('*.java'))
run(jdk/'javac.exe','-encoding','UTF-8','--release','17','-classpath',str(android)+';'+str(app_classes),'-d',classes,*sources)
run(jdk/'jar.exe','--create','--file',work/'classes.jar','-C',classes,'.')
dex=work/'dex'
dex.mkdir(exist_ok=True)
run(jdk/'java.exe','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--release','--lib',android,'--min-api','26','--output',dex,work/'classes.jar')
import zipfile
with zipfile.ZipFile(work/'base.apk') as src, zipfile.ZipFile(work/'unsigned.apk','w') as dest:
    for entry in src.infolist(): dest.writestr(entry,src.read(entry))
    dest.write(dex/'classes.dex','classes.dex')
run(tools/'zipalign.exe','-f','4',work/'unsigned.apk',work/'aligned.apk')
run(jdk/'java.exe','--enable-native-access=ALL-UNNAMED','-jar',tools/'lib/apksigner.jar','sign','--ks',root/'.cache/signing/development.jks','--ks-pass','pass:android','--ks-key-alias','necmobile','--out',work/'check.apk',work/'aligned.apk')
print(work/'check.apk')
