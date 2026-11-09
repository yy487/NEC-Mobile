"""Build the Kotlin/Compose application and matching native cores through Gradle."""
import argparse
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--jdk', type=Path)
parser.add_argument('--cmake', default='cmake')
parser.add_argument('--sdk', type=Path, default=ROOT / '.cache/android-sdk')
parser.add_argument('--gradle-arg', action='append', default=[])
args = parser.parse_args()
environment = os.environ.copy()
if args.jdk:
    environment['JAVA_HOME'] = str(args.jdk.resolve())
    environment['PATH'] = str(args.jdk.resolve() / 'bin') + os.pathsep + environment.get('PATH', '')
environment['ANDROID_HOME'] = str(args.sdk.resolve())
environment['ANDROID_SDK_ROOT'] = str(args.sdk.resolve())
key = ROOT / '.cache/signing/development.jks'
if not key.is_file():
    raise SystemExit('Signing key missing: restore .cache/signing/development.jks before building an update.')
wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
subprocess.run([str(wrapper), 'assembleRelease', '-Pnec.cmake=' + args.cmake,
                '-Pnec.sdk=' + args.sdk.resolve().as_posix(), '--no-daemon', *args.gradle_arg],
               cwd=ROOT, env=environment, check=True)
version = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text(encoding='utf-8').splitlines() if '=' in line)['versionName']
release = ROOT / 'release'
release.mkdir(exist_ok=True)
target = release / f'NEC-Mobile-{version}-arm64.apk'
temporary = target.with_suffix('.apk.pending')
shutil.copy2(ROOT / 'app/build/outputs/apk/release/app-release.apk', temporary)
os.replace(temporary, target)
print('APK: ' + str(target))
print('Bytes: ' + str(target.stat().st_size))
