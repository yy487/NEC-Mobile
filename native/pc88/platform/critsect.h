#pragma once
#include <mutex>
class CriticalSection {
    std::recursive_mutex mutex;
public:
    void lock() { mutex.lock(); }
    void unlock() { mutex.unlock(); }
    class Lock {
        CriticalSection& section;
    public:
        explicit Lock(CriticalSection& s) : section(s) { section.lock(); }
        ~Lock() { section.unlock(); }
    };
};
