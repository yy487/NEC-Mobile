#pragma once
#include <array>
#include <cstdint>
#include <fstream>
#include <string>
#include <sstream>
#include <map>
#include <utility>

inline std::string stateHardwareConfig(const std::string& config) {
    std::istringstream input(config); std::string line, result;
    std::map<std::string,std::string> hardware;
    while (std::getline(input,line)) {
        std::string key=line.substr(0,line.find('='));
        if (key.rfind("display_",0)==0 || key.rfind("audio_",0)==0 || key.rfind("startup_",0)==0 || key=="host_speed" || key=="host_fullspeed") continue;
        // Mixer gains and the host SoundFont do not change the emulated hardware.
        if (key=="output_volume" || key=="midi_volume" || key=="midi_soundfont" || key=="np2kai_Seek_Vol" ||
            key.compare(0,14,"np2kai_volume_")==0 || key=="m88_fm" || key=="m88_ssg" || key=="m88_adpcm" || key=="m88_rhythm") continue;
        hardware[key] = line.substr(line.find('=') + 1);
    }
    // Older states omit options which were fixed at these values in 0.25.
    if (hardware.count("m88_clock")) {
        for (auto value : {std::pair<const char*,const char*>{"m88_cpu_ratio","AUTO"},
            {"m88_sub_always","ON"},{"m88_cpu_wait","OFF"},{"m88_fdd_wait","ON"},{"m88_dipsw","1829"},
            {"m88_pcg","OFF"},{"m88_monitor","24K"},{"m88_fullline","ON"},{"m88_digital_palette","OFF"},{"m88_refreshtiming","1"}}) hardware.emplace(value.first,value.second);
    } else if (hardware.count("np2kai_model")) {
        for (auto value : {std::pair<const char*,const char*>{"pc98_sound_bios","ON"},
            {"np2kai_gdc","uPD7220"},{"np2kai_skipline","Full 255 lines"},{"np2kai_realpal","OFF"},{"np2kai_lcd","OFF"}}) hardware.emplace(value.first,value.second);
    }
    for (const auto& value : hardware) result += value.first + "=" + value.second + "\n";
    return result;
}

struct SnapshotMeta {
    uint32_t platform=0, mode=0;
    std::string directory, config;
    std::array<std::string,2> media;
    std::array<uint32_t,2> indices{};
    bool relocate(const std::string& next) {
        if (directory == next) return true;
        const std::string marker = "/NECMobile/pc" + std::to_string(platform) + "/games/";
        auto before = directory.rfind(marker), after = next.rfind(marker);
        if (before == std::string::npos || after == std::string::npos || directory.substr(before) != next.substr(after)) return false;
        auto path = [&](const std::string& value) {
            return value.compare(0, directory.size() + 1, directory + "/") == 0 ? next + value.substr(directory.size()) : value;
        };
        for (auto& disk : media) disk = path(disk);
        std::istringstream input(config); std::string line, relocated;
        while (std::getline(input, line)) {
            auto split = line.find('=');
            relocated += split == std::string::npos ? line : line.substr(0, split + 1) + path(line.substr(split + 1));
            relocated += '\n';
        }
        config = std::move(relocated); directory = next; return true;
    }
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
        if (!number(platform) || (platform!=88 && platform!=98) || !number(mode) ||
            mode>(platform==88 ? 5u : 2u) || !text(directory,4096) || !text(config,16384)) return false;
        for (size_t i=0;i<2;++i) if (!text(media[i],4096) || !number(indices[i]) || indices[i]>=64) return false;
        return in.peek()==EOF;
    }
};
