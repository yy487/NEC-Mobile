#include "platform/headers.h"
#include "platform/file.h"
#include "platform/status.h"
#include "../core_api.h"
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <set>
#include <cctype>
#undef fopen

extern NecHost m88host;
static std::set<int> writableFiles;
static std::string lowerBasename(const char* path) {
    std::string result(path);
    size_t begin = result.find_last_of("/\\");
    begin = begin == std::string::npos ? 0 : begin + 1;
    for (size_t i = begin; i < result.size(); ++i)
        result[i] = static_cast<char>(std::tolower(static_cast<unsigned char>(result[i])));
    return result;
}
FILE* m88_fopen(const char* path, const char* mode) {
    FILE* result = ::fopen(path, mode);
    if (!result) result = ::fopen(lowerBasename(path).c_str(), mode);
    return result;
}
StatusDisplay statusdisplay;

bool StatusDisplay::Show(int, int, const char* format, ...) {
    char text[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    if (m88host.message) m88host.message(text);
    return true;
}

bool FileIO::Open(const char* path, uint flg) {
    Close();
    name = path;
    flags = flg;
    int mode = (flg & readonly) ? O_RDONLY : O_RDWR;
    if (flg & create) mode |= O_CREAT;
    descriptor = ::open(path, mode, 0600);
    if (descriptor < 0 && errno == ENOENT) descriptor = ::open(lowerBasename(path).c_str(), mode, 0600);
    logical = 0;
    error = descriptor < 0 ? (errno == ENOENT ? file_not_found : unknown) : success;
    if (descriptor >= 0 && !(flg & readonly)) writableFiles.insert(descriptor);
    return descriptor >= 0;
}
bool FileIO::CreateNew(const char* path) {
    Close();
    descriptor = ::open(path, O_RDWR | O_CREAT | O_EXCL, 0600);
    if (descriptor < 0) return false;
    name = path;
    flags = create;
    logical = 0;
    writableFiles.insert(descriptor);
    return true;
}
bool FileIO::Reopen(uint flg) { auto path = name; return Open(path.c_str(), flg); }
void FileIO::Close() {
    if (descriptor < 0) return;
    if (writableFiles.erase(descriptor)) fsync(descriptor);
    ::close(descriptor);
    descriptor = -1;
}
int32 FileIO::Read(void* output, int32 length) {
    if (length < 0 || descriptor < 0) return -1;
    auto* bytes = static_cast<uint8*>(output);
    int32 done = 0;
    while (done < length) {
        ssize_t count = ::read(descriptor, bytes + done, length - done);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return -1;
        if (!count) break;
        done += count;
    }
    return done;
}
int32 FileIO::Write(const void* input, int32 length) {
    if (length < 0 || descriptor < 0 || (flags & readonly)) return -1;
    auto* bytes = static_cast<const uint8*>(input);
    int32 done = 0;
    while (done < length) {
        ssize_t count = ::write(descriptor, bytes + done, length - done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        done += count;
    }
    return done;
}
bool FileIO::Seek(int32 position, SeekMethod method) {
    return lseek(descriptor, static_cast<int64_t>(position) + (method == begin ? logical : 0),
                 method == begin ? SEEK_SET : method == current ? SEEK_CUR : SEEK_END) >= 0;
}
int32 FileIO::Tellp() { return static_cast<int32>(lseek(descriptor, 0, SEEK_CUR) - logical); }
bool FileIO::SetEndOfFile() { return ftruncate(descriptor, lseek(descriptor, 0, SEEK_CUR)) == 0; }
bool FileIO::FlushAll() {
    bool result = true;
    for (int descriptor : writableFiles) if (fsync(descriptor)) result = false;
    return result;
}
