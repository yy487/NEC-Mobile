#include "dos_disk.h"
#if defined(__ANDROID__)
#include <jni.h>
#endif
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <algorithm>
#include <array>
#include <fstream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <functional>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
extern "C" {
#include "ff.h"
#include "diskio.h"
}

namespace {
uint16_t word(const uint8_t* p) { return p[0] | uint16_t(p[1]) << 8; }
uint32_t dword(const uint8_t* p) { return word(p) | uint32_t(word(p + 2)) << 16; }
void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
void fatResult(FRESULT result, const char* operation) {
    if (result != FR_OK) throw std::runtime_error(std::string(operation) + " (FAT " + std::to_string(result) + ")");
}
#if defined(__ANDROID__)
std::string jtext(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* text = env->GetStringUTFChars(value, nullptr);
    std::string result = text ? text : "";
    if (text) env->ReleaseStringUTFChars(value, text);
    return result;
}
std::string jbytes(JNIEnv* env, jbyteArray value) {
    if (!value) return {};
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, jsize(result.size()), reinterpret_cast<jbyte*>(result.data()));
    return result;
}
#endif
struct HdiDisk {
    FILE* file = nullptr;
    uint64_t volume = 0, bytes = 0;
    unsigned sectorSize = 512;
    std::array<uint8_t,2> signature{};
    std::vector<uint64_t> sectorOffsets;
    std::string format = "hdi";
    bool floppy = false;
    bool transfer(uint64_t sector, unsigned count, uint8_t* data, bool write) {
        if (sectorOffsets.empty()) {
            if (fseeko(file, off_t(volume + sector * sectorSize), SEEK_SET)) return false;
            return (write ? fwrite(data, sectorSize, count, file) : fread(data, sectorSize, count, file)) == count;
        }
        for (unsigned i = 0; i < count; ++i) {
            uint64_t offset = sectorOffsets.at(size_t(sector + i));
            if (fseeko(file, off_t(offset), SEEK_SET)) return false;
            size_t size = write ? fwrite(data + size_t(i) * sectorSize, 1, sectorSize, file) : fread(data + size_t(i) * sectorSize, 1, sectorSize, file);
            if (size != sectorSize) return false;
        }
        return true;
    }
    bool openD88(uint64_t length) {
        if (length < 0x2b0) return false;
        std::array<uint8_t,0x2b0> header{};
        fseeko(file, 0, SEEK_SET);
        if (fread(header.data(), 1, header.size(), file) != header.size()) return false;
        if (dword(header.data() + 0x1c) != length || dword(header.data() + 0x20) < header.size() || header[0x1b] > 0x20) return false;
        struct Sector { unsigned cylinder, head, number; uint64_t offset; };
        std::vector<Sector> sectors;
        unsigned cylinders = 0, heads = 0, perTrack = 0, physical = 0;
        for (unsigned track = 0; track < 164; ++track) {
            uint64_t at = dword(header.data() + 0x20 + track * 4);
            if (!at) continue;
            require(at >= header.size() && at + 16 <= length, "D88 track is truncated");
            std::array<uint8_t,16> record{};
            fseeko(file, off_t(at), SEEK_SET);
            require(fread(record.data(), 1, record.size(), file) == record.size(), "D88 sector header is truncated");
            unsigned count = word(record.data() + 4);
            require(count > 0 && count <= 36, "D88 track layout is unsupported");
            if (!perTrack) perTrack = count;
            require(perTrack == count, "D88 has irregular tracks");
            for (unsigned index = 0; index < count; ++index) {
                require(at + 16 <= length, "D88 sector header is truncated");
                fseeko(file, off_t(at), SEEK_SET);
                require(fread(record.data(), 1, record.size(), file) == record.size(), "D88 sector header is truncated");
                unsigned size = word(record.data() + 14);
                require(record[0] * 2u + record[1] == track && record[1] < 2 && record[2] > 0 && record[2] <= perTrack,
                        "D88 sector IDs are irregular");
                require(record[3] >= 2 && record[3] <= 5 && size == (128u << record[3]) && !record[7] && !record[8],
                        "D88 protected or irregular sectors require manual startup");
                require(word(record.data() + 4) == count && at + 16 + size <= length, "D88 sector data is truncated");
                if (!physical) physical = size;
                require(physical == size, "D88 has mixed sector sizes");
                sectors.push_back({record[0], record[1], record[2], at + 16});
                cylinders = std::max(cylinders, unsigned(record[0]) + 1);
                heads = std::max(heads, unsigned(record[1]) + 1);
                at += 16 + size;
            }
        }
        require(cylinders && heads && perTrack && physical, "D88 has no readable sectors");
        sectorOffsets.assign(size_t(cylinders) * heads * perTrack, 0);
        for (const Sector& sector : sectors) {
            size_t index = (size_t(sector.cylinder) * heads + sector.head) * perTrack + sector.number - 1;
            require(!sectorOffsets[index], "D88 has duplicate sector IDs");
            sectorOffsets[index] = sector.offset;
        }
        require(std::find(sectorOffsets.begin(), sectorOffsets.end(), 0) == sectorOffsets.end(), "D88 has missing sectors");
        sectorSize = physical; bytes = sectorOffsets.size() * uint64_t(physical); floppy = true; format = "d88";
        return true;
    }
    explicit HdiDisk(const std::string& path, bool write, bool allowFloppy = false) {
        file = fopen(path.c_str(), write ? "r+b" : "rb");
        require(file != nullptr, "Cannot open DOS disk image");
        try {
            fseeko(file, 0, SEEK_END);
            uint64_t length = uint64_t(ftello(file));
            if (allowFloppy && openD88(length)) {
                std::vector<uint8_t> boot(sectorSize);
                require(transfer(0, 1, boot.data(), false), "Cannot read floppy boot sector");
                signature = {boot[510], boot[511]};
                return;
            }
            std::array<uint8_t,32> header{};
            fseeko(file, 0, SEEK_SET);
            require(fread(header.data(), 1, header.size(), file) == header.size(), "HDI header is truncated");
            uint64_t headerSize = dword(header.data() + 8), payload = dword(header.data() + 12);
            unsigned physical = dword(header.data() + 16), sectors = dword(header.data() + 20), heads = dword(header.data() + 24), cylinders = dword(header.data() + 28);
            require(headerSize >= 32 && (physical == 512 || physical == 1024 || physical == 2048) && sectors && heads && cylinders, "Unsupported HDI geometry");
            require(uint64_t(physical) * sectors * heads * cylinders == payload, "HDI geometry and capacity differ");
            fseeko(file, 0, SEEK_END);
            require(uint64_t(ftello(file)) >= headerSize + payload, "HDI data is truncated");
            if (allowFloppy && heads <= 2 && cylinders <= 164 && sectors <= 36 && payload <= 4 * 1024 * 1024) {
                volume = headerSize; bytes = payload; sectorSize = physical; floppy = true; format = "fdi";
                std::array<uint8_t,512> boot{};
                fseeko(file, off_t(volume), SEEK_SET);
                require(fread(boot.data(), 1, boot.size(), file) == boot.size(), "Floppy boot sector is truncated");
                signature = {boot[510], boot[511]};
                return;
            }
            std::array<uint8_t,512> table{};
            fseeko(file, off_t(headerSize + physical), SEEK_SET);
            require(fread(table.data(), 1, table.size(), file) == table.size(), "PC-98 partition table is truncated");
            for (unsigned pass = 0; pass < 2 && !volume; pass++) for (unsigned index = 0; index < 16; index++) {
                const uint8_t* entry = table.data() + index * 32;
                unsigned mid = entry[0] & 0x7f, sid = entry[1] & 0x7f;
                if (!((mid == 0x21 && (sid == 1 || sid == 0x21)) || (mid == 0x20 && sid == 0x11))) continue;
                if (pass == 0 && !((entry[0] | entry[1]) & 0x80)) continue;
                unsigned startSector = entry[8], startHead = entry[9], startCylinder = word(entry + 10);
                if (startSector >= sectors || startHead >= heads || startCylinder >= cylinders) continue;
                uint64_t start = ((uint64_t(startCylinder) * heads + startHead) * sectors + startSector) * physical;
                if (start < physical + 512 || start + 512 > payload) continue;
                std::array<uint8_t,512> boot{};
                fseeko(file, off_t(headerSize + start), SEEK_SET);
                if (fread(boot.data(), 1, boot.size(), file) != boot.size()) continue;
                unsigned logical = word(boot.data() + 11);
                uint64_t count = word(boot.data() + 19);
                if (!count) count = dword(boot.data() + 32);
                if ((logical != 512 && logical != 1024 && logical != 2048 && logical != 4096) || !count || !boot[13] || !word(boot.data() + 22)) continue;
                uint64_t length = count * logical;
                if (length > payload - start) continue;
                bool overlap = false;
                for (unsigned other = 0; other < 16; other++) {
                    const uint8_t* next = table.data() + other * 32;
                    if (other == index || !next[0]) continue;
                    uint64_t nextStart = ((uint64_t(word(next + 10)) * heads + next[9]) * sectors + next[8]) * physical;
                    if (nextStart > start && nextStart < start + length) overlap = true;
                }
                if (overlap) continue;
                volume = headerSize + start; bytes = length; sectorSize = logical;
                signature = {boot[510], boot[511]};
                break;
            }
            require(volume != 0, "No supported PC-98 DOS FAT12/FAT16 partition");
        } catch (...) { fclose(file); file = nullptr; throw; }
    }
    ~HdiDisk() { if (file) fclose(file); }
};
HdiDisk* currentDisk = nullptr;
std::mutex fatMutex;
struct Volume {
    HdiDisk disk;
    FATFS filesystem{};
    Volume(const std::string& path, bool write, bool allowFloppy = false) : disk(path, write, allowFloppy) {
        currentDisk = &disk;
        FRESULT result = f_mount(&filesystem, "0:", 1);
        if (result != FR_OK || (filesystem.fs_type != FS_FAT12 && filesystem.fs_type != FS_FAT16)) {
            f_mount(nullptr, "0:", 0); currentDisk = nullptr;
            throw std::runtime_error("Template must contain a readable FAT12 or FAT16 DOS volume");
        }
    }
    ~Volume() { f_mount(nullptr, "0:", 0); currentDisk = nullptr; }
};
std::string readFile(const char* name) {
    FIL file{};
    if (f_open(&file, name, FA_READ) != FR_OK) return {};
    if (f_size(&file) > 65536) { f_close(&file); throw std::runtime_error("DOS startup script is too large"); }
    std::string data(size_t(f_size(&file)), '\0'); UINT count = 0;
    FRESULT result = f_read(&file, data.data(), UINT(data.size()), &count);
    f_close(&file); fatResult(result, "Read startup script");
    data.resize(count); return data;
}
void writeFile(const char* name, const std::string& bytes, bool replace) {
    FIL file{};
    fatResult(f_open(&file, name, FA_WRITE | (replace ? FA_CREATE_ALWAYS : FA_CREATE_NEW)), "Create DOS file");
    UINT written = 0;
    FRESULT result = f_write(&file, bytes.data(), UINT(bytes.size()), &written);
    FRESULT close = f_close(&file);
    fatResult(result, "Write DOS file"); fatResult(close, "Close DOS file");
    require(written == bytes.size(), "DOS disk is full");
}
#if defined(__ANDROID__)
void throwJava(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
}
#endif
void requireDosSystem() {
    FILINFO kernel{};
    bool freedos = f_stat("0:/KERNEL.SYS", &kernel) == FR_OK && !(kernel.fattrib & AM_DIR) && kernel.fsize > 0;
    for (const char* path : freedos ? std::vector<const char*>{"0:/KERNEL.SYS", "0:/COMMAND.COM"} :
                                    std::vector<const char*>{"0:/IO.SYS", "0:/MSDOS.SYS", "0:/COMMAND.COM"}) {
        FILINFO info{};
        require(f_stat(path, &info) == FR_OK && !(info.fattrib & AM_DIR) && info.fsize > 0,
                "DOS template is missing its system files or COMMAND.COM");
    }
}
bool exists(const char* name) {
    FILINFO info{};
    return f_stat(name, &info) == FR_OK && !(info.fattrib & AM_DIR) && info.fsize > 0;
}
std::string jsonText(const std::string& value) {
    std::string result = "\"";
    for (unsigned char byte : value) {
        if (byte == '"' || byte == '\\') { result += '\\'; result += char(byte); }
        else if (byte < 32 || byte >= 127) {
            char escaped[7]; snprintf(escaped, sizeof(escaped), "\\u%04x", unsigned(byte)); result += escaped;
        } else result += char(byte);
    }
    return result + "\"";
}
void copyDisk(const std::string& sourcePath, const std::string& target, bool& created) {
    int output = ::open(target.c_str(), O_CREAT | O_EXCL | O_WRONLY, 0600);
    require(output >= 0, "Output disk already exists or cannot be created");
    created = true;
    FILE* copy = fdopen(output, "wb");
    if (!copy) { ::close(output); throw std::runtime_error("Cannot open copied DOS image"); }
    std::ifstream source(sourcePath, std::ios::binary);
    if (!source) { fclose(copy); throw std::runtime_error("Cannot read DOS template"); }
    std::array<char,65536> chunk{};
    bool copied = true;
    while (source) {
        source.read(chunk.data(), chunk.size()); auto count = source.gcount();
        if (count && fwrite(chunk.data(), 1, size_t(count), copy) != size_t(count)) { copied = false; break; }
    }
    if (source.bad()) copied = false;
    if (fclose(copy)) copied = false;
    require(copied, "Copying DOS template failed");
}
}

extern "C" DSTATUS disk_initialize(BYTE drive) { return drive == 0 && currentDisk ? 0 : STA_NOINIT; }
extern "C" DSTATUS disk_status(BYTE drive) { return disk_initialize(drive); }
extern "C" DRESULT disk_read(BYTE drive, BYTE* buffer, LBA_t sector, UINT count) {
    if (drive || !currentDisk || !count || uint64_t(sector) + count > currentDisk->bytes / currentDisk->sectorSize) return RES_PARERR;
    auto& disk = *currentDisk;
    if (!disk.transfer(sector, count, buffer, false)) return RES_ERROR;
    // PC-98 DOS has its own boot code. This signature is only exposed to FatFs.
    if (sector == 0) { buffer[510] = 0x55; buffer[511] = 0xaa; }
    return RES_OK;
}
extern "C" DRESULT disk_write(BYTE drive, const BYTE* buffer, LBA_t sector, UINT count) {
    if (drive || !currentDisk || !count || uint64_t(sector) + count > currentDisk->bytes / currentDisk->sectorSize) return RES_PARERR;
    auto& disk = *currentDisk;
    if (sector == 0) {
        std::vector<uint8_t> data(buffer, buffer + size_t(count) * disk.sectorSize);
        data[510] = disk.signature[0]; data[511] = disk.signature[1];
        return disk.transfer(sector, count, data.data(), true) ? RES_OK : RES_ERROR;
    }
    return disk.transfer(sector, count, const_cast<uint8_t*>(buffer), true) ? RES_OK : RES_ERROR;
}
extern "C" DRESULT disk_ioctl(BYTE drive, BYTE command, void* buffer) {
    if (drive || !currentDisk) return RES_NOTRDY;
    switch (command) {
    case CTRL_SYNC: return fflush(currentDisk->file) == 0 && fsync(fileno(currentDisk->file)) == 0 ? RES_OK : RES_ERROR;
    case GET_SECTOR_COUNT: *static_cast<LBA_t*>(buffer) = LBA_t(currentDisk->bytes / currentDisk->sectorSize); return RES_OK;
    case GET_SECTOR_SIZE: *static_cast<WORD*>(buffer) = WORD(currentDisk->sectorSize); return RES_OK;
    case GET_BLOCK_SIZE: *static_cast<DWORD*>(buffer) = 1; return RES_OK;
    default: return RES_PARERR;
    }
}

std::string NecDos::info(const std::string& path) {
    std::lock_guard<std::mutex> lock(fatMutex);
        Volume volume(path, false); requireDosSystem(); DWORD free = 0; FATFS* filesystem = nullptr;
        fatResult(f_getfree("0:", &free, &filesystem), "Read free space");
        uint64_t available = uint64_t(free) * filesystem->csize * volume.disk.sectorSize;
        std::string info = std::string(filesystem->fs_type == FS_FAT16 ? "FAT16" : "FAT12") + " · " +
            std::to_string(volume.disk.sectorSize) + " B/sector · " + std::to_string(available / 1048576) + " MB free";
        return info;
}
std::string NecDos::autoexec(const std::string& path) {
    std::lock_guard<std::mutex> lock(fatMutex);
    Volume volume(path, false); return readFile("0:/AUTOEXEC.BAT");
}
std::string NecDos::inspect(const std::string& path) {
    std::lock_guard<std::mutex> lock(fatMutex);
    try {
        Volume volume(path, false, true);
        std::vector<uint8_t> boot(volume.disk.sectorSize);
        require(volume.disk.transfer(0, 1, boot.data(), false), "Cannot read boot sector");
        std::string bootCode(reinterpret_cast<const char*>(boot.data()), boot.size());
        std::string entries;
        bool customTarget = false;
        DIR directory{};
        fatResult(f_opendir(&directory, "0:/"), "Read root directory");
        for (unsigned count = 0; count < 512; ++count) {
            FILINFO info{};
            FRESULT result = f_readdir(&directory, &info);
            if (result != FR_OK) { f_closedir(&directory); fatResult(result, "Read root entry"); }
            if (!info.fname[0]) break;
            std::string name = info.fname;
            if (!entries.empty()) entries += ',';
            entries += "{\"name\":" + jsonText(name) + ",\"directory\":" + ((info.fattrib & AM_DIR) ? "true" : "false") +
                    ",\"size\":" + std::to_string(uint64_t(info.fsize)) + "}";
            if (!(info.fattrib & AM_DIR) && name != "IO.SYS" && name != "MSDOS.SYS" && name != "COMMAND.COM") {
                size_t dot = name.find_last_of('.');
                std::string base = dot == std::string::npos ? name : name.substr(0, dot);
                std::string extension = dot == std::string::npos ? "" : name.substr(dot + 1);
                if (base.size() <= 8 && extension.size() <= 3) {
                    std::string token = base + std::string(8 - base.size(), ' ') + extension + std::string(3 - extension.size(), ' ');
                    customTarget |= bootCode.find(token) != std::string::npos;
                }
            }
        }
        f_closedir(&directory);
        std::string programs;
        unsigned visited = 0;
        std::function<void(const std::string&, unsigned)> scan = [&](const std::string& parent, unsigned depth) {
            require(depth <= 12, "DOS directory tree is too deep");
            DIR folder{};
            fatResult(f_opendir(&folder, ("0:/" + parent).c_str()), "Read game directory");
            try {
                while (true) {
                    FILINFO info{};
                    fatResult(f_readdir(&folder, &info), "Read game entry");
                    if (!info.fname[0]) break;
                    require(++visited <= 4096, "DOS directory contains too many files");
                    std::string path = parent + info.fname;
                    if (info.fattrib & AM_DIR) scan(path + "/", depth + 1);
                    else if (info.fsize && path.size() >= 4) {
                        std::string extension = path.substr(path.size() - 4);
                        if (extension == ".BAT" || extension == ".COM" || extension == ".EXE") {
                            if (!programs.empty()) programs += ',';
                            programs += jsonText(path);
                        }
                    }
                }
            } catch (...) { f_closedir(&folder); throw; }
            f_closedir(&folder);
        };
        scan("", 0);
        bool msdos = exists("0:/IO.SYS") && exists("0:/MSDOS.SYS");
        bool freedos = exists("0:/KERNEL.SYS");
        bool needsDos = bootCode.find("IO      SYS") != std::string::npos && bootCode.find("MSDOS   SYS") != std::string::npos;
        customTarget |= bootCode.find("M-DOS/98 IPL ver.") != std::string::npos && exists("0:/MDOS.98");
        std::string kind = ((msdos || freedos) && exists("0:/COMMAND.COM")) ? "ready" : customTarget ? "custom" : needsDos ? "missing-dos" : "unknown";
        std::string result = "{\"supported\":true,\"format\":" + jsonText(volume.disk.format) + ",\"floppy\":" +
                (volume.disk.floppy ? "true" : "false") + ",\"fat\":" + jsonText(volume.filesystem.fs_type == FS_FAT16 ? "FAT16" : "FAT12") +
                ",\"boot\":" + jsonText(kind) + ",\"system\":" + jsonText(freedos ? "freedos" : msdos ? "msdos" : "") +
                ",\"sectorSize\":" + std::to_string(volume.disk.sectorSize) + ",\"autoexec\":" + jsonText(readFile("0:/AUTOEXEC.BAT")) +
                ",\"config\":" + jsonText(readFile("0:/CONFIG.SYS")) + ",\"entries\":[" + entries + "],\"programs\":[" + programs + "]}";
        return result;
    } catch (const std::exception& error) {
        std::string result = "{\"supported\":false,\"reason\":" + jsonText(error.what()) + "}";
        return result;
    }
}
void NecDos::floppy(const std::string& input, const std::string& target, const std::optional<std::string>& autoexec, const std::optional<std::string>& config, const std::vector<File>& files, const std::optional<std::string>& label) {
    std::lock_guard<std::mutex> lock(fatMutex);
    bool created = false;
    try {
        copyDisk(input, target, created);
        Volume volume(target, true, true);
        if (autoexec) {
            requireDosSystem();
            f_chmod("0:/AUTOEXEC.BAT", 0, AM_RDO);
            writeFile("0:/AUTOEXEC.BAT", *autoexec, true);
        }
        if (config) {
            f_chmod("0:/CONFIG.SYS", 0, AM_RDO);
            writeFile("0:/CONFIG.SYS", *config, true);
        }
        if (files.size()) {
            FRESULT result = f_mkdir("0:/NECMOB");
            if (result != FR_EXIST) fatResult(result, "Create driver directory");
        }
        std::array<char,65536> chunk{};
        for (const auto& item : files) {
            const auto& host = item.host; const auto& name = item.name;
            require(!name.empty() && name.find_first_of("/\\:") == std::string::npos && name.find('\0') == std::string::npos && name != "." && name != "..", "Invalid driver name");
            std::ifstream source(host, std::ios::binary);
            require(bool(source), "Cannot read driver");
            FIL file{};
            fatResult(f_open(&file, ("0:/NECMOB/" + name).c_str(), FA_CREATE_ALWAYS | FA_WRITE), "Create driver");
            bool copied = true;
            while (source) {
                source.read(chunk.data(), chunk.size()); UINT count = UINT(source.gcount()), written = 0;
                FRESULT result = f_write(&file, chunk.data(), count, &written);
                if (result != FR_OK || written != count) { copied = false; break; }
            }
            if (source.bad()) copied = false;
            FRESULT closed = f_close(&file);
            require(copied && closed == FR_OK, "Cannot finish driver copy");
        }
        if (label) fatResult(f_setlabel(("0:" + *label).c_str()), "Set volume label");
        fatResult(f_mount(nullptr, "0:", 0), "Close floppy volume");
        require(fflush(volume.disk.file) == 0 && fsync(fileno(volume.disk.file)) == 0, "Floppy write failed");
    } catch (const std::exception& error) {
        if (created) unlink(target.c_str());
        throw;
    }
}
void NecDos::build(const std::string& input, const std::string& target, const std::vector<File>& files, const std::string& startup, const std::string& autoexec) {
    std::lock_guard<std::mutex> lock(fatMutex);
    bool created = false;
    try {
        copyDisk(input, target, created);
        std::array<char,65536> chunk{};
        Volume volume(target, true);
        requireDosSystem();
        fatResult(f_mkdir("0:/NECGAME"), "Template already has NECGAME or has insufficient space");
        fatResult(f_mkdir("0:/NECMOB"), "Template already has NECMOB or has insufficient space");
        for (const auto& item : files) {
            const auto& host = item.host; const auto& relative = item.name;
            require(!relative.empty() && relative.front() != '/' && relative.find(':') == std::string::npos && relative.find('\0') == std::string::npos, "Invalid DOS file path");
            require(relative != ".." && relative.find("../") == std::string::npos && relative.find("/..") == std::string::npos, "DOS file path escapes game directory");
            std::string guest = "0:/NECGAME/" + relative;
            struct stat status{}; require(stat(host.c_str(), &status) == 0, "Game file cannot be read");
            if (S_ISDIR(status.st_mode)) { fatResult(f_mkdir(guest.c_str()), "Create game directory"); continue; }
            FIL file{}; fatResult(f_open(&file, guest.c_str(), FA_CREATE_NEW | FA_WRITE), "Create game file");
            std::ifstream content(host, std::ios::binary); bool good = bool(content);
            while (good && content) {
                content.read(chunk.data(), chunk.size()); UINT count = UINT(content.gcount()), written = 0;
                FRESULT result = f_write(&file, chunk.data(), count, &written);
                if (result != FR_OK || written != count) good = false;
            }
            if (content.bad()) good = false;
            FRESULT close = f_close(&file);
            if (!good || close != FR_OK) throw std::runtime_error("Game file write failed or DOS disk is full: " + host);
        }
        writeFile("0:/NECMOB/START.BAT", startup, false);
        f_chmod("0:/AUTOEXEC.BAT", 0, AM_RDO);
        writeFile("0:/AUTOEXEC.BAT", autoexec, true);
        fatResult(f_mount(nullptr, "0:", 0), "Close DOS volume");
        require(fflush(volume.disk.file) == 0 && fsync(fileno(volume.disk.file)) == 0, "Commit DOS hard disk failed");
    } catch (const std::exception& error) {
        if (created) unlink(target.c_str());
        throw;
    }
}

#if defined(__ANDROID__)
namespace {
std::vector<NecDos::File> importFiles(JNIEnv* env, jobjectArray hosts, jobjectArray names) {
    require(env->GetArrayLength(hosts) == env->GetArrayLength(names), "File lists differ");
    std::vector<NecDos::File> files;
    for (jsize i = 0; i < env->GetArrayLength(hosts); i++) {
        auto host = static_cast<jstring>(env->GetObjectArrayElement(hosts, i));
        auto name = static_cast<jbyteArray>(env->GetObjectArrayElement(names, i));
        files.push_back({jtext(env, host), jbytes(env, name)});
        env->DeleteLocalRef(host); env->DeleteLocalRef(name);
    }
    return files;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_necmobile_Native_hdiInfo(JNIEnv* env, jclass, jstring path) {
    try { return env->NewStringUTF(NecDos::info(jtext(env,path)).c_str()); }
    catch (const std::exception& error) { throwJava(env,error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_necmobile_Native_dosAutoexec(JNIEnv* env, jclass, jstring path) {
    try { auto data=NecDos::autoexec(jtext(env,path)); auto result=env->NewByteArray(jsize(data.size()));
          env->SetByteArrayRegion(result,0,jsize(data.size()),reinterpret_cast<const jbyte*>(data.data())); return result; }
    catch (const std::exception& error) { throwJava(env,error); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_org_necmobile_Native_inspectDisk(JNIEnv* env, jclass, jstring path) {
    return env->NewStringUTF(NecDos::inspect(jtext(env,path)).c_str());
}
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_prepareFloppy(JNIEnv* env, jclass, jstring input, jstring target,
        jbyteArray autoexec, jbyteArray config, jobjectArray hosts, jobjectArray names, jbyteArray label) {
    try { NecDos::floppy(jtext(env,input),jtext(env,target), autoexec ? std::optional<std::string>(jbytes(env,autoexec)) : std::nullopt,
          config ? std::optional<std::string>(jbytes(env,config)) : std::nullopt, importFiles(env,hosts,names),
          label ? std::optional<std::string>(jbytes(env,label)) : std::nullopt); }
    catch (const std::exception& error) { throwJava(env,error); }
}
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_buildDosDisk(JNIEnv* env, jclass, jstring input, jstring target,
        jobjectArray hosts, jobjectArray names, jbyteArray startup, jbyteArray autoexec) {
    try { NecDos::build(jtext(env,input),jtext(env,target),importFiles(env,hosts,names),jbytes(env,startup),jbytes(env,autoexec)); }
    catch (const std::exception& error) { throwJava(env,error); }
}
#endif
