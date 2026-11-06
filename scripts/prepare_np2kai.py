"""Expose actual floppy insertion/ejection in the private NP2kai source copy."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'third_party/np2kai-source'
source = root / 'sdl/libretro/libretro.c'
data = source.read_bytes()
if b'necmobile_set_floppy' not in data:
    data += b'''
/* NEC Mobile: operate the real FDD, including the empty-drive state. */
RETRO_API bool necmobile_set_floppy(unsigned drive, const char* path)
{
   if (drive >= 2 || !(fdc.equip & (1u << drive))) return false;
   if (path && *path && file_attr(path) == -1) return false;
   diskdrv_setfdd(drive, path && *path ? path : NULL, 0);
   return true;
}
'''
    source.write_bytes(data)
linker = root / 'sdl/link.T'
data = linker.read_bytes()
if b'necmobile_set_floppy' not in data:
    if b'retro_*;' not in data:
        raise ValueError('Expected libretro export list not found')
    linker.write_bytes(data.replace(b'retro_*;', b'retro_*; necmobile_set_floppy;'))
print('NP2kai real floppy-control export prepared')

data = source.read_bytes()
marker = b'/* NEC Mobile state and MIDI bridge. */'
previous = data
if marker in data:
    data = data[:data.index(marker)].rstrip() + b'\n'
data += b'''
/* NEC Mobile state and MIDI bridge. */
int necmobile_midi_enabled = 0;
RETRO_API void necmobile_configure_midi(bool enabled) { necmobile_midi_enabled = enabled; }
RETRO_API bool necmobile_save_state(const char* path)
{
   return path && statsave_save_d(path) == STATFLAG_SUCCESS;
}
RETRO_API bool necmobile_check_state(const char* path)
{
   int result;
   if (!path) return false;
   result = statsave_check(path, NULL, 0);
   return result >= 0 && !(result & (STATFLAG_VERSION | STATFLAG_VERCHG));
}
RETRO_API bool necmobile_load_state(const char* path)
{
   int result;
   if (!necmobile_check_state(path)) return false;
   result = statsave_load_d(path);
   return result >= 0 && !(result & (STATFLAG_VERSION | STATFLAG_VERCHG));
}
'''
if data != previous:
    source.write_bytes(data)
data = linker.read_bytes()
if b'necmobile_*;' not in data:
    linker.write_bytes(data.replace(b'necmobile_set_floppy;', b'necmobile_*;'))

source = root / 'sdl/np2.c'
data = source.read_bytes()
if b'necmobile_midi_enabled' not in data:
    data = data.replace(b'initload();', b'''initload();
#if defined(__LIBRETRO__)
    { extern int necmobile_midi_enabled;
      np2cfg.mpuenable = necmobile_midi_enabled ? 1 : 0;
      np2cfg.mpuopt = 0x82; /* PC-98 standard E0D0/E0D2, IRQ 6 */
      np2oscfg.mpu.direct = necmobile_midi_enabled ? 1 : 0;
      np2oscfg.MIDIWAIT = 0;
    }
#endif''', 1)
    source.write_bytes(data)

# Disabled or disconnected MIDI must consume output rather than busy-loop forever.
source = root / 'sdl/cmmidi.c'
data = source.read_bytes()
if b'NEC Mobile: disconnected MIDI' not in data:
    data = data.replace(b'UINT i;\n\n\tfor (;;)', b'''UINT i;
#if defined(__LIBRETRO__)
    /* NEC Mobile: disconnected MIDI */
    if (!retro_midi_interface || !retro_midi_interface->output_enabled()) return;
    for (i = 0; i < cnt; i++) retro_midi_interface->write(cmd[i], 0);
    return; /* Synthesized in the host audio frame, without wall-clock waits. */
#endif

\tfor (;;)''', 1)
    if b'NEC Mobile: disconnected MIDI' not in data:
        raise ValueError('Expected MIDI output loop not found')
    source.write_bytes(data)
print('NP2kai state result and MIDI bridge prepared')

# libretro's "device 0" is a virtual endpoint, not the process's file descriptor 0.
data = source.read_bytes()
for call in (b'close(midi->hmidiout);', b'close(hmidiout);'):
    wrapped = b'#if !defined(__LIBRETRO__)\n\t\t' + call + b'\n#endif'
    if wrapped not in data:
        data = data.replace(call, wrapped)
if data != source.read_bytes():
    source.write_bytes(data)
