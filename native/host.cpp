#include "core_api.h"
#include "machine_config.h"
#include "midi_synth.h"
#include "snapshot_meta.h"
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <future>
#include <memory>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace {
std::mutex windowMutex, statusMutex, commandMutex;
std::condition_variable wake;
ANativeWindow* window;
std::thread worker;
std::atomic<bool> stopping{false}, paused{false};
std::atomic<bool> frameReceived{false};
std::atomic<bool> workerRunning{false};
std::atomic<bool> audioDisconnected{false};
std::string status = "Ready";
std::array<std::string, 2> driveMedia;
std::array<int, 2> driveIndices{};
unsigned videoWidth, videoHeight;
AAudioStream* stream;
unsigned audioRate;
std::chrono::steady_clock::time_point audioRetry{};
unsigned outputVolume = 100;
MidiSynth midiSynth;
std::vector<int16_t> mixedAudio;
bool midiReceived=false, midiAudible=false;
constexpr size_t audioCapacity = 32768;
std::array<int16_t, audioCapacity * 2> audioBuffer{};
std::atomic<uint64_t> audioRead{0}, audioWrite{0};
struct Command { int type, a = 0, b = 0, c = 0; std::string path; std::shared_ptr<std::promise<std::string>> result; };
std::deque<Command> commands;
enum { Key, Mouse, Mount, Flush, Reset, Pause, Resume, ReleaseKeys, MouseMode, ReopenAudio, SaveState, LoadState };

void message(const char* text) {
    if (!text) return;
    __android_log_write(ANDROID_LOG_INFO, "NECMobile", text);
    std::lock_guard<std::mutex> lock(statusMutex);
    status = text;
    // Old M88 messages may contain CP932. NewStringUTF must receive valid UTF-8.
    for (char& byte : status) if (static_cast<unsigned char>(byte) >= 128) byte = '?';
}
aaudio_data_callback_result_t audioCallback(AAudioStream*, void*, void* output, int32_t frames) {
    auto* destination = static_cast<int16_t*>(output);
    uint64_t read = audioRead.load(std::memory_order_relaxed);
    uint64_t write = audioWrite.load(std::memory_order_acquire);
    if (paused.load(std::memory_order_relaxed) || stopping.load(std::memory_order_relaxed)) {
        std::fill(destination, destination + frames * 2, 0);
        audioRead.store(write, std::memory_order_release);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    size_t available = std::min<uint64_t>(frames, write - read);
    for (size_t i = 0; i < available; ++i) {
        destination[i * 2] = audioBuffer[((read + i) % audioCapacity) * 2];
        destination[i * 2 + 1] = audioBuffer[((read + i) % audioCapacity) * 2 + 1];
    }
    std::fill(destination + available * 2, destination + frames * 2, 0);
    audioRead.store(read + available, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
void closeAudio() {
    if (stream) { AAudioStream_requestStop(stream); AAudioStream_close(stream); }
    stream = nullptr;
    audioRate = 0;
    audioRead.store(0);
    audioWrite.store(0);
}
void audioError(AAudioStream*, void*, aaudio_result_t) {
    // AAudio forbids closing the stream from its error callback.
    audioDisconnected.store(true, std::memory_order_release);
}
void audio(const int16_t* samples, size_t frames, unsigned rate) {
    // Advance synthesis even while an output device is being rebuilt.
    if (midiSynth.enabled()) {
        mixedAudio.resize(frames*2); midiSynth.mix(samples,mixedAudio.data(),frames,rate);
        if (!midiAudible && !std::equal(samples,samples+frames*2,mixedAudio.data())) { midiAudible=true; message("MIDI synthesized PCM active"); }
        samples=mixedAudio.data();
    }
    if (audioDisconnected.exchange(false)) { closeAudio(); audioRetry = {}; message("Audio route disconnected; rebuilding"); }
    if (paused.load() || stopping.load()) return;
    if (audioRate != rate) {
        if (std::chrono::steady_clock::now() < audioRetry) return;
        closeAudio();
        audioRetry = std::chrono::steady_clock::now() + std::chrono::seconds(1);
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return;
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSampleRate(builder, rate);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setDataCallback(builder, audioCallback, nullptr);
        AAudioStreamBuilder_setErrorCallback(builder, audioError, nullptr);
        auto result = AAudioStreamBuilder_openStream(builder, &stream);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK) { stream = nullptr; message("Audio unavailable; emulation continues muted"); return; }
        if (AAudioStream_requestStart(stream) != AAUDIO_OK) { closeAudio(); return; }
        audioRate = rate;
        message("Audio output ready");
    }
    if (!stream) return;
    uint64_t write = audioWrite.load(std::memory_order_relaxed);
    uint64_t read = audioRead.load(std::memory_order_acquire);
    size_t count = std::min<uint64_t>(frames, audioCapacity - (write - read));
    for (size_t i = 0; i < count; ++i) {
        audioBuffer[((write + i) % audioCapacity) * 2] = samples[i * 2] * int(outputVolume) / 100;
        audioBuffer[((write + i) % audioCapacity) * 2 + 1] = samples[i * 2 + 1] * int(outputVolume) / 100;
    }
    audioWrite.store(write + count, std::memory_order_release);
}
void video(const uint32_t* pixels, unsigned width, unsigned height) {
    if (!width || !height || width > 2048 || height > 2048) return;
    if (!frameReceived.exchange(true)) message("Running");
    std::lock_guard<std::mutex> lock(windowMutex);
    if (!window) return;
    if (videoWidth != width || videoHeight != height) {
        if (ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888)) return;
        videoWidth = width; videoHeight = height;
    }
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window, &buffer, nullptr)) return;
    if (buffer.width >= int(width) && buffer.height >= int(height)) {
        for (unsigned y = 0; y < height; ++y)
            memcpy(static_cast<uint32_t*>(buffer.bits) + y * buffer.stride, pixels + y * width, width * 4);
    }
    ANativeWindow_unlockAndPost(window);
}
void enqueue(Command command) {
    std::lock_guard<std::mutex> lock(commandMutex);
    if (command.type == Mouse && !commands.empty() && commands.back().type == Mouse && commands.back().c == command.c) {
        commands.back().a = std::clamp(commands.back().a + command.a, -32767, 32767);
        commands.back().b = std::clamp(commands.back().b + command.b, -32767, 32767);
        wake.notify_one(); return;
    }
    if (commands.size() >= 512) {
        commands.erase(std::remove_if(commands.begin(),commands.end(),[](const Command& c) { return c.type==Key || c.type==Mouse; }),commands.end());
        commands.push_back({ReleaseKeys});
    }
    commands.push_back(std::move(command));
    wake.notify_one();
}
void run(int platform, std::string directory, std::string disk, std::string secondDisk, int mode, int firstIndex, int secondIndex, bool mouseEnabled) {
    void* library = nullptr;
    const NecCore* core = nullptr;
    std::array<bool, 512> held{};
    std::array<std::deque<bool>, 512> keyChanges;
    std::array<unsigned, 512> keyHoldFrames{};
    std::array<std::deque<bool>, 3> buttonChanges;
    std::array<unsigned, 3> buttonHoldFrames{};
    unsigned buttons = 0;
    int motionX = 0, motionY = 0;
    bool stateBroken=false;
    SnapshotMeta current;
    current.platform=platform; current.mode=mode; current.directory=directory;
    std::string hardDiskPath;
    for (const char* suffix:{".hdi",".nhd",".hdd",".thd"})
        if (disk.size()>=strlen(suffix) && disk.compare(disk.size()-strlen(suffix),strlen(suffix),suffix)==0) hardDiskPath=disk;
    MachineConfig configuration;
    auto requestEdge = [](std::deque<bool>& edges, bool current, bool requested) {
        if ((edges.empty() ? current : edges.back()) != requested) edges.push_back(requested);
    };
    auto sampleEdge = [](std::deque<bool>& edges, unsigned& hold, bool current, unsigned pressFrames, unsigned releaseFrames) {
        if (hold) --hold;
        if (!hold && !edges.empty()) {
            current = edges.front(); edges.pop_front();
            hold = current ? pressFrames : releaseFrames;
        }
        return current;
    };
    auto releaseKeys = [&] {
        for (size_t i = 0; i < held.size(); ++i) if (held[i]) core->key(i, false);
        held.fill(false);
        for (auto& edges : keyChanges) edges.clear();
        for (auto& edges : buttonChanges) edges.clear();
        keyHoldFrames.fill(0); buttonHoldFrames.fill(0);
        buttons = 0; motionX = motionY = 0;
        core->mouse(0, 0, 0);
    };
    auto syncFile=[](const std::string& path) {
        int fd=::open(path.c_str(),O_RDONLY); if (fd<0) return false;
        bool ok=fsync(fd)==0; ::close(fd); return ok;
    };
    auto saveSession=[&](const std::string& folder) {
        if (!core->flush()) return false;
        { std::lock_guard<std::mutex> lock(statusMutex);
          current.media=driveMedia;
          for (size_t i=0;i<2;++i) current.indices[i]=driveIndices[i]; }
        return core->saveState((folder+"/machine.bin").c_str()) && core->checkState((folder+"/machine.bin").c_str()) && current.write(folder+"/host.bin") && midiSynth.save(folder+"/midi.bin") &&
            syncFile(folder+"/machine.bin") && syncFile(folder+"/host.bin") && syncFile(folder+"/midi.bin");
    };
    auto installMedia=[&](const SnapshotMeta& target) {
        for (int i=0;i<2;++i) if (!core->mount(i,"",0)) return false;
        for (int i=0;i<2;++i) if (!target.media[i].empty() && !core->mount(i,target.media[i].c_str(),target.indices[i])) return false;
        return true;
    };
    auto restoreSession=[&](const std::string& folder) -> std::string {
        SnapshotMeta target;
        MidiSynth checkMidi;
        if (!target.read(folder+"/host.bin") || !checkMidi.load(folder+"/midi.bin") || !core->checkState((folder+"/machine.bin").c_str())) return "State is incomplete or incompatible";
        if (target.platform!=current.platform || target.mode!=current.mode || target.directory!=directory || stateHardwareConfig(target.config)!=stateHardwareConfig(current.config)) return "State machine settings do not match; restore the saved settings first";
        for (const auto& path:target.media) if (!path.empty() && access(path.c_str(),R_OK)) return "State disk is missing";
        if (!hardDiskPath.empty() && access(hardDiskPath.c_str(),R_OK)) return "State disk is missing";
        if (!target.media[0].empty() && target.media[0]==target.media[1] && target.indices[0]==target.indices[1]) return "State contains duplicate disks";
        std::string recovery=directory+"/states/recovery";
        mkdir(recovery.c_str(),0700);
        if (!saveSession(recovery)) return "Cannot protect the current session; load cancelled";
        SnapshotMeta previous=current;
        bool ok=installMedia(target) && core->loadState((folder+"/machine.bin").c_str()) && midiSynth.load(folder+"/midi.bin");
        if (!ok) {
            if (!installMedia(previous) || !core->loadState((recovery+"/machine.bin").c_str()) || !midiSynth.load(recovery+"/midi.bin")) {
                stateBroken=true; paused.store(true);
                return "State recovery failed; restart the game";
            }
        }
        if (ok) {
            std::lock_guard<std::mutex> lock(statusMutex);
            driveMedia=target.media;
            for (size_t i=0;i<2;++i) driveIndices[i]=target.indices[i];
        }
        for (const auto* name:{"machine.bin","host.bin","midi.bin"}) unlink((recovery+"/"+name).c_str());
        rmdir(recovery.c_str());
        return ok ? "" : "State load failed; current session restored";
    };
    try {
        configuration=readMachineConfig(directory.c_str());
        for (const auto& setting:configuration) current.config+=setting.first+"="+setting.second+"\n";
        if (!hardDiskPath.empty()) current.config+="host_hard_disk="+hardDiskPath+"\n";
        outputVolume = machineInt(readMachineConfig(directory.c_str()),"output_volume",100,0,100);
        if (machineText(configuration,"midi_enabled","OFF")=="ON") {
            if (!midiSynth.open(machineText(configuration,"midi_soundfont",""),machineInt(configuration,"midi_volume",80,0,100)))
                throw std::runtime_error("MIDI soundfont cannot be loaded; import a valid SF2 or turn MIDI off");
            message("MIDI synthesizer ready");
        }
        library = dlopen(platform == 88 ? "libpc88.so" : "libpc98.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        auto entry = reinterpret_cast<const NecCore* (*)()>(dlsym(library, "nec_core"));
        if (!entry) throw std::runtime_error("Native core entry is missing");
        core = entry();
        message("Starting core...");
        // PC-88 needs both internal disk selections installed before the boot reset.
        if (!core->open(directory.c_str(), platform == 88 ? "" : disk.c_str(), platform == 88 ? "" : secondDisk.c_str(), mode, {video, audio, message, [](uint8_t byte) {
                if (midiSynth.enabled()) { if (!midiReceived) { midiReceived=true; message("MIDI hardware output active"); } midiSynth.write(byte); }
            }})) {
            message("Core initialization failed. Check the selected platform, ROMs and disk.");
            throw std::runtime_error("Core initialization failed");
        }
        if (platform == 88) {
            if (firstIndex < 0 || firstIndex >= 64 || secondIndex < 0 || secondIndex >= 64 ||
                    (!secondDisk.empty() && disk == secondDisk && firstIndex == secondIndex) ||
                    (!disk.empty() && !core->mount(0, disk.c_str(), firstIndex)) ||
                    (!secondDisk.empty() && !core->mount(1, secondDisk.c_str(), secondIndex)))
                throw std::runtime_error("Boot disk selection failed");
            core->reset();
        }
        if (!core->enableMouse(mouseEnabled)) throw std::runtime_error("Mouse connection failed");
        message(frameReceived.load() ? "Running" : "Core started; waiting for video");
        {
            std::lock_guard<std::mutex> lock(statusMutex);
            driveMedia = {hardDiskPath.empty() ? disk : "", secondDisk};
            driveIndices = {firstIndex, secondIndex};
        }
        auto next = std::chrono::steady_clock::now();
        auto lastFlush = next;
        while (!stopping.load()) {
            std::deque<Command> pending;
            {
                std::lock_guard<std::mutex> lock(commandMutex);
                pending.swap(commands);
            }
            for (const auto& command : pending) {
                switch (command.type) {
                case Key:
                    if (command.a >= 0 && command.a < int(held.size())) {
                        if (!paused.load()) requestEdge(keyChanges[command.a], held[command.a], command.b != 0);
                    }
                    break;
                case Mouse:
                    if (!paused.load() && mouseEnabled) {
                        motionX += command.a; motionY += command.b;
                        for (size_t i = 0; i < buttonChanges.size(); ++i)
                            requestEdge(buttonChanges[i], (buttons & (1u << i)) != 0, (command.c & (1u << i)) != 0);
                    }
                    break;
                case MouseMode:
                    releaseKeys();
                    if (core->enableMouse(command.a != 0)) mouseEnabled = command.a != 0;
                    else message("Mouse connection failed");
                    break;
                case Mount: {
                    if (command.a < 0 || command.a > 1 || command.b < 0 || command.b >= 64) { message("Disk mount failed"); break; }
                    std::string previous;
                    int previousIndex;
                    bool occupied;
                    {
                        std::lock_guard<std::mutex> lock(statusMutex);
                        previous = driveMedia[command.a]; previousIndex = driveIndices[command.a];
                        occupied = !command.path.empty() && driveMedia[1 - command.a] == command.path && driveIndices[1 - command.a] == command.b;
                    }
                    if (occupied) { message("Disk already in other drive"); break; }
                    if (core->mount(command.a, command.path.c_str(), command.b)) {
                        {
                            std::lock_guard<std::mutex> lock(statusMutex);
                            driveMedia[command.a] = command.path; driveIndices[command.a] = command.b;
                        }
                        message(command.path.empty() ? "Disk ejected" : "Disk mounted");
                    } else if (platform == 88 && !command.path.empty() && !core->mount(command.a, previous.c_str(), previousIndex)) {
                        { std::lock_guard<std::mutex> lock(statusMutex); driveMedia[command.a].clear(); driveIndices[command.a] = 0; }
                        message("Disk mount failed; drive empty");
                    } else message(command.path.empty() ? "Disk eject failed" : "Disk mount failed");
                    break;
                }
                case Flush: message(core->flush() ? "Disk changes flushed" : "Disk write failed"); break;
                case Reset: releaseKeys(); midiSynth.reset(); core->reset(); message("Reset"); break;
                case Pause:
                    releaseKeys(); paused.store(true); closeAudio();
                    message(core->flush() ? "Paused" : "Paused; disk write failed"); break;
                case Resume: if (!stateBroken) paused.store(false); audioRetry = {}; next = std::chrono::steady_clock::now(); message("Running"); break;
                case ReleaseKeys: releaseKeys(); break;
                case ReopenAudio: closeAudio(); audioRetry = {}; audioDisconnected.store(false); break;
                case SaveState: case LoadState: {
                    std::string result;
                    try {
                        releaseKeys(); closeAudio(); audioRetry={};
                        if (stateBroken) result="State recovery failed; restart the game";
                        else if (!paused.load()) result="Pause the game before using a state slot";
                        else if (command.path.compare(0,directory.size()+8,directory+"/states/")!=0 || command.path.find("..")!=std::string::npos) result="Invalid state slot path";
                        else if (command.type==SaveState) { if (!saveSession(command.path)) result="State save failed; previous slot preserved"; }
                        else result=restoreSession(command.path);
                    } catch (const std::exception&) { result="State operation failed"; }
                    if (command.result) command.result->set_value(result);
                    message(result.empty() ? (command.type==SaveState ? "State saved" : "State loaded") : result.c_str());
                    next=std::chrono::steady_clock::now(); break;
                }
                }
            }
            if (paused.load()) {
                std::unique_lock<std::mutex> lock(commandMutex);
                wake.wait_for(lock, std::chrono::milliseconds(100), [] { return stopping.load() || !commands.empty(); });
                continue;
            }
            auto now = std::chrono::steady_clock::now();
            if (now < next) {
                std::unique_lock<std::mutex> lock(commandMutex);
                wake.wait_until(lock, next, [] { return stopping.load() || !commands.empty(); });
                continue;
            }
            for (size_t i = 0; i < held.size(); ++i) {
                bool state = sampleEdge(keyChanges[i], keyHoldFrames[i], held[i], 2, 1);
                if (state != held[i]) { held[i] = state; core->key(i, state); }
            }
            for (size_t i = 0; i < buttonChanges.size(); ++i) {
                // Slow guest polling must see complete clicks even when the physical tap is brief.
                bool state = sampleEdge(buttonChanges[i], buttonHoldFrames[i], (buttons & (1u << i)) != 0, 6, 2);
                if (state) buttons |= 1u << i;
                else buttons &= ~(1u << i);
            }
            core->mouse(motionX, motionY, buttons);
            motionX = motionY = 0;
            core->frame();
            now = std::chrono::steady_clock::now();
            if (now - lastFlush > std::chrono::seconds(2)) {
                if (!core->flush()) message("Disk write failed");
                lastFlush = now;
            }
            double fps = core->fps();
            if (fps < 10 || fps > 200) fps = 60;
            next += std::chrono::microseconds(static_cast<int64_t>(1000000.0 / fps));
            if (now - next > std::chrono::milliseconds(250)) next = now;
            std::unique_lock<std::mutex> lock(commandMutex);
            wake.wait_until(lock, next, [] { return stopping.load() || !commands.empty(); });
        }
        releaseKeys();
        bool saved = core->flush();
        core->close();
        core = nullptr;
        message(saved ? "Stopped; disk changes flushed" : "Stopped; disk write failed");
    } catch (const std::exception& error) {
        message(error.what());
        if (core) core->close();
    }
    closeAudio();
    midiSynth.close();
    if (library) dlclose(library);
    { std::lock_guard<std::mutex> lock(commandMutex);
      workerRunning.store(false);
      for (auto& command:commands) if (command.result) command.result->set_value("Game session has stopped");
      commands.clear(); }
}
std::string string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* text = env->GetStringUTFChars(value, nullptr);
    std::string copy = text ? text : "";
    if (text) env->ReleaseStringUTFChars(value, text);
    return copy;
}
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_necmobile_Native_start(JNIEnv* env, jclass, jint platform, jstring dir, jstring disk, jstring secondDisk, jint mode, jint firstIndex, jint secondIndex, jboolean mouseEnabled) {
    if (worker.joinable()) return false;
    stopping.store(false); paused.store(true); frameReceived.store(false);
    workerRunning.store(true);
    { std::lock_guard<std::mutex> lock(commandMutex); commands.clear(); }
    { std::lock_guard<std::mutex> lock(statusMutex); driveMedia = {}; driveIndices = {}; }
    worker = std::thread(run, platform, string(env, dir), string(env, disk), string(env, secondDisk), mode, firstIndex, secondIndex, mouseEnabled != 0);
    return true;
}
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_surface(JNIEnv* env, jclass, jobject surface) {
    std::lock_guard<std::mutex> lock(windowMutex);
    if (window) ANativeWindow_release(window);
    window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    videoWidth = videoHeight = 0;
}
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_key(JNIEnv*, jclass, jint key, jboolean down) { enqueue({Key, key, down}); }
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_mouse(JNIEnv*, jclass, jint x, jint y, jint buttons) { enqueue({Mouse, x, y, buttons}); }
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_mouseMode(JNIEnv*, jclass, jboolean enabled) { enqueue({MouseMode, enabled}); }
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_pause(JNIEnv*, jclass, jboolean value) { enqueue({value ? Pause : Resume}); }
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_flush(JNIEnv*, jclass) { enqueue({Flush}); }
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_audioChanged(JNIEnv*, jclass) { enqueue({ReopenAudio}); }
extern "C" JNIEXPORT jboolean JNICALL Java_org_necmobile_Native_checkSoundFont(JNIEnv* env,jclass,jstring path) {
    try { return MidiSynth::validate(string(env,path)); } catch (...) { return false; }
}
extern "C" JNIEXPORT jstring JNICALL Java_org_necmobile_Native_state(JNIEnv* env,jclass,jstring folder,jboolean load) {
    auto result=std::make_shared<std::promise<std::string>>(); auto future=result->get_future();
    { std::lock_guard<std::mutex> lock(commandMutex);
      if (!workerRunning.load() || stopping.load()) return env->NewStringUTF("Game session has stopped");
      commands.push_back({load ? LoadState : SaveState,0,0,0,string(env,folder),result}); }
    wake.notify_one();
    return env->NewStringUTF(future.get().c_str());
}
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_reset(JNIEnv*, jclass) { enqueue({Reset}); }
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_releaseKeys(JNIEnv*, jclass) { enqueue({ReleaseKeys}); }
extern "C" JNIEXPORT jstring JNICALL Java_org_necmobile_Native_disk(JNIEnv* env, jclass, jint drive) {
    std::lock_guard<std::mutex> lock(statusMutex);
    return env->NewStringUTF(drive >= 0 && drive < 2 ? driveMedia[drive].c_str() : "");
}
extern "C" JNIEXPORT jint JNICALL Java_org_necmobile_Native_diskIndex(JNIEnv*, jclass, jint drive) {
    std::lock_guard<std::mutex> lock(statusMutex);
    return drive >= 0 && drive < 2 ? driveIndices[drive] : 0;
}
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_mount(JNIEnv* env, jclass, jint drive, jstring path, jint index) { enqueue({Mount, drive, index, 0, string(env, path)}); }
extern "C" JNIEXPORT jstring JNICALL Java_org_necmobile_Native_status(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(statusMutex);
    return env->NewStringUTF(status.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_org_necmobile_Native_stop(JNIEnv*, jclass) {
    stopping.store(true); wake.notify_all();
    if (worker.joinable()) worker.join();
}
