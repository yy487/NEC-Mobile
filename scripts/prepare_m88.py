"""Apply the portable compiler fixes to the private M88 source copy."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1] / 'third_party' / 'm88' / 'src'
table = re.compile(rb'((?:const\s+)?[\w:]+::(?:InFuncPtr|OutFuncPtr|CommandFunc)\s+([\w:]+)::\w+\s*\[\d*\]\s*=\s*\{)(.*?)(\};)', re.S)

for directory in ('common', 'devices', 'pc88'):
    for path in (ROOT / directory).glob('*.cpp'):
        data = path.read_bytes()
        def qualify(match):
            body = re.sub(rb'&([A-Za-z_]\w*)(?=\s*[,\)])',
                          lambda name: b'&' + match[2] + b'::' + name[1], match[3])
            return match[1] + body + match[4]
        changed = table.sub(qualify, data)
        if path.name == 'sound.cpp':
            changed = changed.replace(b'delete[] n;', b'delete n;')
            if b'Sound::ResetAfterState' not in changed:
                changed += b'''\n// NEC Mobile: discard old PCM and rebase CPU-relative audio timing after a state load.
bool PC8801::Sound::ResetAfterState()
{
    bool ok = SetRate(samplingrate, buffersize);
    prevtime = pc->GetCPUTick();
    tdiff = 0;
    soundbuf.FillWhenEmpty(false);
    return ok;
}
'''
        if path.name == 'psg.cpp':
            changed = changed.replace(b'{  0,  1, -1,  0 }', b'{  0,  1, 255,  0 }')
        if path.name == 'screen.cpp':
            changed = changed.replace(b'BETable0[1 << sizeof(packed)] = { -1 }', b'BETable0[1 << sizeof(packed)] = { 0xffffffffu }')
        if path.name == 'srcbuf.cpp':
            changed = changed.replace(b'if (read > buffersize)', b'if (read >= buffersize)')
        if path.name == 'opnif.cpp':
            changed = changed.replace(b'opn.Init(clock, 8000, 0)', b'opn.Init(clock, currentrate = 8000, 0)')
        if path.name == 'mouse.cpp' and b'port40 = 0; phase = 0; triggertime = 0;' not in changed:
            newline = b'\r\n' if b'\r\n' in changed else b'\n'
            changed = changed.replace(b'pc = pc88;', b'pc = pc88;' + newline +
                b'\tmove.x = move.y = 0; port40 = 0; phase = 0; triggertime = 0; data = -1;')
        if path.name == 'pc88.cpp':
            changed = changed.replace(b'{ 0x50, IOBus::portin,  CRTC::getstatus }', b'{ 0x51, IOBus::portin,  CRTC::getstatus }')
            changed = changed.replace(b'{ 0x51, IOBus::portin,  CRTC::in }', b'{ 0x50, IOBus::portin,  CRTC::in }')
        if path.name == 'pc88.cpp' and b'AndroidInitialConfig' not in changed:
            changed = re.sub(rb'\tReset\(\);(\r?\n)\tregion.Reset\(\);',
                             lambda match: b'\textern void AndroidInitialConfig(PC88*);' + match[1] +
                             b'\tAndroidInitialConfig(this);' + match[1] + b'\tReset();' + match[1] + b'\tregion.Reset();',
                             changed, count=1)
        if path.name == 'sio.cpp' and b'AndroidMidiByte' not in changed:
            changed = changed.replace(b'void IOCALL SIO::SetData(uint, uint d)\n{',
                b'void IOCALL SIO::SetData(uint, uint d)\n{\n\tif (GetID() == DEV_ID(\'S\', \'I\', \'O\', \'M\')) { extern void AndroidMidiByte(uint8); AndroidMidiByte(uint8(d)); }')
            # Vendor files normally retain CRLF.
            if b'AndroidMidiByte' not in changed:
                changed = changed.replace(b'void IOCALL SIO::SetData(uint, uint d)\r\n{',
                    b'void IOCALL SIO::SetData(uint, uint d)\r\n{\r\n\tif (GetID() == DEV_ID(\'S\', \'I\', \'O\', \'M\')) { extern void AndroidMidiByte(uint8); AndroidMidiByte(uint8(d)); }')
        if path.name == 'sio.cpp':
            changed = changed.replace(b'if (GetID() == DEV_ID(\'S\', \'I\', \'O\', \'M\'))', b'if (txen && GetID() == DEV_ID(\'S\', \'I\', \'O\', \'M\'))')
            changed = changed.replace(b'mode = clear;\n\tstatus = TXRDY', b'mode = clear;\n\trxen = txen = false;\n\tstatus = TXRDY')
            changed = changed.replace(b'mode = clear;\r\n\tstatus = TXRDY', b'mode = clear;\r\n\trxen = txen = false;\r\n\tstatus = TXRDY')
        if path.name == 'device.cpp':
            changed = changed.replace(b'n->entry->SaveStatus(data);', b'if (!n->entry->SaveStatus(data)) return false;')
        if changed != data:
            path.write_bytes(changed)
            print('adapted ' + directory + '/' + path.name)

header = ROOT / 'pc88/sound.h'
data = header.read_bytes()
if b'ResetAfterState' not in data:
    data = data.replace(b'void FillWhenEmpty(bool f)', b'void BeginStateLoad() { enabled = false; }\n\tbool ResetAfterState();\n\tvoid FillWhenEmpty(bool f)')
    header.write_bytes(data)
