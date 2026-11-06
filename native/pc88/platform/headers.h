#pragma once
#include <cstdio>
#include <cstdlib>
#include <cstdarg>
#include <cmath>
#include <cstring>
#include <cassert>
#include <climits>
#include <ctime>
#include <cerrno>
#include <map>
#include <string>
#include <vector>
#include <utility>
#include <algorithm>
#include "types.h"
using namespace std;
inline int localtime_s(tm* result, const time_t* value) {
    return localtime_r(value, result) ? 0 : errno;
}
inline int MulDiv(int a, int b, int c) {
    if (!c) return -1;
    int64_t numerator = int64_t(a) * b, denominator = c;
    if (denominator < 0) { denominator = -denominator; numerator = -numerator; }
    int64_t value = (numerator + (numerator < 0 ? -denominator / 2 : denominator / 2)) / denominator;
    return value < INT_MIN || value > INT_MAX ? -1 : static_cast<int>(value);
}
FILE* m88_fopen(const char* name, const char* mode);
#define fopen m88_fopen
