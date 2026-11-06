#pragma once
#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

inline bool readStateFile(const std::string& path, std::vector<uint8_t>& data, size_t limit=192*1024*1024) {
    std::ifstream file(path,std::ios::binary|std::ios::ate);
    auto size=file.tellg();
    if (size<=0 || uint64_t(size)>limit) return false;
    data.resize(size_t(size)); file.seekg(0);
    return bool(file.read(reinterpret_cast<char*>(data.data()),std::streamsize(data.size())));
}
inline bool writeStateFile(const std::string& path, const void* data, size_t size) {
    std::ofstream file(path,std::ios::binary|std::ios::trunc);
    file.write(static_cast<const char*>(data),std::streamsize(size)); file.close();
    return bool(file);
}
