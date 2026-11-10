#pragma once
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <utility>

namespace NecRhythm {
struct Sample {
    std::unique_ptr<int16_t[]> pcm;
    uint32_t rate = 0, frames = 0;
};
inline uint16_t word(const uint8_t* p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
inline uint32_t number(const uint8_t* p) { return uint32_t(word(p)) | uint32_t(word(p + 2)) << 16; }

template<class File> bool read(File& file, Sample& sample) {
    uint8_t riff[12];
    if (file.Read(riff, 12) != 12 || std::memcmp(riff, "RIFF", 4) || std::memcmp(riff + 8, "WAVE", 4)) return false;
    uint32_t length = number(riff + 4), rate = 0;
    if (length < 4 || length > 16 * 1024 * 1024) return false;
    uint32_t remaining = length - 4;
    for (unsigned chunk = 0; chunk < 128 && remaining >= 8; ++chunk) {
        uint8_t header[8];
        if (file.Read(header, 8) != 8) return false;
        remaining -= 8;
        uint32_t bytes = number(header + 4);
        if (bytes > remaining || (bytes & 1) > remaining - bytes) return false;
        if (!std::memcmp(header, "fmt ", 4)) {
            uint8_t format[16];
            if (bytes < 16 || file.Read(format, 16) != 16 || word(format) != 1 || word(format + 2) != 1 ||
                    word(format + 12) != 2 || word(format + 14) != 16) return false;
            rate = number(format + 4);
            if (rate < 400 || rate > 384000 || !file.Seek(int32_t(bytes - 16 + (bytes & 1)), File::current)) return false;
        } else if (!std::memcmp(header, "data", 4)) {
            if (!rate || !bytes || (bytes & 1) || bytes / 2 >= 0x100000) return false;
            std::unique_ptr<int16_t[]> pcm(new (std::nothrow) int16_t[bytes / 2]);
            if (!pcm || file.Read(pcm.get(), int32_t(bytes)) != int32_t(bytes)) return false;
            sample.pcm = std::move(pcm); sample.rate = rate; sample.frames = bytes / 2;
            return true;
        } else if (!file.Seek(int32_t(bytes + (bytes & 1)), File::current)) return false;
        remaining -= bytes + (bytes & 1);
    }
    return false;
}

template<class File, class Bank> bool load(Bank& bank, uint32_t outputRate, const char* path) {
    if (!outputRate) return false;
    static const char* names[] = {"BD", "SD", "TOP", "HH", "TOM", "RIM"};
    std::string directory = path ? path : "";
    if (!directory.empty() && directory.back() != '/' && directory.back() != '\\') directory += '/';
    Sample loaded[6];
    for (unsigned i = 0; i < 6; ++i) {
        File file;
        std::string name = directory + "2608_" + names[i] + ".WAV";
        if (!file.Open(name.c_str(), File::readonly)) {
            if (i != 5 || !file.Open((directory + "2608_RYM.WAV").c_str(), File::readonly)) return false;
        }
        if (!read(file, loaded[i])) return false;
    }
    for (unsigned i = 0; i < 6; ++i) {
        delete[] bank[i].sample;
        bank[i].sample = loaded[i].pcm.release();
        bank[i].rate = loaded[i].rate;
        bank[i].step = loaded[i].rate * 1024 / outputRate;
        bank[i].pos = bank[i].size = loaded[i].frames * 1024;
    }
    return true;
}
}
