#include "headers.h"
#include "pc88/pc88.h"
#include "pc88/mouse.h"
#include "pc88/config.h"
#include "../../native/pc88/mouse_input.h"
#ifdef _MSC_VER
#define __attribute__(value)
#endif
#include "../../native/core_api.h"
#include <stdexcept>

NecHost m88host{};
void AndroidInitialConfig(PC88*) {}
class ClockMachine : public PC88 { int GetTicks() override { return 0; } };
static void require(bool valid) { if (!valid) throw std::runtime_error("Mouse protocol check failed"); }
int main() {
    ClockMachine clock; clock.Scheduler::Init();
    AndroidMouseInput input;
    PC8801::Mouse device(DEV_ID('T','M','S','E'));
    device.Init(&clock); require(device.Connect(&input));
    PC8801::Config config{}; config.mousesensibility = 4; device.ApplyConfig(&config);
    input.Enable(true); input.inject(12, -7, 1);
    device.Strobe(0x40,64); require((device.GetMove(0)&255)==0xff);
    device.Strobe(0x40,0); require((device.GetMove(0)&255)==0xf4);
    device.Strobe(0x40,64); require((device.GetMove(0)&255)==0xf0);
    device.Strobe(0x40,0); require((device.GetMove(0)&255)==0xf7);
    require((device.GetButton(0)&255)==0xfe);
    input.inject(0,0,2); require((device.GetButton(0)&255)==0xfd);
    input.inject(0,0,0); require((device.GetButton(0)&255)==0xff);
    POINT movement{}; input.inject(300,-300,0);
    input.GetMovement(&movement); require(movement.x==-127 && movement.y==127);
    input.GetMovement(&movement); require(movement.x==-127 && movement.y==127);
    input.GetMovement(&movement); require(movement.x==-46 && movement.y==46);
    input.Enable(false); input.inject(20,20,3); input.GetMovement(&movement);
    require(movement.x==0 && movement.y==0 && input.GetButton()==0);
    std::puts("M88 mouse: real four-phase nibble protocol, direction, both buttons, motion carry and disable passed");
}
