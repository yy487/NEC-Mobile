#pragma once
#include "memory_region.h"
#include <array>
#include <functional>
#include <string>
#include <vector>

class MemoryEditor {
public:
    using Regions = std::function<std::vector<NecMemoryRegion>()>;
    explicit MemoryEditor(Regions regions) : regions_(std::move(regions)) { locks_.reserve(128); undo_.reserve(32); }
    std::string command(int action, const std::vector<int64_t>& args);
    void tick();
    void invalidate();
private:
    struct Ref { uint32_t region; size_t size, offset; int type; };
    struct Scan {
        uint32_t region;
        std::vector<uint8_t> previous;
        std::array<std::vector<uint64_t>, 11> bits;
    };
    struct Lock { Ref ref; int64_t value; };
    struct Undo { Ref ref; std::array<uint8_t,4> before{}, after{}; };
    Regions regions_;
    std::vector<Scan> scans_;
    std::vector<Lock> locks_;
    std::vector<Undo> undo_;
    uint64_t epoch_ = 1, count_ = 0;
    bool searched_ = false;
    int mask_ = 0, region_ = 0;
    static int width(int type);
    static bool decode(const uint8_t*, int type, int64_t& value);
    static std::array<uint8_t,4> encode(int type, int64_t value);
    static bool same(const Ref&, const Ref&);
    static bool overlaps(const Ref&, const Ref&);
    static uint8_t* locate(const std::vector<NecMemoryRegion>&, const Ref&);
    Ref reference(const std::vector<int64_t>&, size_t start);
    bool locked(const Ref&) const;
    void write(const std::vector<NecMemoryRegion>&, const Ref&, int64_t);
    void search(const std::vector<NecMemoryRegion>&, const std::vector<int64_t>&);
    std::string view(const std::vector<NecMemoryRegion>&, size_t page);
};
