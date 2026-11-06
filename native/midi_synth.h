#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>

struct tsf;
class MidiSynth {
public:
    ~MidiSynth();
    bool open(const std::string& path, int volume);
    void close();
    bool enabled() const { return font != nullptr; }
    void reset();
    void write(uint8_t byte);
    void mix(const int16_t* input, int16_t* output, size_t frames, unsigned rate);
    bool save(const std::string& path) const;
    bool load(const std::string& path);
    static bool validate(const std::string& path);
private:
    struct Channel {
        std::array<uint8_t,128> controls{}, notes{}, held{};
        uint16_t pitch = 8192;
        uint8_t program = 0;
    };
    tsf* font = nullptr;
    std::array<Channel,16> channels{};
    uint8_t status = 0, position = 0, first = 0;
    bool exclusive = false;
    std::vector<uint8_t> sysex;
    std::vector<int16_t> pcm;
    unsigned sampleRate = 0;
    int volume = 80;
    void preset(unsigned channel);
    void event(uint8_t command, uint8_t a, uint8_t b);
};
