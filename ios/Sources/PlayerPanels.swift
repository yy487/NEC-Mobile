import SwiftUI

struct StateSlotsView: View {
    @ObservedObject var session: PlayerSession
    @State private var selected = 1
    @State private var actions = false
    @State private var overwrite = false
    private var slots: StateStore { StateStore(session.store.files.directory(session.game)) }
    var body: some View {
        ScrollView { LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            ForEach(StateStore.slots, id: \.self) { slot in Button { selected = slot; actions = true } label: { Text(slots.label(slot)).font(.caption).frame(maxWidth: .infinity, minHeight: 64).padding(6).background(necSurface).cornerRadius(12) } }
        }.padding() }.navigationTitle("即时存档 / 读档")
            .confirmationDialog(slots.label(selected), isPresented: $actions, titleVisibility: .visible) {
                Button("保存当前进度") { if (try? slots.record(selected)) != nil { overwrite = true } else { session.state(selected, load: false) } }
                Button("读取此槽") { session.state(selected, load: true) }
                Button("删除此槽", role: .destructive) { session.removeState(selected) }
            }
            .alert("覆盖存档？", isPresented: $overwrite) { Button("保存") { session.state(selected, load: false) }; Button("取消", role: .cancel) {} } message: { Text("将用当前进度替换这个槽。") }
    }
}
struct RuntimeDisksView: View {
    @ObservedObject var session: PlayerSession
    private func mounted(_ drive: Int) -> String {
        let path = NECBridge.disk(drive); guard !path.isEmpty else { return "空" }
        let name = (path as NSString).lastPathComponent
        return session.game.media.first { $0.file == name }?.label(NECBridge.diskIndex(drive)) ?? name
    }
    var body: some View {
        Form {
            ForEach(0..<2) { drive in
                Section("软驱 \(drive == 0 ? "A" : "B") · \(mounted(drive))") {
                    Button("取出当前盘") { session.mount(drive, ref: nil) }.disabled(NECBridge.disk(drive).isEmpty)
                    ForEach(session.game.media.filter { !$0.hard }) { disk in
                        ForEach(disk.titles.indices, id: \.self) { index in
                            Button(disk.label(index)) { session.mount(drive, ref: DiskRef(file: disk.file, index: index)) }
                                .disabled(NECBridge.disk(1-drive) == session.store.files.mediaURL(session.game, disk).path && NECBridge.diskIndex(1-drive) == index)
                        }
                    }
                }
            }
            if session.game.platform == 98 {
                Button("新建并保存 1.2 MB 存档盘") {
                    let game = session.game
                    session.store.perform("正在创建存档盘…", operation: { var fresh = try session.store.files.find(game.id, platform: game.platform); _ = try session.store.files.addBlank(&fresh); return fresh.media }) { session.game.media = $0 }
                }
            }
        }.navigationTitle("更换 / 取出磁盘").disabled(session.busy || session.store.busy)
    }
}
struct InputSettingsView: View {
    @ObservedObject var session: PlayerSession
    private let presets: [(String, String, Bool, Bool, Int, Bool)] = [
        ("keypad-enter","小键盘 + Enter",false,true,13,false), ("keypad-space","小键盘 + 空格",false,true,32,false),
        ("arrows-enter","方向键 + Enter",false,false,13,false), ("arrows-space","方向键 + 空格",false,false,32,false),
        ("mouse","鼠标触控板",true,true,13,false), ("text","文字命令输入",false,true,13,true)]
    var body: some View {
        Form {
            Picker("操作方式", selection: $session.game.input.mode) { Text("自动配置").tag("auto"); Text("滑动按键").tag("keys"); Text("虚拟鼠标").tag("mouse") }
            Picker("轻触确认键", selection: $session.game.input.tap) { Text("Enter").tag(13); Text("空格").tag(32) }
            Toggle("滑动使用小键盘 2 / 4 / 6 / 8", isOn: $session.game.input.keypad)
            Picker("鼠标速度", selection: $session.game.input.mouseSpeed) { Text("慢速").tag(0.6); Text("标准").tag(1.0); Text("快速").tag(1.6) }
            Section("操作预设") {
                ForEach(presets, id: \.0) { preset in
                    Button(preset.1 + (session.game.input.preset == preset.0 ? " · 已选" : "")) {
                        session.game.input.preset = preset.0; session.game.input.last = preset.2 ? "mouse" : "keys"; session.game.input.mode = "auto"
                        session.game.input.keypad = preset.3; session.game.input.tap = preset.4; session.game.input.fullKeyboard = preset.5
                    }
                }
            }
            Text("自动配置优先使用已套用预设，再沿用上次方式，首次采用滑动按键。检测到鼠标通信后提供试用建议。滑动先输入一次方向，保持触摸 420 毫秒后持续按住，松手释放。单指轻触为所选确认键，双指轻触为另一确认键。\n\n虚拟鼠标采用触控板方式，滑动移动光标，轻触左击，双指右击，长按拖动。").font(.caption).foregroundColor(.secondary)
        }.navigationTitle("操作方式").onDisappear { session.cancelTouches?(); NECBridge.releaseKeys(); session.persistInput() }
    }
}
struct PlayerSoundView: View {
    @ObservedObject var session: PlayerSession
    @State private var game: Game?
    var body: some View {
        Group {
            if let current = game {
                Form {
                    ForEach((session.store.files.schema.platforms[String(current.platform)] ?? []).filter { [1,2,3,6].contains($0.group) }) { option in
                        OptionControl(option: option, game: Binding(get: { game ?? current }, set: { game = $0 }))
                    }
                    Button("保存，下次启动生效") { if let game = game { session.store.save(game) { session.message = "音源设置已保存，下次启动生效" } } }
                }
            } else { ProgressView() }
        }.navigationTitle("本游戏音源设置").onAppear { let original = session.game; session.store.queue.async { let value = try? session.store.files.find(original.id, platform: original.platform); DispatchQueue.main.async { game = value } } }
    }
}
