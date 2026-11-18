"""Expose actual floppy insertion/ejection in the private NP2kai source copy."""
from pathlib import Path
import re

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

rhythm_source = root / 'sound/fmgen/fmgen_opna.cpp'
rhythm_data = rhythm_source.read_bytes()
if b'NecRhythm::load' not in rhythm_data:
    body = b'bool OPNA::LoadRhythmSample(const char* path)\n{\n    return NecRhythm::load<FileIO>(rhythm, rate, path);\n}'
    rhythm_data, count = re.subn(rb'bool OPNA::LoadRhythmSample\(const char\* path\)\s*\{.*?\r?\n\}', lambda match: body, rhythm_data, count=1, flags=re.S)
    if count != 1: raise ValueError('PC-98 rhythm loader not found')
    at = rhythm_data.index(b'#include')
    rhythm_source.write_bytes(rhythm_data[:at] + b'#include "../../../../native/rhythm_wav.h"\n' + rhythm_data[at:])

file_source = root / 'sound/fmgen/fmgen_file.cpp'
file_data = file_source.read_bytes()
if b'NEC Mobile: imported resource names' not in file_data:
    file_data = file_data.replace(b'#include "fmgen_file.h"', b'#include "fmgen_file.h"\n#include <string>', 1)
    file_data = file_data.replace(b'pfile = fopen(filename, mode);', b'''pfile = fopen(filename, mode);
    // NEC Mobile: imported resource names use lowercase basenames on Android.
    if (!pfile && (flg & readonly)) {
        std::string fallback(filename);
        size_t first = fallback.find_last_of("/\\\\");
        first = first == std::string::npos ? 0 : first + 1;
        for (size_t i = first; i < fallback.size(); ++i)
            if (fallback[i] >= 'A' && fallback[i] <= 'Z') fallback[i] += 'a' - 'A';
        if (fallback != filename) pfile = fopen(fallback.c_str(), mode);
    }''', 1)
    if b'NEC Mobile: imported resource names' not in file_data: raise ValueError('FMGEN open entry not found')
    file_data, count = re.subn(rb'\tDWORD readsize;\s*if \(!\(readsize = fread\(dest, 1, size, pfile\)\)\)\s*return -1;\s*return size;',
        b'\tif (size < 0) return -1;\n\treturn int32(fread(dest, 1, size, pfile));', file_data, count=1)
    if count != 1: raise ValueError('FMGEN read entry not found')
    file_source.write_bytes(file_data)
file_data = file_source.read_bytes()
fixed_seek = file_data.replace(b'return fseek(pfile, pos, origin);', b'return fseek(pfile, pos, origin) == 0;')
if fixed_seek != file_data: file_source.write_bytes(fixed_seek)

# PCM buffers belong to this process; save files retain the existing struct layout.
rhythm_data = rhythm_source.read_bytes()
old_load = b'memcpy(rhythm, data->rhythm, sizeof(Rhythm) * 6);'
if old_load in rhythm_data:
    rhythm_data = rhythm_data.replace(old_load, b'''for (unsigned i = 0; i < 6; ++i) {
        rhythm[i].pan = data->rhythm[i].pan;
        rhythm[i].level = data->rhythm[i].level;
        rhythm[i].volume = data->rhythm[i].volume;
        rhythm[i].pos = Min(data->rhythm[i].pos, rhythm[i].size);
    }''', 1)
    rhythm_source.write_bytes(rhythm_data)
opna_source = root / 'sound/opna.c'
opna_data = opna_source.read_bytes()
old_reload = b'getbiospath(path, "", NELEMENTS(path));\n\t\t\tOPNA_LoadRhythmSample(opna->fmgen, path);'
if old_reload in opna_data:
    opna_data = opna_data.replace(old_reload, b'/* The initialized rhythm buffers are retained by OPNA_DataLoad. */', 1)
    opna_data = opna_data.replace(b'OEMCHAR\tpath[MAX_PATH];\n\t\t\tvoid* buf;', b'void* buf;', 1)
    opna_source.write_bytes(opna_data)

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
unsigned int necmobile_mouse_reads = 0;
RETRO_API unsigned int necmobile_mouse_read_count(void) { return necmobile_mouse_reads; }
static char necmobile_initial_floppies[2][MAX_PATH];
static int necmobile_initial_media = 0;
int necmobile_boot_device = 0;
RETRO_API void necmobile_configure_media(const char* first, const char* second, bool hard_boot) {
   snprintf(necmobile_initial_floppies[0], MAX_PATH, "%s", first ? first : "");
   snprintf(necmobile_initial_floppies[1], MAX_PATH, "%s", second ? second : "");
   necmobile_boot_device = hard_boot ? 1 : 2;
   necmobile_initial_media = 1;
}
void necmobile_apply_initial_media(void) {
   unsigned drive;
   if (!necmobile_initial_media) return;
   for (drive = 0; drive < 2; drive++) {
      snprintf(np2cfg.fddfile[drive], MAX_PATH, "%s", necmobile_initial_floppies[drive]);
      diskdrv_setfdd(drive, necmobile_initial_floppies[drive][0] ? necmobile_initial_floppies[drive] : NULL, 0);
   }
   necmobile_complete_media();
}
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
   extern void necmobile_clear_mouse_observation(void);
   if (!necmobile_check_state(path)) return false;
   result = statsave_load_d(path);
   necmobile_clear_mouse_observation();
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
if b'necmobile_apply_initial_media' not in data:
    needle = b'//printf("bd:%s\\n",base_dir);'
    if needle not in data: raise ValueError('Expected initial disk mounting entry not found')
    data = data.replace(needle, b'''#if defined(__LIBRETRO__)
    { extern void necmobile_apply_initial_media(void); necmobile_apply_initial_media(); }
#endif
''' + needle, 1)
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

if data != source.read_bytes(): source.write_bytes(data)

boot_source = root / 'bios/bios1b.c'
boot_data = boot_source.read_bytes()
if b'necmobile_boot_device' not in boot_data:
    needle = b'bootseg = 0;'
    if needle not in boot_data: raise ValueError('Expected BIOS bootstrap entry not found')
    boot_data = boot_data.replace(needle, b'''#if defined(__LIBRETRO__)
    { extern int necmobile_boot_device;
      if (necmobile_boot_device == 1) { bootseg = boot_hd(0x80); if (bootseg) return bootseg; }
      else if (necmobile_boot_device == 2) { bootseg = boot_fd(0, 3); if (bootseg) return bootseg; }
    }
#endif
\t''' + needle, 1)
    boot_source.write_bytes(boot_data)
boot_data = boot_source.read_bytes()
previous_boot = b'if (necmobile_boot_device) { bootseg = boot_hd(0x80); if (bootseg) return bootseg; }'
if previous_boot in boot_data:
    boot_data = boot_data.replace(previous_boot, b'''if (necmobile_boot_device == 1) { bootseg = boot_hd(0x80); if (bootseg) return bootseg; }
      else if (necmobile_boot_device == 2) { bootseg = boot_fd(0, 3); if (bootseg) return bootseg; }''', 1)
    boot_source.write_bytes(boot_data)

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

mouse_source = root / 'io/mouseif.c'
mouse_data = mouse_source.read_bytes()
if b'necmobile_mouse_parts' not in mouse_data:
    mouse_newline = b'\r\n' if b'\r\n' in mouse_data else b'\n'
    mouse_data = mouse_data.replace(b'\r\n', b'\n')
    mouse_data = re.sub(rb'\r?\n#if defined\(__LIBRETRO__\)\s*extern unsigned int necmobile_mouse_reads;\s*\+\+necmobile_mouse_reads;\s*#endif', b'', mouse_data)
    mouse_data = mouse_data.replace(b'static int mouseif_test = 0;', b'''static int mouseif_test = 0;
#if defined(__LIBRETRO__)
static unsigned int necmobile_mouse_parts;
void necmobile_clear_mouse_observation(void) { necmobile_mouse_parts = 0; }
#endif''', 1)
    for needle in (b'void mouseif_sync(void) {', b'void mouseif_reset(const NP2CFG *pConfig) {'):
        mouse_data = mouse_data.replace(needle, needle + b'\n#if defined(__LIBRETRO__)\n    necmobile_clear_mouse_observation();\n#endif', 1)
    needle = b'portc = mouseif.upd8255.portc;\n\t\tif (portc & 0x80)'
    replacement = b'''portc = mouseif.upd8255.portc;
#if defined(__LIBRETRO__)
        /* Count all four coordinate nibbles from one mouse sample. */
        necmobile_mouse_parts |= 1u << ((portc >> 5) & 3);
        if (necmobile_mouse_parts == 15) {
            extern unsigned int necmobile_mouse_reads;
            ++necmobile_mouse_reads;
            necmobile_mouse_parts |= 16;
        }
#endif
\t\tif (portc & 0x80)'''
    if needle not in mouse_data: raise ValueError('PC-98 mouse coordinate entry not found')
    mouse_data = mouse_data.replace(needle, replacement, 1)
    mouse_source.write_bytes(mouse_data.replace(b'\n', mouse_newline))

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
