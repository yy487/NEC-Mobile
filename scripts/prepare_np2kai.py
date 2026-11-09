"""Expose actual floppy insertion/ejection in the private NP2kai source copy."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'third_party/np2kai-source'
sound_source = root / 'sound/soundrom.c'
sound_data = sound_source.read_bytes()
sound_previous = sound_data
sound_data = sound_data.replace(
    b'0x01,0x00,0x00,0x00,0xd2,0x00,0x08,0x00,0xcb',
    b'0x01,0x00,0x00,0x00,0xd2,0x00,0x08,0x00,0xcf')
if b'static BRESULT readrom(' not in sound_data:
    start = sound_data.index(b'static BRESULT loadsoundrom(')
    end = sound_data.index(b'// ----', start)
    sound_data = sound_data[:start] + b'''static BRESULT readrom(UINT address, const OEMCHAR *name) {
    OEMCHAR path[MAX_PATH];
    UINT8 image[0x4000];
    FILEH fh;
    UINT size = 0;

    getbiospath(path, name, NELEMENTS(path));
    fh = file_open_rb(path);
    if (fh == FILEH_INVALID) return FAILURE;
    if (file_getsize(fh) == sizeof(image)) {
        size = file_read(fh, image, sizeof(image));
    }
    file_close(fh);
    if (size != sizeof(image)) return FAILURE;
    CopyMemory(mem + address, image, sizeof(image));
    return SUCCESS;
}

static BRESULT loadsoundrom(UINT address, const OEMCHAR *name) {
    OEMCHAR romname[24];
    file_cpyname(romname, file_sound, NELEMENTS(romname));
    if (name) file_catname(romname, name, NELEMENTS(romname));
    file_catname(romname, file_extrom, NELEMENTS(romname));
    if (readrom(address, romname) != SUCCESS) return FAILURE;
    file_cpyname(soundrom.name, romname, NELEMENTS(soundrom.name));
    soundrom.address = address;
    if (address == 0xd0000) CPU_RAM_D000 &= ~(0x0f << 0);
    else if (address == 0xd4000) CPU_RAM_D000 &= ~(0x0f << 4);
    return SUCCESS;
}

static BRESULT loadsoundromsne(const OEMCHAR *name) {
    OEMCHAR romname[24];
    file_cpyname(romname, file_sound, NELEMENTS(romname));
    if (name) {
        file_catname(romname, name, NELEMENTS(romname));
        file_catname(romname, OEMTEXT("ex"), NELEMENTS(romname));
    }
    file_catname(romname, file_extrom, NELEMENTS(romname));
    return readrom(0xC4000, romname);
}

''' + sound_data[end:]
if b'extern int necmobile_sound_bios_enabled;' not in sound_data:
    sound_data = sound_data.replace(b'#include <dosio.h>', b'#include <dosio.h>\n#if defined(__LIBRETRO__)\nextern int necmobile_sound_bios_enabled;\n#endif')
    for declaration in (b'void soundrom_load(UINT32 address, const OEMCHAR *primary) {',
                        b'void soundrom_loadsne(const OEMCHAR *primary) {'):
        if declaration not in sound_data:
            raise ValueError('Sound BIOS configuration entry not found')
        sound_data = sound_data.replace(declaration, declaration + b'\n#if defined(__LIBRETRO__)\n    if (!necmobile_sound_bios_enabled) { soundrom_reset(); return; }\n#endif', 1)
if sound_data != sound_previous:
    sound_source.write_bytes(sound_data)

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
#include <sound/soundrom.h>
#include <diskimage/fddfile.h>
RETRO_API void necmobile_complete_media(void)
{
   unsigned tick;
   for (tick = 0; tick < 100; tick++) diskdrv_callback();
}
RETRO_API const char* necmobile_floppy_path(unsigned drive)
{
   UINT type;
   int readonly;
   if (drive >= 2) return NULL;
   return fdd_getfileex(drive, &type, &readonly);
}
RETRO_API const char* necmobile_sound_rom(void)
{
   return soundrom.address ? soundrom.name : NULL;
}
int necmobile_midi_enabled = 0;
int necmobile_sound_bios_enabled = 1;
int necmobile_font_kind = 0;
RETRO_API const char* necmobile_font_resource(void) {
   return necmobile_font_kind == 1 ? "FONT.BMP" : necmobile_font_kind == 2 ? "FONT.ROM" : "internal fallback";
}
RETRO_API void necmobile_configure_sound_bios(bool enabled) { necmobile_sound_bios_enabled = enabled; }
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

for name, kind in (('font/fontpc98.c', 1), ('font/fontv98.c', 2)):
    font_source = root / name
    font_data = font_source.read_bytes()
    if b'necmobile_font_kind' not in font_data:
        needle = b'if (loading & FONT_KNJ1) {'
        if needle not in font_data:
            raise ValueError('Expected font loading entry not found: ' + name)
        font_data = font_data.replace(needle, needle + b'\n#if defined(__LIBRETRO__)\n        extern int necmobile_font_kind;\n        necmobile_font_kind = ' + str(kind).encode('ascii') + b';\n#endif', 1)
        font_source.write_bytes(font_data)

# libretro's "device 0" is a virtual endpoint, not the process's file descriptor 0.
data = source.read_bytes()
for call in (b'close(midi->hmidiout);', b'close(hmidiout);'):
    wrapped = b'#if !defined(__LIBRETRO__)\n\t\t' + call + b'\n#endif'
    if wrapped not in data:
        data = data.replace(call, wrapped)
if data != source.read_bytes():
    source.write_bytes(data)
