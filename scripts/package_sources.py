"""Package the Android application, required core sources, and original licenses."""
from pathlib import Path
import os
import zipfile

root = Path(__file__).resolve().parents[1]
release = root / 'release'
release.mkdir(exist_ok=True)
version = dict(line.split('=', 1) for line in (root / 'version.properties').read_text(encoding='utf-8').splitlines() if '=' in line)['versionName']
target = release / f'NEC-Mobile-{version}-sources.zip'
staging = target.with_suffix('.zip.part')
files = []
for folder in ('app', 'native', 'scripts', 'tests',
               'third_party/m88/src/common', 'third_party/m88/src/devices',
               'third_party/m88/src/if', 'third_party/m88/src/pc88',
               'third_party/np2kai-source', 'third_party/tinysoundfont', 'third_party/fatfs', 'gradle'):
    base = root / folder
    for file in sorted(base.rglob('*')):
        relative = file.relative_to(root)
        if not file.is_file() or any(part in ('.git', '.vs', '__pycache__', 'build', 'obj', 'bin') for part in relative.parts):
            continue
        if file.name == '.installed.json':
            continue
        if relative.parent.as_posix() == 'third_party/m88/src/devices' and file.name in ('Z80_x86.cpp', 'Z80Debug.cpp', 'Z80Test.cpp', 'Z80diag.cpp'):
            continue
        if relative.parent.as_posix() == 'third_party/m88/src/pc88' and file.name in ('ioview.cpp', 'memview.cpp'):
            continue
        bundled_firmware = relative.is_relative_to('app/src/main/assets/firmware') and file.suffix.lower() in ('.rom', '.fnt', '.wav')
        if not bundled_firmware and file.suffix.lower() in ('.rom', '.wav', '.fnt', '.sf2', '.sf3', '.dls',
                                  '.d88', '.88d', '.fdi', '.hdi', '.nhd', '.hdd', '.thd', '.xdf', '.hdm',
                                  '.bin', '.exe', '.dll', '.pdb', '.obj', '.apk', '.idsig', '.jks', '.keystore', '.zip'):
            continue
        files.append((file, relative))
for name in ('README.md', 'README.en.md', 'README.ja.md', 'CHANGELOG.md', 'COPYING', 'LICENSE.md', '.gitignore', '.gitattributes',
             'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'version.properties', 'gradlew', 'gradlew.bat',
             'third_party/m88/README.md', 'third_party/m88/readme.txt', 'third_party/m88/README.necmobile.md'):
    file = root / name
    if file.exists():
        files.append((file, Path(name)))
for name in ('README.md', 'fdkernel-source-20220120.zip', 'freecom-source-20220117.zip'):
    relative = Path('third_party/freedos98') / name
    files.append((root / relative, relative))
with zipfile.ZipFile(staging, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
    for file, relative in files:
        output.write(file, 'NEC-Mobile/' + relative.as_posix())
os.replace(staging, target)
print(target)
print('Source files: ' + str(len(files)))
