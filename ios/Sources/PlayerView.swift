import SwiftUI
import UIKit
import AVFoundation

final class OrientationDelegate: NSObject, UIApplicationDelegate {
    static var mask = UIInterfaceOrientationMask.all
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask { Self.mask }
    static func apply(_ value: String) {
        mask = value == "LANDSCAPE" ? .landscape : value == "PORTRAIT" ? .portrait : .all
        if #available(iOS 16.0, *) {
            for case let scene as UIWindowScene in UIApplication.shared.connectedScenes { scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations(); scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in } }
        } else { UIViewController.attemptRotationToDeviceOrientation() }
    }
}
struct MetalSurface: UIViewRepresentable {
    @ObservedObject var session: PlayerSession
    func makeUIView(context: Context) -> TouchDisplay { TouchDisplay(session: session) }
    func updateUIView(_ view: TouchDisplay, context: Context) { view.aspect = session.game.options["display_aspect"] ?? "4:3" }
    static func dismantleUIView(_ view: TouchDisplay, coordinator: ()) { view.cancelInput() }
}
struct PlayerView: View {
    @ObservedObject var session: PlayerSession
    @EnvironmentObject private var library: LibraryStore
    @State private var exit = false
    @State private var reset = false
    @State private var position = CGPoint(x: 1, y: 0.9)
    @State private var drag = CGSize.zero
    @State private var dragOrigin: CGPoint?
    @State private var landscape = false
    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottomTrailing) {
                Color.black.ignoresSafeArea(); MetalSurface(session: session).ignoresSafeArea()
                if session.keyboardVisible { KeyboardOverlay(session: session).frame(width: min(360, geometry.size.width - 16), height: min(geometry.size.height * (landscape ? 0.8 : 0.48), 400)).padding(.bottom, 65).padding(.trailing, 8) }
                if session.mouseMode && !session.keyboardVisible { HStack { mouseButton("左键", 1); mouseButton("右键", 2) }.frame(maxWidth: .infinity, alignment: .leading).padding(8) }
                if session.busy || session.closing { VStack { ProgressView(); Text(session.closing ? "正在保存并退出…" : session.message) }.padding(20).background(necSurface.opacity(0.95)).cornerRadius(15).frame(maxWidth: .infinity, maxHeight: .infinity) }
                toolWheel(geometry)
                if !session.message.isEmpty && !session.busy && !session.closing { Text(session.message).font(.caption).padding(10).background(necSurface.opacity(0.9)).cornerRadius(10).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top).padding(.top, 8).allowsHitTesting(false) }
            }.onAppear { landscape = geometry.size.width > geometry.size.height; loadPosition(); OrientationDelegate.apply(session.game.options["display_orientation"] ?? "AUTO") }
                .onChange(of: geometry.size) { size in session.cancelTouches?(); session.toolsExpanded = false; landscape = size.width > size.height; loadPosition() }
        }
        .statusBar(hidden: true).preferredColorScheme(.dark).tint(necAccent)
        .sheet(item: $session.panel) { panel in NavigationView { panelView(panel).toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("返回游戏") { session.panel = nil }.disabled(session.busy || library.busy) } } }.navigationViewStyle(.stack).interactiveDismissDisabled(session.busy || library.busy)
            .alert("操作未完成", isPresented: showingError(inPanel: true)) { errorActions } message: { Text(session.error ?? "") } }
        .alert("操作未完成", isPresented: showingError(inPanel: false)) { errorActions } message: { Text(session.error ?? "") }
        .onDisappear { session.cancelTouches?(); OrientationDelegate.apply("AUTO"); if !session.closing { session.stop() } }
        .onReceive(session.store.$error) { value in if let value = value { session.error = value; session.store.error = nil } }
    }
    private func showingError(inPanel: Bool) -> Binding<Bool> { Binding(get: { session.error != nil && (session.panel != nil) == inPanel }, set: { if !$0 { session.error = nil } }) }
    @ViewBuilder private var errorActions: some View {
        if session.error?.hasPrefix("退出保存未完成") == true { Button("重试退出") { session.error = nil; session.stop() }; Button("退出但不保存", role: .destructive) { session.error = nil; session.stop(save: false) } }
        if !session.ready { Button("返回游戏库") { session.error = nil; session.stop(save: false) } }
        Button("知道了", role: .cancel) { session.error = nil }
    }
    @ViewBuilder private func panelView(_ panel: PlayerPanel) -> some View {
        switch panel {
        case .menu:
            Form {
                Section { Text(session.game.name).font(.headline); Text("本次 \(duration(session.elapsed)) · 累计 \(duration(session.game.playMillis + session.elapsed))").font(.caption) }
                Button(session.userPaused ? "继续运行" : "暂停") { session.userPaused.toggle(); session.panel = nil }
                Button("即时存档 / 读档") { session.panel = .states }
                Button("更换 / 取出磁盘") { session.panel = .disks }
                Button("屏幕方向与画面") { session.panel = .display }
                Button("操作方式 / 鼠标") { session.panel = .input }
                Button("本游戏音源设置") { session.panel = .sound }
                Button("提交磁盘写入") { NECBridge.flush(); session.message = "磁盘写入已提交" }
                Button("恢复声音输出") { NECBridge.audioChanged(); session.message = "正在重建音频输出" }
                Button("将当前画面设为封面") { capture() }
                DisclosureGroup("实际音频输出与机器配置") { Text(NECBridge.audioInfo()).font(.caption); Text(AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portName).joined(separator: " / ")).font(.caption); Text(session.machineInfo).font(.caption) }
                Button("复位机器", role: .destructive) { reset = true }
                    .confirmationDialog("复位机器？", isPresented: $reset, titleVisibility: .visible) { Button("复位", role: .destructive) { session.reset() }; Button("取消", role: .cancel) {} } message: { Text("未保存的机器进度会丢失。") }
                Button("退出游戏", role: .destructive) { exit = true }
                    .confirmationDialog("退出游戏？", isPresented: $exit, titleVisibility: .visible) { Button("保存并退出") { session.panel = nil; session.stop() }; Button("继续游玩", role: .cancel) {} }
            }.navigationTitle("游戏菜单")
        case .states: StateSlotsView(session: session)
        case .disks: RuntimeDisksView(session: session)
        case .input: InputSettingsView(session: session)
        case .sound: PlayerSoundView(session: session)
        case .display:
            Form {
                Picker("屏幕方向", selection: Binding(get: { session.game.options["display_orientation"] ?? "AUTO" }, set: { value in session.game.options["display_orientation"] = value; OrientationDelegate.apply(value); saveOption("display_orientation", value) })) { Text("自动旋转").tag("AUTO"); Text("固定横屏").tag("LANDSCAPE"); Text("固定竖屏").tag("PORTRAIT") }
                Picker("画面比例", selection: Binding(get: { session.game.options["display_aspect"] ?? "4:3" }, set: { value in session.game.options["display_aspect"] = value; saveOption("display_aspect", value) })) { Text("4:3").tag("4:3"); Text("原始像素比例").tag("NATIVE"); Text("填满屏幕").tag("FILL"); Text("整数缩放").tag("INTEGER") }
            }.navigationTitle("画面设置")
        case .keyboard: KeyboardOverlay(session: session)
        }
    }
    private func saveOption(_ key: String, _ value: String) { let game = session.game; session.store.queue.async { try? session.store.files.update(game.id, platform: game.platform) { $0.options[key] = value } } }
    private func capture() {
        guard let data = session.display?.snapshotPNG() else { session.error = "未能取得当前画面"; return }
        let directory = session.store.files.directory(session.game)
        session.store.perform("正在保存封面…", operation: { try data.write(to: directory.appendingPathComponent("cover.png"), options: .atomic) }) { _ in session.message = "已设为游戏封面" }
    }
    private func loadPosition() { let key = landscape ? "wheel.landscape" : "wheel.portrait"; position = CGPoint(x: UserDefaults.standard.object(forKey: key + ".x") as? Double ?? 1, y: UserDefaults.standard.object(forKey: key + ".y") as? Double ?? 0.9) }
    private func toolWheel(_ geometry: GeometryProxy) -> some View {
        let width = geometry.size.width, height = geometry.size.height, expanded = session.toolsExpanded
        let diameter = min(272.0, min(width - geometry.safeAreaInsets.leading - geometry.safeAreaInsets.trailing, height - geometry.safeAreaInsets.top - geometry.safeAreaInsets.bottom) - 16)
        let radius = expanded ? diameter / 2 : 24
        let baseX = dragOrigin?.x ?? (position.x < 0.5 ? 32 + geometry.safeAreaInsets.leading : width - 32 - geometry.safeAreaInsets.trailing)
        let baseY = dragOrigin?.y ?? (geometry.safeAreaInsets.top + 32 + position.y * max(1, height - 64 - geometry.safeAreaInsets.top - geometry.safeAreaInsets.bottom))
        let centerX = max(radius + 8 + geometry.safeAreaInsets.leading, min(width - radius - 8 - geometry.safeAreaInsets.trailing, baseX + drag.width))
        let centerY = max(radius + 8 + geometry.safeAreaInsets.top, min(height - radius - 8 - geometry.safeAreaInsets.bottom, baseY + drag.height))
        return ZStack {
            if expanded {
                Circle().fill(necBackground.opacity(0.94)).frame(width: diameter, height: diameter)
                ForEach(0..<8) { index in
                    let angle = Double(index) * Double.pi / 4 - Double.pi / 2, distance = (diameter - 56) / 2 - 8
                    wheelAction(index).frame(width: 56, height: 56).background(necSurface).clipShape(Circle()).offset(x: cos(angle) * distance, y: sin(angle) * distance)
                }
            }
            Button { session.toolsExpanded.toggle() } label: { Text(expanded ? "×" : "≡").font(.system(size: expanded ? 30 : 24, weight: .bold)).frame(width: expanded ? 68 : 48, height: expanded ? 68 : 48).background(necSurface.opacity(expanded ? 0.95 : 0.6)).clipShape(Circle()) }
                .accessibilityLabel(expanded ? "收起工具栏" : "展开工具栏")
                .highPriorityGesture(DragGesture(minimumDistance: 12).onChanged { value in if dragOrigin == nil { dragOrigin = CGPoint(x: centerX, y: centerY) }; session.toolsExpanded = false; drag = value.translation }.onEnded { value in
                    let origin = dragOrigin ?? CGPoint(x: centerX, y: centerY)
                    position.x = origin.x + value.translation.width < width / 2 ? 0 : 1; position.y = max(0, min(1, (origin.y + value.translation.height - geometry.safeAreaInsets.top - 32) / max(1, height - 64 - geometry.safeAreaInsets.top - geometry.safeAreaInsets.bottom))); drag = .zero; dragOrigin = nil
                    let key = landscape ? "wheel.landscape" : "wheel.portrait"; UserDefaults.standard.set(Double(position.x), forKey: key + ".x"); UserDefaults.standard.set(Double(position.y), forKey: key + ".y")
                })
        }.frame(width: diameter, height: diameter).position(x: centerX, y: centerY).foregroundColor(.white).disabled(session.busy || session.closing || library.busy)
    }
    @ViewBuilder private func wheelAction(_ index: Int) -> some View {
        switch index {
        case 0: EmulatedKey(session: session, name: "Enter", code: 13)
        case 1: EmulatedKey(session: session, name: "Esc", code: 27)
        case 2: EmulatedKey(session: session, name: "空格", code: 32)
        case 3: Button("快存") { session.toolsExpanded = false; session.state(7, load: false) }.font(.caption)
        case 4: Button("快读") { session.toolsExpanded = false; session.state(7, load: true) }.font(.caption)
        case 5: Button("键盘") { session.cancelTouches?(); session.toolsExpanded = false; session.keyboardVisible.toggle() }.font(.caption)
        case 6: Button(session.mouseMode ? "鼠标" : session.mouseSuggested ? "试用鼠标" : "按键") { session.toggleInput() }.font(.caption).highPriorityGesture(LongPressGesture().onEnded { _ in session.toolsExpanded = false; session.panel = .input })
        default: Button("菜单") { session.toolsExpanded = false; session.panel = .menu }.font(.caption)
        }
    }
    private func mouseButton(_ name: String, _ mask: Int) -> some View {
        Text(name).font(.caption).padding(12).background(necSurface.opacity(0.85)).cornerRadius(10).gesture(DragGesture(minimumDistance: 0).onChanged { _ in if session.interactive { session.mouseButtons |= mask; NECBridge.mouseX(0, y: 0, buttons: session.mouseButtons | session.gestureButtons) } }.onEnded { _ in session.mouseButtons &= ~mask; NECBridge.mouseX(0, y: 0, buttons: session.mouseButtons | session.gestureButtons) })
    }
}

struct EmulatedKey: View {
    @ObservedObject var session: PlayerSession
    let name: String; let code: Int; var sticky = false
    @State private var down = false
    var body: some View {
        Text(name).font(.system(size: 12)).foregroundColor(session.modifiers.contains(code) || down ? necAccent : .white).frame(maxWidth: .infinity, minHeight: 38)
            .background(down ? necAccent.opacity(0.2) : Color.clear).contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { _ in
                guard session.interactive, !down else { return }; down = true
                if !sticky { NECBridge.key(code, down: true) }
            }.onEnded { _ in
                if sticky && down { if session.modifiers.contains(code) { session.modifiers.remove(code); NECBridge.key(code, down: false) } else { session.modifiers.insert(code); NECBridge.key(code, down: true) } }
                else { NECBridge.key(code, down: false) }; down = false
            }).onDisappear { if down || (sticky && session.modifiers.contains(code)) { NECBridge.key(code, down: false); session.modifiers.remove(code); down = false } }
    }
}
struct KeyboardOverlay: View {
    @ObservedObject var session: PlayerSession
    private func row(_ names: [String], _ codes: [Int], sticky: Bool = false) -> some View {
        HStack(spacing: 2) { ForEach(names.indices, id: \.self) { index in EmulatedKey(session: session, name: names[index], code: codes[index], sticky: sticky).background(necSurface).cornerRadius(5) } }
    }
    var body: some View {
        ScrollView { VStack(spacing: 3) {
            if session.game.input.fullKeyboard {
                ForEach(["1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"], id: \.self) { letters in row(letters.map { String($0).uppercased() }, letters.unicodeScalars.map { Int($0.value) }) }
                row(["@", "[", "]", "\\", "-", "^", ":", ";", ",", ".", "/", "_"], [64,91,93,92,45,94,58,59,44,46,47,95])
            }
            row(["Esc","F1","F2","F3","F4","F5"], [27,282,283,284,285,286])
            if session.game.platform == 88 && !session.game.input.fullKeyboard { row((0...9).map(String.init), Array(48...57)) }
            if session.game.input.fullKeyboard { row(["F6","F7","F8","F9","F10"], [287,288,289,290,291]) }
            row(["←","↑","↓","→","空格","Enter"], [276,273,274,275,32,13]); row(["SHIFT","CTRL","KANA"], [304,306,302], sticky: true)
            row(["BS","TAB","STOP"], [8,9,19])
            Button(session.game.input.fullKeyboard ? "精简键盘" : "完整键盘") { NECBridge.releaseKeys(); session.modifiers.removeAll(); session.game.input.fullKeyboard.toggle(); session.persistInput() }.font(.caption).padding(8)
        }.padding(5) }.background(necBackground.opacity(0.92)).cornerRadius(12)
    }
}
