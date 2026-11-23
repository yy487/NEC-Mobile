"""Run one simulator smoke session against both bundled emulator cores."""
from pathlib import Path
import json
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
def run(*args): return subprocess.check_output(list(args), text=True).strip()
runtimes = json.loads(run('xcrun', 'simctl', 'list', 'runtimes', '-j'))['runtimes']
runtime = next(item for item in reversed(runtimes) if item.get('isAvailable') and '.iOS-' in item['identifier'])
available = json.loads(run('xcrun', 'simctl', 'list', 'devices', 'available', '-j'))['devices'].get(runtime['identifier'], [])
device_type = next((item['deviceTypeIdentifier'] for item in available if item['name'].startswith('iPhone') and item.get('deviceTypeIdentifier')), 'com.apple.CoreSimulator.SimDeviceType.iPhone-16')
device = run('xcrun', 'simctl', 'create', 'NEC Mobile CI', device_type, runtime['identifier'])
try:
    run('xcrun', 'simctl', 'boot', device)
    subprocess.run(['xcrun', 'simctl', 'bootstatus', device, '-b'], check=True)
    app = ROOT / 'work/ios-simulator/Release-iphonesimulator/NECMobile.app'
    run('xcrun', 'simctl', 'install', device, str(app))
    run('xcrun', 'simctl', 'launch', device, 'org.necmobile.ios', '--smoke-test')
    data = Path(run('xcrun', 'simctl', 'get_app_container', device, 'org.necmobile.ios', 'data'))
    output = data / 'Documents/ios-smoke.json'
    deadline = time.monotonic() + 180
    while not output.is_file() and time.monotonic() < deadline: time.sleep(1)
    if not output.is_file():
        log = run('xcrun', 'simctl', 'spawn', device, 'log', 'show', '--last', '3m', '--style', 'compact', '--predicate', 'process == "NECMobile"')
        (ROOT / 'work/ios-smoke.log').write_text(log, encoding='utf-8')
        raise SystemExit('Simulator smoke timed out. See ios-smoke.log.')
    result = json.loads(output.read_text(encoding='utf-8'))
    (ROOT / 'work/ios-smoke.log').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result['status'] != 'passed': raise SystemExit(1)
finally:
    subprocess.run(['xcrun', 'simctl', 'shutdown', device], check=False)
    subprocess.run(['xcrun', 'simctl', 'delete', device], check=False)
