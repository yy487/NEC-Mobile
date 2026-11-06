"""Build the private NP2kai source with real floppy insertion/ejection."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
ndk = root / '.cache/android-sdk/ndk/28.2.13676358'
source = root / 'third_party/np2kai-source'
subprocess.run([sys.executable, '-X', 'utf8', root / 'scripts/prepare_np2kai.py'], check=True)
command = [ndk / ('ndk-build.cmd' if os.name == 'nt' else 'ndk-build'),
    'NDK_PROJECT_PATH=' + root.as_posix(),
    'APP_BUILD_SCRIPT=' + (source / 'jni/Android.mk').as_posix(),
    'NDK_APPLICATION_MK=' + (source / 'jni/Application.mk').as_posix(),
    'APP_ABI=arm64-v8a', 'APP_PLATFORM=android-26', 'APP_SHORT_COMMANDS=true',
    'NDK_OUT=work/np2kai-build/obj', 'NDK_LIBS_OUT=work/np2kai-build/libs',
    'NP2KAI_VERSION=necmobile-0.2.0', 'NP2KAI_HASH=local-floppy-control', '-j4']
if os.name == 'nt': command.append('HOST_OS=windows')
subprocess.run(list(map(str, command)), cwd=root, check=True)
