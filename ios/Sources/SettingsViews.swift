import SwiftUI
import UniformTypeIdentifiers
import AVFoundation

extension LibraryFiles {
    func importSoundFont(_ source: URL) throws {
        guard source.pathExtension.lowercased() == "sf2", byteSize(source) > 0, byteSize(source) <= 64 * 1024 * 1024, NECBridge.validSoundFont(source.path) else { throw NECError(message: "请选择有效的 SF2 音色库，最大 64 MB") }
        let directory = root.appendingPathComponent("midi"); try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = UUID().uuidString.lowercased() + ".sf2", target = directory.appendingPathComponent(name)
        try fileManager.copyItem(at: source, to: target); try Data(source.lastPathComponent.utf8).write(to: target.appendingPathExtension("name"), options: .atomic)
        UserDefaults.standard.set(name, forKey: "soundfont")
    }
    func soundFonts() -> [(String, String, URL)] {
        ((try? fileManager.contentsOfDirectory(at: root.appendingPathComponent("midi"), includingPropertiesForKeys: nil)) ?? []).filter { $0.pathExtension == "sf2" }.map { ($0.lastPathComponent, (try? String(contentsOf: $0.appendingPathExtension("name"), encoding: .utf8)) ?? $0.lastPathComponent, $0) }.sorted { $0.1.localizedStandardCompare($1.1) == .orderedAscending }
    }
    func storageUsage() throws -> [String: Int64] {
        var disks: Int64 = 0, states: Int64 = 0, raw: Int64 = 0
        for platform in [88, 98] { for game in try list(platform) {
            for disk in game.media { disks += byteSize(mediaURL(game, disk)) }
            let slots = StateStore(directory(game)); states += byteSize(slots.root)
            for slot in StateStore.slots { if let folder = try? slots.folder(slot) { raw += byteSize(folder.appendingPathComponent("machine.bin")) } }
        } }
        return ["游戏磁盘": disks, "即时存档": states, "资源与其他数据": max(0, byteSize(root) - disks - states), "合计": byteSize(root), "未压缩即时档": raw]
    }
    func compressStates() throws -> Int64 {
        var removed: Int64 = 0
        for platform in [88, 98] { for game in try list(platform) { removed += try StateStore(directory(game)).compact() } }
        return removed
    }
    func cleanTemporary() throws -> Int64 {
        let before = byteSize(root)
        for platform in [88, 98] { for game in try list(platform) {
            let slots = StateStore(directory(game)); slots.prune()
            for file in (try? fileManager.contentsOfDirectory(at: slots.root, includingPropertiesForKeys: nil)) ?? [] {
                if file.lastPathComponent == "recovery" || (file.lastPathComponent.hasPrefix("load-") && UUID(uuidString: String(file.lastPathComponent.dropFirst(5))) != nil) { try fileManager.removeItem(at: file) }
                else if UUID(uuidString: file.lastPathComponent) != nil {
                    for child in (try? fileManager.contentsOfDirectory(at: file, includingPropertiesForKeys: nil)) ?? [] where child.lastPathComponent.hasPrefix("pack-") && child.pathExtension == "pending" { try fileManager.removeItem(at: child) }
                }
            }
        } }
        for file in (try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            for prefix in ["import-", "resource-import-", "dos-import-"] where file.lastPathComponent.hasPrefix(prefix) && UUID(uuidString: String(file.lastPathComponent.dropFirst(prefix.count))) != nil { try fileManager.removeItem(at: file) }
        }
        return max(0, before - byteSize(root))
    }
}

struct PlatformSettingsView: View {
    @ObservedObject var store: LibraryStore
    let choose: (ImportPurpose, [UTType], Bool) -> Void
    var body: some View {
        Form {
            Section { Picker("当前平台", selection: $store.platform) { Text("PC-98").tag(98); Text("PC-88").tag(88) }.pickerStyle(.segmented) }
            Section("系统资源 · PC-\(store.platform)") {
                Button { choose(.resource(nil), [.data, .zip], true) } label: { Label("系统 ROM / 字库", systemImage: "cpu") }
                if store.platform == 98 { NavigationLink { TemplatesView(store: store, choose: choose) } label: { Label("DOS 系统模板", systemImage: "internaldrive") } }
            }
            Section("音频 · 所有游戏") {
                NavigationLink { SoundFontsView(store: store, choose: choose) } label: { Label("MIDI 音色库", systemImage: "pianokeys") }
                NavigationLink { AudioSettingsView() } label: { Label("手机音频输出", systemImage: "speaker.wave.2") }
            }
            Section("数据管理") { NavigationLink { StorageView(store: store) } label: { Label("数据占用与清理", systemImage: "folder") } }
            Section("帮助") {
                NavigationLink("使用说明") { ScrollView { Text("在顶部切换 PC-88 / PC-98，通过添加游戏导入镜像、文件夹或 ZIP。多盘可一次选入。游戏管理中配置机型、音源、字库和 A / B / 硬盘盘位。\n\nPC-98 缺少 DOS 时，可准备内置 FreeDOS(98) 启动环境，指定游戏入口。DOS 目录可写入独立 HDI。\n\n游玩中展开工具球使用 Esc、Enter、空格、快存、快读、键盘和菜单。滑动先输入一次方向，保持触摸 420 毫秒后持续按住。虚拟鼠标模式下滑动移动光标、轻触左击、双指右击、长按拖动。实体键盘和鼠标也可使用。\n\n六个手动档、快速档和退出会话独立保存。切后台时自动保存，下次启动选择继续或重新启动。即时档采用压缩格式，读取时临时展开。\n\n游戏内存档可通过导出磁盘副本备份。移除游戏或卸载应用会删除应用内数据。目录也可通过“文件”App 或电脑的文件共享访问。\n\n安装测试 IPA 时由设备持有人使用自己的签名账号，免费签名的有效期由 Apple 个人开发配置决定。").padding().textSelection(.enabled) }.navigationTitle("使用说明") }
                NavigationLink("来源与许可") { LicensesView() }
            }
            Text("NEC Mobile · \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · iOS").font(.caption).foregroundColor(.secondary)
        }.navigationTitle("平台设置").disabled(store.busy)
    }
}
struct SoundFontsView: View {
    @ObservedObject var store: LibraryStore
    let choose: (ImportPurpose, [UTType], Bool) -> Void
    @State private var selected = UserDefaults.standard.string(forKey: "soundfont") ?? ""
    @State private var refresh = UUID()
    var body: some View {
        List {
            ForEach(store.files.soundFonts(), id: \.0) { name, label, url in
                Button { selected = name; UserDefaults.standard.set(name, forKey: "soundfont") } label: { HStack { Text(label); Spacer(); if selected == name { Image(systemName: "checkmark") } } }
                    .swipeActions { Button("移除", role: .destructive) { store.perform("正在移除音色库…", operation: { try fileManager.removeItem(at: url); try? fileManager.removeItem(at: url.appendingPathExtension("name")); if selected == name { UserDefaults.standard.removeObject(forKey: "soundfont") } }) { _ in refresh = UUID() } } }
            }
            Button("导入 SF2 音色库") { choose(.soundfont, [.data], false) }
        }.id(refresh).navigationTitle("MIDI 音色库").onReceive(store.$games) { _ in refresh = UUID(); selected = UserDefaults.standard.string(forKey: "soundfont") ?? "" }
    }
}
struct AudioSettingsView: View {
    @AppStorage("audioRate") private var rate = 0
    @AppStorage("audioBuffer") private var buffer = 40
    var body: some View {
        Form {
            Picker("采样率", selection: $rate) { Text("系统选择").tag(0); Text("44100 Hz").tag(44100); Text("48000 Hz").tag(48000) }
            Picker("缓冲", selection: $buffer) { ForEach([20, 40, 80, 120], id: \.self) { Text("\($0) ms").tag($0) } }
            Section("当前媒体输出") { Text(AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portName).joined(separator: " / ")); Text("输出设备跟随系统，采样率和缓冲在下次启动生效。").font(.caption).foregroundColor(.secondary) }
        }.navigationTitle("手机音频输出")
    }
}
struct TemplatesView: View {
    @ObservedObject var store: LibraryStore
    let choose: (ImportPurpose, [UTType], Bool) -> Void
    @State private var templates: [DosTemplate] = []
    var body: some View {
        List {
            Section("DOS 硬盘模板") { ForEach(templates) { Text("\($0.name) · \($0.drive)") } }
            Button("导入自备 DOS 系统盘") { choose(.template, [.data], false) }
            Button("导入自备 DOS 内存驱动") { choose(.resource(nil), [.data], true) }
            Text("内置 FreeDOS(98) 提供软盘和硬盘启动环境。自备 HDI 用于目录导入，FDI / D88 系统盘可用于补齐启动环境。").font(.caption).foregroundColor(.secondary)
        }.navigationTitle("DOS 系统模板").onAppear { load() }.onReceive(store.$games) { _ in load() }
    }
    private func load() { store.queue.async { let values = (try? store.files.templates()) ?? []; DispatchQueue.main.async { templates = values } } }
}
struct StorageView: View {
    @ObservedObject var store: LibraryStore
    @State private var usage: [String: Int64] = [:]
    @State private var message = ""
    var body: some View {
        Form {
            if !message.isEmpty { Text(message).foregroundColor(necAccent) }
            ForEach(["游戏磁盘", "即时存档", "资源与其他数据", "合计"], id: \.self) { name in HStack { Text(name); Spacer(); Text(formattedBytes(usage[name] ?? 0)).foregroundColor(.secondary) } }
            if (usage["未压缩即时档"] ?? 0) > 0 { Button("压缩已有即时档") { store.perform("正在压缩…", operation: { try store.files.compressStates() }) { bytes in message = "压缩完成，已释放 \(formattedBytes(bytes))"; load() } } }
            Button("清理历史残留") { store.perform("正在清理…", operation: { try store.files.cleanTemporary() }) { bytes in message = "已释放 \(formattedBytes(bytes))"; load() } }
            Text("即时档包含模拟内存和设备状态，采用快速压缩保存。操作完成后回收临时展开文件。").font(.caption).foregroundColor(.secondary)
            if store.busy { ProgressView(store.progress) }
        }.navigationTitle("数据占用与清理").onAppear { load() }.disabled(store.busy)
    }
    private func load() { store.queue.async { let result = (try? store.files.storageUsage()) ?? [:]; DispatchQueue.main.async { usage = result } } }
}
struct LicensesView: View {
    private var directory: URL { LibraryFiles.assets.appendingPathComponent("licenses") }
    private var files: [URL] { ((try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []).sorted { $0.lastPathComponent < $1.lastPathComponent } }
    var body: some View {
        List(files, id: \.path) { file in NavigationLink(file.lastPathComponent) { ScrollView { Text(text(file)).font(.caption).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding() }.navigationTitle(file.lastPathComponent) } }.navigationTitle("来源与许可")
    }
    private func text(_ file: URL) -> String { guard let data = try? Data(contentsOf: file) else { return "无法读取许可文件" }; return String(data: data, encoding: .utf8) ?? String(data: data, encoding: oemJapanese) ?? "文本编码无法显示" }
}
