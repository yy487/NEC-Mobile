#import "NECBridge.h"
#include "host_engine.h"
#include "dos_disk.h"
#include <optional>

static NSString *text(const std::string& value) { return [[NSString alloc] initWithBytes:value.data() length:value.size() encoding:NSUTF8StringEncoding] ?: @"内核返回了非 UTF-8 消息"; }
static std::string bytes(NSData *data) { return data.length ? std::string(static_cast<const char *>(data.bytes), data.length) : std::string(); }
static std::vector<NecDos::File> diskFiles(NSArray<NSDictionary *> *files) {
    std::vector<NecDos::File> result;
    for (NSDictionary *file in files) result.push_back({[file[@"host"] UTF8String], bytes(file[@"name"])});
    return result;
}
@implementation NECBridge
+ (NSString *)start:(NSInteger)platform directory:(NSString *)directory first:(NSString *)first second:(NSString *)second mode:(NSInteger)mode firstIndex:(NSInteger)firstIndex secondIndex:(NSInteger)secondIndex {
    try { return NecEngine::start(int(platform),directory.UTF8String,first.UTF8String,second.UTF8String,int(mode),int(firstIndex),int(secondIndex),true) ? @"" : @"已有运行中的内核"; }
    catch (const std::exception& error) { return text(error.what()); }
}
+ (void)stop { NecEngine::stop(); }
+ (void)pause:(BOOL)value { NecEngine::pause(value); }
+ (void)key:(NSInteger)code down:(BOOL)down { NecEngine::key(int(code),down); }
+ (void)mouseX:(NSInteger)x y:(NSInteger)y buttons:(NSInteger)buttons { NecEngine::mouse(int(x),int(y),int(buttons)); }
+ (void)releaseKeys { NecEngine::releaseKeys(); }
+ (void)reset { NecEngine::reset(); }
+ (void)flush { NecEngine::flush(); }
+ (void)audioChanged { NecEngine::audioChanged(); }
+ (NSInteger)startupState { return NecEngine::startup(); }
+ (NSUInteger)videoGeometry { return NecEngine::geometry(); }
+ (NSString *)statusText { return text(NecEngine::statusText()); }
+ (NSString *)startupError { return text(NecEngine::errorText()); }
+ (NSString *)machineInfo { return text(NecEngine::machineText()); }
+ (NSString *)audioInfo { return text(NecEngine::audioText()); }
+ (NSString *)takeWarning { return text(NecEngine::takeWarning()); }
+ (BOOL)mouseActive { return NecEngine::mouseActive(); }
+ (NSString *)disk:(NSInteger)drive { return text(NecEngine::disk(int(drive))); }
+ (NSInteger)diskIndex:(NSInteger)drive { return NecEngine::diskIndex(int(drive)); }
+ (NSString *)mount:(NSInteger)drive path:(NSString *)path index:(NSInteger)index { return text(NecEngine::mount(int(drive),path.UTF8String,int(index))); }
+ (NSString *)state:(NSString *)folder load:(BOOL)load { return text(NecEngine::state(folder.UTF8String,load)); }
+ (BOOL)validSoundFont:(NSString *)path { return NecEngine::validSoundFont(path.UTF8String); }
+ (NSString *)inspectDisk:(NSString *)path { return text(NecDos::inspect(path.UTF8String)); }
+ (NSData *)dosAutoexec:(NSString *)path {
    try { auto result=NecDos::autoexec(path.UTF8String); return [NSData dataWithBytes:result.data() length:result.size()]; }
    catch (...) { return [NSData data]; }
}
+ (NSString *)buildDos:(NSString *)source target:(NSString *)target files:(NSArray<NSDictionary *> *)files startup:(NSData *)startup autoexec:(NSData *)autoexec {
    try { NecDos::build(source.UTF8String,target.UTF8String,diskFiles(files),bytes(startup),bytes(autoexec)); return @""; }
    catch (const std::exception& error) { return text(error.what()); }
}
+ (NSString *)prepareFloppy:(NSString *)source target:(NSString *)target files:(NSArray<NSDictionary *> *)files autoexec:(NSData *)autoexec config:(NSData *)config label:(NSData *)label {
    try { NecDos::floppy(source.UTF8String,target.UTF8String,autoexec ? std::optional<std::string>(bytes(autoexec)) : std::nullopt,
            config ? std::optional<std::string>(bytes(config)) : std::nullopt,diskFiles(files), label ? std::optional<std::string>(bytes(label)) : std::nullopt); return @""; }
    catch (const std::exception& error) { return text(error.what()); }
}
@end
