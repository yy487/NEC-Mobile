#pragma once
#include <array>
#include <cstdint>
#include <fstream>
#include <string>
#include <sstream>

inline std::string stateHardwareConfig(const std::string& config) {
    std::istringstream input(config); std::string line, result;
    while (std::getline(input,line)) {
        std::string key=line.substr(0,line.find('='));
        // Mixer gains and the host SoundFont do not change the emulated hardware.
        if (key=="output_volume" || key=="midi_volume" || key=="midi_soundfont" || key=="np2kai_Seek_Vol" ||
            key.compare(0,14,"np2kai_volume_")==0 || key=="m88_fm" || key=="m88_ssg" || key=="m88_adpcm" || key=="m88_rhythm") continue;
        result+=line+"\n";
    }
    return result;
}

struct SnapshotMeta {
    uint32_t platform=0, mode=0;
    std::string directory, config;
    std::array<std::string,2> media;
    std::array<uint32_t,2> indices{};
    bool write(const std::string& path) const {
        std::ofstream out(path,std::ios::binary|std::ios::trunc);
        out.write("NECSTATE024",11);
        auto number=[&](uint32_t n) { for (unsigned i=0;i<4;++i) out.put(char(n>>(i*8))); };
        auto text=[&](const std::string& s) { number(uint32_t(s.size())); out.write(s.data(),std::streamsize(s.size())); };
        number(platform); number(mode); text(directory); text(config);
        for (size_t i=0;i<2;++i) { text(media[i]); number(indices[i]); }
        out.close(); return bool(out);
    }
    bool read(const std::string& path) {
        std::ifstream in(path,std::ios::binary); char magic[11];
        if (!in.read(magic,11) || std::string(magic,11)!="NECSTATE024") return false;
        auto number=[&](uint32_t& n) { n=0; for (unsigned i=0;i<4;++i) { int c=in.get(); if (c<0) return false; n|=uint32_t(c)<<(i*8); } return true; };
        auto text=[&](std::string& s,size_t limit) { uint32_t n; if (!number(n) || n>limit) return false; s.resize(n); return bool(in.read(s.data(),n)) && s.find('\0')==std::string::npos; };
        if (!number(platform) || (platform!=88 && platform!=98) || !number(mode) || mode>2 || !text(directory,4096) || !text(config,16384)) return false;
        for (size_t i=0;i<2;++i) if (!text(media[i],4096) || !number(indices[i]) || indices[i]>=64) return false;
        return in.peek()==EOF;
    }
};
