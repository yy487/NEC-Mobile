#pragma once
#include <string>
#include <cstdint>

namespace NecEngine {
bool start(int platform, const std::string& directory, const std::string& disk, const std::string& secondDisk,
           int mode, int firstIndex, int secondIndex, bool mouseEnabled);
void stop();
void pause(bool value);
void key(int code, bool down);
void mouse(int x, int y, int buttons);
void releaseKeys();
void reset();
void flush();
void audioChanged();
std::string mount(int drive, const std::string& path, int index);
std::string state(const std::string& directory, bool load);
std::string statusText();
std::string errorText();
std::string machineText();
std::string audioText();
std::string takeWarning();
std::string disk(int drive);
int diskIndex(int drive);
int startup();
uint32_t geometry();
bool mouseActive();
bool validSoundFont(const std::string& path);
}
