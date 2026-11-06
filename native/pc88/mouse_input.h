#pragma once
#include "headers.h"
#include "if/ifui.h"
#include "if/ifguid.h"

class AndroidMouseInput final : public IMouseUI {
    unsigned refs = 0, buttons = 0;
    int x = 0, y = 0;
    bool enabled = false;
public:
    long QueryInterface(REFIID id, void** result) override {
        if (id != ChIID_MouseUI) { *result = nullptr; return E_NOINTERFACE; }
        *result = static_cast<IMouseUI*>(this); AddRef(); return S_OK;
    }
    ulong AddRef() override { return ++refs; }
    ulong Release() override { return --refs; } // Owned by AndroidMachine, not by the COM reference.
    bool Enable(bool value) override { enabled = value; x = y = 0; buttons = 0; return true; }
    void inject(int dx, int dy, unsigned mask) {
        if (!enabled) return;
        x = std::clamp(x + dx, -32767, 32767); y = std::clamp(y + dy, -32767, 32767);
        buttons = mask & 3;
    }
    bool GetMovement(POINT* output) override {
        int dx = std::clamp(x, -127, 127), dy = std::clamp(y, -127, 127);
        x -= dx; y -= dy;
        // The PC-88 mouse reports counters with the opposite sign to host motion.
        output->x = -dx; output->y = -dy; return true;
    }
    uint GetButton() override { return buttons; }
};
