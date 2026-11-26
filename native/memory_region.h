#pragma once
#include <stddef.h>
#include <stdint.h>

typedef struct NecMemoryRegion {
    uint32_t id;
    uint32_t address;
    size_t size;
    uint8_t* data;
    char name[48];
} NecMemoryRegion;
