#pragma once
#include <fstream>
#include <map>
#include <stdexcept>
#include <string>

using MachineConfig = std::map<std::string, std::string>;
inline MachineConfig readMachineConfig(const char* directory) {
    std::ifstream file(std::string(directory)+"/machine.cfg");
    MachineConfig settings;
    if (!file) return settings;
    std::string line;
    unsigned count=0;
    while (std::getline(file,line)) {
        if (++count>64 || line.size()>256) throw std::runtime_error("Machine configuration is too large");
        if (!line.empty() && line.back()=='\r') line.pop_back();
        auto split=line.find('=');
        if (line.empty()) continue;
        if (split==std::string::npos || split==0 || split>64) throw std::runtime_error("Invalid machine configuration");
        settings[line.substr(0,split)]=line.substr(split+1);
    }
    if (file.bad()) throw std::runtime_error("Cannot read machine configuration");
    return settings;
}
inline int machineInt(const MachineConfig& settings,const char* key,int fallback,int low,int high) {
    auto found=settings.find(key);
    if (found==settings.end()) return fallback;
    try {
        size_t used=0; int value=std::stoi(found->second,&used);
        return used==found->second.size() && value>=low && value<=high ? value : fallback;
    } catch (...) { return fallback; }
}
inline std::string machineText(const MachineConfig& settings,const char* key,const char* fallback) {
    auto found=settings.find(key); return found==settings.end() ? fallback : found->second;
}
