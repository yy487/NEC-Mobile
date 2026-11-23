import Foundation

extension LibraryFiles {
    func dosComponent(_ component: String) throws {
        let fields = component.split(separator: ".", omittingEmptySubsequences: false)
        let invalid = NECError(message: "DOS 名称需符合代码页 932 的 8.3 规则：\(component)")
        guard !component.isEmpty, component != ".", component != "..", fields.count <= 2,
              !component.unicodeScalars.contains(where: { $0.value <= 32 || "\"*+,/:;<=>?[\\]|&%()!^".unicodeScalars.contains($0) }),
              component.range(of: #"(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)"#, options: .regularExpression) == nil else { throw invalid }
        let stem = try oem(String(fields[0])), ext = try oem(fields.count > 1 ? String(fields[1]) : "")
        guard !stem.isEmpty, stem.count <= 8, fields.count == 1 || (!ext.isEmpty && ext.count <= 3) else { throw invalid }
    }
    func templates() throws -> [DosTemplate] {
        let builtin = try asset("boot/freedos-v2.hdi")
        var result = [DosTemplate(id: "builtin", name: "FreeDOS(98)", file: builtin, drive: "C:", autoexec: String(data: NECBridge.dosAutoexec(builtin.path), encoding: oemJapanese) ?? "@ECHO OFF\r\n")]
        let folder = platformRoot(98).appendingPathComponent("dos-templates")
        for file in (try? fileManager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? [] where ["hdi", "nhd", "hdd", "thd"].contains(file.pathExtension.lowercased()) {
            let info = diskInfo(file)
            guard info["boot"] as? String == "ready" else { continue }
            let label = (try? String(contentsOf: file.appendingPathExtension("name"), encoding: .utf8)) ?? file.lastPathComponent
            result.append(DosTemplate(id: file.lastPathComponent, name: label, file: file, drive: info["system"] as? String == "freedos" ? "C:" : "A:", autoexec: String(data: NECBridge.dosAutoexec(file.path), encoding: oemJapanese) ?? "@ECHO OFF\r\n"))
        }
        return result
    }
    func importTemplate(_ source: URL) throws {
        guard Self.supported98.contains(source.pathExtension.lowercased()), diskInfo(source)["boot"] as? String == "ready" else { throw NECError(message: "请选择有 DOS 系统的 PC-98 磁盘模板") }
        let folder = platformRoot(98).appendingPathComponent("dos-templates"); try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent(UUID().uuidString.lowercased()).appendingPathExtension(source.pathExtension.lowercased())
        try fileManager.copyItem(at: source, to: target); try Data(source.lastPathComponent.utf8).write(to: target.appendingPathExtension("name"), options: .atomic)
    }
    func bootTemplates() throws -> [(String, String, URL)] {
        var result = [("builtin", "FreeDOS(98)", try asset("boot/freedos-v2.fdi"))]
        for file in (try? fileManager.contentsOfDirectory(at: platformRoot(98).appendingPathComponent("dos-templates"), includingPropertiesForKeys: nil)) ?? [] where ["fdi", "d88", "88d", "hdm", "xdf"].contains(file.pathExtension.lowercased()) {
            if diskInfo(file)["boot"] as? String == "ready" { result.append((file.lastPathComponent, (try? String(contentsOf: file.appendingPathExtension("name"), encoding: .utf8)) ?? file.lastPathComponent, file)) }
        }
        return result
    }
    func prepareBoot(_ game: inout Game, entry: String, source: String) throws {
        guard game.platform == 98 else { return }
        if source.isEmpty {
            for ref in [game.hardDisk, game.floppyA] {
                if let disk = game.disk(ref), disk.role != "boot" {
                    let info = diskInfo(mediaURL(game, disk))
                    if ["ready", "custom"].contains(info["boot"] as? String ?? "") { game.startupHint = "使用游戏配套启动环境"; return }
                }
            }
        }
        var selected: DiskImage?
        var programs: [String] = []
        for disk in game.media where disk.role.isEmpty && (source.isEmpty || disk.file == source) {
            let info = diskInfo(mediaURL(game, disk)), found = info["programs"] as? [String] ?? []
            if !source.isEmpty || info["boot"] as? String == "missing-dos" || (!found.isEmpty && info["supported"] as? Bool == true) { selected = disk; programs = found; break }
        }
        guard let data = selected, !programs.isEmpty else { throw NECError(message: "请在启动环境中选择游戏数据盘和入口") }
        let preferred = programs.first { $0.uppercased() == "AUTOEXEC.BAT" } ?? (programs.count == 1 ? programs.first : nil)
        let requested = entry.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\\", with: "/")
        guard let command = requested.isEmpty ? preferred : programs.first(where: { $0.caseInsensitiveCompare(requested) == .orderedSame }) else { throw NECError(message: "有多个启动文件，请选择入口后准备启动环境") }
        for component in command.split(separator: "/") { try dosComponent(String(component)) }
        let available = try bootTemplates(), template = available.first { $0.0 == game.startupTemplate } ?? available[0]
        let drive = data.hard ? "C:" : "B:", working = (command as NSString).deletingLastPathComponent.replacingOccurrences(of: "/", with: "\\")
        let startup = "@ECHO OFF\r\n\(drive)\r\nCD \\\(working)\r\n\(drive)\\\(command.replacingOccurrences(of: "/", with: "\\"))\r\n"
        var drivers: [[String: Any]] = [], configuration: Data?
        if game.startupMemory != "none" {
            guard diskInfo(template.2)["system"] as? String == "msdos" else { throw NECError(message: "所选内存驱动配置需要用户提供的兼容 MS-DOS 系统盘") }
            let folder = platformRoot(98).appendingPathComponent("drivers"), himem = folder.appendingPathComponent("himem.sys"), emm = folder.appendingPathComponent("emm386.exe")
            guard fileManager.fileExists(atPath: himem.path), game.startupMemory != "ems" || fileManager.fileExists(atPath: emm.path) else { throw NECError(message: "请先导入匹配的 HIMEM.SYS / EMM386.EXE") }
            drivers.append(["host": himem.path, "name": try oem("HIMEM.SYS")])
            var config = "DEVICE=\\NECMOB\\HIMEM.SYS\r\nDOS=HIGH\r\nFILES=30\r\nBUFFERS=10\r\n"
            if game.startupMemory == "ems" { let memory = Int(game.options["np2kai_ExMemory"] ?? "3") ?? 3; let kb = max(512, min(32768, (memory - 1) * 1024)); config += "DEVICE=\\NECMOB\\EMM386.EXE /EM=\(kb)\r\n"; drivers.append(["host": emm.path, "name": try oem("EMM386.EXE")]) }
            configuration = try oem(config)
        }
        let file = UUID().uuidString.lowercased() + ".fdi", target = directory(game).appendingPathComponent("media/\(file)")
        try checked(NECBridge.prepareFloppy(template.2.path, target: target.path, files: drivers, autoexec: try oem(startup), config: configuration, label: nil))
        game.media.append(DiskImage(file: file, name: "\(template.1) · \(command)", titles: [""], role: "boot"))
        game.floppyA = DiskRef(file: file); game.floppyB = data.hard ? nil : DiskRef(file: data.file)
        game.hardDisk = data.hard ? DiskRef(file: data.file) : nil; game.bootDevice = "floppy"
        game.startupEntry = command; game.startupSource = data.file; game.startupHint = "\(template.1) · \(drive)\\\(command)"
    }
    func stageDOS(_ source: URL) throws -> DosDraft {
        let root = self.root.appendingPathComponent("dos-import-" + UUID().uuidString)
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        do {
            var input = source
            if source.pathExtension.lowercased() == "zip" {
                input = root.appendingPathComponent("unpacked"); try checked(NECBridge.extractZip(source.path, target: input.path))
                let children = try fileManager.contentsOfDirectory(at: input, includingPropertiesForKeys: [.isDirectoryKey], options: .skipsHiddenFiles)
                if children.count == 1, (try children[0].resourceValues(forKeys: [.isDirectoryKey])).isDirectory == true { input = children[0] }
            }
            let sourceRoot = root.appendingPathComponent("source"); try fileManager.createDirectory(at: sourceRoot, withIntermediateDirectories: true)
            let originals = try files(input); var names = Set<String>(), programs: [String] = [], total: Int64 = 0
            for original in originals {
                guard original.path.hasPrefix(input.path + "/") else { throw NECError(message: "请选择 DOS 游戏文件夹或 ZIP") }
                let relative = String(original.path.dropFirst(input.path.count + 1)); for part in relative.split(separator: "/") { try dosComponent(String(part)) }
                guard names.insert(relative.uppercased()).inserted else { throw NECError(message: "DOS 目录有大小写重名文件") }
                total += byteSize(original); guard total <= 8 * 1024 * 1024 * 1024 else { throw NECError(message: "DOS 游戏目录过大") }
                let destination = sourceRoot.appendingPathComponent(relative); try fileManager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true); try fileManager.copyItem(at: original, to: destination)
                if ["bat", "exe", "com"].contains(original.pathExtension.lowercased()) { programs.append(relative) }
            }
            guard !programs.isEmpty else { throw NECError(message: "未找到 BAT、EXE 或 COM 启动文件") }
            programs.sort { a, b in let left = (a.contains("/") ? 10 : 0) + (a.lowercased().hasSuffix(".bat") ? 0 : 2); let right = (b.contains("/") ? 10 : 0) + (b.lowercased().hasSuffix(".bat") ? 0 : 2); return left == right ? a.localizedStandardCompare(b) == .orderedAscending : left < right }
            let all = try fileManager.subpathsOfDirectory(atPath: sourceRoot.path).sorted { $0.split(separator: "/").count < $1.split(separator: "/").count }.map { sourceRoot.appendingPathComponent($0) }
            return DosDraft(directory: root, name: source.deletingPathExtension().lastPathComponent, entries: all, programs: programs)
        } catch { try? fileManager.removeItem(at: root); throw error }
    }
    func commitDOS(_ draft: DosDraft, template: DosTemplate, entry: String, working: String, drive: String, initialization: String) throws -> Game {
        guard draft.programs.contains(entry), ["A:", "B:", "C:", "D:", "E:", "F:"].contains(drive) else { throw NECError(message: "DOS 入口或盘符无效") }
        for part in working.split(separator: "/") { try dosComponent(String(part)) }
        let source = draft.directory.appendingPathComponent("source"), work = working.trimmingCharacters(in: CharacterSet(charactersIn: "/\\"))
        guard work.isEmpty || fileManager.fileExists(atPath: source.appendingPathComponent(work).path) else { throw NECError(message: "工作目录不存在") }
        let startup = "@ECHO OFF\r\n\(drive)\r\nCD \\NECGAME\(work.isEmpty ? "" : "\\" + work.replacingOccurrences(of: "/", with: "\\"))\r\n\\NECGAME\\\(entry.replacingOccurrences(of: "/", with: "\\"))\r\n"
        let autoexec = initialization.replacingOccurrences(of: "\u{1a}", with: "") + "\r\n\(drive)\\NECMOB\\START.BAT\r\n"
        var game = Game(name: draft.name, platform: 98); let folder = directory(game); try fileManager.createDirectory(at: folder.appendingPathComponent("media"), withIntermediateDirectories: true)
        do {
            let target = folder.appendingPathComponent("media/dos-game.hdi")
            let entries: [[String: Any]] = try draft.entries.map { file in ["host": file.path, "name": try oem(String(file.path.dropFirst(source.path.count + 1)))] }
            try checked(NECBridge.buildDos(template.file.path, target: target.path, files: entries, startup: try oem(startup), autoexec: try oem(autoexec)))
            game.media = [DiskImage(file: "dos-game.hdi", name: "DOS 游戏硬盘", titles: [""])]; game.hardDisk = DiskRef(file: "dos-game.hdi"); game.bootDevice = "hdd"
            game.startupEntry = "NECGAME/" + entry; game.startupHint = "\(template.name) · \(entry)"
            for file in draft.entries where ["font.bmp", "freecg98.bmp", "font.rom"].contains(file.lastPathComponent.lowercased()) {
                let dest = folder.appendingPathComponent("firmware-sources/game"); try fileManager.createDirectory(at: dest, withIntermediateDirectories: true); try fileManager.copyItem(at: file, to: dest.appendingPathComponent(file.lastPathComponent.lowercased()))
            }
            try save(game); try? fileManager.removeItem(at: draft.directory); return game
        } catch { try? fileManager.removeItem(at: folder); throw error }
    }
}
