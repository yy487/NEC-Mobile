import Foundation

enum IOSSmoke {
    static func run(_ store: LibraryStore) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            store.queue.async {
                var results: [String] = []
                do {
                    for platform in [88, 98] {
                        let source: URL
                        if platform == 88 {
                            source = store.files.root.appendingPathComponent("blank.d88")
                            var header = Data(repeating: 0, count: 0x2b0); let length = UInt32(header.count)
                            for offset in 0..<4 { header[0x1c + offset] = UInt8((length >> (offset * 8)) & 255) }
                            try header.write(to: source)
                        } else { source = try store.files.asset("boot/freedos-v2.fdi") }
                        let game = try store.files.importImages([source], platform: platform, title: "Core smoke \(platform)")
                        try store.files.prepare(game)
                        let first = game.hardDisk ?? game.floppyA
                        try checked(NECBridge.start(platform, directory: store.files.directory(game).path, first: try store.files.path(game, first), second: try store.files.path(game, game.floppyB), mode: game.mode, firstIndex: first?.index ?? 0, secondIndex: game.floppyB?.index ?? 0))
                        var deadline = ProcessInfo.processInfo.systemUptime + 45
                        while NECBridge.startupState() == 1 && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.05) }
                        guard NECBridge.startupState() == 2 else { throw NECError(message: "PC-\(platform) 启动失败：\(NECBridge.startupError())") }
                        NECBridge.pause(false); Thread.sleep(forTimeInterval: 0.5); NECBridge.key(13, down: true); NECBridge.key(13, down: false); Thread.sleep(forTimeInterval: 0.3); NECBridge.pause(true)
                        guard NECBridge.videoGeometry() != 0 else { throw NECError(message: "PC-\(platform) 没有输出画面") }
                        let slots = StateStore(store.files.directory(game)); try slots.save(7); try slots.load(7); try slots.save(0)
                        NECBridge.stop()
                        try checked(NECBridge.start(platform, directory: store.files.directory(game).path, first: try store.files.path(game, first), second: try store.files.path(game, game.floppyB), mode: game.mode, firstIndex: first?.index ?? 0, secondIndex: game.floppyB?.index ?? 0))
                        deadline = ProcessInfo.processInfo.systemUptime + 45
                        while NECBridge.startupState() == 1 && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.05) }
                        guard NECBridge.startupState() == 2 else { throw NECError(message: "PC-\(platform) 再次初始化失败") }
                        try slots.load(0); NECBridge.stop(); try store.files.remove(game)
                        results.append("PC-\(platform) boot, video, input, compressed save/load and same-process reopen passed")
                    }
                    try atomicJSON(["status": "passed", "checks": results.joined(separator: "\n")], to: store.files.root.deletingLastPathComponent().appendingPathComponent("ios-smoke.json"))
                } catch {
                    NECBridge.stop(); try? atomicJSON(["status": "failed", "error": error.localizedDescription, "checks": results.joined(separator: "\n")], to: store.files.root.deletingLastPathComponent().appendingPathComponent("ios-smoke.json"))
                }
                continuation.resume()
            }
        }
    }
}
