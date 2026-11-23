import Foundation

enum IOSSmoke {
    private static var started = false
    @MainActor static func start(_ store: LibraryStore) {
        guard !started else { return }; started = true
        Task { await run(store) }
    }
    @MainActor static func wait(_ label: String, _ predicate: () -> Bool) async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 45
        while !predicate() {
            guard ProcessInfo.processInfo.systemUptime < deadline else { throw NECError(message: label + " 超时") }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
    }
    static func files<T>(_ store: LibraryStore, _ operation: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            store.queue.async { do { continuation.resume(returning: try operation()) } catch { continuation.resume(throwing: error) } }
        }
    }
    @MainActor static func capture(_ name: String, documents: URL) async throws {
        try Data(name.utf8).write(to: documents.appendingPathComponent("ios-smoke-stage.txt"), options: .atomic)
        try await wait("截图 " + name) { (try? String(contentsOf: documents.appendingPathComponent("ios-smoke-ack.txt"), encoding: .utf8)) == name }
    }
    @MainActor static func run(_ store: LibraryStore) async {
        let documents = store.files.root.deletingLastPathComponent()
        var results: [String] = []
        do {
            for platform in [88, 98] {
                let game: Game = try await files(store) {
                    if platform == 88 {
                        let imported = try store.files.importContainer(documents.appendingPathComponent("ios-import.zip"), platform: 88)
                        guard imported.media.first?.titles.count == 2, imported.media.first?.name == "テスト.d88" else { throw NECError(message: "ZIP 日文名称或 D88 内嵌盘导入失败") }
                        return imported
                    }
                    let source = documents.appendingPathComponent("dos-demo")
                    try fileManager.createDirectory(at: source, withIntermediateDirectories: true)
                    try Data("@ECHO OFF\r\nECHO NEC Mobile iOS DOS import\r\nECHO PASS>C:\\IOSBOOT.TXT\r\nPAUSE\r\n".utf8).write(to: source.appendingPathComponent("開始.BAT"))
                    let draft = try store.files.stageDOS(source), template = try store.files.templates()[0]
                    var imported = try store.files.commitDOS(draft, template: template, entry: "開始.BAT", working: "", drive: "C:", initialization: template.autoexec)
                    let programs = store.files.diskInfo(store.files.mediaURL(imported, imported.media[0]))["programs"] as? [String] ?? []
                    guard programs.contains("NECGAME/開始.BAT") else { throw NECError(message: "DOS 日文入口解码失败") }
                    imported.options["np2kai_FastMC"] = "ON"
                    let blank = try store.files.addBlank(&imported); imported.floppyA = DiskRef(file: blank.file); try store.files.save(imported)
                    return imported
                }
                store.platform = platform; store.reload()
                try await wait("游戏库") { store.games.contains { $0.id == game.id } }
                try await capture("pc\(platform)-library", documents: documents)
                store.start(game)
                guard let session = store.player else { throw NECError(message: "未创建游玩会话") }
                try await wait("启动 PC-\(platform)") { session.ready || session.error != nil }
                try checked(session.error ?? "")
                try await Task.sleep(nanoseconds: platform == 98 ? 15_000_000_000 : 3_000_000_000)
                guard NECBridge.videoGeometry() != 0, let png = session.display?.snapshotPNG(), png.count > 100 else { throw NECError(message: "PC-\(platform) 未输出可用画面") }
                try png.write(to: documents.appendingPathComponent("pc\(platform)-frame.png"))
                session.toolsExpanded = true
                try await capture("pc\(platform)-player", documents: documents)
                session.toolsExpanded = false
                NECBridge.key(13, down: true); NECBridge.key(13, down: false)
                try await Task.sleep(nanoseconds: 300_000_000)
                session.userPaused = true
                session.state(7, load: false)
                try await wait("快速存档") { !session.busy }; try checked(session.error ?? "")
                if platform == 98 {
                    let entries = try await files(store) { store.files.diskInfo(store.files.mediaURL(game, game.media[0]))["entries"] as? [[String: Any]] ?? [] }
                    guard entries.contains(where: { $0["name"] as? String == "IOSBOOT.TXT" }) else { throw NECError(message: "DOS 启动脚本未执行，请查看 PC-98 画面") }
                    results.append("PC-98: Japanese DOS startup script executed and wrote IOSBOOT.TXT")
                }
                session.userPaused = false
                session.state(7, load: true)
                try await wait("快速读档") { !session.busy }; try checked(session.error ?? "")
                session.state(1, load: false)
                try await wait("手动存档") { !session.busy }; try checked(session.error ?? "")
                session.panel = .disks
                session.mount(0, ref: nil)
                try await wait("退盘") { !session.busy }; try checked(session.error ?? "")
                guard NECBridge.disk(0).isEmpty else { throw NECError(message: "退盘后软驱未清空") }
                session.mount(0, ref: game.floppyA)
                try await wait("装盘") { !session.busy }; try checked(session.error ?? "")
                guard NECBridge.disk(0) == (try store.files.path(game, game.floppyA)) else { throw NECError(message: "装盘结果不匹配") }
                try await capture("pc\(platform)-disks", documents: documents)
                session.panel = nil; session.foreground = false
                try await wait("后台会话保存") { StateStore(store.files.directory(game)).hasAuto() || session.error != nil }; try checked(session.error ?? "")
                session.foreground = true; session.stop()
                try await wait("保存并退出") { store.player == nil || session.error != nil }; try checked(session.error ?? "")
                let saved = try await files(store) { try store.files.find(game.id, platform: platform) }
                guard saved.playMillis > 0 else { throw NECError(message: "游玩时长没有保存") }
                try await Task.sleep(nanoseconds: 800_000_000)
                store.start(saved, slot: 0)
                guard let reopened = store.player else { throw NECError(message: "未重新创建会话") }
                try await wait("继续上次游戏") { reopened.ready || reopened.error != nil }; try checked(reopened.error ?? "")
                reopened.panel = .states
                try await capture("pc\(platform)-states", documents: documents)
                reopened.panel = nil; reopened.stop()
                try await wait("结束会话") { store.player == nil || reopened.error != nil }; try checked(reopened.error ?? "")
                try await Task.sleep(nanoseconds: 800_000_000)
                try await files(store) {
                    let original = store.files.directory(game)
                    let moved = documents.appendingPathComponent("Relocated/NECMobile/pc\(platform)/games/\(game.id)")
                    try fileManager.createDirectory(at: moved.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try fileManager.moveItem(at: original, to: moved)
                    defer { NECBridge.stop(); try? fileManager.moveItem(at: moved, to: original) }
                    let configuration = moved.appendingPathComponent("machine.cfg")
                    let text = try String(contentsOf: configuration, encoding: .utf8).replacingOccurrences(of: original.path, with: moved.path)
                    try Data(text.utf8).write(to: configuration, options: .atomic)
                    func path(_ ref: DiskRef?) -> String { ref.map { moved.appendingPathComponent("media/\($0.file)").path } ?? "" }
                    let first = saved.hardDisk ?? saved.floppyA
                    try checked(NECBridge.start(platform, directory: moved.path, first: path(first), second: path(saved.floppyB), mode: saved.mode, firstIndex: first?.index ?? 0, secondIndex: saved.floppyB?.index ?? 0))
                    let deadline = ProcessInfo.processInfo.systemUptime + 45
                    while NECBridge.startupState() == 1 && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.05) }
                    guard NECBridge.startupState() == 2 else { throw NECError(message: "目录移动后启动失败：" + NECBridge.startupError()) }
                    try StateStore(moved).load(0)
                    guard NECBridge.disk(0) == path(saved.floppyA) else { throw NECError(message: "目录移动后存档磁盘路径错误") }
                    NECBridge.pause(false); Thread.sleep(forTimeInterval: 0.3)
                }
                results.append("PC-\(platform): import, rendered UI, quick/manual state, eject/mount, background save, playtime and resume passed")
                results.append("PC-\(platform): saved session restored after moving the data directory")
            }
            try atomicJSON(["status": "passed", "checks": results.joined(separator: "\n")], to: documents.appendingPathComponent("ios-smoke.json"))
        } catch {
            NECBridge.stop(); try? atomicJSON(["status": "failed", "error": error.localizedDescription, "checks": results.joined(separator: "\n")], to: documents.appendingPathComponent("ios-smoke.json"))
        }
    }
}
