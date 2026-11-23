import SwiftUI
import UniformTypeIdentifiers

let necBackground = Color(red: 22/255, green: 27/255, blue: 25/255)
let necSurface = Color(red: 32/255, green: 39/255, blue: 36/255)
let necAccent = Color(red: 199/255, green: 236/255, blue: 170/255)

enum ImportPurpose { case games, dos, resource(Game?), soundfont, template }
struct ShareItem: Identifiable { let id = UUID(); var url: URL }
struct ShareView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
struct DocumentPicker: UIViewControllerRepresentable {
    var types: [UTType]; var multiple: Bool; var complete: ([URL]) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(complete) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false); picker.allowsMultipleSelection = multiple; picker.delegate = context.coordinator; return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let complete: ([URL]) -> Void; init(_ complete: @escaping ([URL]) -> Void) { self.complete = complete }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { complete(urls) }
    }
}

@main struct NECMobileApp: App {
    @UIApplicationDelegateAdaptor(OrientationDelegate.self) private var delegate
    @StateObject private var store = LibraryStore()
    @Environment(\.scenePhase) private var phase
    init() {
        let nav = UINavigationBarAppearance(); nav.configureWithOpaqueBackground(); nav.backgroundColor = UIColor(necBackground)
        nav.titleTextAttributes = [.foregroundColor: UIColor.white]; nav.largeTitleTextAttributes = [.foregroundColor: UIColor.white]
        UINavigationBar.appearance().standardAppearance = nav; UINavigationBar.appearance().scrollEdgeAppearance = nav
        let tab = UITabBarAppearance(); tab.configureWithOpaqueBackground(); tab.backgroundColor = UIColor(necBackground)
        UITabBar.appearance().standardAppearance = tab; UITabBar.appearance().scrollEdgeAppearance = tab
    }
    var body: some Scene {
        WindowGroup {
            RootView(store: store).preferredColorScheme(.dark).tint(necAccent)
                .onChange(of: phase) { store.player?.foreground = $0 == .active }
                .task { if ProcessInfo.processInfo.arguments.contains("--smoke-test") { await IOSSmoke.run(store) } }
        }
    }
}

struct RootView: View {
    @ObservedObject var store: LibraryStore
    @State private var add = false
    @State private var picker = false
    @State private var purpose = ImportPurpose.games
    @State private var types: [UTType] = [.data]
    @State private var multiple = true
    @State private var query88 = ""
    @State private var query98 = ""
    @State private var filter88 = 0
    @State private var filter98 = 0
    @State private var resume: Game?
    @State private var tab = 0
    @State private var pendingImages: [URL] = []
    @State private var importName = ""
    @State private var naming = false
    private func choose(_ purpose: ImportPurpose, types: [UTType], multiple: Bool = false) { self.purpose = purpose; self.types = types; self.multiple = multiple; picker = true }
    var body: some View {
        TabView(selection: $tab) {
            NavigationView {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        platformSwitch
                        if store.busy { ProgressView(store.progress).frame(maxWidth: .infinity) }
                        if let recent = store.games.first(where: { $0.played != nil }) { recentCard(recent) }
                        HStack { Text("全部游戏").font(.headline); Spacer(); Text("\(visible.count) / \(store.games.count) 款").font(.caption).foregroundColor(.secondary) }
                        HStack {
                            TextField("搜索游戏", text: store.platform == 88 ? $query88 : $query98).textFieldStyle(.roundedBorder).autocapitalization(.none)
                            Menu { ForEach(0..<(store.platform == 88 ? 2 : 4), id: \.self) { value in Button(["全部类型", "软盘", "硬盘", "混合介质"][value]) { if store.platform == 88 { filter88 = value } else { filter98 = value } } } } label: { Image(systemName: "line.3.horizontal.decrease.circle").font(.title2) }.accessibilityLabel("按镜像类型筛选")
                        }
                        LazyVStack(spacing: 0) { ForEach(visible) { game in gameRow(game) } }
                        if visible.isEmpty { Text(store.games.isEmpty ? "添加镜像、游戏文件夹或 ZIP 以建立游戏库" : "没有符合条件的游戏").foregroundColor(.secondary).frame(maxWidth: .infinity).padding() }
                    }.padding(20)
                }.background(necBackground).navigationTitle("NEC Mobile").navigationBarTitleDisplayMode(.inline)
                    .toolbar { Button { add = true } label: { Label("添加游戏", systemImage: "plus") }.disabled(store.busy) }
            }.navigationViewStyle(.stack).id(store.platform).tabItem { Label("游戏库", systemImage: "books.vertical") }.tag(0)
            NavigationView { UpdatesView() }.navigationViewStyle(.stack).tabItem { Label("最近更新", systemImage: "book") }.tag(1)
            NavigationView { PlatformSettingsView(store: store, choose: choose) }.navigationViewStyle(.stack).tabItem { Label("设置", systemImage: "gearshape") }.tag(2)
        }
        .disabled(store.busy && store.player == nil)
        .confirmationDialog("添加游戏", isPresented: $add, titleVisibility: .visible) {
            Button("选择多张磁盘镜像") { choose(.games, types: [.data], multiple: true) }
            Button("游戏文件夹") { choose(.games, types: [.folder]) }
            Button("游戏 ZIP") { choose(.games, types: [.zip]) }
            if store.platform == 98 { Button("DOS 游戏目录") { choose(.dos, types: [.folder]) }; Button("DOS 游戏 ZIP") { choose(.dos, types: [.zip]) } }
        }
        .sheet(isPresented: $picker) { DocumentPicker(types: types, multiple: multiple) { urls in picker = false; receive(urls) } }
        .sheet(isPresented: $naming) {
            NavigationView { Form {
                Text("已选择 \(pendingImages.count) 个文件").foregroundColor(.secondary)
                TextField("游戏名称", text: $importName)
                Button("添加到游戏库") { naming = false; store.importGames(pendingImages, name: importName); if store.platform == 88 { query88 = ""; filter88 = 0 } else { query98 = ""; filter98 = 0 } }
                    .disabled(importName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || importName.count > 80)
            }.navigationTitle("添加游戏").toolbar { Button("取消") { naming = false; pendingImages = [] } } }
        }
        .sheet(item: $store.dosDraft) { DOSWizard(store: store, draft: $0) }
        .fullScreenCover(item: $store.player) { PlayerView(session: $0) }
        .alert("操作未完成", isPresented: Binding(get: { store.error != nil && store.dosDraft == nil && store.player == nil && !picker && !naming }, set: { if !$0 { store.error = nil } })) { Button("知道了") { store.error = nil } } message: { Text(store.error ?? "") }
        .confirmationDialog("继续上次游戏？", isPresented: Binding(get: { resume != nil }, set: { if !$0 { resume = nil } }), titleVisibility: .visible) {
            if let game = resume { Button("继续游戏") { resume = nil; store.start(game, slot: 0) }; Button("重新启动") { resume = nil; store.start(game) } }
        }
    }
    private var query: String { (store.platform == 88 ? query88 : query98).trimmingCharacters(in: .whitespacesAndNewlines) }
    private var filter: Int { store.platform == 88 ? filter88 : filter98 }
    private var visible: [Game] { store.games.filter { game in (filter == 0 || game.mediaType == filter) && (query.isEmpty || game.name.localizedCaseInsensitiveContains(query) || game.media.contains { $0.name.localizedCaseInsensitiveContains(query) }) } }
    private var platformSwitch: some View { Picker("游戏平台", selection: $store.platform) { Text("PC-98").tag(98); Text("PC-88").tag(88) }.pickerStyle(.segmented) }
    private func start(_ game: Game) { if StateStore(store.files.directory(game)).hasAuto() { resume = game } else { store.start(game) } }
    private func recentCard(_ game: Game) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("最近启动").font(.headline)
            ZStack(alignment: .trailing) {
                Image(systemName: game.mediaType == 1 ? "externaldrive.fill" : "internaldrive.fill").font(.system(size: 90)).rotationEffect(.degrees(15)).foregroundColor(necAccent.opacity(0.35)).padding(20)
                VStack(alignment: .leading, spacing: 8) {
                    Text(game.name).font(.title2.bold()).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading).padding(.trailing, 65)
                    Text(game.mediaInfo).font(.caption).foregroundColor(.secondary)
                    Button { if StateStore(store.files.directory(game)).hasAuto() { store.start(game, slot: 0) } else { store.start(game) } } label: { Label(StateStore(store.files.directory(game)).hasAuto() ? "继续游戏" : "启动游戏", systemImage: "play.fill") }.buttonStyle(.borderedProminent)
                }.padding(18)
            }.background(LinearGradient(colors: [Color(red: 37/255, green: 46/255, blue: 38/255), Color(red: 45/255, green: 61/255, blue: 45/255)], startPoint: .leading, endPoint: .trailing)).cornerRadius(17)
        }
    }
    private func gameRow(_ game: Game) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 11) {
                NavigationLink { GameDetailView(store: store, initial: game, choose: choose) } label: {
                    HStack(spacing: 11) {
                        if let image = UIImage(contentsOfFile: store.files.directory(game).appendingPathComponent("cover.png").path) { Image(uiImage: image).resizable().scaledToFill().frame(width: 43, height: 43).clipped().cornerRadius(11) }
                        else { Image(systemName: game.mediaType == 1 ? "externaldrive" : "internaldrive").frame(width: 43, height: 43).background(necSurface).cornerRadius(11) }
                        VStack(alignment: .leading, spacing: 3) { Text(game.name).font(.system(size: 15, weight: .semibold)).lineLimit(2); Text(game.mediaInfo).font(.caption).foregroundColor(.secondary); if game.playMillis > 0 { Text("游玩 \(duration(game.playMillis))").font(.caption2).foregroundColor(.secondary) } }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                }.buttonStyle(.plain)
                Button { start(game) } label: { Image(systemName: "play.fill").frame(width: 44, height: 44).background(necAccent.opacity(0.1)).clipShape(Circle()) }.accessibilityLabel("启动 \(game.name)")
                NavigationLink { GameDetailView(store: store, initial: game, choose: choose) } label: { Image(systemName: "ellipsis").frame(width: 30, height: 44) }.accessibilityLabel("游戏管理")
            }.padding(.vertical, 10)
            Divider()
        }
    }
    private func receive(_ urls: [URL]) {
        let purpose = purpose, platform = store.platform
        switch purpose {
        case .games:
            pendingImages = urls; importName = urls.first?.deletingPathExtension().lastPathComponent ?? ""
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { naming = !urls.isEmpty }
        case .dos:
            guard let first = urls.first else { return }; store.perform("正在准备 DOS 目录…", operation: { let scoped = first.startAccessingSecurityScopedResource(); defer { if scoped { first.stopAccessingSecurityScopedResource() } }; return try store.files.stageDOS(first) }) { store.dosDraft = $0 }
        default:
            store.perform("正在导入资源…", operation: {
                for url in urls {
                    let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    switch purpose {
                    case .resource(let game):
                        let effectivePlatform = game?.platform ?? platform
                        if url.pathExtension.lowercased() == "zip" {
                            let temporary = store.files.root.appendingPathComponent("resource-import-" + UUID().uuidString); defer { try? fileManager.removeItem(at: temporary) }
                            try checked(NECBridge.extractZip(url.path, target: temporary.path))
                            for file in try store.files.files(temporary) where ["rom", "fnt", "bmp", "bin", "wav", "sys", "exe"].contains(file.pathExtension.lowercased()) { try store.files.importResource(file, platform: effectivePlatform, game: game) }
                        } else { try store.files.importResource(url, platform: effectivePlatform, game: game) }
                    case .soundfont: try store.files.importSoundFont(url)
                    case .template: try store.files.importTemplate(url)
                    default: break
                    }
                }
            }) { _ in store.progress = "资源已导入，下次启动生效" }
        }
    }
}

struct UpdatesView: View {
    private var sections: [(String, String)] {
        let text = (try? String(contentsOf: LibraryFiles.assets.appendingPathComponent("updates/changelog.md"), encoding: .utf8)) ?? ""
        return text.components(separatedBy: "\n## ").dropFirst().map { section in let lines = section.components(separatedBy: "\n"); return (lines[0].components(separatedBy: " ·")[0], lines.dropFirst().joined(separator: "\n")) }
    }
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 20) {
            Text("当前版本 \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")").foregroundColor(.secondary)
            ForEach(sections, id: \.0) { title, text in VStack(alignment: .leading, spacing: 10) { Text(title).foregroundColor(necAccent).font(.headline); Text(.init(text)).frame(maxWidth: .infinity, alignment: .leading).padding(16).background(necSurface).cornerRadius(15) } }
        }.padding(20) }.background(necBackground).navigationTitle("最近更新")
    }
}
