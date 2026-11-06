#pragma once
#include "types.h"
// External physical sound boards have no Android counterpart; software FM stays active.
class PiccoloChip {
public:
    virtual ~PiccoloChip() = default;
    virtual int Init(uint) = 0;
    virtual void Reset(bool) = 0;
    virtual bool SetReg(uint32, uint, uint) = 0;
    virtual void SetChannelMask(uint) = 0;
    virtual void SetVolume(int, int) = 0;
};
enum PICCOLO_CHIPTYPE { PICCOLO_INVALID, PICCOLO_YMF288, PICCOLO_YM2608 };
class Piccolo {
public:
    static Piccolo* GetInstance() { return nullptr; }
    static void DeleteInstance() {}
    int GetChip(PICCOLO_CHIPTYPE, PiccoloChip**) { return -1; }
    int IsDriverBased() { return 0; }
    uint32 GetCurrentTime() { return 0; }
    bool SetLatencyBufferSize(uint) { return false; }
    bool SetMaximumLatency(uint) { return false; }
};
