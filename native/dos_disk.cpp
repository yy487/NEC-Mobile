#include <jni.h>
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
struct HdiDisk {
    FILE* file = nullptr;
    uint64_t volume = 0, bytes = 0;
    unsigned sectorSize = 512;
    std::array<uint8_t,2> signature{};
    explicit HdiDisk(const std::string& path, bool write) {
        file = fopen(path.c_str(), write ? "r+b" : "rb");
        require(file != nullptr, "Cannot open HDI");
        try {
            std::array<uint8_t,32> header{};
            require(fread(header.data(), 1, header.size(), file) == header.size(), "HDI header is truncated");
            uint64_t headerSize = dword(header.data() + 8), payload = dword(header.data() + 12);
            unsigned physical = dword(header.data() + 16), sectors = dword(header.data() + 20), heads = dword(header.data() + 24), cylinders = dword(header.data() + 28);
            require(headerSize >= 32 && (physical == 512 || physical == 1024 || physical == 2048) && sectors && heads && cylinders, "Unsupported HDI geometry");
            require(uint64_t(physical) * sectors * heads * cylinders == payload, "HDI geometry and capacity differ");
            fseeko(file, 0, SEEK_END);
            require(uint64_t(ftello(file)) >= headerSize + payload, "HDI data is truncated");
            std::array<uint8_t,512> table{};
            fseeko(file, off_t(headerSize + physical), SEEK_SET);
            require(fread(table.data(), 1, table.size(), file) == table.size(), "PC-98 partition table is truncated");
            for (unsigned pass = 0; pass < 2 && !volume; pass++) for (unsigned index = 0; index < 16; index++) {
                const uint8_t* entry = table.data() + index * 32;
                unsigned mid = entry[0] & 0x7f, sid = entry[1] & 0x7f;
                if (!((mid == 0x21 && sid == 1) || (mid == 0x20 && sid == 0x11))) continue;
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
    Volume(const std::string& path, bool write) : disk(path, write) {
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
void throwJava(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
}
void requireDosSystem() {
    for (const char* path : {"0:/IO.SYS", "0:/MSDOS.SYS", "0:/COMMAND.COM"}) {
        FILINFO info{};
        require(f_stat(path, &info) == FR_OK && !(info.fattrib & AM_DIR) && info.fsize > 0,
                "DOS template is missing IO.SYS, MSDOS.SYS or COMMAND.COM");
    }
}
}

extern "C" DSTATUS disk_initialize(BYTE drive) { return drive == 0 && currentDisk ? 0 : STA_NOINIT; }
extern "C" DSTATUS disk_status(BYTE drive) { return disk_initialize(drive); }
extern "C" DRESULT disk_read(BYTE drive, BYTE* buffer, LBA_t sector, UINT count) {
    if (drive || !currentDisk || !count || uint64_t(sector) + count > currentDisk->bytes / currentDisk->sectorSize) return RES_PARERR;
    auto& disk = *currentDisk;
    if (fseeko(disk.file, off_t(disk.volume + uint64_t(sector) * disk.sectorSize), SEEK_SET)) return RES_ERROR;
    if (fread(buffer, disk.sectorSize, count, disk.file) != count) return RES_ERROR;
    // PC-98 DOS has its own boot code. This signature is only exposed to FatFs.
    if (sector == 0) { buffer[510] = 0x55; buffer[511] = 0xaa; }
    return RES_OK;
}
extern "C" DRESULT disk_write(BYTE drive, const BYTE* buffer, LBA_t sector, UINT count) {
    if (drive || !currentDisk || !count || uint64_t(sector) + count > currentDisk->bytes / currentDisk->sectorSize) return RES_PARERR;
    auto& disk = *currentDisk;
    if (fseeko(disk.file, off_t(disk.volume + uint64_t(sector) * disk.sectorSize), SEEK_SET)) return RES_ERROR;
    if (sector == 0) {
        std::vector<uint8_t> data(buffer, buffer + size_t(count) * disk.sectorSize);
        data[510] = disk.signature[0]; data[511] = disk.signature[1];
        return fwrite(data.data(), disk.sectorSize, count, disk.file) == count ? RES_OK : RES_ERROR;
    }
    return fwrite(buffer, disk.sectorSize, count, disk.file) == count ? RES_OK : RES_ERROR;
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

extern "C" JNIEXPORT jstring JNICALL Java_org_necmobile_Native_hdiInfo(JNIEnv* env, jclass, jstring path) {
    std::lock_guard<std::mutex> lock(fatMutex);
    try {
        Volume volume(jtext(env, path), false); requireDosSystem(); DWORD free = 0; FATFS* filesystem = nullptr;
        fatResult(f_getfree("0:", &free, &filesystem), "Read free space");
        uint64_t available = uint64_t(free) * filesystem->csize * volume.disk.sectorSize;
        std::string info = std::string(filesystem->fs_type == FS_FAT16 ? "FAT16" : "FAT12") + " · " +
            std::to_string(volume.disk.sectorSize) + " B/sector · " + std::to_string(available / 1048576) + " MB free";
        return env->NewStringUTF(info.c_str());
    } catch (const std::exception& error) { throwJava(env, error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_necmobile_Native_dosAutoexec(JNIEnv* env, jclass, jstring path) {
    std::lock_guard<std::mutex> lock(fatMutex);
    try {
        Volume volume(jtext(env, path), false); std::string data = readFile("0:/AUTOEXEC.BAT");
        jbyteArray result = env->NewByteArray(jsize(data.size()));
        env->SetByteArrayRegion(result, 0, jsize(data.size()), reinterpret_cast<const jbyte*>(data.data()));
        return result;
    } catch (const std::exception& error) { throwJava(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_buildDosDisk(JNIEnv* env, jclass, jstring input, jstring destination, jobjectArray hosts, jobjectArray names, jbyteArray startup, jbyteArray autoexec) {
    std::lock_guard<std::mutex> lock(fatMutex);
    std::string target = jtext(env, destination);
    bool created = false;
    try {
        require(env->GetArrayLength(hosts) == env->GetArrayLength(names), "DOS import file list differs");
        int output = ::open(target.c_str(), O_CREAT | O_EXCL | O_WRONLY, 0600);
        require(output >= 0, "Output HDI already exists or cannot be created"); created = true;
        FILE* copy = fdopen(output, "wb");
        if (!copy) { ::close(output); throw std::runtime_error("Cannot open copied DOS image"); }
        std::ifstream source(jtext(env, input), std::ios::binary);
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
        Volume volume(target, true);
        requireDosSystem();
        fatResult(f_mkdir("0:/NECGAME"), "Template already has NECGAME or has insufficient space");
        fatResult(f_mkdir("0:/NECMOB"), "Template already has NECMOB or has insufficient space");
        for (jsize index = 0; index < env->GetArrayLength(hosts); index++) {
            auto hostObject = static_cast<jstring>(env->GetObjectArrayElement(hosts, index));
            auto nameObject = static_cast<jbyteArray>(env->GetObjectArrayElement(names, index));
            std::string host = jtext(env, hostObject), relative = jbytes(env, nameObject);
            env->DeleteLocalRef(hostObject); env->DeleteLocalRef(nameObject);
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
        writeFile("0:/NECMOB/START.BAT", jbytes(env, startup), false);
        f_chmod("0:/AUTOEXEC.BAT", 0, AM_RDO);
        writeFile("0:/AUTOEXEC.BAT", jbytes(env, autoexec), true);
        fatResult(f_mount(nullptr, "0:", 0), "Close DOS volume");
        require(fflush(volume.disk.file) == 0 && fsync(fileno(volume.disk.file)) == 0, "Commit DOS hard disk failed");
    } catch (const std::exception& error) {
        if (created) unlink(target.c_str());
        throwJava(env, error);
    }
}
