#include "platform/headers.h"
#include "../core_api.h"
#include "../machine_config.h"
#include "../state_file.h"
#include "pc88/pc88.h"
#include "pc88/config.h"
#include "pc88/diskmgr.h"
#include "pc88/tapemgr.h"
#include "pc88/sound.h"
#include "pc88/opnif.h"
#include "pc88/beep.h"
#include "pc88/mouse.h"
#include "mouse_input.h"
#include "error.h"
#include "file.h"
#include <array>
#include <memory>
#include <unistd.h>

NecHost m88host{};

class AndroidDraw final : public Draw {
    std::vector<uint8_t> indexed;
    std::vector<uint32_t> pixels;
    std::array<uint32_t, 256> colors{};
    uint width = 640, height = 400;
public:
    bool Init(uint w, uint h, uint bpp) override {
        if (bpp != 8 || w > 2048 || h > 2048) return false;
        Resize(w, h);
        return true;
    }
    bool Cleanup() override { return true; }
    bool Lock(uint8** output, int* stride) override { *output = indexed.data(); *stride = width; return true; }
    bool Unlock() override { return true; }
    uint GetStatus() override { return readytodraw; }
    void Resize(uint w, uint h) override {
        width = w; height = h;
        indexed.resize(w * h);
        pixels.resize(w * h);
    }
    void DrawScreen(const Region&) override {
        for (size_t i = 0; i < indexed.size(); ++i) pixels[i] = colors[indexed[i]];
        if (m88host.video) m88host.video(pixels.data(), width, height);
    }
    void SetPalette(uint start, uint count, const Palette* entries) override {
        for (uint i = 0; i < count && start + i < colors.size(); ++i)
            colors[start + i] = 0xff000000u | (uint32_t(entries[i].blue) << 16) |
                                (uint32_t(entries[i].green) << 8) | entries[i].red;
    }
    bool SetFlipMode(bool) override { return false; }
};

class AndroidKeyboard final : public Device {
    static const InFuncPtr functions[];
    static const Descriptor descriptor;
public:
    std::array<uint8_t, 16> rows;
    AndroidKeyboard() : Device(DEV_ID('A', 'K', 'B', 'D')) { rows.fill(255); }
    const Descriptor* GetDesc() const override { return &descriptor; }
    uint Read(uint port) { return IOBus::Active(rows[port & 15], 255); }
    void key(int code, bool down) {
        int row = -1, bit = 0;
        if (code >= 'a' && code <= 'z') code -= 'a' - 'A';
        if (code >= 256 && code <= 265) { int n = code - 256; row = n / 8; bit = n % 8; }
        else if (code >= '@' && code <= 'Z') { int n = code - '@'; row = 2 + n / 8; bit = n % 8; }
        else if (code >= '0' && code <= '7') { row = 6; bit = code - '0'; }
        else if (code == '8' || code == '9') { row = 7; bit = code - '8'; }
        else {
            switch (code) {
            case 13: row = 1; bit = 7; break;
            case 32: row = 9; bit = 6; break;
            case 27: row = 9; bit = 7; break;
            case 273: row = 8; bit = 1; break;
            case 275: row = 8; bit = 2; break;
            case 274: row = 10; bit = 1; break;
            case 276: row = 10; bit = 2; break;
            case 303: case 304: row = 8; bit = 6; break;
            case 305: case 306: row = 8; bit = 7; break;
            case 307: case 308: row = 8; bit = 4; break;
            case 302: row = 8; bit = 5; break;
            case 8: row = 8; bit = 3; break;
            case 9: row = 10; bit = 0; break;
            case 19: row = 9; bit = 0; break;
            case '[': row = 5; bit = 3; break;
            case '\\': row = 5; bit = 4; break;
            case ']': row = 5; bit = 5; break;
            case '^': row = 5; bit = 6; break;
            case '-': row = 5; bit = 7; break;
            case ':': row = 7; bit = 2; break;
            case ';': row = 7; bit = 3; break;
            case ',': row = 7; bit = 4; break;
            case '.': row = 7; bit = 5; break;
            case '/': row = 7; bit = 6; break;
            case '_': row = 7; bit = 7; break;
            default: if (code >= 282 && code <= 286) { row = 9; bit = code - 281; }
            }
        }
        if (row >= 0) {
            if (down) rows[row] &= ~(1u << bit);
            else rows[row] |= 1u << bit;
        }
    }
};
const IDevice::InFuncPtr AndroidKeyboard::functions[] = {
    static_cast<IDevice::InFuncPtr>(&AndroidKeyboard::Read)
};
const IDevice::Descriptor AndroidKeyboard::descriptor = {functions, nullptr};

class AndroidMachine final : public PC88 {
    AndroidMouseInput mouseInput;
    PC8801::Mouse mouseDevice{DEV_ID('A', 'M', 'S', 'E')};
    bool mouseEnabled = false;
public:
    ~AndroidMachine() { if (mouseEnabled) bus1.Disconnect(&mouseDevice); }
    bool keyboard(AndroidKeyboard* keyboard) {
        IOBus::Connector ports[17]{};
        for (unsigned i = 0; i < 16; ++i) ports[i] = {static_cast<ushort>(i), IOBus::portin, 0};
        return bus1.Connect(keyboard, ports);
    }
    bool enableMouse(bool enabled) {
        if (enabled == mouseEnabled) return true;
        mouseInput.Enable(enabled);
        if (enabled) {
            mouseDevice.Init(this);
            if (!mouseDevice.Connect(&mouseInput)) return false;
            PC8801::Config config{}; config.mousesensibility = 4;
            mouseDevice.ApplyConfig(&config);
            const IOBus::Connector ports[]{
                {popnio, IOBus::portin, PC8801::Mouse::getmove},
                {popnio + 1, IOBus::portin, PC8801::Mouse::getbutton},
                {0x40, IOBus::portout, PC8801::Mouse::strobe},
                {vrtc, IOBus::portout, PC8801::Mouse::vsync}, {0, 0, 0}
            };
            if (!bus1.Connect(&mouseDevice, ports)) return false;
        } else bus1.Disconnect(&mouseDevice);
        mouseEnabled = enabled; return true;
    }
    void mouse(int dx, int dy, unsigned buttons) { mouseInput.inject(dx, dy, buttons); }
    bool snapshot(std::vector<uint8_t>& bytes) {
        bytes.assign(devlist.GetStatusSize(),0);
        return devlist.SaveStatus(bytes.data());
    }
    bool checkSnapshot(const std::vector<uint8_t>& bytes) {
        std::vector<uint8_t> shape;
        if (!snapshot(shape) || bytes.size()!=shape.size()) return false;
        // Match every device and its payload size before the legacy unbounded loader.
        for (size_t at=0;at<bytes.size();) {
            if (bytes.size()-at<8 || memcmp(bytes.data()+at,shape.data()+at,8)) return false;
            uint32_t id, count; memcpy(&id,bytes.data()+at,4); memcpy(&count,bytes.data()+at+4,4);
            at+=8;
            if (!id) return count==0 && at==bytes.size();
            size_t aligned=(uint64_t(count)+3)&~uint64_t(3);
            if (aligned>bytes.size()-at) return false;
            at+=aligned;
        }
        return false;
    }
    bool restore(const std::vector<uint8_t>& bytes) {
        if (!checkSnapshot(bytes)) return false;
        Reset();
        if (!devlist.LoadStatus(bytes.data())) return false;
        UpdateScreen(true); return true;
    }
};

static std::unique_ptr<AndroidDraw> draw;
static std::unique_ptr<DiskManager> disks;
static std::unique_ptr<TapeManager> tape;
static std::unique_ptr<AndroidMachine> machine;
static std::unique_ptr<AndroidKeyboard> keyboard;
static std::unique_ptr<PC8801::Sound> sound;
static double sampleRemainder;
static bool initialized;
static int requestedMode;
static PC8801::Config initialConfig;
static MachineConfig machineConfig;

void AndroidInitialConfig(PC88* pc) {
    initialConfig = {};
    auto& config = initialConfig;
    config.basicmode = requestedMode == 1 ? PC8801::Config::N88V1 : requestedMode == 2 ? PC8801::Config::N88V1H : PC8801::Config::N88V2;
    config.clock = machineInt(machineConfig,"m88_clock",40,40,80);
    config.speed = 1000;
    config.mainsubratio = config.clock >= 80 ? 2 : 1;
    config.cpumode = PC8801::Config::msauto;
    config.dipsw = 1829;
    config.refreshtiming = 1;
    config.opnclock = 8000000;
    config.flags = PC8801::Config::fullline | PC8801::Config::precisemixing | PC8801::Config::enableopna;
    if (machineText(machineConfig,"m88_board","OPNA")=="OPN") config.flags &= ~PC8801::Config::enableopna;
    config.erambanks=machineInt(machineConfig,"m88_erambanks",0,0,32);
    config.volfm=machineInt(machineConfig,"m88_fm",0,-40,20);
    config.volssg=machineInt(machineConfig,"m88_ssg",0,-40,20);
    config.voladpcm=machineInt(machineConfig,"m88_adpcm",0,-40,20);
    config.volrhythm=machineInt(machineConfig,"m88_rhythm",0,-40,20);
    config.flag2 = PC8801::Config::usefmclock;
    config.lpffc = 9000;
    config.lpforder = 4;
    pc->ApplyConfig(&config);
}

static bool flush() {
    if (!disks) return true;
    disks->Update();
    return FileIO::FlushAll();
}
static void closeCore() {
    flush();
    if (initialized && machine) machine->DeInit();
    initialized = false;
    sound.reset();
    tape.reset();
    machine.reset();
    keyboard.reset();
    disks.reset();
    draw.reset();
}
static bool openCore(const char* directory, const char* disk, const char* secondDisk, int mode, NecHost host) {
    closeCore();
    m88host = host;
    requestedMode = mode;
    machineConfig=readMachineConfig(directory);
    if (chdir(directory)) { host.message("PC-88 directory cannot be opened"); return false; }
    draw = std::make_unique<AndroidDraw>();
    disks = std::make_unique<DiskManager>();
    tape = std::make_unique<TapeManager>();
    machine = std::make_unique<AndroidMachine>();
    keyboard = std::make_unique<AndroidKeyboard>();
    if (!disks->Init() || (*disk && !disks->Mount(0, disk, false, 0, false)) ||
        (*secondDisk && !disks->Mount(1, secondDisk, false, 0, false)) ||
        !machine->Init(draw.get(), disks.get(), tape.get())) {
        host.message(Error::GetErrorText());
        closeCore();
        return false;
    }
    initialized = true;
    auto& config = initialConfig;
    if (!machine->keyboard(keyboard.get())) { closeCore(); return false; }
    sound = std::make_unique<PC8801::Sound>();
    if (!sound->Init(machine.get(), 48000, 8192)) { closeCore(); return false; }
    machine->GetOPN1()->Connect(sound.get());
    machine->GetOPN2()->Connect(sound.get());
    if (machineText(machineConfig,"m88_beep","ON")!="OFF") machine->GetBEEP()->Connect(sound.get());
    sound->ApplyConfig(&config);
    sound->FillWhenEmpty(false);
    machine->Reset();
    sampleRemainder = 0;
    std::string description="PC-88 config: board="+machineText(machineConfig,"m88_board","OPNA")+
        ", BEEP="+machineText(machineConfig,"m88_beep","ON")+", FM="+std::to_string(config.volfm)+", clock="+std::to_string(config.clock);
    host.message(description.c_str());
    return true;
}
static double fps() { return machine ? 100000.0 / machine->GetFramePeriod() : 60.0; }
static void frame() {
    machine->Proceed(machine->GetFramePeriod(), initialConfig.clock, initialConfig.clock);
    machine->UpdateScreen();
    sound->Update();
    sampleRemainder += 48000.0 / fps();
    int samples = static_cast<int>(sampleRemainder);
    sampleRemainder -= samples;
    int16_t pcm[4096];
    samples = std::min(samples, 2048);
    int actual = sound->GetSoundSource()->Get(pcm, samples);
    if (actual > 0) m88host.audio(pcm, actual, 48000);
}
static void key(int code, bool pressed) { if (keyboard) keyboard->key(code, pressed); }
static void mouse(int dx, int dy, unsigned buttons) { if (machine) machine->mouse(dx, dy, buttons); }
static bool enableMouse(bool enabled) { return machine && machine->enableMouse(enabled); }
static bool mount(int drive, const char* path, int index) {
    if (!disks || drive < 0 || drive > 1 || index < 0 || index >= 64 || !flush()) return false;
    if (!*path) return disks->Unmount(drive);
    return disks->Mount(drive, path, false, index, false);
}
static void reset() { keyboard->rows.fill(255); sound->BeginStateLoad(); machine->Reset(); sound->ResetAfterState(); }
namespace PC8801 { void AndroidMidiByte(uint8_t byte) { if (m88host.midi) m88host.midi(byte); } }
static bool saveState(const char* path) {
    std::vector<uint8_t> data;
    return machine && machine->snapshot(data) && writeStateFile(path,data.data(),data.size());
}
static bool checkState(const char* path) {
    std::vector<uint8_t> data;
    return machine && readStateFile(path,data,16*1024*1024) && machine->checkSnapshot(data);
}
static bool loadState(const char* path) {
    std::vector<uint8_t> data;
    if (!machine || !readStateFile(path,data,16*1024*1024) || !machine->checkSnapshot(data)) return false;
    sound->BeginStateLoad();
    bool restored=machine->restore(data);
    bool audioRestored=sound->ResetAfterState();
    keyboard->rows.fill(255); sampleRemainder=0;
    return restored && audioRestored;
}
extern "C" const NecCore* nec_core() {
    static const NecCore api{"M88 C++ / PC-8801", openCore, frame, key, mouse, mount, flush, reset, closeCore, fps, enableMouse, saveState, checkState, loadState};
    return &api;
}
