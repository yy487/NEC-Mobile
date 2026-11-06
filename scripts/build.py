"""Build and sign the ARM64 APK using only the Android SDK/NDK and a JDK."""
import argparse
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--jdk', type=Path)
parser.add_argument('--cmake', default='cmake')
args = parser.parse_args()
jdk = args.jdk or Path(shutil.which('javac') or '').resolve().parent.parent
sdk = ROOT / '.cache' / 'android-sdk'
tools = sdk / 'build-tools' / '36.0.0'
ndk = sdk / 'ndk' / '28.2.13676358'
android = sdk / 'platforms' / 'android-36' / 'android.jar'
native = ROOT / 'work' / 'build-arm64'
release = ROOT / 'release'
release.mkdir(exist_ok=True)
staging = Path(tempfile.mkdtemp(prefix='apk-', dir=ROOT / 'work'))

def run(*command):
    print('> ' + ' '.join(str(item) for item in command), flush=True)
    subprocess.run([str(item) for item in command], cwd=ROOT, check=True)

def java(tool):
    return jdk / 'bin' / (tool + '.exe' if os.name == 'nt' else tool)

run(java('java'), '-version')
run(os.sys.executable, '-X', 'utf8', ROOT / 'scripts' / 'prepare_m88.py')
run(os.sys.executable, '-X', 'utf8', ROOT / 'scripts' / 'build_np2kai.py')
run(args.cmake, '-S', ROOT / 'native', '-B', native, '-G', 'MinGW Makefiles',
    '-DCMAKE_MAKE_PROGRAM=' + (ndk / 'prebuilt/windows-x86_64/bin/make.exe').as_posix(),
    '-DCMAKE_TOOLCHAIN_FILE=' + (ndk / 'build/cmake/android.toolchain.cmake').as_posix(),
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26', '-DCMAKE_BUILD_TYPE=Release')
run(args.cmake, '--build', native, '--parallel', '4')

generated, classes, dex = (staging / name for name in ('generated', 'classes', 'dex'))
for directory in (generated, classes, dex):
    directory.mkdir()
compiled = staging / 'resources.zip'
base = staging / 'base.apk'
run(tools / 'aapt2.exe', 'compile', '--dir', ROOT / 'app/src/main/res', '-o', compiled)
run(tools / 'aapt2.exe', 'link', '-o', base, '-I', android,
    '--manifest', ROOT / 'app/src/main/AndroidManifest.xml', '--java', generated,
    '--min-sdk-version', '26', '--target-sdk-version', '36',
    '--version-code', '7', '--version-name', '0.2.4-alpha',
    '-A', ROOT / 'app/src/main/assets', compiled)
sources = sorted((ROOT / 'app/src/main/java').rglob('*.java')) + sorted(generated.rglob('*.java'))
source_list = staging / 'sources.txt'
source_list.write_text('\n'.join('"' + file.as_posix() + '"' for file in sources), encoding='utf-8')
run(java('javac'), '-encoding', 'UTF-8', '--release', '17', '-classpath', android,
    '-d', classes, '@' + str(source_list))
jar = staging / 'classes.jar'
run(java('jar'), '--create', '--file', jar, '-C', classes, '.')
run(java('java'), '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release',
    '--lib', android, '--min-api', '26', '--output', dex, jar)

libraries = {
    'libpc88.so': native / 'pc88/libpc88.so',
    'libpc98.so': native / 'libpc98.so',
    'libnec_host.so': native / 'libnec_host.so',
    'libnp2kai.so': ROOT / 'work/np2kai-build/libs/arm64-v8a/libretro.so',
}
unsigned = staging / 'unsigned.apk'
strip = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
with zipfile.ZipFile(base) as resources, zipfile.ZipFile(unsigned, 'w') as apk:
    for entry in resources.infolist():
        apk.writestr(entry.filename, resources.read(entry), compress_type=entry.compress_type)
    for file in sorted(dex.glob('*.dex')):
        apk.write(file, file.name, compress_type=zipfile.ZIP_DEFLATED)
    for name, file in libraries.items():
        stripped = staging / name
        run(strip, '--strip-unneeded', '-o', stripped, file)
        apk.write(stripped, 'lib/arm64-v8a/' + name, compress_type=zipfile.ZIP_STORED)

aligned = staging / 'aligned.apk'
run(tools / 'zipalign.exe', '-P', '16', '-f', '4', unsigned, aligned)
signing = ROOT / '.cache' / 'signing'
signing.mkdir(exist_ok=True)
keystore = signing / 'development.jks'
if not keystore.exists():
    run(java('keytool'), '-genkeypair', '-keystore', keystore, '-storepass', 'android',
        '-keypass', 'android', '-alias', 'necmobile', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-dname', 'CN=NEC Mobile Development, O=Local, C=CN')
signed = staging / 'signed.apk'
run(java('java'), '--enable-native-access=ALL-UNNAMED', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', keystore,
    '--ks-key-alias', 'necmobile', '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--out', signed, aligned)
destination = release / 'NEC-Mobile-0.2.4-alpha-arm64.apk'
os.replace(signed, destination)
print('APK: ' + str(destination), flush=True)
print('Bytes: ' + str(destination.stat().st_size), flush=True)
