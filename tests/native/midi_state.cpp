#include "../../native/midi_synth.h"
#include "../../native/snapshot_meta.h"
#include "../../native/state_file.h"
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <iostream>
#include <stdexcept>
#undef assert
#define assert(expression) do { if (!(expression)) throw std::runtime_error(#expression); } while (0)

int main(int argc,char** argv) {
    assert(argc==3);
    std::string font=argv[1], root=argv[2];
    assert(MidiSynth::validate(font));
    std::vector<uint8_t> bytes;
    assert(readStateFile(font,bytes));
    assert(writeStateFile(root+"/truncated.sf2",bytes.data(),bytes.size()/2));
    assert(!MidiSynth::validate(root+"/truncated.sf2"));
    // MIDI running status survives interleaved realtime clock messages.
    MidiSynth synth; assert(synth.open(font,100));
    for (uint8_t byte:{0xc0,0,0x90,60,0xf8,110,64,110}) synth.write(byte);
    std::vector<int16_t> silence(4096), output(4096);
    synth.mix(silence.data(),output.data(),2048,48000);
    assert(std::any_of(output.begin(),output.end(),[](int16_t n) { return n!=0; }));
    assert(synth.save(root+"/midi.bin"));
    synth.reset(); synth.mix(silence.data(),output.data(),2048,44100);
    // TinySoundFont intentionally fades reset voices for 10 ms.
    synth.mix(silence.data(),output.data(),2048,44100);
    assert(std::all_of(output.begin(),output.end(),[](int16_t n) { return n==0; }));
    assert(synth.load(root+"/midi.bin"));
    synth.mix(silence.data(),output.data(),2048,44100);
    assert(std::any_of(output.begin(),output.end(),[](int16_t n) { return n!=0; }));
    assert(readStateFile(root+"/midi.bin",bytes));
    assert(writeStateFile(root+"/bad-midi.bin",bytes.data(),13));
    assert(!synth.load(root+"/bad-midi.bin"));
    SnapshotMeta meta; meta.platform=88; meta.directory="/game"; meta.config="midi_enabled=ON\n";
    meta.media={"/game/media/test.d88","/game/media/test.d88"}; meta.indices={0,1};
    assert(meta.write(root+"/host.bin")); SnapshotMeta loaded;
    assert(loaded.read(root+"/host.bin") && loaded.media==meta.media && loaded.indices==meta.indices && loaded.config==meta.config);
    assert(readStateFile(root+"/host.bin",bytes)); bytes[19]=0xff; bytes[20]=0xff;
    assert(writeStateFile(root+"/bad-host.bin",bytes.data(),bytes.size()));
    assert(!loaded.read(root+"/bad-host.bin"));
    assert(stateHardwareConfig("midi_enabled=ON\nmidi_soundfont=old.sf2\nmidi_volume=80\nnp2kai_volume_F=30\n")==
           stateHardwareConfig("midi_enabled=ON\nmidi_soundfont=new.sf2\nmidi_volume=60\nnp2kai_volume_F=60\n"));
    assert(stateHardwareConfig("m88_clock=40\n")!=stateHardwareConfig("m88_clock=80\n"));
    std::cout << "MIDI PCM, running status, stream-rate change, saved notes, invalid input and slot metadata passed\n";
}
