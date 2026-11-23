import SwiftUI
import UniformTypeIdentifiers

struct GameDetailView: View {
    @ObservedObject var store: LibraryStore
    let initial: Game
    let choose: (ImportPurpose, [UTType], Bool) -> Void
    @State private var game: Game
    @State private var share: ShareItem?
    @State private var remove = false
    @State private var diskToRemove: DiskImage?
    @State private var repair = false
    @Environment(\.presentationMode) private var presentation
    init(store: LibraryStore, initial: Game, choose: @escaping (ImportPurpose, [UTType], Bool) -> Void) {
        self.store = store; self.initial = initial; self.choose = choose; _game = State(initialValue: initial)
    }
    var body: some View {
        Form {
            Section { VStack(alignment: .leading, spacing: 6) { Text(game.name).font(.title2.bold()); Text("PC-\(game.platform) · \(game.mediaInfo)").font(.caption).foregroundColor(.secondary); Text("累计游玩 \(duration(game.playMillis))").font(.caption).foregroundColor(.secondary) } }
            Section("游戏配置") {
                TextField("显示名称", text: $game.name)
                if game.platform == 88 { Picker("机器模式", selection: $game.mode) { ForEach(Array(store.files.schema.modes.enumerated()), id: \.offset) { Text($0.element).tag($0.offset) } } }
                else if let option = options.first(where: { $0.key == "np2kai_model" }) { OptionControl(option: option, game: $game) }
                Toggle("静音", isOn: Binding(get: { game.options["output_volume"] == "0" }, set: { game.options["output_volume"] = $0 ? "0" : "100" }))
                Button("保存配置") { store.save(game) }
                Button { store.save(game) { store.start(game) } } label: { Label("启动", systemImage: "play.fill") }
            }
            Section("启动与机器") {
                NavigationLink(game.platform == 98 ? "启动环境与盘位" : "默认盘位") { BootView(store: store, game: $game) }
                NavigationLink("完整机器设置") { MachineSettingsView(store: store, game: $game) }
                if game.platform == 98 { NavigationLink("字库设置 · FREECG98.BMP") { FontsView(store: store, game: $game, choose: choose) } }
            }
            Section("关联镜像") {
                ForEach(game.media) { disk in
                    HStack { VStack(alignment: .leading) { Text(disk.name); if disk.titles.count > 1 { Text("含 \(disk.titles.count) 张内嵌盘").font(.caption).foregroundColor(.secondary) } }; Spacer()
                        Menu { Button("导出磁盘副本") { share = ShareItem(url: store.files.mediaURL(game, disk)) }; Button("移除文件", role: .destructive) { diskToRemove = disk } } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                    }
                }
                if game.platform == 98 {
                    Button("新建 1.2 MB 存档盘") { modify { game in _ = try store.files.addBlank(&game) } }
                    Button("添加 DOS 维护工具盘") { modify { game in _ = try store.files.addTools(&game) } }
                }
            }
            Section("游戏资源与维护") {
                Button("导入本游戏固件 / 字库") { choose(.resource(game), [.data, .zip], true) }
                Button("修复磁盘名称编码") { repair = true }
                if !game.lastMachineInfo.isEmpty { Text(game.lastMachineInfo).font(.caption).textSelection(.enabled) }
                Button("移除游戏", role: .destructive) { remove = true }
            }
        }.navigationTitle("游戏管理").navigationBarTitleDisplayMode(.inline).disabled(store.busy)
            .sheet(item: $share) { ShareView(url: $0.url) }
            .sheet(isPresented: $repair) { RepairNamesView(store: store, game: $game) }
            .alert("移除游戏？", isPresented: $remove) { Button("移除", role: .destructive) { store.perform("正在移除…", operation: { try store.files.remove(game) }) { _ in presentation.wrappedValue.dismiss() } }; Button("取消", role: .cancel) {} } message: { Text("将删除应用内磁盘和即时档，需要的游戏内存档可先导出磁盘。") }
            .alert("移除磁盘副本？", isPresented: Binding(get: { diskToRemove != nil }, set: { if !$0 { diskToRemove = nil } })) { Button("移除", role: .destructive) { if let disk = diskToRemove { diskToRemove = nil; modify { value in try store.files.removeDisk(value, disk: disk); value.media.removeAll { $0.file == disk.file } } } }; Button("取消", role: .cancel) { diskToRemove = nil } } message: { Text("此磁盘副本中的游戏内存档也会删除。") }
    }
    private var options: [MachineOption] { store.files.schema.platforms[String(game.platform)] ?? [] }
    private func modify(_ change: @escaping (inout Game) throws -> Void) {
        let current = game
        store.perform("正在更新游戏…", operation: { var result = try store.files.find(current.id, platform: current.platform); try change(&result); return try store.files.find(result.id, platform: result.platform) }) { game = $0 }
    }
}

struct OptionControl: View {
    let option: MachineOption
    @Binding var game: Game
    private var value: String { option.value(game) }
    var body: some View {
        if option.key == "m88_dipsw" {
            NavigationLink("DIP-SW · \(option.display(value))") { DipsView(game: $game) }
        } else if option.range {
            VStack(alignment: .leading) {
                Text("\(option.label) · \(option.display(value))")
                Slider(value: Binding(get: { Double(Int(value) ?? option.min) }, set: { game.options[option.key] = String(Int($0.rounded())) }), in: Double(option.min)...Double(option.max), step: 1)
            }
        } else {
            Picker(option.label, selection: Binding(get: { value }, set: { game.options[option.key] = $0 })) {
                ForEach(Array(option.values.enumerated()), id: \.offset) { index, raw in Text(option.names.indices.contains(index) ? option.names[index] : raw).tag(raw) }
            }
        }
    }
}
struct MachineSettingsView: View {
    @ObservedObject var store: LibraryStore
    @Binding var game: Game
    var body: some View {
        Form {
            if game.platform == 88 { Section("BASIC 模式") { Picker("机器模式", selection: $game.mode) { ForEach(Array(store.files.schema.modes.enumerated()), id: \.offset) { Text($0.element).tag($0.offset) } } } }
            ForEach([0, 4, 1, 2, 3, 5, 6], id: \.self) { group in
                Section(["机器与 CPU", "模拟音源", "混音器", "高级音源", "兼容与 DIP-SW", "画面与布局", "手机输出与速度"][group]) {
                    ForEach((store.files.schema.platforms[String(game.platform)] ?? []).filter { $0.group == group }) { OptionControl(option: $0, game: $game) }
                }
            }
        }.navigationTitle("PC-\(game.platform) 系统设置").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("保存") { store.save(game) } }.disabled(store.busy)
    }
}
struct DipsView: View {
    @Binding var game: Game
    let names = ["终端 / BASIC", "40 / 80 字符", "20 / 25 行", "S 参数", "DEL 处理", "校验方式", "奇偶", "数据位", "停止位", "X 参数", "通信方式", "启动模式"]
    var body: some View { Form { ForEach(0..<12) { bit in Toggle(names[bit], isOn: Binding(get: { ((Int(game.options["m88_dipsw"] ?? "1829") ?? 1829) & (1 << bit)) != 0 }, set: { enabled in let current = Int(game.options["m88_dipsw"] ?? "1829") ?? 1829; game.options["m88_dipsw"] = String(enabled ? current | (1 << bit) : current & ~(1 << bit)) })) }; Button("恢复默认值") { game.options["m88_dipsw"] = "1829" } }.navigationTitle("DIP-SW") }
}
struct DrivePicker: View {
    let title: String
    let media: [DiskImage]
    @Binding var selected: DiskRef?
    var body: some View {
        Picker(title, selection: $selected) {
            Text("空").tag(nil as DiskRef?)
            ForEach(media) { disk in ForEach(disk.titles.indices, id: \.self) { index in Text(disk.label(index)).tag(DiskRef(file: disk.file, index: index) as DiskRef?) } }
        }
    }
}
struct BootView: View {
    @ObservedObject var store: LibraryStore
    @Binding var game: Game
    @State private var programs: [String] = []
    @State private var templates: [(String, String, URL)] = []
    var body: some View {
        Form {
            Section("默认盘位") {
                DrivePicker(title: "软驱 A", media: game.media.filter { !$0.hard }, selected: $game.floppyA)
                DrivePicker(title: "软驱 B", media: game.media.filter { !$0.hard }, selected: $game.floppyB)
                if game.platform == 98 { DrivePicker(title: "硬盘槽", media: game.media.filter(\.hard), selected: $game.hardDisk) }
                Picker("引导设备", selection: $game.bootDevice) { Text("自动").tag("auto"); Text("软驱 A").tag("floppy"); if game.platform == 98 { Text("硬盘").tag("hdd") } }
                Text("A、B 为模拟软驱槽，DOS 盘符由游戏系统分配。").font(.caption).foregroundColor(.secondary)
            }
            if game.platform == 98 {
                Section("准备启动环境") {
                    Picker("系统盘", selection: $game.startupTemplate) { Text("默认 FreeDOS(98)").tag(""); ForEach(templates, id: \.0) { Text($0.1).tag($0.0) } }
                    Picker("游戏数据盘", selection: $game.startupSource) { Text("自动选择").tag(""); ForEach(game.media.filter { $0.role.isEmpty }) { Text($0.name).tag($0.file) } }.onChange(of: game.startupSource) { _ in loadPrograms() }
                    if !programs.isEmpty { Picker("启动文件", selection: $game.startupEntry) { Text("自动选择").tag(""); ForEach(programs, id: \.self) { Text($0).tag($0) } } }
                    TextField("启动路径，也可留空自动选择", text: $game.startupEntry).autocapitalization(.none)
                    Picker("DOS 内存驱动", selection: $game.startupMemory) { Text("无").tag("none"); Text("XMS · 自备驱动").tag("xms"); Text("XMS + EMS · 自备驱动").tag("ems") }
                    Button("准备启动环境") {
                        let current = game
                        store.perform("正在准备启动环境…", operation: { var prepared = current; let entry = prepared.startupEntry, source = prepared.startupSource; try store.files.prepareBoot(&prepared, entry: entry, source: source); try store.files.save(prepared); return prepared }) { game = $0 }
                    }
                    if !game.startupHint.isEmpty { Text(game.startupHint).font(.caption).foregroundColor(.secondary) }
                }
            }
            Button("保存盘位") { store.perform("正在保存盘位…", operation: { try store.files.validate(game); try store.files.save(game) }) }
        }.navigationTitle("启动环境与盘位").disabled(store.busy)
            .onAppear { store.queue.async { let values = (try? store.files.bootTemplates()) ?? []; DispatchQueue.main.async { templates = values } }; loadPrograms() }
    }
    private func loadPrograms() {
        let selected = game.disk(DiskRef(file: game.startupSource)) ?? game.media.first { $0.role.isEmpty }
        guard let disk = selected else { programs = []; return }
        let current = game; store.queue.async { let result = store.files.diskInfo(store.files.mediaURL(current, disk))["programs"] as? [String] ?? []; DispatchQueue.main.async { programs = result } }
    }
}
struct FontsView: View {
    @ObservedObject var store: LibraryStore
    @Binding var game: Game
    let choose: (ImportPurpose, [UTType], Bool) -> Void
    @State private var fonts: [FontChoice] = []
    var body: some View {
        Form {
            Picker("本游戏字库", selection: $game.font) { Text("自动按来源选择").tag(""); ForEach(fonts) { Text($0.name).tag($0.id) } }
            Button("保存选择") { store.save(game) }
            Button("导入本游戏字库") { choose(.resource(game), [.data], false) }
            Text("支持 FREECG98.BMP、FONT.BMP 和 FONT.ROM。BMP 需为 2048×2048、1 位未压缩格式。").font(.caption).foregroundColor(.secondary)
        }.navigationTitle("字库设置").onAppear { fonts = (try? store.files.fonts(game)) ?? [] }.onReceive(store.$games) { _ in fonts = (try? store.files.fonts(game)) ?? [] }
    }
}
struct RepairNamesView: View {
    @ObservedObject var store: LibraryStore
    @Binding var game: Game
    @State private var selected = Set<String>()
    @Environment(\.presentationMode) private var presentation
    private var repairs: [(DiskImage, String)] { game.media.compactMap { disk in guard let bytes = disk.name.data(using: oemChinese, allowLossyConversion: false), String(data: bytes, encoding: oemChinese) == disk.name, let recovered = String(data: bytes, encoding: oemJapanese), recovered != disk.name else { return nil }; return (disk, recovered) } }
    var body: some View {
        NavigationView { Form {
            if repairs.isEmpty { Text("没有找到可按 GBK → Shift_JIS 恢复的磁盘名称") }
            ForEach(repairs, id: \.0.file) { disk, name in Toggle(isOn: Binding(get: { selected.contains(disk.file) }, set: { if $0 { selected.insert(disk.file) } else { selected.remove(disk.file) } })) { VStack(alignment: .leading) { Text(disk.name); Text("→ \(name)").foregroundColor(necAccent) } } }
            Button("应用所选名称") { let mapping = Dictionary(uniqueKeysWithValues: repairs.filter { selected.contains($0.0.file) }.map { ($0.0.file, $0.1) }); for index in game.media.indices { if let name = mapping[game.media[index].file] { game.media[index].name = name } }; store.save(game) { presentation.wrappedValue.dismiss() } }
        }.navigationTitle("修复名称编码").toolbar { Button("关闭") { presentation.wrappedValue.dismiss() } } }.onAppear { selected = Set(repairs.map { $0.0.file }) }
    }
}
struct DOSWizard: View {
    @ObservedObject var store: LibraryStore
    let draft: DosDraft
    @State private var templates: [DosTemplate] = []
    @State private var templateID = "builtin", entry = "", working = "", drive = "C:", initialization = "", name = ""
    @Environment(\.presentationMode) private var presentation
    private var selected: DosTemplate? { templates.first { $0.id == templateID } ?? templates.first }
    var body: some View {
        NavigationView { Form {
            Section("建立 DOS 游戏硬盘") {
                TextField("游戏名称", text: $name)
                Picker("系统模板", selection: $templateID) { ForEach(templates) { Text($0.name).tag($0.id) } }.onChange(of: templateID) { _ in drive = selected?.drive ?? "C:"; initialization = selected?.autoexec ?? "" }
                Picker("启动文件", selection: $entry) { ForEach(draft.programs, id: \.self) { Text($0).tag($0) } }.onChange(of: entry) { working = ($0 as NSString).deletingLastPathComponent }
                DisclosureGroup("高级启动设置") { TextField("工作目录", text: $working).autocapitalization(.none); Picker("DOS 盘符", selection: $drive) { ForEach(["A:", "B:", "C:", "D:", "E:", "F:"], id: \.self) { Text($0).tag($0) } }; TextEditor(text: $initialization).frame(minHeight: 120) }
                Button("建立硬盘") { guard let selected = selected else { return }; var value = draft; value.name = name; store.perform("正在建立游戏硬盘…", operation: { try store.files.commitDOS(value, template: selected, entry: entry, working: working, drive: drive, initialization: initialization) }) { _ in store.dosDraft = nil; presentation.wrappedValue.dismiss() } }.disabled(selected == nil || entry.isEmpty || name.isEmpty)
            }
            if store.busy { ProgressView(store.progress) }
        }.navigationTitle("DOS 目录导入").toolbar { Button("取消") { if !store.busy { store.queue.async { try? fileManager.removeItem(at: draft.directory) }; store.dosDraft = nil; presentation.wrappedValue.dismiss() } }.disabled(store.busy) } }
            .onAppear { name = draft.name; entry = draft.programs.first ?? ""; working = (entry as NSString).deletingLastPathComponent; store.queue.async { let result = (try? store.files.templates()) ?? []; DispatchQueue.main.async { templates = result; drive = selected?.drive ?? "C:"; initialization = selected?.autoexec ?? "" } } }
            .interactiveDismissDisabled(store.busy)
    }
}
