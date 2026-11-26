#include "memory_editor.h"
#include <algorithm>
#include <cstring>
#include <sstream>
#include <stdexcept>

namespace {
std::string quoted(const char* value) {
    std::string out = "\"";
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(value); *p; ++p) {
        if (*p == '"' || *p == '\\') out += '\\';
        if (*p >= 32) out += char(*p);
    }
    return out + '"';
}
bool matches(int relation, int64_t value, int64_t previous, int64_t target, int64_t upper) {
    switch (relation) {
    case 0: return value == target;
    case 1: return true;
    case 2: return value > previous;
    case 3: return value < previous;
    case 4: return value != previous;
    case 5: return value == previous;
    case 6: return value > target;
    case 7: return value < target;
    case 8: return value >= target && value <= upper;
    case 9: return value - previous == target;
    case 10: return previous - value == target;
    default: throw std::runtime_error("搜索条件无效");
    }
}
}

int MemoryEditor::width(int type) {
    static constexpr int widths[] = {1,2,4,1,2,4,1,2,4,2,4};
    if (type < 0 || type >= 11) throw std::runtime_error("数值格式无效");
    return widths[type];
}
bool MemoryEditor::decode(const uint8_t* data, int type, int64_t& value) {
    int bytes = width(type);
    uint32_t raw = 0;
    for (int i = 0; i < bytes; ++i) raw |= uint32_t(data[i]) << (i * 8);
    if (type < 3) value = raw;
    else if (type < 6) {
        if (bytes == 1) value = int8_t(raw);
        else if (bytes == 2) value = int16_t(raw);
        else value = int32_t(raw);
    } else {
        value = 0;
        for (int i = bytes - 1; i >= 0; --i) {
            uint8_t b = data[type >= 9 ? bytes - 1 - i : i];
            if ((b & 15) > 9 || (b >> 4) > 9) return false;
            value = value * 100 + (b >> 4) * 10 + (b & 15);
        }
    }
    return true;
}
std::array<uint8_t,4> MemoryEditor::encode(int type, int64_t value) {
    int bytes = width(type);
    int64_t low = 0, high = (int64_t(1) << (bytes * 8)) - 1;
    if (type >= 3 && type < 6) { high >>= 1; low = -high - 1; }
    if (type >= 6) { high = 1; for (int i = 0; i < bytes; ++i) high *= 100; --high; }
    if (value < low || value > high) throw std::runtime_error("数值超出所选格式的范围");
    std::array<uint8_t,4> out{};
    for (int i = 0; i < bytes; ++i) {
        if (type < 6) out[i] = uint8_t(uint64_t(value) >> (i * 8));
        else {
            uint8_t b = uint8_t(((value / 10) % 10) * 16 + value % 10);
            out[type >= 9 ? bytes - 1 - i : i] = b;
            value /= 100;
        }
    }
    return out;
}
bool MemoryEditor::same(const Ref& a, const Ref& b) {
    return a.region == b.region && a.size == b.size && a.offset == b.offset && a.type == b.type;
}
bool MemoryEditor::overlaps(const Ref& a, const Ref& b) {
    return a.region == b.region && a.offset < b.offset + width(b.type) && b.offset < a.offset + width(a.type);
}
uint8_t* MemoryEditor::locate(const std::vector<NecMemoryRegion>& regions, const Ref& ref) {
    for (const auto& r : regions) if (r.id == ref.region && r.size == ref.size && r.data &&
        ref.offset <= r.size && size_t(width(ref.type)) <= r.size - ref.offset) return r.data + ref.offset;
    throw std::runtime_error("内存区域或地址已改变，请重新搜索");
}
MemoryEditor::Ref MemoryEditor::reference(const std::vector<int64_t>& args, size_t at) {
    if (args.size() < at + 4 || args[at] < 0 || args[at] > UINT32_MAX || args[at+1] <= 0 || args[at+2] < 0)
        throw std::runtime_error("内存地址无效");
    if (args[at+3] < 0 || args[at+3] >= 11) throw std::runtime_error("数值格式无效");
    return {uint32_t(args[at]), size_t(args[at+1]), size_t(args[at+2]), int(args[at+3])};
}
bool MemoryEditor::locked(const Ref& ref) const {
    return std::any_of(locks_.begin(), locks_.end(), [&](const Lock& lock) { return same(lock.ref, ref); });
}
void MemoryEditor::write(const std::vector<NecMemoryRegion>& regions, const Ref& ref, int64_t value) {
    auto bytes = encode(ref.type, value);
    uint8_t* data = locate(regions, ref);
    Undo record; record.ref = ref; record.after = bytes;
    std::copy_n(data, width(ref.type), record.before.begin());
    if (undo_.size() == 32) undo_.erase(undo_.begin());
    undo_.push_back(record);
    std::copy_n(bytes.data(), width(ref.type), data);
}
void MemoryEditor::invalidate() {
    scans_.clear(); locks_.clear(); undo_.clear();
    searched_ = false; count_ = 0; ++epoch_;
}
void MemoryEditor::tick() {
    if (locks_.empty()) return;
    std::vector<NecMemoryRegion> regions;
    try { regions = regions_(); } catch (...) { locks_.clear(); return; }
    for (auto it = locks_.begin(); it != locks_.end();) {
        try {
            auto bytes = encode(it->ref.type, it->value);
            std::copy_n(bytes.data(), width(it->ref.type), locate(regions, it->ref));
            ++it;
        } catch (const std::exception&) { it = locks_.erase(it); }
    }
}
void MemoryEditor::search(const std::vector<NecMemoryRegion>& regions, const std::vector<int64_t>& args) {
    if (args.size() != 6) throw std::runtime_error("搜索参数无效");
    int relation = int(args[1]), mask = int(args[2]), selected = int(args[3]);
    int64_t target = args[4], upper = args[5];
    if (relation < 0 || relation > 10 || mask <= 0 || (mask & ~2047)) throw std::runtime_error("搜索条件无效");
    if (relation == 8 && upper < target) throw std::runtime_error("范围上限不能小于下限");
    if ((relation == 9 || relation == 10) && target < 0) throw std::runtime_error("变化量请填写非负数");
    bool first = !searched_;
    if (first && ((relation >= 2 && relation <= 5) || relation >= 9)) throw std::runtime_error("请先搜索具体数值或选择未知初始值");
    if (!first && (mask != mask_ || selected != region_ || relation == 1)) throw std::runtime_error("更换格式、区域或初始值模式时，请先重新搜索");
    if (!first && !count_) throw std::runtime_error("没有候选地址，请重新搜索");
    std::vector<Scan> fresh;
    uint64_t totalBytes = 0;
    if (first) {
        for (const auto& r : regions) if (selected == -1 || r.id == uint32_t(selected)) {
            if (!r.data || !r.size || totalBytes + r.size > 128 * 1024 * 1024) throw std::runtime_error("所选内存过大，请选择单个内存区域");
            totalBytes += r.size;
            Scan scan; scan.region = r.id; scan.previous.assign(r.data, r.data + r.size);
            for (int t = 0; t < 11; ++t) if (mask & (1 << t)) scan.bits[t].resize((r.size + 63) / 64);
            fresh.push_back(std::move(scan));
        }
        if (fresh.empty()) throw std::runtime_error("没有可搜索的游戏内存");
    }
    auto& scans = first ? fresh : scans_;
    std::vector<std::vector<uint8_t>> snapshots;
    if (!first) for (const auto& scan : scans) {
        Ref ref{scan.region, scan.previous.size(), 0, 0};
        auto* data = locate(regions, ref);
        snapshots.emplace_back(data, data + ref.size);
    }
    uint64_t found = 0;
    size_t scanIndex = 0;
    for (auto& scan : scans) {
        Ref ref{scan.region, scan.previous.size(), 0, 0};
        std::vector<uint8_t> current;
        if (!first) current = std::move(snapshots[scanIndex++]);
        const uint8_t* now = first ? scan.previous.data() : current.data();
        for (int t = 0; t < 11; ++t) if (mask & (1 << t)) {
            auto& bits = scan.bits[t];
            if (first) {
                for (size_t offset = 0; offset + width(t) <= ref.size; ++offset) {
                    int64_t value;
                    if (decode(now + offset, t, value) && matches(relation, value, 0, target, upper)) {
                        bits[offset / 64] |= uint64_t(1) << (offset % 64); ++found;
                    }
                }
            } else for (size_t word = 0; word < bits.size(); ++word) {
                uint64_t pending = bits[word], kept = 0;
                while (pending) {
                    unsigned bit = unsigned(__builtin_ctzll(pending)); pending &= pending - 1;
                    size_t offset = word * 64 + bit;
                    int64_t value, previous;
                    if (decode(now + offset, t, value) && decode(scan.previous.data() + offset, t, previous) &&
                        matches(relation, value, previous, target, upper)) { kept |= uint64_t(1) << bit; ++found; }
                }
                bits[word] = kept;
            }
        }
        if (!first) scan.previous = std::move(current);
    }
    if (first) scans_ = std::move(fresh);
    count_ = found; searched_ = true; mask_ = mask; region_ = selected;
    if (!found) scans_.clear();
}
std::string MemoryEditor::view(const std::vector<NecMemoryRegion>& regions, size_t page) {
    std::ostringstream out;
    page = count_ ? std::min<uint64_t>(page, (count_ - 1) / 50) : 0;
    out << "{\"epoch\":" << epoch_ << ",\"searched\":" << (searched_ ? "true" : "false") << ",\"count\":" << count_
        << ",\"page\":" << page << ",\"mask\":" << mask_ << ",\"region\":" << region_
        << ",\"undo\":" << (!undo_.empty() ? "true" : "false") << ",\"locks\":" << locks_.size() << ",\"regions\":[";
    bool comma = false;
    for (const auto& r : regions) {
        if (comma) out << ',';
        comma = true;
        out << "{\"id\":" << r.id << ",\"size\":" << r.size << ",\"base\":" << r.address << ",\"name\":" << quoted(r.name) << '}';
    }
    out << "],\"hits\":["; comma = false;
    uint64_t skip = page * 50; size_t emitted = 0;
    for (const auto& scan : scans_) for (int t = 0; t < 11; ++t) for (size_t word = 0; word < scan.bits[t].size() && emitted < 50; ++word) {
        uint64_t bits = scan.bits[t][word];
        unsigned population = unsigned(__builtin_popcountll(bits));
        if (skip >= population) { skip -= population; continue; }
        while (bits && emitted < 50) {
            unsigned bit = unsigned(__builtin_ctzll(bits)); bits &= bits - 1;
            if (skip) { --skip; continue; }
            Ref ref{scan.region, scan.previous.size(), word * 64 + bit, t};
            int64_t value; bool valid = decode(locate(regions, ref), t, value);
            if (comma) out << ',';
            comma = true; ++emitted;
            out << "{\"region\":" << ref.region << ",\"size\":" << ref.size << ",\"offset\":" << ref.offset << ",\"type\":" << t
                << ",\"value\":" << (valid ? std::to_string(value) : "null") << ",\"locked\":" << (locked(ref) ? "true" : "false") << '}';
        }
    }
    out << "]}";
    return out.str();
}
std::string MemoryEditor::command(int action, const std::vector<int64_t>& args) {
    try {
        auto regions = regions_();
        if (regions.empty()) throw std::runtime_error("此内核暂未提供可修改的游戏内存");
        if (action != 0 && action != 6 && action != 7 && (args.empty() || uint64_t(args[0]) != epoch_)) throw std::runtime_error("机器状态已改变，请刷新修改器");
        if (action == 0) return view(regions, args.empty() || args[0] < 0 ? 0 : size_t(args[0]));
        if (action == 1) search(regions, args);
        else if (action == 2) { scans_.clear(); count_ = 0; searched_ = false; }
        else if (action == 3 || action == 4) {
            if (args.size() != (action == 3 ? 6u : 7u)) throw std::runtime_error("修改参数无效");
            Ref ref = reference(args, 1); locate(regions, ref);
            bool enable = action == 4 && args[6] != 0;
            if (enable && !locked(ref) && locks_.size() >= 128) throw std::runtime_error("最多同时锁定 128 项");
            if (action == 3 || enable) write(regions, ref, args[5]);
            locks_.erase(std::remove_if(locks_.begin(), locks_.end(), [&](const Lock& lock) { return overlaps(lock.ref, ref); }), locks_.end());
            if (enable) locks_.push_back({ref,args[5]});
        } else if (action == 5) {
            if (undo_.empty()) throw std::runtime_error("没有可以撤销的修改");
            const auto& record = undo_.back(); uint8_t* data = locate(regions, record.ref);
            if (std::memcmp(data, record.after.data(), width(record.ref.type))) throw std::runtime_error("该地址的数值已变化，请重新搜索后修改");
            locks_.erase(std::remove_if(locks_.begin(), locks_.end(), [&](const Lock& lock) { return overlaps(lock.ref, record.ref); }), locks_.end());
            std::copy_n(record.before.data(), width(record.ref.type), data); undo_.pop_back();
        } else if (action == 6) {
            if (args.size() % 4 || args.size() > 128 * 4) throw std::runtime_error("修改表参数无效");
            std::ostringstream out; out << "{\"epoch\":" << epoch_ << ",\"saved\":[";
            for (size_t i = 0; i < args.size(); i += 4) {
                if (i) out << ',';
                try {
                    Ref ref = reference(args, i); int64_t value;
                    bool valid = decode(locate(regions, ref), ref.type, value);
                    out << "{\"valid\":" << (valid ? "true" : "false") << ",\"value\":" << (valid ? std::to_string(value) : "null")
                        << ",\"locked\":" << (locked(ref) ? "true" : "false") << '}';
                } catch (const std::exception&) { out << "{\"valid\":false,\"value\":null,\"locked\":false}"; }
            }
            out << "]}"; return out.str();
        } else if (action == 7) locks_.clear();
        else throw std::runtime_error("修改器操作无效");
        return view(regions, 0);
    } catch (const std::bad_alloc&) { return "{\"error\":\"内存不足，请清除搜索或选择较小的内存区域\"}"; }
      catch (const std::exception& error) { return "{\"error\":" + quoted(error.what()) + '}'; }
}
