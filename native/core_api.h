#pragma once
#include <cstddef>
#include <cstdint>

struct NecHost {
    void (*video)(const uint32_t* rgba, unsigned width, unsigned height);
    void (*audio)(const int16_t* stereo, size_t frames, unsigned rate);
    void (*message)(const char* text);
    void (*midi)(uint8_t byte);
};

// Calls belong to one emulation thread. Keys use libretro/SDL 1 key numbers.
struct NecCore {
    const char* name;
    bool (*open)(const char* directory, const char* disk, const char* secondDisk, int mode, NecHost host);
    void (*frame)();
    void (*key)(int code, bool pressed);
    void (*mouse)(int dx, int dy, unsigned buttons);
    bool (*mount)(int drive, const char* path, int index);
    bool (*flush)();
    void (*reset)();
    void (*close)();
    double (*fps)();
    bool (*enableMouse)(bool enabled);
    bool (*saveState)(const char* path);
    bool (*checkState)(const char* path);
    bool (*loadState)(const char* path);
};

extern "C" __attribute__((visibility("default"))) const NecCore* nec_core();
