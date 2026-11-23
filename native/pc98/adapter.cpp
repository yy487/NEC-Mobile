#include "../core_api.h"
#include "../machine_config.h"
#include "../snapshot_meta.h"
#include "libretro.h"
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <array>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <string>
#include <vector>
#include <fstream>
#include "../platform_log.h"

#define RETRO_FUNCTIONS(X) \
    X(retro_set_environment) X(retro_set_video_refresh) X(retro_set_audio_sample) \
    X(retro_set_audio_sample_batch) X(retro_set_input_poll) X(retro_set_input_state) \
    X(retro_init) X(retro_deinit) X(retro_load_game) X(retro_unload_game) \
    X(retro_run) X(retro_reset) X(retro_get_system_av_info)
#define POINTER(name) static decltype(&name) p_##name;
RETRO_FUNCTIONS(POINTER)
#undef POINTER

static void* library;
static bool (*setFloppy)(unsigned, const char*);
static bool (*saveMachine)(const char*), (*checkMachine)(const char*), (*loadMachine)(const char*);
static void (*configureMidi)(bool);
static void (*configureSoundBios)(bool);
static const char* (*soundRomName)();
static const char* (*fontResource)();
static unsigned (*readMouseCounter)();
static void (*configureMedia)(const char*, const char*, bool);
static void (*completeMedia)();
static const char* (*floppyPath)(unsigned);
static NecHost host{};
static std::string systemDirectory, saveDirectory;
static std::map<std::string, std::string> options;
static std::set<std::string> media;
static std::array<bool, RETROK_LAST> keys{};
static retro_disk_control_callback disks{};
static retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
static std::vector<uint32_t> pixels;
static double refresh = 60, sampleRate = 44100;
static bool loaded, initialized, variablesChanged;
static int mouseX, mouseY, polledX, polledY;
static unsigned mouseButtons;
static bool midiEnabled;
static bool midiOutput() { return midiEnabled; }
static bool midiInput() { return false; }
static bool midiRead(uint8_t*) { return false; }
static bool midiWrite(uint8_t byte, uint32_t) { if (midiEnabled && host.midi) host.midi(byte); return true; }
static bool midiFlush() { return true; }

static void coreLog(retro_log_level level, const char* format, ...) {
    if (level < RETRO_LOG_WARN) return;
    char text[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    // Missing optional firmware is logged by the core while probing; it is not a fatal UI error.
    necLog(level >= RETRO_LOG_ERROR ? NecLogError : NecLogWarning, "NP2kai", text);
}
static void definitions(const retro_core_option_definition* list) {
    if (!list) return;
    for (; list->key; ++list)
        options.emplace(list->key, list->default_value ? list->default_value : list->values[0].value);
}
static void definitions(const retro_core_option_v2_definition* list) {
    if (!list) return;
    for (; list->key; ++list)
        options.emplace(list->key, list->default_value ? list->default_value : list->values[0].value);
}
static bool environment(unsigned command, void* data) {
    switch (command) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
        *static_cast<const char**>(data) = systemDirectory.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *static_cast<const char**>(data) = saveDirectory.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        static_cast<retro_log_callback*>(data)->log = coreLog; return true;
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH; return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *static_cast<bool*>(data) = true; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        pixelFormat = *static_cast<retro_pixel_format*>(data); return pixelFormat <= RETRO_PIXEL_FORMAT_RGB565;
    case RETRO_ENVIRONMENT_SET_MESSAGE:
        host.message(static_cast<retro_message*>(data)->msg); return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
        host.message(static_cast<retro_message_ext*>(data)->msg); return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *static_cast<unsigned*>(data) = 2; return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        definitions(static_cast<retro_core_options_v2*>(data)->definitions); return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
        definitions(static_cast<retro_core_options_v2_intl*>(data)->us->definitions); return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        definitions(static_cast<retro_core_option_definition*>(data)); return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
        definitions(static_cast<retro_core_options_intl*>(data)->us); return true;
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
        for (auto* item = static_cast<retro_variable*>(data); item->key; ++item) {
            std::string value = item->value ? item->value : "";
            auto start = value.find(';');
            if (start == std::string::npos) continue;
            value.erase(0, start + 1);
            while (!value.empty() && value.front() == ' ') value.erase(0, 1);
            options.emplace(item->key, value.substr(0, value.find('|')));
        }
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto* variable = static_cast<retro_variable*>(data);
        auto found = options.find(variable->key);
        variable->value = found == options.end() ? nullptr : found->second.c_str();
        return found != options.end();
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *static_cast<bool*>(data) = variablesChanged; variablesChanged = false; return true;
    case RETRO_ENVIRONMENT_GET_MIDI_INTERFACE:
        *static_cast<retro_midi_interface*>(data) = {midiInput, midiOutput, midiRead, midiWrite, midiFlush}; return true;
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
        disks = *static_cast<retro_disk_control_callback*>(data); return true;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
    case RETRO_ENVIRONMENT_SET_ROTATION:
        return true;
    default: return false;
    }
}
static void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || !width || !height || width > 2048 || height > 2048) return;
    pixels.resize(size_t(width) * height);
    for (unsigned y = 0; y < height; ++y) {
        auto* row = static_cast<const uint8_t*>(data) + y * pitch;
        for (unsigned x = 0; x < width; ++x) {
            uint32_t r, g, b;
            if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t value; memcpy(&value, row + x * 4, 4);
                r = (value >> 16) & 255; g = (value >> 8) & 255; b = value & 255;
            } else {
                uint16_t value; memcpy(&value, row + x * 2, 2);
                r = (value >> (pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ? 11 : 10)) & 31;
                g = (value >> 5) & (pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ? 63 : 31);
                b = value & 31;
                r = r * 255 / 31; b = b * 255 / 31;
                g = g * 255 / (pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ? 63 : 31);
            }
            pixels[y * width + x] = 0xff000000u | (b << 16) | (g << 8) | r;
        }
    }
    host.video(pixels.data(), width, height);
}
static size_t audio(const int16_t* pcm, size_t frames) {
    host.audio(pcm, frames, static_cast<unsigned>(sampleRate)); return frames;
}
static void audioSample(int16_t left, int16_t right) { int16_t pair[]{left, right}; audio(pair, 1); }
static void poll() { polledX = mouseX; polledY = mouseY; mouseX = mouseY = 0; }
static int16_t input(unsigned, unsigned device, unsigned, unsigned id) {
    if (device == RETRO_DEVICE_KEYBOARD) return id < keys.size() && keys[id];
    if (device == RETRO_DEVICE_MOUSE) {
        if (id == RETRO_DEVICE_ID_MOUSE_X) return polledX;
        if (id == RETRO_DEVICE_ID_MOUSE_Y) return polledY;
        if (id == RETRO_DEVICE_ID_MOUSE_LEFT) return (mouseButtons & 1) != 0;
        if (id == RETRO_DEVICE_ID_MOUSE_RIGHT) return (mouseButtons & 2) != 0;
    }
    return 0;
}
static bool flush() {
    bool result = fflush(nullptr) == 0;
    for (const auto& path : media) {
        int fd = ::open(path.c_str(), O_RDONLY);
        if (fd < 0) { result = false; continue; }
        if (fsync(fd)) result = false;
        ::close(fd);
    }
    return result;
}
static void closeCore() {
    if (library) {
        if (loaded) p_retro_unload_game();
        if (initialized) p_retro_deinit();
        flush();
        dlclose(library);
    }
    library = nullptr;
    setFloppy = nullptr;
    saveMachine = checkMachine = loadMachine = nullptr; configureMidi = nullptr; configureSoundBios = nullptr; soundRomName = nullptr;
    completeMedia = nullptr; floppyPath = nullptr;
    fontResource = nullptr;
    readMouseCounter = nullptr;
    configureMedia = nullptr;
    loaded = initialized = false;
    disks = {};
    media.clear();
    keys.fill(false);
}
static bool openCore(const char* directory, const char* disk, const char* secondDisk, int mode, NecHost callbacks) {
    closeCore();
    host = callbacks;
    systemDirectory = directory;
    saveDirectory = directory;
    options.clear();
    options["np2kai_drive"] = "FDD1";
    options["np2kai_inputmouse"] = "ON";
    options["np2kai_keyboard"] = "Ja";
    // BIOS-driven menus need keyboard repeat as well as a held matrix key.
    options["np2kai_keyrepeat"] = "ON";
    options["np2kai_keyrepeat_delay"] = "250ms";
    options["np2kai_keyrepeat_interval"] = "100ms";
    options["np2kai_uselasthddmount"] = "OFF";
    options["np2kai_clk_mult"] = mode == 1 ? "8" : mode == 2 ? "16" : "4";
    options["np2kai_SNDboard"] = "PC9801-86";
    options["np2kai_joymode"] = "OFF";
    options["np2kai_stick2mouse"] = "OFF";
    MachineConfig startup = readMachineConfig(directory);
    for (const auto& entry : startup)
        if (entry.first.compare(0,7,"np2kai_")==0) options[entry.first]=entry.second;
    variablesChanged = false;
    midiEnabled = machineText(startup,"midi_enabled","OFF")=="ON";
    mouseX = mouseY = polledX = polledY = 0;
    mouseButtons = 0;
    pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    if (chdir(directory)) return false;
#if defined(__APPLE__)
    Dl_info image{};
    if (!dladdr(reinterpret_cast<const void*>(&openCore), &image) || !image.dli_fname) return false;
    std::string binary(image.dli_fname);
    auto framework = binary.find_last_of('/');
    auto parent = binary.find_last_of('/', framework - 1);
    std::string corePath = binary.substr(0, parent) + "/np2kai.framework/np2kai";
    library = dlopen(corePath.c_str(), RTLD_NOW | RTLD_LOCAL);
#else
    library = dlopen("libnp2kai.so", RTLD_NOW | RTLD_LOCAL);
#endif
    if (!library) { host.message(dlerror()); return false; }
#define LOAD(name) p_##name = reinterpret_cast<decltype(p_##name)>(dlsym(library, #name)); if (!p_##name) { closeCore(); return false; }
    RETRO_FUNCTIONS(LOAD)
#undef LOAD
    setFloppy = reinterpret_cast<decltype(setFloppy)>(dlsym(library, "necmobile_set_floppy"));
    if (!setFloppy) { host.message("This PC-98 core lacks real floppy control"); closeCore(); return false; }
    saveMachine = reinterpret_cast<decltype(saveMachine)>(dlsym(library,"necmobile_save_state"));
    checkMachine = reinterpret_cast<decltype(checkMachine)>(dlsym(library,"necmobile_check_state"));
    loadMachine = reinterpret_cast<decltype(loadMachine)>(dlsym(library,"necmobile_load_state"));
    configureMidi = reinterpret_cast<decltype(configureMidi)>(dlsym(library,"necmobile_configure_midi"));
    configureSoundBios = reinterpret_cast<decltype(configureSoundBios)>(dlsym(library,"necmobile_configure_sound_bios"));
    soundRomName = reinterpret_cast<decltype(soundRomName)>(dlsym(library,"necmobile_sound_rom"));
    fontResource = reinterpret_cast<decltype(fontResource)>(dlsym(library,"necmobile_font_resource"));
    readMouseCounter = reinterpret_cast<decltype(readMouseCounter)>(dlsym(library,"necmobile_mouse_read_count"));
    configureMedia = reinterpret_cast<decltype(configureMedia)>(dlsym(library,"necmobile_configure_media"));
    completeMedia = reinterpret_cast<decltype(completeMedia)>(dlsym(library,"necmobile_complete_media"));
    floppyPath = reinterpret_cast<decltype(floppyPath)>(dlsym(library,"necmobile_floppy_path"));
    if (!saveMachine || !checkMachine || !loadMachine || !configureMidi || !configureSoundBios || !soundRomName || !fontResource || !completeMedia || !floppyPath || !readMouseCounter || !configureMedia) {
        host.message("PC-98 core bridge missing"); closeCore(); return false;
    }
    configureMidi(midiEnabled);
    bool soundBiosEnabled = machineText(startup,"pc98_sound_bios","ON")=="ON";
    configureSoundBios(soundBiosEnabled);
    std::string legacyPath = disk;
    size_t dot = legacyPath.find_last_of('.');
    std::string extension = dot == std::string::npos ? "" : legacyPath.substr(dot);
    for (char& letter : extension) if (letter >= 'A' && letter <= 'Z') letter += 'a' - 'A';
    bool legacyHard = extension == ".hdi" || extension == ".nhd" || extension == ".thd" || extension == ".hdd";
    std::string floppyA = machineText(startup, "startup_floppy_a", legacyHard ? "" : disk);
    std::string floppyB = machineText(startup, "startup_floppy_b", secondDisk);
    std::string hardDisk = machineText(startup, "startup_hard_disk", legacyHard ? disk : "");
    configureMedia(floppyA.c_str(), floppyB.c_str(), machineText(startup, "startup_boot_device", hardDisk.empty() ? "floppy" : "hdd") == "hdd");
    p_retro_set_environment(environment);
    p_retro_set_video_refresh(video);
    p_retro_set_audio_sample(audioSample);
    p_retro_set_audio_sample_batch(audio);
    p_retro_set_input_poll(poll);
    p_retro_set_input_state(input);
    p_retro_init();
    initialized = true;
    std::vector<std::string> startupPaths;
    if (!hardDisk.empty()) startupPaths.push_back(hardDisk);
    if (!floppyA.empty()) startupPaths.push_back(floppyA);
    if (!floppyB.empty()) startupPaths.push_back(floppyB);
    std::string bootPath = startupPaths.empty() ? "" : startupPaths.front();
    if (startupPaths.size() > 1) {
        bootPath = systemDirectory + "/dual-floppy.cmd";
        std::ofstream command(bootPath, std::ios::trunc);
        command << "np2kai";
        for (const std::string& path : startupPaths) command << " \"" << path << "\"";
        command << "\n";
        command.close();
        if (!command) { host.message("Cannot prepare the two-drive boot configuration"); closeCore(); return false; }
    }
    retro_game_info game{bootPath.c_str(), nullptr, 0, nullptr};
    loaded = p_retro_load_game(!bootPath.empty() ? &game : nullptr);
    if (!loaded) { closeCore(); return false; }
    for (const std::string& path : startupPaths) media.insert(path);
    // NP2kai creates its machine on the first run call, before AV geometry is valid.
    p_retro_run();
    completeMedia();
    host.message((std::string("PC-98 font loaded: ") + fontResource()).c_str());
    const char* rom = soundRomName();
    host.message((std::string("PC-98 sound BIOS loaded: ") + (rom && *rom ? rom : soundBiosEnabled ? "none" : "disabled")).c_str());
    if (soundBiosEnabled && (!rom || !*rom) && (options["np2kai_SNDboard"].find("86") != std::string::npos || options["np2kai_SNDboard"].find("26K") != std::string::npos) && host.warning)
        host.warning("Sound BIOS ROM missing");
    retro_system_av_info info{};
    p_retro_get_system_av_info(&info);
    refresh = info.timing.fps > 1 ? info.timing.fps : 60;
    sampleRate = info.timing.sample_rate > 1 ? info.timing.sample_rate : 44100;
    std::string description="PC-98 config: board="+options["np2kai_SNDboard"]+", BEEP="+options["np2kai_BEEP_vol"]+
        ", FM="+options["np2kai_volume_F"]+", RAM="+options["np2kai_ExMemory"]+", clock="+options["np2kai_clk_mult"];
    host.message(description.c_str());
    return true;
}
static void frame() { p_retro_run(); }
static void key(int code, bool pressed) { if (code >= 0 && unsigned(code) < keys.size()) keys[code] = pressed; }
static void mouse(int dx, int dy, unsigned buttons) {
    mouseX += dx; mouseY += dy; mouseButtons = buttons;
}
static bool mount(int drive, const char* path, int index, bool duringPlay) {
    if (drive < 0 || drive > 1 || index != 0 || !setFloppy) return false;
    if (!flush()) return false;
    const char* old = floppyPath(drive);
    std::string previous = old ? old : "";
    if (!setFloppy(drive, *path ? path : nullptr)) return false;
    if (duringPlay && *path && !previous.empty()) {
        // The guest must see the empty drive during NP2kai's 20-frame media change.
        for (unsigned frame = 0; frame < 40; ++frame) {
            const char* mounted = floppyPath(drive);
            if (mounted && *mounted) break;
            p_retro_run();
        }
    } else completeMedia();
    const char* actual = floppyPath(drive);
    if (std::string(actual ? actual : "") != path) {
        setFloppy(drive, previous.empty() ? nullptr : previous.c_str());
        completeMedia();
        return false;
    }
    if (*path) media.insert(path);
    return true;
}
static void reset() { keys.fill(false); p_retro_reset(); }
static double fps() { return refresh; }
static bool enableMouse(bool) { return true; }
static unsigned mouseReads() { return readMouseCounter ? readMouseCounter() : 0; }
static const char* diskPath(int drive) { return floppyPath && drive >= 0 && drive < 2 ? floppyPath(drive) : ""; }
static bool saveState(const char* path) { return saveMachine && saveMachine(path); }
static bool checkState(const char* path) { return checkMachine && checkMachine(path); }
static bool loadState(const char* path) {
#if defined(__APPLE__)
    // iOS can move the data container when an app is updated or restored.
    SnapshotMeta metadata;
    std::string statePath(path);
    auto relocate = reinterpret_cast<void (*)(const char*, const char*)>(dlsym(library, "necmobile_state_paths"));
    if (!relocate || !metadata.read(statePath.substr(0, statePath.find_last_of('/')) + "/host.bin")) return false;
    relocate(metadata.directory.c_str(), saveDirectory.c_str());
#endif
    if (!loadMachine || !loadMachine(path)) return false;
    keys.fill(false); mouseX=mouseY=polledX=polledY=0; mouseButtons=0;
    variablesChanged = true;
    return true;
}
extern "C" const NecCore* nec_core() {
    static const NecCore api{"NP2kai / PC-98", openCore, frame, key, mouse, mount, flush, reset, closeCore, fps, enableMouse, saveState, checkState, loadState, diskPath, mouseReads};
    return &api;
}
