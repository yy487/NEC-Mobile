import Foundation
import Combine
import UIKit
import UniformTypeIdentifiers

final class LibraryFiles {
    let root: URL
    let schema: OptionSchema
    static let supported98 = ["d88", "88d", "fdi", "hdi", "nhd", "hdd", "thd", "xdf", "hdm"]
    static let supported88 = ["d88", "88d"]
    init() throws {
        root = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("NECMobile", isDirectory: true)
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        schema = try JSONDecoder().decode(OptionSchema.self, from: Data(contentsOf: Self.assets.appendingPathComponent("machine-options.json")))
    }
    static var assets: URL { Bundle.main.bundleURL.appendingPathComponent("AppResources", isDirectory: true) }
    func platformRoot(_ platform: Int) -> URL { root.appendingPathComponent("pc\(platform)", isDirectory: true) }
    func directory(_ game: Game) -> URL { platformRoot(game.platform).appendingPathComponent("games/\(game.id)", isDirectory: true) }
    func mediaURL(_ game: Game, _ disk: DiskImage) -> URL { directory(game).appendingPathComponent("media/\(disk.file)") }
    func path(_ game: Game, _ ref: DiskRef?) throws -> String {
        guard let ref = ref else { return "" }
        guard let disk = game.disk(ref), disk.titles.indices.contains(ref.index), fileManager.fileExists(atPath: mediaURL(game, disk).path) else { throw NECError(message: "存档或盘位使用的镜像已移除") }
        return mediaURL(game, disk).path
    }
    func list(_ platform: Int) throws -> [Game] {
        let path = platformRoot(platform).appendingPathComponent("games.json")
        guard fileManager.fileExists(atPath: path.path) else { return [] }
        let result = try JSONDecoder().decode([Game].self, from: Data(contentsOf: path))
        for game in result {
            guard UUID(uuidString: game.id) != nil, game.platform == platform, game.media.allSatisfy({ ($0.file as NSString).lastPathComponent == $0.file && !$0.file.contains("/") && !$0.file.contains("\\") }) else { throw NECError(message: "游戏记录无效") }
        }
        return result
    }
    func find(_ id: String, platform: Int) throws -> Game {
        guard let game = try list(platform).first(where: { $0.id == id }) else { throw NECError(message: "找不到游戏记录") }; return game
    }
    func save(_ game: Game) throws {
        var entries = try list(game.platform); if let index = entries.firstIndex(where: { $0.id == game.id }) { entries[index] = game } else { entries.append(game) }
        try atomicJSON(entries, to: platformRoot(game.platform).appendingPathComponent("games.json"))
    }
    func update(_ id: String, platform: Int, change: (inout Game) throws -> Void) throws {
        var game = try find(id, platform: platform); try change(&game); try save(game)
    }
    func remove(_ game: Game) throws {
        let entries = try list(game.platform).filter { $0.id != game.id }
        try atomicJSON(entries, to: platformRoot(game.platform).appendingPathComponent("games.json")); try fileManager.removeItem(at: directory(game))
    }
    func removeDisk(_ game: Game, disk: DiskImage) throws {
        guard ![game.floppyA?.file, game.floppyB?.file, game.hardDisk?.file].contains(disk.file) else { throw NECError(message: "请先从默认盘位移除这张盘") }
        guard try !StateStore(directory(game)).references(disk.file) else { throw NECError(message: "即时档正在引用这张盘，请先管理对应槽位") }
        try update(game.id, platform: game.platform) { $0.media.removeAll { $0.file == disk.file } }; try fileManager.removeItem(at: mediaURL(game, disk))
    }
    func asset(_ relative: String) throws -> URL {
        let target = root.appendingPathComponent("templates/\(relative)")
        if fileManager.fileExists(atPath: target.path) { return target }
        let source = Self.assets.appendingPathComponent("dos/\(relative).gz")
        try fileManager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temporary = target.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
        defer { try? fileManager.removeItem(at: temporary) }
        try checked(NECBridge.gunzip(source.path, target: temporary.path)); try fileManager.moveItem(at: temporary, to: target); return target
    }
    func diskInfo(_ file: URL) -> [String: Any] {
        var result = (try? JSONSerialization.jsonObject(with: Data(NECBridge.inspectDisk(file.path).utf8))) as? [String: Any] ?? [:]
        func decode(_ value: String) -> String { value.data(using: .isoLatin1).flatMap { String(data: $0, encoding: oemJapanese) } ?? value }
        if let programs = result["programs"] as? [String] { result["programs"] = programs.map(decode) }
        for key in ["autoexec", "config"] { if let text = result[key] as? String { result[key] = decode(text) } }
        if let entries = result["entries"] as? [[String: Any]] { result["entries"] = entries.map { entry in var value = entry; if let name = value["name"] as? String { value["name"] = decode(name) }; return value } }
        return result
    }
    func validate(_ game: Game) throws {
        for ref in [game.floppyA, game.floppyB, game.hardDisk] { _ = try path(game, ref) }
        guard game.floppyA == nil || game.floppyA != game.floppyB else { throw NECError(message: "同一内嵌盘不能同时装入 A、B") }
        guard game.platform == 98 || game.hardDisk == nil else { throw NECError(message: "PC-88 使用软盘启动") }
        if game.bootDevice == "hdd" { guard game.hardDisk != nil else { throw NECError(message: "请选择硬盘启动镜像") } }
        else if game.floppyA == nil && game.hardDisk == nil { throw NECError(message: "请选择启动盘") }
        for ref in [game.floppyA, game.floppyB] { if let disk = game.disk(ref), disk.hard { throw NECError(message: "软驱需要软盘镜像") } }
        if let disk = game.disk(game.hardDisk), !disk.hard { throw NECError(message: "硬盘槽需要硬盘镜像") }
    }
    func files(_ source: URL, depth: Int = 0) throws -> [URL] {
        guard depth <= 12 else { throw NECError(message: "目录层级过深") }
        let values = try source.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey])
        guard values.isSymbolicLink != true else { throw NECError(message: "导入目录包含符号链接") }
        if values.isDirectory != true { return [source] }
        var result: [URL] = []
        for child in try fileManager.contentsOfDirectory(at: source, includingPropertiesForKeys: nil, options: .skipsHiddenFiles) {
            result += try files(child, depth: depth + 1); guard result.count <= 4096 else { throw NECError(message: "请选择单款游戏目录") }
        }
        return result
    }
    func importImages(_ sources: [URL], platform: Int, title: String?) throws -> Game {
        var game = Game(name: title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? sources.first?.deletingPathExtension().lastPathComponent ?? "游戏", platform: platform)
        guard !game.name.isEmpty, game.name.count <= 80 else { throw NECError(message: "游戏名称需为 1–80 个字符") }
        let destination = directory(game); try fileManager.createDirectory(at: destination.appendingPathComponent("media"), withIntermediateDirectories: true)
        var total: Int64 = 0
        do {
            for source in sources {
                let ext = source.pathExtension.lowercased(), allowed = platform == 88 ? Self.supported88 : Self.supported98
                let size = byteSize(source); total += size
                guard size > 0, size <= 4 * 1024 * 1024 * 1024, total <= 8 * 1024 * 1024 * 1024 else { throw NECError(message: "游戏文件为空或超过导入上限") }
                if allowed.contains(ext) {
                    let name = UUID().uuidString.lowercased() + "." + ext, target = destination.appendingPathComponent("media/\(name)")
                    try fileManager.copyItem(at: source, to: target); try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
                    game.media.append(DiskImage(file: name, name: source.lastPathComponent, titles: try Binary.titles(target, platform: platform)))
                } else if ["rom", "fnt", "bmp", "wav", "bin"].contains(ext) {
                    guard size <= 16 * 1024 * 1024 else { throw NECError(message: "单个固件、字库或 WAV 资源超过 16 MB") }
                    let name = source.lastPathComponent.lowercased(); guard !name.contains("\n"), name.utf8.count <= 96 else { throw NECError(message: "资源名称无效") }
                    let folder = destination.appendingPathComponent("firmware-sources/game"); try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
                    let target = folder.appendingPathComponent(name); guard !fileManager.fileExists(atPath: target.path) else { throw NECError(message: "游戏包有重名资源 \(name)") }
                    try fileManager.copyItem(at: source, to: target)
                } else if ["png", "jpg", "jpeg", "webp"].contains(ext), ["cover", "folder", "front", "title"].contains(source.deletingPathExtension().lastPathComponent.lowercased()) {
                    let image = try Data(contentsOf: source); if let decoded = UIImage(data: image), let png = decoded.pngData() { try png.write(to: destination.appendingPathComponent("cover.png"), options: .atomic) }
                }
            }
            guard !game.media.isEmpty else { throw NECError(message: "没有找到当前平台支持的镜像") }
            game.media.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            let soft = game.media.filter { !$0.hard }
            game.hardDisk = game.media.first(where: \.hard).map { DiskRef(file: $0.file) }
            let boot = soft.first { $0.name.range(of: #"(?i)(?:^|[ ._-])(s|system|boot)\.[^.]+$"#, options: .regularExpression) != nil } ?? soft.first
            game.floppyA = boot.map { DiskRef(file: $0.file) }
            if let boot = boot, boot.titles.count > 1 { game.floppyB = DiskRef(file: boot.file, index: 1) }
            else { game.floppyB = soft.first { $0.file != boot?.file }.map { DiskRef(file: $0.file) } }
            game.bootDevice = game.hardDisk == nil ? "floppy" : "hdd"
            if platform == 98 { do { try prepareBoot(&game, entry: "", source: "") } catch { game.startupHint = error.localizedDescription } }
            try save(game); return game
        } catch { try? fileManager.removeItem(at: destination); throw error }
    }
    func importContainer(_ source: URL, platform: Int) throws -> Game {
        if source.pathExtension.lowercased() == "zip" {
            let temporary = root.appendingPathComponent("import-" + UUID().uuidString)
            defer { try? fileManager.removeItem(at: temporary) }
            try checked(NECBridge.extractZip(source.path, target: temporary.path))
            return try importImages(files(temporary), platform: platform, title: source.deletingPathExtension().lastPathComponent)
        }
        let values = try source.resourceValues(forKeys: [.isDirectoryKey])
        return try importImages(values.isDirectory == true ? files(source) : [source], platform: platform,
                                title: values.isDirectory == true ? source.lastPathComponent : source.deletingPathExtension().lastPathComponent)
    }
    func fonts(_ game: Game) throws -> [FontChoice] {
        var result: [FontChoice] = []
        for (key, folder) in [("game", directory(game).appendingPathComponent("firmware-sources/game")), ("global", platformRoot(98).appendingPathComponent("resources"))] {
            for file in (try? fileManager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? [] where ["font.bmp", "freecg98.bmp", "font.rom"].contains(file.lastPathComponent.lowercased()) {
                result.append(FontChoice(id: key + "/" + file.lastPathComponent, name: file.lastPathComponent + " · " + (key == "game" ? "游戏" : "全局"), source: file))
            }
        }
        return result
    }
    func validateFont(_ source: URL) throws {
        guard source.pathExtension.lowercased() == "bmp" else { return }
        let data = try Data(contentsOf: source)
        guard data.count >= 54, data[0] == 66, data[1] == 77, Binary.u32(data, 18) == 2048, Int32(bitPattern: Binary.u32(data, 22)).magnitude == 2048,
              Binary.u16(data, 26) == 1, Binary.u16(data, 28) == 1, Binary.u32(data, 30) == 0, Int(Binary.u32(data, 10)) + 2048 * 256 <= data.count else {
            throw NECError(message: "需使用 2048×2048、1 位未压缩 BMP 字库")
        }
    }
    func prepare(_ game: Game) throws {
        try validate(game)
        let folder = directory(game), resources = game.platform == 98 ? folder.appendingPathComponent("np2kai") : folder
        try fileManager.createDirectory(at: resources, withIntermediateDirectories: true)
        var candidates: [String: URL] = [:]
        for location in [Self.assets.appendingPathComponent("firmware/pc\(game.platform)"), platformRoot(game.platform).appendingPathComponent("resources"), folder.appendingPathComponent("firmware-sources/game")] {
            var sources: [String: URL] = [:]
            for file in (try? fileManager.contentsOfDirectory(at: location, includingPropertiesForKeys: nil)) ?? [] { sources[file.lastPathComponent.lowercased()] = file }
            if sources["2608_rim.wav"] == nil, let alias = sources["2608_rym.wav"] { sources["2608_rim.wav"] = alias }
            candidates.merge(sources) { _, newer in newer }
        }
        for (name, source) in candidates where !(game.platform == 98 && ["font.bmp", "font.rom", "freecg98.bmp"].contains(name)) {
            let target = resources.appendingPathComponent(name); if fileManager.fileExists(atPath: target.path) { try fileManager.removeItem(at: target) }; try fileManager.copyItem(at: source, to: target)
        }
        if game.platform == 98 {
            let choices = try fonts(game)
            let selected = choices.first { $0.id == game.font } ?? choices.first ?? FontChoice(id: "builtin", name: "内置字库", source: Self.assets.appendingPathComponent("firmware/pc98/font.rom"))
            try validateFont(selected.source)
            for name in ["font.bmp", "font.rom"] { try? fileManager.removeItem(at: resources.appendingPathComponent(name)) }
            try fileManager.copyItem(at: selected.source, to: resources.appendingPathComponent(selected.source.pathExtension.lowercased() == "bmp" ? "font.bmp" : "font.rom"))
        }
        var settings: [String: String] = [:]
        for option in schema.platforms[String(game.platform)] ?? [] { settings[option.key] = option.value(game) }
        settings["audio_rate"] = String(UserDefaults.standard.integer(forKey: "audioRate"))
        settings["audio_buffer_ms"] = String(UserDefaults.standard.object(forKey: "audioBuffer") as? Int ?? 40)
        settings["firmware_revision"] = game.firmwareRevision
        if game.platform == 98 {
            settings["startup_floppy_a"] = try path(game, game.floppyA); settings["startup_floppy_b"] = try path(game, game.floppyB)
            settings["startup_hard_disk"] = try path(game, game.hardDisk); settings["startup_boot_device"] = game.bootDevice; settings["startup_font"] = game.font
        }
        if settings["midi_enabled"] == "ON" {
            guard let selected = UserDefaults.standard.string(forKey: "soundfont"), fileManager.fileExists(atPath: root.appendingPathComponent("midi/\(selected)").path) else { throw NECError(message: "请先导入并选择 SF2 音色库，或关闭本游戏 MIDI") }
            settings["midi_soundfont"] = root.appendingPathComponent("midi/\(selected)").path
        }
        let text = settings.keys.sorted().map { "\($0)=\(settings[$0]!)\n" }.joined()
        try Data(text.utf8).write(to: folder.appendingPathComponent("machine.cfg"), options: .atomic)
    }
    func restored(_ game: Game, slot: Int) throws -> Game {
        var result = game; let saved = try StateStore(directory(game)).info(slot); result.mode = saved.mode
        let keys = Set((schema.platforms[String(game.platform)] ?? []).map(\.key)); result.options = saved.configuration.filter { keys.contains($0.key) }
        func ref(_ path: String, _ index: Int) throws -> DiskRef? {
            guard !path.isEmpty else { return nil }; let name = (path as NSString).lastPathComponent
            guard game.media.contains(where: { $0.file == name }) else { throw NECError(message: "即时档引用的磁盘已移除") }; return DiskRef(file: name, index: index)
        }
        result.floppyA = try ref(saved.first, saved.firstIndex); result.floppyB = try ref(saved.second, saved.secondIndex)
        result.hardDisk = try ref(saved.configuration["host_hard_disk"] ?? "", 0); result.bootDevice = saved.configuration["startup_boot_device"] ?? "auto"
        result.font = saved.configuration["startup_font"] ?? game.font; result.firmwareRevision = saved.configuration["firmware_revision"] ?? ""; return result
    }
    func importResource(_ source: URL, platform: Int, game: Game? = nil) throws {
        let name = source.lastPathComponent.lowercased(), ext = source.pathExtension.lowercased()
        guard ["rom", "fnt", "bmp", "wav", "bin", "sys", "exe"].contains(ext), byteSize(source) > 0, byteSize(source) <= 16 * 1024 * 1024 else { throw NECError(message: "请选择有效的 ROM、字库、节奏 WAV 或 DOS 驱动资源，最大 16 MB") }
        if name.hasPrefix("sound") && ext == "rom" && byteSize(source) != 0x4000 { throw NECError(message: "声卡 BIOS ROM 需为 16 KB") }
        if platform == 98 && name.hasSuffix(".bmp") { try validateFont(source) }
        let folder = game.map { directory($0).appendingPathComponent("firmware-sources/game") } ?? platformRoot(platform).appendingPathComponent(["sys", "exe"].contains(ext) ? "drivers" : "resources")
        try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent(name); let data = try Data(contentsOf: source); try data.write(to: target, options: .atomic)
        if let game = game { try update(game.id, platform: platform) { $0.firmwareRevision = UUID().uuidString } }
        else { for game in try list(platform) { try update(game.id, platform: platform) { $0.firmwareRevision = UUID().uuidString } } }
    }
    func addBlank(_ game: inout Game) throws -> DiskImage {
        let template = try asset("blank/fat12-1200-v2.fdi"), file = UUID().uuidString.lowercased() + ".fdi"
        let target = directory(game).appendingPathComponent("media/\(file)")
        try checked(NECBridge.prepareFloppy(template.path, target: target.path, files: [], autoexec: nil, config: nil, label: Data("NECSAVE".utf8)))
        let disk = DiskImage(file: file, name: "存档盘 \(game.media.filter { $0.role == "save" }.count + 1)", titles: [""], role: "save"); game.media.append(disk); try save(game); return disk
    }
    func addTools(_ game: inout Game) throws -> DiskImage {
        if let existing = game.media.first(where: { $0.role == "tools" }) { return existing }
        let template = try asset("tools/freedos-v2.fdi"), file = UUID().uuidString.lowercased() + ".fdi"
        try fileManager.copyItem(at: template, to: directory(game).appendingPathComponent("media/\(file)"))
        let disk = DiskImage(file: file, name: "FreeDOS(98) 维护工具盘", titles: [""], role: "tools"); game.media.append(disk); try save(game); return disk
    }
}

final class LibraryStore: ObservableObject {
    let files: LibraryFiles
    let queue = DispatchQueue(label: "org.necmobile.files", qos: .userInitiated)
    @Published var games: [Game] = []
    @Published var platform: Int = UserDefaults.standard.object(forKey: "platform") as? Int ?? 98 { didSet { UserDefaults.standard.set(platform, forKey: "platform"); reload() } }
    @Published var busy = false; @Published var progress = ""; @Published var error: String?
    @Published var player: PlayerSession?
    @Published var dosDraft: DosDraft?
    init() {
        do { files = try LibraryFiles() } catch { fatalError("无法准备应用目录：\(error)") }
        reload()
    }
    func reload() {
        let platform = platform
        queue.async { do { let games = try self.files.list(platform).sorted { ($0.played ?? .distantPast) > ($1.played ?? .distantPast) }; DispatchQueue.main.async { if self.platform == platform { self.games = games } } }
            catch { DispatchQueue.main.async { self.error = error.localizedDescription } } }
    }
    func perform<T>(_ message: String, operation: @escaping () throws -> T, complete: @escaping (T) -> Void = { _ in }) {
        guard !busy else { return }; busy = true; progress = message
        queue.async { do { let result = try operation(); DispatchQueue.main.async { self.busy = false; self.progress = ""; self.reload(); complete(result) } }
            catch { DispatchQueue.main.async { self.busy = false; self.progress = ""; self.error = error.localizedDescription } } }
    }
    func save(_ game: Game, complete: @escaping () -> Void = {}) { perform("正在保存配置…", operation: { try self.files.save(game) }) { _ in complete() } }
    func start(_ game: Game, slot: Int? = nil) {
        guard player == nil else { return }
        let session = PlayerSession(store: self, game: game, restoreSlot: slot)
        player = session; session.begin()
    }
    func importGames(_ urls: [URL], name: String? = nil) {
        let platform = platform
        perform("正在导入游戏…", operation: {
            var scoped: [URL] = []; defer { scoped.forEach { $0.stopAccessingSecurityScopedResource() } }
            for url in urls where url.startAccessingSecurityScopedResource() { scoped.append(url) }
            if urls.count == 1, let first = urls.first {
                let directory = try first.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true
                if first.pathExtension.lowercased() == "zip" || directory {
                    var game = try self.files.importContainer(first, platform: platform)
                    if let name = name { let value = name.trimmingCharacters(in: .whitespacesAndNewlines); guard !value.isEmpty, value.count <= 80 else { throw NECError(message: "游戏名称需为 1–80 个字符") }; game.name = value; try self.files.save(game) }
                    return game
                }
            }
            return try self.files.importImages(urls, platform: platform, title: name)
        }) { _ in self.progress = "已添加到游戏库" }
    }
}
