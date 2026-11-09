"""Build the four matching ARM64 libraries used by the Android application."""
import argparse
from pathlib import Path
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--sdk', type=Path, default=ROOT / '.cache/android-sdk')
parser.add_argument('--cmake', default='cmake')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
ndk = args.sdk / 'ndk/28.2.13676358'
build = ROOT / 'work/build-arm64'

def run(*command):
    subprocess.run([str(value) for value in command], cwd=ROOT, check=True)

run(sys.executable, '-X', 'utf8', ROOT / 'scripts/prepare_m88.py')
run(sys.executable, '-X', 'utf8', ROOT / 'scripts/build_np2kai.py', '--ndk', ndk)
generator = 'MinGW Makefiles' if os.name == 'nt' else 'Unix Makefiles'
command = [args.cmake, '-S', ROOT / 'native', '-B', build, '-G', generator,
           '-DCMAKE_TOOLCHAIN_FILE=' + (ndk / 'build/cmake/android.toolchain.cmake').as_posix(),
           '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26', '-DCMAKE_BUILD_TYPE=Release']
if os.name == 'nt':
    command.append('-DCMAKE_MAKE_PROGRAM=' + (ndk / 'prebuilt/windows-x86_64/bin/make.exe').as_posix())
run(*command)
run(args.cmake, '--build', build, '--parallel', '4')
output = args.output / 'arm64-v8a'
output.mkdir(parents=True, exist_ok=True)
libraries = {
    'libpc88.so': build / 'pc88/libpc88.so',
    'libpc98.so': build / 'libpc98.so',
    'libnec_host.so': build / 'libnec_host.so',
    'libnp2kai.so': ROOT / 'work/np2kai-build/libs/arm64-v8a/libretro.so',
}
for name, source in libraries.items():
    shutil.copy2(source, output / name)
