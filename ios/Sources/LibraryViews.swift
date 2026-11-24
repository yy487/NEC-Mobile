import SwiftUI
import UniformTypeIdentifiers

let necBackground = Color(red: 22/255, green: 27/255, blue: 25/255)
let necSurface = Color(red: 32/255, green: 39/255, blue: 36/255)
let necAccent = Color(red: 199/255, green: 236/255, blue: 170/255)

enum ImportPurpose { case games, dos, resource(Game?), soundfont, template }
struct ImportRequest: Identifiable {
    let id = UUID()
    var purpose: ImportPurpose; var types: [UTType]; var multiple: Bool; var platform: Int
}
struct GameImportRequest: Identifiable { let id = UUID(); var urls: [URL]; var platform: Int }
enum LibrarySheet: Identifiable {
    case picker(ImportRequest), naming(GameImportRequest), dos(DosDraft), share(URL), repair(Binding<Game>)
    var id: String {
        switch self {
        case .picker(let request): return "picker-\(request.id)"
        case .naming(let request): return "naming-\(request.id)"
        case .dos(let draft): return "dos-\(draft.id)"
        case .share(let url): return "share-" + url.path
        case .repair(let game): return "repair-" + game.wrappedValue.id
        }
    }
}
final class LibraryPresentation: ObservableObject {
    @Published var sheet: LibrarySheet?
    @Published private(set) var presenting = false
    private var afterDismiss: (() -> Void)?
    func show(_ value: LibrarySheet) { presenting = true; sheet = value }
    func close() { sheet = nil }
    func transition(_ action: @escaping () -> Void) {
        afterDismiss = action; sheet = nil
        if !presenting { finishTransition() }
    }
    func didDismiss() { presenting = false; finishTransition() }
    private func finishTransition() {
        let action = afterDismiss; afterDismiss = nil
        if let action = action { DispatchQueue.main.async(execute: action) }
    }
}
struct GameImportNameView: View {
    let request: GameImportRequest
    let submit: (String) -> Void
    let cancel: () -> Void
    @State private var name: String
    init(request: GameImportRequest, submit: @escaping (String) -> Void, cancel: @escaping () -> Void) {
        self.request = request; self.submit = submit; self.cancel = cancel
        _name = State(initialValue: request.urls.first?.deletingPathExtension().lastPathComponent ?? "")
    }
    var body: some View {
        NavigationView { Form {
            Text("已选择 \(request.urls.count) 个文件").foregroundColor(.secondary)
            ForEach(request.urls, id: \.self) { Text($0.lastPathComponent).font(.caption).foregroundColor(.secondary) }
            TextField("游戏名称", text: $name).accessibilityIdentifier("library.importName")
            Text("名称需为 1–80 个字符").font(.caption).foregroundColor(.secondary)
            Button("添加到游戏库") { submit(name) }.accessibilityIdentifier("library.confirmImport")
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name.count > 80)
        }.navigationTitle("添加游戏").toolbar { Button("取消", action: cancel) } }.navigationViewStyle(.stack)
    }
}
struct ShareView: UIViewControllerRepresentable {
    let url: URL
    let complete: () -> Void
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in DispatchQueue.main.async { complete() } }
        return controller
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
struct DocumentPicker: UIViewControllerRepresentable {
    var types: [UTType]; var multiple: Bool; var complete: ([URL]) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(complete) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false); picker.allowsMultipleSelection = multiple; picker.delegate = context.coordinator; picker.view.accessibilityIdentifier = "library.documentPicker"
#if targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--import-ui-fixtures") {
            picker.directoryURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("ImportUI", isDirectory: true)
        }
#endif
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let complete: ([URL]) -> Void; init(_ complete: @escaping ([URL]) -> Void) { self.complete = complete }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { complete(urls) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { complete([]) }
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
                .task { if ProcessInfo.processInfo.arguments.contains("--smoke-test") { IOSSmoke.start(store) } }
        }
    }
}

struct RootView: View {
    @ObservedObject var store: LibraryStore
    @StateObject private var presentation = LibraryPresentation()
    @State private var query88 = ""
    @State private var query98 = ""
    @State private var filter88 = 0
    @State private var filter98 = 0
    @State private var resume: Game?
    @State private var tab = 0
    private func choose(_ purpose: ImportPurpose, types: [UTType], multiple: Bool = false) {
        guard !store.busy && !presentation.presenting else { return }
        presentation.show(.picker(ImportRequest(purpose: purpose, types: types, multiple: multiple, platform: store.platform)))
    }
    private func errorPresented(inSheet: Bool) -> Binding<Bool> {
        Binding(get: { store.error != nil && store.player == nil && (inSheet ? presentation.sheet != nil : !presentation.presenting) }, set: { if !$0 { store.error = nil } })
    }
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
                    .toolbar {
                        Menu {
                            Button("选择多张磁盘镜像") { choose(.games, types: [.data], multiple: true) }.accessibilityIdentifier("library.importImages")
                            Button("游戏文件夹") { choose(.games, types: [.folder]) }.accessibilityIdentifier("library.importFolder")
                            Button("游戏 ZIP") { choose(.games, types: [.zip]) }.accessibilityIdentifier("library.importZip")
                            if store.platform == 98 { Button("DOS 游戏目录") { choose(.dos, types: [.folder]) }.accessibilityIdentifier("library.importDOS"); Button("DOS 游戏 ZIP") { choose(.dos, types: [.zip]) } }
                        } label: { Label("添加游戏", systemImage: "plus") }
                            .accessibilityIdentifier("library.addGame").disabled(store.busy)
                    }
            }.navigationViewStyle(.stack).id(store.platform).tabItem { Label("游戏库", systemImage: "books.vertical") }.tag(0)
            NavigationView { UpdatesView() }.navigationViewStyle(.stack).tabItem { Label("最近更新", systemImage: "book") }.tag(1)
            NavigationView { PlatformSettingsView(store: store, choose: choose) }.navigationViewStyle(.stack).tabItem { Label("设置", systemImage: "gearshape") }.tag(2)
        }
        .disabled(store.busy && store.player == nil)
        .sheet(item: $presentation.sheet, onDismiss: presentation.didDismiss) { active in
            sheetContent(active).disabled(store.busy).interactiveDismissDisabled(store.busy)
                .alert("操作未完成", isPresented: errorPresented(inSheet: true)) { Button("知道了") { store.error = nil } } message: { Text(store.error ?? "") }
        }
        .fullScreenCover(item: $store.player) { PlayerView(session: $0).id($0.id).environmentObject(store) }
        .alert("操作未完成", isPresented: errorPresented(inSheet: false)) { Button("知道了") { store.error = nil } } message: { Text(store.error ?? "") }
        .confirmationDialog("继续上次游戏？", isPresented: Binding(get: { resume != nil }, set: { if !$0 { resume = nil } }), titleVisibility: .visible) {
            if let game = resume { Button("继续游戏") { resume = nil; store.start(game, slot: 0) }; Button("重新启动") { resume = nil; store.start(game) } }
        }
    }
    @ViewBuilder private func sheetContent(_ active: LibrarySheet) -> some View {
        switch active {
        case .picker(let request):
            DocumentPicker(types: request.types, multiple: request.multiple) { urls in
                if urls.isEmpty { presentation.close() } else { presentation.transition { receive(urls, request: request) } }
            }
        case .naming(let request):
            GameImportNameView(request: request, submit: { name in
                presentation.transition { store.importGames(request.urls, name: name, platform: request.platform) { _ in imported(request.platform) } }
            }, cancel: presentation.close)
        case .dos(let draft): DOSWizard(store: store, draft: draft) { imported(98) }
        case .share(let url): ShareView(url: url, complete: presentation.close)
        case .repair(let game): RepairNamesView(store: store, game: game)
        }
    }
    private func imported(_ platform: Int) { if platform == 88 { query88 = ""; filter88 = 0 } else { query98 = ""; filter98 = 0 }; tab = 0 }
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
                NavigationLink { GameDetailView(store: store, initial: game, choose: choose, present: presentation.show) } label: {
                    HStack(spacing: 11) {
                        if let image = UIImage(contentsOfFile: store.files.directory(game).appendingPathComponent("cover.png").path) { Image(uiImage: image).resizable().scaledToFill().frame(width: 43, height: 43).clipped().cornerRadius(11) }
                        else { Image(systemName: game.mediaType == 1 ? "externaldrive" : "internaldrive").frame(width: 43, height: 43).background(necSurface).cornerRadius(11) }
                        VStack(alignment: .leading, spacing: 3) { Text(game.name).font(.system(size: 15, weight: .semibold)).lineLimit(2); Text(game.mediaInfo).font(.caption).foregroundColor(.secondary); if game.playMillis > 0 { Text("游玩 \(duration(game.playMillis))").font(.caption2).foregroundColor(.secondary) } }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                }.buttonStyle(.plain)
                Button { start(game) } label: { Image(systemName: "play.fill").frame(width: 44, height: 44).background(necAccent.opacity(0.1)).clipShape(Circle()) }.accessibilityLabel("启动 \(game.name)")
                NavigationLink { GameDetailView(store: store, initial: game, choose: choose, present: presentation.show) } label: { Image(systemName: "ellipsis").frame(width: 30, height: 44) }.accessibilityLabel("游戏管理")
            }.padding(.vertical, 10)
            Divider()
        }
    }
    private func receive(_ urls: [URL], request: ImportRequest) {
        let purpose = request.purpose, platform = request.platform
        switch purpose {
        case .games:
            presentation.show(.naming(GameImportRequest(urls: urls, platform: platform)))
        case .dos:
            guard !urls.isEmpty else { return }; store.perform("正在准备 DOS 目录…", operation: { try store.files.readingSelected(urls) { try store.files.stageDOS($0[0]) } }) { presentation.show(.dos($0)) }
        default:
            store.perform("正在导入资源…", operation: {
                try store.files.readingSelected(urls) { selected in
                for url in selected {
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
