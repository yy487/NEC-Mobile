#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

using AppleAudioRenderer = void (*)(void*, int32_t);
using AppleAudioError = void (*)();
unsigned necAppleOpenAudio(unsigned requestedRate, unsigned bufferMs, AppleAudioRenderer renderer, AppleAudioError error);
void necAppleCloseAudio();
void necApplePresentFrame(const uint32_t* pixels, unsigned width, unsigned height);
std::string necAppleCorePath(int platform);
