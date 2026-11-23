"""Copy distributable resources and the shared option schema into an iOS app."""
from pathlib import Path
import argparse
import plistlib
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--app', type=Path, required=True)
args = parser.parse_args()
app = args.app.resolve()
resources = app / 'AppResources'
shutil.copytree(ROOT / 'app/src/main/assets', resources, dirs_exist_ok=True)
(resources / 'updates').mkdir(exist_ok=True)
shutil.copy2(ROOT / 'CHANGELOG.md', resources / 'updates/changelog.md')
classes = ROOT / 'work/ios-options'
classes.mkdir(parents=True, exist_ok=True)
subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), str(ROOT / 'app/src/main/java/org/necmobile/MachineOptions.java'),
                str(ROOT / 'ios/Tools/ExportOptions.java')], check=True)
subprocess.run(['java', '-cp', str(classes), 'org.necmobile.ExportOptions', str(resources / 'machine-options.json')], check=True)
version = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text(encoding='utf-8').splitlines() if '=' in line)
plist = app / 'Info.plist'
with plist.open('rb') as source: info = plistlib.load(source)
info['CFBundleShortVersionString'] = version['versionName']
info['CFBundleVersion'] = version['versionCode']
with plist.open('wb') as output: plistlib.dump(info, output)
