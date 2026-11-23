import Foundation
import CoreFoundation

struct NECError: LocalizedError { let message: String; var errorDescription: String? { message } }
func checked(_ message: String) throws { if !message.isEmpty { throw NECError(message: message) } }
let fileManager = FileManager.default
let oemJapanese = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.dosJapanese.rawValue)))
let oemChinese = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
func oem(_ value: String) throws -> Data {
    guard let data = value.data(using: oemJapanese, allowLossyConversion: false), String(data: data, encoding: oemJapanese) == value else {
        throw NECError(message: "DOS 文件名或命令无法用代码页 932 保存")
    }
    return data
}
func duration(_ milliseconds: Int64) -> String {
    let minutes = max(0, milliseconds) / 60000
    if minutes == 0 { return milliseconds == 0 ? "0 分钟" : "不足 1 分钟" }
    return minutes < 60 ? "\(minutes) 分钟" : "\(minutes / 60) 小时 \(minutes % 60) 分钟"
}
func jsonData<T: Encodable>(_ value: T) throws -> Data { let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]; return try encoder.encode(value) }
func atomicJSON<T: Encodable>(_ value: T, to url: URL) throws { try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true); try jsonData(value).write(to: url, options: .atomic) }
func byteSize(_ url: URL) -> Int64 {
    let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey]
    if let values = try? url.resourceValues(forKeys: keys), values.isRegularFile == true { return Int64(values.fileSize ?? 0) }
    guard let files = fileManager.enumerator(at: url, includingPropertiesForKeys: Array(keys)) else { return 0 }
    return files.compactMap { ($0 as? URL).flatMap { try? $0.resourceValues(forKeys: keys) } }.reduce(0) { $0 + ($1.isRegularFile == true ? Int64($1.fileSize ?? 0) : 0) }
}
func formattedBytes(_ value: Int64) -> String { ByteCountFormatter.string(fromByteCount: value, countStyle: .file) }

struct DiskRef: Codable, Hashable { var file: String; var index: Int = 0 }
struct DiskImage: Codable, Identifiable, Hashable {
    var file: String; var name: String; var titles: [String]; var role: String = ""
    var id: String { file }
    var hard: Bool { ["hdi", "nhd", "hdd", "thd"].contains((file as NSString).pathExtension.lowercased()) }
    func label(_ index: Int) -> String { titles.count > 1 ? "\(name) · \(index + 1) · \(titles.indices.contains(index) ? titles[index] : "")" : name }
}
struct Game: Codable, Identifiable {
    var id = UUID().uuidString.lowercased()
    var name: String
    var platform: Int
    var media: [DiskImage] = []
    var floppyA: DiskRef?; var floppyB: DiskRef?; var hardDisk: DiskRef?
    var bootDevice = "auto"
    var mode = 0
    var options: [String: String] = [:]
    var font = ""
    var firmwareRevision = ""
    var startupEntry = ""
    var startupSource = ""
    var startupTemplate = ""
    var startupMemory = "none"
    var startupHint = ""
    var played: Date?
    var playMillis: Int64 = 0
    var lastMachineInfo = ""
    var input = InputSettings()
    var lastPlaySession = ""
    var lastPlayMillis: Int64 = 0
    var mediaType: Int { let h = media.contains(where: \.hard), f = media.contains { !$0.hard }; return h && f ? 3 : h ? 2 : 1 }
    var mediaInfo: String { ["", "软盘", "硬盘", "混合介质"][mediaType] + " · " + Set(media.map { ($0.file as NSString).pathExtension.uppercased() }).sorted().joined(separator: " / ") + (media.reduce(0, { $0 + $1.titles.count }) > 1 ? " · \(media.reduce(0, { $0 + $1.titles.count })) 张盘" : "") }
    func disk(_ ref: DiskRef?) -> DiskImage? { media.first { $0.file == ref?.file } }
    func label(_ ref: DiskRef?) -> String { guard let ref = ref, let disk = disk(ref) else { return "空" }; return disk.label(ref.index) }
}
struct InputSettings: Codable {
    var mode = "auto"; var last = "keys"; var tap = 13; var keypad = true; var mouseSpeed = 1.0; var fullKeyboard = false; var preset = ""
}
struct MachineOption: Codable, Identifiable {
    var key: String; var label: String; var group: Int; var fallback: String; var values: [String]; var names: [String]; var min: Int; var max: Int
    var id: String { key }
    var range: Bool { values.isEmpty }
    func value(_ game: Game) -> String { game.options[key] ?? (key == "np2kai_clk_mult" ? (game.mode == 1 ? "8" : game.mode == 2 ? "16" : "4") : fallback) }
    func display(_ value: String) -> String {
        if let index = values.firstIndex(of: value), names.indices.contains(index) { return names[index] }
        if key == "m88_dipsw" { return String(format: "0x%03X", Int(value) ?? 0) }
        if range { return min < 0 ? "\(value) dB" : value }
        return value
    }
}
struct OptionSchema: Codable { var modes: [String]; var platforms: [String: [MachineOption]] }
struct FontChoice: Identifiable { var id: String; var name: String; var source: URL }
struct SnapshotPointer: Codable { var folder: String; var date: Date }
struct DosDraft: Identifiable {
    var id = UUID(); var directory: URL; var name: String; var entries: [URL]; var programs: [String]
}
struct DosTemplate: Identifiable { var id: String; var name: String; var file: URL; var drive: String; var autoexec: String }

enum Binary {
    static func u16(_ data: Data, _ offset: Int) -> UInt16 { UInt16(data[offset]) | UInt16(data[offset + 1]) << 8 }
    static func u32(_ data: Data, _ offset: Int) -> UInt32 { UInt32(u16(data, offset)) | UInt32(u16(data, offset + 2)) << 16 }
    static func titles(_ url: URL, platform: Int) throws -> [String] {
        if platform == 98 { return [""] }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        let length = try handle.seekToEnd(); var offset: UInt64 = 0; var result: [String] = []
        while offset < length {
            guard result.count < 64, length - offset >= 0x2b0 else { throw NECError(message: "D88 文件头或内嵌盘数量无效") }
            try handle.seek(toOffset: offset); let header = try handle.read(upToCount: 0x2b0) ?? Data()
            guard header.count == 0x2b0 else { throw NECError(message: "D88 文件已截断") }
            let size = UInt64(u32(header, 0x1c)); guard size >= 0x2b0, size <= length - offset else { throw NECError(message: "D88 内嵌盘大小无效") }
            let name = Data(header.prefix(17).prefix { $0 != 0 }); result.append(String(data: name, encoding: oemJapanese) ?? "磁盘 \(result.count + 1)")
            offset += size
        }
        guard !result.isEmpty else { throw NECError(message: "磁盘镜像为空") }; return result
    }
}
