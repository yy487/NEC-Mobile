#pragma once
#include "types.h"
#include <string>
class FileIO {
public:
    enum Flags { open = 1, readonly = 2, create = 4 };
    enum SeekMethod { begin, current, end };
    enum Error { success, file_not_found, sharing_violation, unknown = -1 };
    FileIO() = default;
    explicit FileIO(const char* name, uint flags = 0) { Open(name, flags); }
    virtual ~FileIO() { Close(); }
    bool Open(const char*, uint flags = 0);
    bool CreateNew(const char*);
    bool Reopen(uint flags = 0);
    void Close();
    Error GetError() { return error; }
    int32 Read(void*, int32);
    int32 Write(const void*, int32);
    bool Seek(int32, SeekMethod);
    int32 Tellp();
    bool SetEndOfFile();
    uint GetFlags() { return flags | (descriptor >= 0 ? open : 0); }
    void SetLogicalOrigin(int32 origin) { logical = origin; }
    static bool FlushAll();
private:
    int descriptor = -1;
    uint flags = 0;
    int32 logical = 0;
    Error error = success;
    std::string name;
    FileIO(const FileIO&) = delete;
    FileIO& operator=(const FileIO&) = delete;
};
