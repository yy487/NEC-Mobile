"""Build the shared NP2kai framework with Apple's device or simulator SDK."""
from pathlib import Path
import argparse
import os
import plistlib
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--sdk', choices=['iphoneos', 'iphonesimulator'], required=True)
parser.add_argument('--arch', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
sdk = subprocess.check_output(['xcrun', '--sdk', args.sdk, '--show-sdk-path'], text=True).strip()
workspace = args.output.resolve().parent / 'np2-source'
source = workspace / 'third_party/np2kai-source'
if not source.exists():
    shutil.copytree(ROOT / 'third_party/np2kai-source', source, ignore=shutil.ignore_patterns('.git', '*.o'))
    (workspace / 'native').mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / 'native/rhythm_wav.h', workspace / 'native/rhythm_wav.h')
framework = args.output.resolve()
framework.mkdir(parents=True, exist_ok=True)
target = args.arch + '-apple-ios15.0' + ('-simulator' if args.sdk == 'iphonesimulator' else '')
subprocess.run(['make', '-f', 'Makefile.libretro', 'platform=necmobile_ios', 'IOSSDK=' + sdk,
                'APPLE_TARGET=' + target, 'TARGET=' + str(framework / 'np2kai'),
                'NP2KAI_VERSION=necmobile-ios', 'NP2KAI_HASH=apple-port', '-j' + str(min(6, os.cpu_count() or 2))],
               cwd=source / 'sdl', check=True)
with (framework / 'Info.plist').open('wb') as output:
    plistlib.dump({'CFBundleIdentifier': 'org.necmobile.np2kai', 'CFBundleExecutable': 'np2kai',
                  'CFBundleName': 'np2kai', 'CFBundlePackageType': 'FMWK', 'CFBundleVersion': '1',
                  'CFBundleShortVersionString': '1.0', 'MinimumOSVersion': '15.0',
                  'CFBundleSupportedPlatforms': ['iPhoneSimulator' if args.sdk == 'iphonesimulator' else 'iPhoneOS']}, output)
