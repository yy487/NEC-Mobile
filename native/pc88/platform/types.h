#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <strings.h>

using uchar = unsigned char;
using ushort = unsigned short;
using uint = unsigned int;
using ulong = unsigned long;
using uint8 = uint8_t;
using uint16 = uint16_t;
using uint32 = uint32_t;
using sint8 = int8_t;
using sint16 = int16_t;
using sint32 = int32_t;
using int8 = int8_t;
using int16 = int16_t;
using int32 = int32_t;
using intpointer = intptr_t;
using packed = uint32;
using HWND = void*;
using UINT = unsigned int;
using WPARAM = uintptr_t;
using LPARAM = intptr_t;
struct PROPSHEETPAGE;
struct POINT { int32_t x, y; };
#define ENDIAN_IS_SMALL
#define USE_NEW_CAST
#define STATIC_CAST(t, o) static_cast<t>(o)
#define REINTERPRET_CAST(t, o) reinterpret_cast<t>(o)
#define PACK(p) ((p) | ((p) << 8) | ((p) << 16) | ((p) << 24))
#define MEMCALL
#define IFCALL
#define IOCALL
#define CALLBACK
#define __stdcall
#define interface struct
#define MAX_PATH 4096
#define _stricmp strcasecmp
#define _strnicmp strncasecmp
#define stricmp strcasecmp
#define strnicmp strncasecmp
#define _strdup strdup

struct GUID { uint32 a; uint16 b, c; uint8 d[8]; };
using REFIID = const GUID&;
inline bool operator==(const GUID& a, const GUID& b) { return !memcmp(&a, &b, sizeof(a)); }
inline bool operator!=(const GUID& a, const GUID& b) { return !(a == b); }
#define DEFINE_GUID(n, a, b, c, ...) inline const GUID n = {a, b, c, {__VA_ARGS__}}
constexpr int32 E_NOINTERFACE = static_cast<int32>(0x80004002u);
constexpr int32 S_OK = 0;
