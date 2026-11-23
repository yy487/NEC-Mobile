import Foundation

struct StateNeedsRestart: Error {}
struct StateInfo { var mode: Int; var configuration: [String: String]; var first: String; var firstIndex: Int; var second: String; var secondIndex: Int }
final class StateStore {
    static let slots = [1, 2, 3, 4, 5, 6, 7, 0]
    let gameDirectory: URL
    var root: URL { gameDirectory.appendingPathComponent("states", isDirectory: true) }
    init(_ directory: URL) { gameDirectory = directory }
    func pointer(_ slot: Int) -> URL { root.appendingPathComponent(slot == 0 ? "auto.json" : slot == 7 ? "quick.json" : "slot-\(slot).json") }
    func record(_ slot: Int) throws -> SnapshotPointer? {
        let path = pointer(slot); guard fileManager.fileExists(atPath: path.path) else { return nil }
        let value = try JSONDecoder().decode(SnapshotPointer.self, from: Data(contentsOf: path))
        guard UUID(uuidString: value.folder) != nil else { throw NECError(message: "存档槽记录无效") }; return value
    }
    func folder(_ slot: Int) throws -> URL {
        guard let value = try record(slot) else { throw NECError(message: "此槽没有存档") }
        let path = root.appendingPathComponent(value.folder, isDirectory: true)
        guard fileManager.fileExists(atPath: path.path) else { throw NECError(message: "存档文件缺失") }; return path
    }
    func hasAuto() -> Bool { fileManager.fileExists(atPath: pointer(0).path) }
    static func title(_ slot: Int) -> String { slot == 0 ? "上次会话" : slot == 7 ? "快速存档" : "槽 \(slot)" }
    func label(_ slot: Int) -> String {
        do { guard let value = try record(slot) else { return Self.title(slot) + " · 空" }; return Self.title(slot) + " · " + value.date.formatted(date: .numeric, time: .shortened) }
        catch { return Self.title(slot) + " · 无法读取" }
    }
    func save(_ slot: Int) throws {
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        let old = try? folder(slot), fresh = root.appendingPathComponent(UUID().uuidString.lowercased(), isDirectory: true)
        try fileManager.createDirectory(at: fresh, withIntermediateDirectories: false)
        do {
            try checked(NECBridge.state(fresh.path, load: false)); try compress(fresh)
            try atomicJSON(SnapshotPointer(folder: fresh.lastPathComponent, date: Date()), to: pointer(slot))
        } catch { try? fileManager.removeItem(at: fresh); throw error }
        if let old = old { try? fileManager.removeItem(at: old) }
        prune()
    }
    func load(_ slot: Int) throws {
        let selected = try folder(slot)
        var source = selected, temporary: URL?
        defer { if let temporary = temporary { try? fileManager.removeItem(at: temporary) } }
        if fileManager.fileExists(atPath: selected.appendingPathComponent("machine.bin.gz").path) {
            let directory = root.appendingPathComponent("load-" + UUID().uuidString.lowercased(), isDirectory: true); temporary = directory
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: false)
            for name in ["host.bin", "midi.bin"] { try fileManager.copyItem(at: selected.appendingPathComponent(name), to: directory.appendingPathComponent(name)) }
            try checked(NECBridge.gunzip(selected.appendingPathComponent("machine.bin.gz").path, target: directory.appendingPathComponent("machine.bin").path)); source = directory
        }
        let result = NECBridge.state(source.path, load: true)
        if result == "State machine settings do not match; restore the saved settings first" { throw StateNeedsRestart() }
        try checked(result); try? compress(selected)
    }
    func remove(_ slot: Int) throws {
        let directory = try? folder(slot); if fileManager.fileExists(atPath: pointer(slot).path) { try fileManager.removeItem(at: pointer(slot)) }
        if let directory = directory { try fileManager.removeItem(at: directory) }; prune()
    }
    func compress(_ directory: URL) throws {
        let raw = directory.appendingPathComponent("machine.bin"); guard fileManager.fileExists(atPath: raw.path) else { return }
        let packed = directory.appendingPathComponent("machine.bin.gz"), pending = directory.appendingPathComponent("pack-" + UUID().uuidString + ".pending")
        defer { try? fileManager.removeItem(at: pending) }
        try checked(NECBridge.gzip(raw.path, target: pending.path))
        if fileManager.fileExists(atPath: packed.path) { _ = try fileManager.replaceItemAt(packed, withItemAt: pending) }
        else { try fileManager.moveItem(at: pending, to: packed) }
        try fileManager.removeItem(at: raw)
    }
    func compact() throws -> Int64 {
        let before = byteSize(root)
        for slot in Self.slots { if let directory = try? folder(slot) { try compress(directory) } }
        return max(0, before - byteSize(root))
    }
    func prune() {
        var retained = Set<String>()
        for slot in Self.slots { do { if let value = try record(slot) { retained.insert(value.folder) } } catch { return } }
        for file in (try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            if UUID(uuidString: file.lastPathComponent) != nil && !retained.contains(file.lastPathComponent) { try? fileManager.removeItem(at: file) }
        }
    }
    func info(_ slot: Int) throws -> StateInfo {
        let data = try Data(contentsOf: folder(slot).appendingPathComponent("host.bin")); var offset = 0
        func take(_ count: Int) throws -> Data {
            guard count >= 0, offset <= data.count - count else { throw NECError(message: "存档记录已截断") }
            defer { offset += count }; return data.subdata(in: offset ..< offset + count)
        }
        func number() throws -> Int { Int(Binary.u32(try take(4), 0)) }
        func string() throws -> String { let size = try number(); guard size <= 16384, let result = String(data: try take(size), encoding: .utf8) else { throw NECError(message: "存档字符串无效") }; return result }
        guard String(data: try take(11), encoding: .ascii) == "NECSTATE024" else { throw NECError(message: "存档版本不兼容") }
        let platform = try number(); guard platform == 88 || platform == 98 else { throw NECError(message: "存档平台无效") }
        let mode = try number(); _ = try string(); let config = try string()
        let first = try string(), firstIndex = try number(), second = try string(), secondIndex = try number()
        var settings: [String: String] = [:]
        for line in config.split(separator: "\n") { let fields = line.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false); if fields.count == 2 { settings[String(fields[0])] = String(fields[1]) } }
        return StateInfo(mode: mode, configuration: settings, first: first, firstIndex: firstIndex, second: second, secondIndex: secondIndex)
    }
    func references(_ file: String) throws -> Bool {
        for slot in Self.slots where (try? record(slot)) != nil {
            let saved = try info(slot)
            if [saved.first, saved.second, saved.configuration["host_hard_disk"] ?? ""].contains(where: { ($0 as NSString).lastPathComponent == file }) { return true }
        }
        return false
    }
}
