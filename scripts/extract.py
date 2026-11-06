"""Install downloaded development dependencies inside this project only."""
import json
from pathlib import Path, PurePosixPath
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS = ROOT / 'work' / 'downloads'
PACKAGES = {
    'platforms-android-36': '.cache/android-sdk/platforms/android-36',
    'build-tools-36.0.0': '.cache/android-sdk/build-tools/36.0.0',
    'ndk-28.2.13676358': '.cache/android-sdk/ndk/28.2.13676358',
    'platform-tools': '.cache/android-sdk/platform-tools',
    'np2core': 'third_party/np2kai-binary',
    'np2kai-source': 'third_party/np2kai-source',
}

for name, relative in PACKAGES.items():
    archive = DOWNLOADS / (name + '.zip')
    destination = ROOT / relative
    marker = destination / '.installed.json'
    if marker.exists() or not archive.exists():
        continue
    print('extracting ' + name, flush=True)
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            components = PurePosixPath(entry.filename).parts
            if not components or '..' in components or ':' in entry.filename or '\\' in entry.filename:
                raise ValueError('Unsafe archive path: ' + entry.filename)
            # All upstream archives have one top-level directory.
            stripped = components if name == 'np2core' else components[1:]
            target = destination.joinpath(*stripped)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError('Archive entry escapes destination')
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            elif stripped:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as incoming, target.open('wb') as outgoing:
                    import shutil
                    shutil.copyfileobj(incoming, outgoing)
    marker.write_text(json.dumps({'archive': archive.name}) + '\n', encoding='utf-8')
    print('installed ' + name, flush=True)
