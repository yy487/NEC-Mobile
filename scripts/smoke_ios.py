"""Run one simulator smoke session against both bundled emulator cores."""
from pathlib import Path
import json
import os
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
def run(*args): return subprocess.check_output(list(args), text=True, timeout=45).strip()
runtimes = json.loads(run('xcrun', 'simctl', 'list', 'runtimes', '-j'))['runtimes']
runtime = next(item for item in reversed(runtimes) if item.get('isAvailable') and '.iOS-' in item['identifier'])
available = json.loads(run('xcrun', 'simctl', 'list', 'devices', 'available', '-j'))['devices'].get(runtime['identifier'], [])
device_type = next((item['deviceTypeIdentifier'] for item in available if item['name'].startswith('iPhone') and item.get('deviceTypeIdentifier')), 'com.apple.CoreSimulator.SimDeviceType.iPhone-16')
prepared = next((item for item in available if item['name'].startswith('iPhone')), None) if os.environ.get('GITHUB_ACTIONS') else None
created = prepared is None
device = run('xcrun', 'simctl', 'create', 'NEC Mobile CI', device_type, runtime['identifier']) if created else prepared['udid']
try:
    if not prepared or prepared['state'] != 'Booted': run('xcrun', 'simctl', 'boot', device)
    subprocess.run(['xcrun', 'simctl', 'bootstatus', device, '-b'], check=True, timeout=180)
    app = ROOT / 'work/ios-simulator/Release-iphonesimulator/NECMobile.app'
    run('xcrun', 'simctl', 'install', device, str(app))
    initial_data = Path(run('xcrun', 'simctl', 'get_app_container', device, 'org.necmobile.ios', 'data'))
    fixtures = initial_data / 'Documents/ImportUI'
    fixtures.mkdir(parents=True, exist_ok=True)
    header = bytearray(0x2b0); header[0x1c:0x20] = len(header).to_bytes(4, 'little')
    (fixtures / 'UI-Disks.d88').write_bytes(header * 2)
    (fixtures / 'UI-Bad.d88').write_bytes(b'incomplete disk')
    folder = fixtures / 'UIFolder'; folder.mkdir(exist_ok=True)
    (folder / 'disk.d88').write_bytes(header)
    (folder / 'EMPTY.TXT').write_bytes(b'')
    dos = fixtures / 'UIDOS'; dos.mkdir(exist_ok=True)
    (dos / 'START.BAT').write_bytes(b'@ECHO OFF\r\nECHO UI DOS\r\nPAUSE\r\n')
    with zipfile.ZipFile(fixtures / 'UI-Archive.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('disk.d88', header)
    result_bundle = ROOT / 'work/ios-import-ui.xcresult'
    with (ROOT / 'work/ios-import-ui.log').open('w', encoding='utf-8') as log:
        try:
            check = subprocess.run(['xcodebuild', 'test', '-project', str(ROOT / 'work/ios-simulator/NECMobileApple.xcodeproj'),
            '-scheme', 'NECMobile', '-configuration', 'Release', '-destination', 'id=' + device,
            '-resultBundlePath', str(result_bundle), '-parallel-testing-enabled', 'NO',
            '-only-testing:NECMobileImportUITests', 'CODE_SIGNING_ALLOWED=NO'],
                stdout=log, stderr=subprocess.STDOUT, timeout=480)
        except subprocess.TimeoutExpired:
            print((ROOT / 'work/ios-import-ui.log').read_text(encoding='utf-8')[-14000:], flush=True)
            raise SystemExit('UI check exceeded eight minutes; partial log retained.')
    if result_bundle.exists():
        subprocess.run(['xcrun', 'xcresulttool', 'export', 'attachments', '--path', str(result_bundle),
                        '--output-path', str(ROOT / 'work/ios-import-ui')], check=False, timeout=45)
    if check.returncode:
        log = run('xcrun', 'simctl', 'spawn', device, 'log', 'show', '--last', '5m', '--style', 'compact', '--predicate', 'process == "NECMobile"')
        (ROOT / 'work/ios-runtime.log').write_text(log, encoding='utf-8')
        print((ROOT / 'work/ios-import-ui.log').read_text(encoding='utf-8')[-14000:])
        raise SystemExit('Add-game UI check failed; see ios-import-ui.log.')
    print('Add game button, import menu and document picker passed')
    data = Path(run('xcrun', 'simctl', 'get_app_container', device, 'org.necmobile.ios', 'data'))
    (data / 'Documents').mkdir(exist_ok=True)
    fixture = data / 'Documents/ios-import.zip'
    header = bytearray(0x2b0)
    header[0x1c:0x20] = len(header).to_bytes(4, 'little')
    with zipfile.ZipFile(fixture, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('sample.d88', header * 2)
    fixture.write_bytes(fixture.read_bytes().replace(b'sample.d88', 'テスト.d88'.encode('cp932')))
    run('xcrun', 'simctl', 'launch', device, 'org.necmobile.ios', '--smoke-test')
    output = data / 'Documents/ios-smoke.json'
    captures = ROOT / 'work/ios-smoke'
    captures.mkdir(exist_ok=True)
    stage = data / 'Documents/ios-smoke-stage.txt'
    previous = ''
    deadline = time.monotonic() + 240
    while not output.is_file() and time.monotonic() < deadline:
        current = stage.read_text(encoding='utf-8') if stage.is_file() else ''
        if current and current != previous:
            if not current.replace('-', '').isalnum(): raise SystemExit('Invalid screenshot name')
            time.sleep(0.8)
            run('xcrun', 'simctl', 'io', device, 'screenshot', str(captures / (current + '.png')))
            (data / 'Documents/ios-smoke-ack.txt').write_text(current, encoding='utf-8')
            previous = current
        time.sleep(0.2)
    if not output.is_file():
        log = run('xcrun', 'simctl', 'spawn', device, 'log', 'show', '--last', '3m', '--style', 'compact', '--predicate', 'process == "NECMobile"')
        (ROOT / 'work/ios-smoke.log').write_text(log, encoding='utf-8')
        raise SystemExit('Simulator smoke timed out. See ios-smoke.log.')
    result = json.loads(output.read_text(encoding='utf-8'))
    (ROOT / 'work/ios-smoke.log').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    for frame in (data / 'Documents').glob('pc*-frame.png'):
        (captures / frame.name).write_bytes(frame.read_bytes())
    if result['status'] != 'passed':
        run('xcrun', 'simctl', 'io', device, 'screenshot', str(captures / 'failure.png'))
        log = run('xcrun', 'simctl', 'spawn', device, 'log', 'show', '--last', '2m', '--style', 'compact', '--predicate', 'process == "NECMobile"')
        (ROOT / 'work/ios-runtime.log').write_text(log, encoding='utf-8')
        raise SystemExit(1)
finally:
    for operation in ['shutdown', 'delete'] if created else ['shutdown']:
        try: subprocess.run(['xcrun', 'simctl', operation, device], check=False, timeout=30)
        except subprocess.TimeoutExpired: print('Simulator cleanup timed out: ' + operation, flush=True)
