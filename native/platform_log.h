#pragma once
#if defined(__ANDROID__)
#include <android/log.h>
inline void necLog(int level, const char* tag, const char* text) { __android_log_write(level, tag, text); }
constexpr int NecLogInfo = ANDROID_LOG_INFO, NecLogWarning = ANDROID_LOG_WARN, NecLogError = ANDROID_LOG_ERROR;
#else
#include <cstdio>
constexpr int NecLogInfo = 0, NecLogWarning = 1, NecLogError = 2;
inline void necLog(int, const char* tag, const char* text) { std::fprintf(stderr, "[%s] %s\n", tag, text); }
#endif
