#import "NECBridge.h"
#import <CoreFoundation/CFStringEncodingExt.h>
#include <zlib.h>
#include <array>
#include <algorithm>
#include <vector>
#include <fstream>
#include <filesystem>
#include <stdexcept>
#include <unistd.h>
#include <fcntl.h>

namespace {
void requireArchive(bool value, const char *message) { if (!value) throw std::runtime_error(message); }
uint16_t u16(const uint8_t *p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
uint32_t u32(const uint8_t *p) { return u16(p) | uint32_t(u16(p+2)) << 16; }
NSString *archiveError(const std::exception& error) { return [NSString stringWithUTF8String:error.what()] ?: @"文件处理失败"; }
std::array<uint8_t, 46> readHeader(std::ifstream& file, size_t count) {
    std::array<uint8_t,46> data{}; file.read(reinterpret_cast<char *>(data.data()),count);
    requireArchive(file.gcount() == std::streamsize(count), "ZIP 文件已截断"); return data;
}
void gzipFile(NSString *source, NSString *target, bool decompress) {
    requireArchive(![source isEqualToString:target] && !std::filesystem::exists(target.UTF8String), "输出文件已存在");
    FILE *plain = fopen((decompress ? target : source).UTF8String, decompress ? "wb" : "rb");
    requireArchive(plain != nullptr, "无法打开临时文件");
    gzFile packed = gzopen((decompress ? source : target).UTF8String, decompress ? "rb" : "wb1");
    if (!packed) { fclose(plain); if (decompress) unlink(target.UTF8String); throw std::runtime_error("无法打开压缩文件"); }
    std::array<uint8_t,65536> buffer{}; bool good = true; uint64_t total = 0;
    while (good) {
        int count = decompress ? gzread(packed,buffer.data(),unsigned(buffer.size())) : int(fread(buffer.data(),1,buffer.size(),plain));
        if (count < 0) { good = false; break; }
        if (!count) break;
        total += count;
        if (total > 512ull * 1024 * 1024 && decompress) { good = false; break; }
        good = decompress ? fwrite(buffer.data(),1,size_t(count),plain) == size_t(count) : gzwrite(packed,buffer.data(),unsigned(count)) == count;
    }
    if (ferror(plain)) good = false;
    if (decompress && (fflush(plain) || fsync(fileno(plain)))) good = false;
    if (fclose(plain)) good = false;
    if (gzclose(packed) != Z_OK) good = false;
    if (!decompress && good) { int fd = open(target.UTF8String,O_RDONLY); if (fd < 0 || fsync(fd)) good = false; if (fd >= 0) close(fd); }
    if (!good) { unlink(target.UTF8String); throw std::runtime_error("压缩文件处理失败，请检查文件和剩余空间"); }
}
void unzip(NSString *source, NSString *target) {
    std::ifstream file(source.UTF8String,std::ios::binary|std::ios::ate); requireArchive(bool(file), "无法读取 ZIP");
    uint64_t length = uint64_t(file.tellg()); requireArchive(length >= 22, "ZIP 文件过短");
    size_t tailLength = size_t(std::min<uint64_t>(length,65557)); std::vector<uint8_t> tail(tailLength);
    file.seekg(std::streamoff(length-tailLength)); file.read(reinterpret_cast<char *>(tail.data()),tail.size());
    size_t end = tail.size();
    for (size_t i = tail.size()-22;;i--) { if (u32(tail.data()+i)==0x06054b50 && i+22+u16(tail.data()+i+20)==tail.size()) {end=i;break;} if(i==0)break; }
    requireArchive(end < tail.size(), "找不到 ZIP 目录");
    auto *e = tail.data()+end; unsigned entries=u16(e+10); uint64_t central=u32(e+16);
    requireArchive(!u16(e+4) && !u16(e+6) && entries==u16(e+8) && entries<=4096 && central<length, "不支持分卷或过大的 ZIP");
    std::filesystem::path root(target.UTF8String); requireArchive(!std::filesystem::exists(root), "解压目录已存在");
    std::filesystem::create_directories(root); uint64_t expanded=0;
    try {
        for(unsigned entry=0;entry<entries;entry++) {
            file.seekg(std::streamoff(central)); auto header=readHeader(file,46); requireArchive(u32(header.data())==0x02014b50,"ZIP 目录无效");
            unsigned flags=u16(header.data()+8),method=u16(header.data()+10), nameLength=u16(header.data()+28);
            uint64_t packed=u32(header.data()+20), size=u32(header.data()+24), local=u32(header.data()+42);
            requireArchive(!(flags&1) && (method==0 || method==8) && packed!=0xffffffff && size!=0xffffffff && local!=0xffffffff,"不支持加密、ZIP64 或此压缩格式");
            requireArchive(nameLength>0 && nameLength<=4096 && ((u32(header.data()+38)>>16)&0170000)!=0120000,"ZIP 路径无效或包含链接");
            std::vector<uint8_t> name(nameLength); file.read(reinterpret_cast<char *>(name.data()),name.size()); requireArchive(file.gcount()==std::streamsize(name.size()),"ZIP 名称已截断");
            central += 46 + nameLength + u16(header.data()+30) + u16(header.data()+32);
            NSStringEncoding encoding = flags&2048 ? NSUTF8StringEncoding : CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingDOSJapanese);
            NSString *decoded=[[NSString alloc] initWithBytes:name.data() length:name.size() encoding:encoding];
            requireArchive(decoded!=nil,"ZIP 文件名编码无效");
            NSString *normalized=[decoded stringByReplacingOccurrencesOfString:@"\\" withString:@"/"];
            requireArchive(![normalized hasPrefix:@"/"] && ![normalized containsString:@":"] && ![normalized containsString:[NSString stringWithFormat:@"%C",(unichar)0]],"ZIP 路径无效");
            for(NSString *part in [normalized componentsSeparatedByString:@"/"]) requireArchive(![part isEqual:@".."],"ZIP 路径越界");
            std::filesystem::path destination=root/std::string(normalized.UTF8String);
            if ([normalized hasSuffix:@"/"]) {std::filesystem::create_directories(destination);continue;}
            expanded+=size; requireArchive(expanded<=8ull*1024*1024*1024 && size<=4ull*1024*1024*1024,"ZIP 解压大小超过上限");
            requireArchive(!std::filesystem::exists(destination),"ZIP 中包含重名文件"); std::filesystem::create_directories(destination.parent_path());
            file.seekg(std::streamoff(local)); auto localHeader=readHeader(file,30); requireArchive(u32(localHeader.data())==0x04034b50,"ZIP 文件头无效");
            uint64_t offset=local+30+u16(localHeader.data()+26)+u16(localHeader.data()+28); requireArchive(offset+packed<=length,"ZIP 内容已截断"); file.seekg(std::streamoff(offset));
            std::ofstream output(destination,std::ios::binary); requireArchive(bool(output),"无法写入解压文件");
            std::array<uint8_t,65536> input{},data{}; uint64_t left=packed,written=0; z_stream stream{};
            if(method==8) requireArchive(inflateInit2(&stream,-MAX_WBITS)==Z_OK,"无法初始化 ZIP 解压");
            bool good=true, finished=method==0;
            while(left && good) {
                size_t chunk=size_t(std::min<uint64_t>(left,input.size())); file.read(reinterpret_cast<char *>(input.data()),chunk); if(file.gcount()!=std::streamsize(chunk)){good=false;break;} left-=chunk;
                if(method==0){output.write(reinterpret_cast<char *>(input.data()),chunk);written+=chunk;}
                else {stream.next_in=input.data();stream.avail_in=unsigned(chunk); do {stream.next_out=data.data();stream.avail_out=unsigned(data.size());int result=inflate(&stream,Z_NO_FLUSH);size_t count=data.size()-stream.avail_out;written+=count;if(result!=Z_OK && result!=Z_STREAM_END){good=false;break;}if(written>size){good=false;break;}output.write(reinterpret_cast<char *>(data.data()),count);if(result==Z_STREAM_END){finished=true;break;}}while(stream.avail_in || stream.avail_out==0);}
                if(written>size || !output) good=false;
                if(finished && method==8 && left) good=false;
            }
            if(method==8) inflateEnd(&stream); output.close(); requireArchive(good && finished && written==size && bool(output),"ZIP 内容解压失败");
        }
    } catch(...) {std::filesystem::remove_all(root);throw;}
}
}
@implementation NECBridge (Archive)
+ (NSString *)gzip:(NSString *)source target:(NSString *)target { try{gzipFile(source,target,false);return @"";}catch(const std::exception& error){return archiveError(error);} }
+ (NSString *)gunzip:(NSString *)source target:(NSString *)target { try{gzipFile(source,target,true);return @"";}catch(const std::exception& error){return archiveError(error);} }
+ (NSString *)extractZip:(NSString *)source target:(NSString *)target { try{unzip(source,target);return @"";}catch(const std::exception& error){return archiveError(error);} }
@end
