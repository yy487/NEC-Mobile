#pragma once
class StatusDisplay {
public:
    bool Show(int, int, const char*, ...);
    void UpdateDisplay() {}
    void WaitSubSys() {}
    void FDAccess(unsigned, bool, bool) {}
};
extern StatusDisplay statusdisplay;
