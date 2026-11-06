#include "midi_synth.h"
#define TSF_IMPLEMENTATION
#include "../third_party/tinysoundfont/tsf.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <map>

namespace {
uint16_t u16(const uint8_t* p) { return p[0] | unsigned(p[1]) << 8; }
uint32_t u32(const uint8_t* p) { return u16(p) | uint32_t(u16(p + 2)) << 16; }
// The upstream loader expects valid SF2 table indices. Check them before parsing.
bool readFont(const std::string& path, std::vector<uint8_t>& data, size_t& samples) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    auto size = file.tellg();
    if (size < 12 || size > 64 * 1024 * 1024) return false;
    data.resize(size_t(size)); file.seekg(0);
    if (!file.read(reinterpret_cast<char*>(data.data()), size)) return false;
    if (memcmp(data.data(), "RIFF", 4) || memcmp(data.data()+8, "sfbk", 4) || u32(data.data()+4) != data.size()-8) return false;
    std::map<std::string,std::pair<const uint8_t*,size_t>> tables;
    samples = 0;
    for (size_t at=12; at<data.size();) {
        if (data.size()-at<8) return false;
        size_t n=u32(data.data()+at+4), end=at+8+n;
        if (end>data.size() || (n&1)) return false;
        if (!memcmp(data.data()+at,"LIST",4)) {
            if (n<4) return false;
            bool pdta=!memcmp(data.data()+at+8,"pdta",4), sdta=!memcmp(data.data()+at+8,"sdta",4);
            for (size_t child=at+12; child<end;) {
                if (end-child<8) return false;
                size_t count=u32(data.data()+child+4), next=child+8+count;
                if (next>end) return false;
                std::string id(reinterpret_cast<const char*>(data.data()+child),4);
                if (pdta && !tables.emplace(id,std::make_pair(data.data()+child+8,count)).second) return false;
                if (sdta && id=="smpl") { if (samples || count%2) return false; samples=count/2; }
                child=next+(count&1);
                if (child>end) return false;
            }
        }
        at=end;
    }
    const std::map<std::string,size_t> sizes{{"phdr",38},{"pbag",4},{"pmod",10},{"pgen",4},{"inst",22},{"ibag",4},{"imod",10},{"igen",4},{"shdr",46}};
    for (auto& item:sizes) {
        auto t=tables.find(item.first);
        if (t==tables.end() || !t->second.second || t->second.second%item.second) return false;
    }
    auto count=[&](const char* key) { return tables[key].second/sizes.at(key); };
    if (!samples || count("phdr")<2 || count("phdr")>2048 || count("inst")<2 || count("shdr")<2) return false;
    auto indices=[&](const char* from, size_t offset, const char* to) {
        auto t=tables[from]; size_t stride=sizes.at(from), prev=0;
        for (size_t at=0; at<t.second; at+=stride) {
            size_t index=u16(t.first+at+offset);
            if (index<prev || index>=count(to)) return false;
            prev=index;
        }
        return true;
    };
    if (!indices("phdr",24,"pbag") || !indices("pbag",0,"pgen") || !indices("pbag",2,"pmod") ||
        !indices("inst",20,"ibag") || !indices("ibag",0,"igen") || !indices("ibag",2,"imod")) return false;
    uint64_t expanded=0;
    std::vector<size_t> instrumentRegions(count("inst"),0);
    for (size_t inst=0;inst+1<count("inst");++inst) {
        auto hdr=tables["inst"].first; auto bags=tables["ibag"].first; auto gens=tables["igen"].first;
        size_t begin=u16(hdr+inst*22+20), end=u16(hdr+(inst+1)*22+20);
        for (size_t b=begin;b<end;++b) for (size_t g=u16(bags+b*4);g<u16(bags+(b+1)*4);++g)
            if (u16(gens+g*4)==53) ++instrumentRegions[inst];
    }
    for (auto key:{"pgen","igen"}) {
        auto t=tables[key];
        for (size_t at=0; at<t.second; at+=4) {
            unsigned op=u16(t.first+at), value=u16(t.first+at+2);
            if ((op==41 && value>=count("inst")-1) || (op==53 && value>=count("shdr")-1)) return false;
            if (op==41 && std::string(key)=="pgen") { expanded+=instrumentRegions[value]; if (expanded>131072) return false; }
        }
    }
    auto sh=tables["shdr"];
    for (size_t at=0; at+46<sh.second; at+=46) {
        auto p=sh.first+at;
        uint32_t start=u32(p+20), end=u32(p+24), loopStart=u32(p+28), loopEnd=u32(p+32), rate=u32(p+36);
        if (start>=end || end>samples || loopStart>loopEnd || loopEnd>samples || rate<400 || rate>384000 || (u16(p+44)&0x8000)) return false;
    }
    return true;
}
tsf* loadFont(const std::string& path) {
    std::vector<uint8_t> data;
    size_t samples=0;
    if (!readFont(path,data,samples)) return nullptr;
    tsf* font=tsf_load_memory(data.data(),int(data.size()));
    if (!font) return nullptr;
    // Sample offsets can also be modified by generators.
    for (int p=0;p<font->presetNum;++p) for (int r=0;r<font->presets[p].regionNum;++r) {
        auto& region=font->presets[p].regions[r];
        if (region.offset>=region.end || region.end>samples || region.loop_start>region.loop_end || region.loop_end>=region.end) {
            tsf_close(font); return nullptr;
        }
    }
    return font;
}
}

MidiSynth::~MidiSynth() { close(); }
bool MidiSynth::validate(const std::string& path) { tsf* f=loadFont(path); if (!f) return false; tsf_close(f); return true; }
bool MidiSynth::open(const std::string& path, int gain) {
    close(); font=loadFont(path); volume=std::clamp(gain,0,100);
    if (!font) return false;
    if (!tsf_set_max_voices(font,128)) { close(); return false; }
    reset(); return true;
}
void MidiSynth::close() { if (font) tsf_close(font); font=nullptr; sampleRate=0; }
void MidiSynth::preset(unsigned ch) {
    if (!font) return;
    tsf_channel_set_bank(font,ch,channels[ch].controls[0]*128+channels[ch].controls[32]);
    if (!tsf_channel_set_presetnumber(font,ch,channels[ch].program,ch==9))
        tsf_channel_set_presetindex(font,ch,0);
}
void MidiSynth::reset() {
    status=position=first=0; exclusive=false; sysex.clear();
    channels={};
    if (font) tsf_reset(font);
    for (unsigned ch=0;ch<16;++ch) {
        channels[ch].pitch=8192;
        channels[ch].controls[7]=100; channels[ch].controls[10]=64; channels[ch].controls[11]=127;
        channels[ch].controls[100]=channels[ch].controls[101]=127; channels[ch].controls[6]=2;
        preset(ch);
        if (font) {
            tsf_channel_midi_control(font,ch,7,100);
            tsf_channel_midi_control(font,ch,10,64);
            tsf_channel_midi_control(font,ch,11,127);
        }
    }
}
void MidiSynth::event(uint8_t command,uint8_t a,uint8_t b) {
    unsigned ch=command&15; auto& c=channels[ch];
    switch (command&0xf0) {
    case 0x80: case 0x90:
        if ((command&0xf0)==0x90 && b) {
            c.notes[a]=b; c.held[a]=1;
            if (font) tsf_channel_note_on(font,ch,a,b/127.f);
        } else {
            c.held[a]=0; if (c.controls[64]<64) c.notes[a]=0;
            if (font) tsf_channel_note_off(font,ch,a);
        }
        break;
    case 0xb0:
        c.controls[a]=b;
        if (a==64 && b<64) for (size_t n=0;n<128;++n) if (!c.held[n]) c.notes[n]=0;
        if (a==120 || a==123) { if (a==120 || c.controls[64]<64) c.notes.fill(0); c.held.fill(0); }
        if (a==121) {
            c.controls[11]=127; c.controls[64]=0; c.pitch=8192;
            for (size_t n=0;n<128;++n) if (!c.held[n]) c.notes[n]=0;
        }
        if (font) tsf_channel_midi_control(font,ch,a,b);
        if (a==0 || a==32) preset(ch);
        break;
    case 0xc0: c.program=a; preset(ch); break;
    case 0xe0: c.pitch=a+(uint16_t(b)<<7); if (font) tsf_channel_set_pitchwheel(font,ch,c.pitch); break;
    }
}
void MidiSynth::write(uint8_t byte) {
    if (byte>=0xf8) { if (byte==0xff) reset(); return; }
    if (exclusive) {
        if (byte==0xf7) {
            bool gm=sysex.size()>=4 && sysex[0]==0x7e && sysex[2]==9;
            bool gs=sysex.size()>=8 && sysex[0]==0x41 && sysex[3]==0x12 && sysex[4]==0x40 && sysex[5]==0 && sysex[6]==0x7f;
            bool xg=sysex.size()>=7 && sysex[0]==0x43 && sysex[2]==0x4c && sysex[3]==0 && sysex[4]==0 && sysex[5]==0x7e;
            exclusive=false; if (gm || gs || xg) reset(); return;
        }
        if (!(byte&0x80)) { if (sysex.size()<256) sysex.push_back(byte); return; }
        exclusive=false;
    }
    if (byte&0x80) {
        position=0; status=byte;
        if (byte==0xf0) { exclusive=true; sysex.clear(); status=0; }
        return;
    }
    if (status<0x80 || status>=0xf0) return;
    if ((status&0xf0)==0xc0 || (status&0xf0)==0xd0) { event(status,byte,0); return; }
    if (!position) { first=byte; position=1; }
    else { event(status,first,byte); position=0; }
}
void MidiSynth::mix(const int16_t* input,int16_t* output,size_t frames,unsigned rate) {
    if (!font) { std::copy(input,input+frames*2,output); return; }
    if (sampleRate!=rate) { sampleRate=rate; tsf_set_output(font,TSF_STEREO_INTERLEAVED,int(rate),-8.f); tsf_set_volume(font,volume/100.f*0.4f); }
    pcm.resize(frames*2); tsf_render_short(font,pcm.data(),int(frames),0);
    for (size_t i=0;i<frames*2;++i) output[i]=int16_t(std::clamp(int(input[i])+int(pcm[i]),-32768,32767));
}
bool MidiSynth::save(const std::string& path) const {
    std::ofstream f(path,std::ios::binary|std::ios::trunc);
    f.write("NECMIDI1",8); f.write(reinterpret_cast<const char*>(channels.data()),sizeof(channels));
    uint8_t parser[]{status,position,first,uint8_t(exclusive)};
    f.write(reinterpret_cast<const char*>(parser),4);
    uint16_t size=uint16_t(sysex.size()); f.write(reinterpret_cast<const char*>(&size),2);
    f.write(reinterpret_cast<const char*>(sysex.data()),size); f.close(); return bool(f);
}
bool MidiSynth::load(const std::string& path) {
    std::ifstream f(path,std::ios::binary); char magic[8]; std::array<Channel,16> saved{}; uint8_t parser[4]; uint16_t size=0;
    if (!f.read(magic,8) || memcmp(magic,"NECMIDI1",8) || !f.read(reinterpret_cast<char*>(saved.data()),sizeof(saved)) ||
        !f.read(reinterpret_cast<char*>(parser),4) || !f.read(reinterpret_cast<char*>(&size),2) || size>256 || parser[1]>1 || parser[2]>127 || parser[3]>1) return false;
    std::vector<uint8_t> exclusiveBytes(size);
    if (!f.read(reinterpret_cast<char*>(exclusiveBytes.data()),size) || f.peek()!=EOF) return false;
    for (auto& c:saved) {
        if (c.pitch>16383 || c.program>127) return false;
        for (size_t n=0;n<128;++n) if (c.controls[n]>127 || c.notes[n]>127 || c.held[n]>1) return false;
    }
    reset(); channels=saved;
    if (font) for (unsigned ch=0;ch<16;++ch) {
        preset(ch);
        for (int controller:{7,10,11,64,100,101,6,38}) tsf_channel_midi_control(font,ch,controller,channels[ch].controls[controller]);
        tsf_channel_set_pitchwheel(font,ch,channels[ch].pitch);
        for (unsigned n=0;n<128;++n) if (channels[ch].notes[n]) {
            tsf_channel_note_on(font,ch,n,channels[ch].notes[n]/127.f);
            if (!channels[ch].held[n]) tsf_channel_note_off(font,ch,n);
        }
    }
    status=parser[0]; position=parser[1]; first=parser[2]; exclusive=parser[3]!=0; sysex=std::move(exclusiveBytes);
    return true;
}
