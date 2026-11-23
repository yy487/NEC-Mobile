"""Build the iOS app with Xcode and package a device IPA for personal signing."""
from pathlib import Path
import argparse
import platform
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--simulator', action='store_true')
parser.add_argument('--configuration', choices=['Debug', 'Release'], default='Release')
args = parser.parse_args()
if platform.system() != 'Darwin': raise SystemExit('iOS compilation uses macOS and Xcode. Run the iOS workflow on GitHub Actions.')
sdk = 'iphonesimulator' if args.simulator else 'iphoneos'
arch = platform.machine() if args.simulator else 'arm64'
build = ROOT / 'work' / ('ios-simulator' if args.simulator else 'ios-device')
subprocess.run(['python3', str(ROOT / 'scripts/prepare_m88.py')], check=True)
subprocess.run(['python3', str(ROOT / 'scripts/prepare_np2kai.py')], check=True)
subprocess.run(['cmake', '-S', str(ROOT / 'ios'), '-B', str(build), '-G', 'Xcode', '-DCMAKE_SYSTEM_NAME=iOS',
                '-DCMAKE_OSX_SYSROOT=' + sdk, '-DCMAKE_OSX_ARCHITECTURES=' + arch, '-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0'], check=True)
subprocess.run(['cmake', '--build', str(build), '--config', args.configuration, '--parallel', '4', '--', 'CODE_SIGNING_ALLOWED=NO'], check=True)
app = build / (args.configuration + '-' + sdk) / 'NECMobile.app'
if not app.is_dir(): raise SystemExit('Xcode did not produce NECMobile.app')
release = ROOT / 'release'
release.mkdir(exist_ok=True)
if args.simulator:
    target = release / 'NEC-Mobile-iOS-simulator.zip'
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as output:
        for file in sorted(app.rglob('*')):
            if file.is_file(): output.write(file, 'NECMobile.app/' + file.relative_to(app).as_posix())
else:
    target = release / 'NEC-Mobile-iOS-unsigned.ipa'
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as output:
        for file in sorted(app.rglob('*')):
            if file.is_file(): output.write(file, 'Payload/NECMobile.app/' + file.relative_to(app).as_posix())
print(target)
