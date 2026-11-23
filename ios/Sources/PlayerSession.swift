import Foundation
import Combine
import UIKit
import AVFoundation

enum PlayerPanel: String, Identifiable { case menu, states, disks, input, keyboard, sound, display; var id: String { rawValue } }
final class PlayerSession: ObservableObject, Identifiable {
    let id = UUID()
    unowned let store: LibraryStore
    @Published var game: Game
    @Published var ready = false
    @Published var busy = true
    @Published var closing = false
    @Published var userPaused = false { didSet { applyPause() } }
    @Published var foreground = true { didSet { applyPause(); if !foreground { saveAutomatic() } } }
    @Published var panel: PlayerPanel? { didSet { applyPause() } }
    @Published var keyboardVisible = false
    @Published var toolsExpanded = false
    @Published var message = "正在准备游戏…"
    @Published var error: String?
    @Published var elapsed: Int64 = 0
    @Published var machineInfo = ""
    @Published var mouseSuggested = false
    @Published var modifiers = Set<Int>()
    private var timer: Timer?
    private var observers: [NSObjectProtocol] = []
    private var appliedPause = true
    private var audioInterrupted = false
    private var autoSaving = false
    private var revision: Int64 = 0
    private var savedRevision: Int64 = -1 // Accessed on store.queue.
    private var restoredSlot: Int?
    private var started = false
    private var everRunning = false
    private var runningSince: TimeInterval?
    private var accumulated: TimeInterval = 0
    private var lastCheckpoint: TimeInterval = 0
    private let playSession = UUID().uuidString
    var cancelTouches: (() -> Void)?
    weak var display: NECDisplayView?
    var mouseButtons = 0
    var gestureButtons = 0
    var mouseMode: Bool { game.input.mode == "mouse" || (game.input.mode == "auto" && game.input.last == "mouse") }
    var interactive: Bool { ready && !appliedPause && !busy && !closing }
    init(store: LibraryStore, game: Game, restoreSlot: Int?) {
        self.store = store; self.game = game; restoredSlot = restoreSlot
    }
    deinit { timer?.invalidate(); observers.forEach { NotificationCenter.default.removeObserver($0) } }
    func begin() {
        guard !started else { return }; started = true
        UIApplication.shared.isIdleTimerDisabled = true
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            guard let self = self else { return }
            if let reason = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt, reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue { self.userPaused = true; self.message = "耳机已断开，游戏已暂停" }
            NECBridge.audioChanged()
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let self = self else { return }; let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            self.audioInterrupted = type == AVAudioSession.InterruptionType.began.rawValue; self.applyPause()
            if !self.audioInterrupted { NECBridge.audioChanged() }
        })
        timer = Timer.scheduledTimer(withTimeInterval: 0.7, repeats: true) { [weak self] _ in self?.tick() }
        open(game, slot: restoredSlot)
    }
    private func open(_ original: Game, slot: Int?) {
        busy = true; ready = false; applyPause()
        store.queue.async {
            do {
                let configured = try slot.map { try self.store.files.restored(original, slot: $0) } ?? original
                try self.store.files.prepare(configured)
                let first = configured.hardDisk ?? configured.floppyA
                let result = NECBridge.start(configured.platform, directory: self.store.files.directory(configured).path,
                    first: try self.store.files.path(configured, first), second: try self.store.files.path(configured, configured.floppyB),
                    mode: configured.mode, firstIndex: first?.index ?? 0, secondIndex: configured.floppyB?.index ?? 0)
                try checked(result)
                let deadline = ProcessInfo.processInfo.systemUptime + 45
                while NECBridge.startupState() == 1 && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.02) }
                guard NECBridge.startupState() == 2 else { throw NECError(message: NECBridge.startupError().isEmpty ? "内核启动超时" : NECBridge.startupError()) }
                if let slot = slot { try StateStore(self.store.files.directory(configured)).load(slot) }
                try self.store.files.update(original.id, platform: original.platform) { $0.played = Date(); $0.lastMachineInfo = NECBridge.machineInfo() }
                DispatchQueue.main.async { self.game = configured; self.machineInfo = NECBridge.machineInfo(); self.busy = false; self.ready = true; self.message = ""; self.applyPause() }
            } catch {
                NECBridge.stop(); DispatchQueue.main.async { self.busy = false; self.error = error.localizedDescription; self.message = "启动未完成" }
            }
        }
    }
    private func updateTime(_ running: Bool) {
        let now = ProcessInfo.processInfo.systemUptime
        if running && runningSince == nil { runningSince = now }
        if !running, let since = runningSince { accumulated += max(0, now - since); runningSince = nil }
        elapsed = Int64((accumulated + (runningSince.map { max(0, now - $0) } ?? 0)) * 1000)
    }
    func applyPause() {
        let paused = userPaused || !foreground || panel != nil || busy || autoSaving || !ready || closing || audioInterrupted
        if paused { cancelTouches?(); mouseButtons = 0; gestureButtons = 0; modifiers.removeAll() }
        if ready && paused != appliedPause { NECBridge.pause(paused); if !paused { revision += 1; everRunning = true } }
        appliedPause = paused; updateTime(!paused)
    }
    private func tick() {
        guard !closing else { return }; updateTime(interactive)
        mouseSuggested = !mouseMode && game.input.mode == "auto" && game.input.preset.isEmpty && NECBridge.mouseActive()
        let warning = NECBridge.takeWarning(); if !warning.isEmpty { message = warning == "Sound BIOS ROM missing" ? "需要声卡 BIOS 的游戏请导入 sound86.rom 或 sound.rom" : warning }
        if ProcessInfo.processInfo.systemUptime - lastCheckpoint >= 60 { lastCheckpoint = ProcessInfo.processInfo.systemUptime; checkpoint() }
    }
    private func checkpoint() {
        let time = elapsed, game = game, session = playSession
        store.queue.async {
            do { try self.store.files.update(game.id, platform: game.platform) { current in
                let previous = current.lastPlaySession == session ? current.lastPlayMillis : 0
                current.playMillis += max(0, time - previous); current.lastPlaySession = session; current.lastPlayMillis = max(previous, time)
            } } catch { DispatchQueue.main.async { self.message = "游玩时长记录未完成" } }
        }
    }
    func persistInput() {
        let input = game.input, game = game
        store.queue.async { try? self.store.files.update(game.id, platform: game.platform) { $0.input = input } }
    }
    func toggleInput() {
        cancelTouches?(); NECBridge.releaseKeys(); game.input.last = mouseMode ? "keys" : "mouse"; game.input.mode = game.input.last; persistInput()
    }
    func saveAutomatic() {
        guard ready && everRunning && !closing && !autoSaving else { return }; checkpoint(); autoSaving = true; applyPause()
        let game = game, revision = revision
        let task = UIApplication.shared.beginBackgroundTask(withName: "NEC session save", expirationHandler: nil)
        store.queue.async {
            var message: String?
            if self.savedRevision != revision {
                do { try StateStore(self.store.files.directory(game)).save(0); self.savedRevision = revision }
                catch { message = error.localizedDescription }
            }
            DispatchQueue.main.async { self.autoSaving = false; if task != .invalid { UIApplication.shared.endBackgroundTask(task) }; if let message = message { self.error = "自动存档未完成：\(message)" }; self.applyPause() }
        }
    }
    func state(_ slot: Int, load: Bool) {
        guard ready && !busy && !closing else { return }; busy = true; panel = nil; message = load ? "正在读取…" : "正在保存…"; applyPause()
        let game = game
        store.queue.async {
            do {
                let slots = StateStore(self.store.files.directory(game)); if load { try slots.load(slot) } else { try slots.save(slot) }
                DispatchQueue.main.async { if load { self.revision += 1 }; self.busy = false; self.message = (load ? "已读取 · " : "已保存 · ") + StateStore.title(slot); self.applyPause() }
            } catch is StateNeedsRestart {
                NECBridge.stop(); DispatchQueue.main.async { self.ready = false; self.open(game, slot: slot) }
            } catch { DispatchQueue.main.async { self.busy = false; self.error = error.localizedDescription; self.applyPause() } }
        }
    }
    func removeState(_ slot: Int) {
        let game = game; store.perform("正在删除存档…", operation: { try StateStore(self.store.files.directory(game)).remove(slot) }) { _ in self.objectWillChange.send() }
    }
    func mount(_ drive: Int, ref: DiskRef?) {
        guard !busy && ready else { return }; busy = true; applyPause(); let game = game
        store.queue.async {
            do { try checked(NECBridge.mount(drive, path: try self.store.files.path(game, ref), index: ref?.index ?? 0)); DispatchQueue.main.async { self.revision += 1; self.busy = false; self.message = ref == nil ? "磁盘已取出" : "磁盘已装入"; self.applyPause() } }
            catch { DispatchQueue.main.async { self.busy = false; self.error = error.localizedDescription; self.applyPause() } }
        }
    }
    func reset() { revision += 1; NECBridge.reset() }
    func clickMouse(_ mask: Int) { NECBridge.mouseX(0, y: 0, buttons: mouseButtons | gestureButtons | mask); NECBridge.mouseX(0, y: 0, buttons: mouseButtons | gestureButtons) }
    func stop(save: Bool = true) {
        guard !closing else { return }; closing = true; applyPause(); timer?.invalidate(); checkpoint(); let game = game, revision = revision, running = ready && everRunning
        store.queue.async {
            do {
                if save && running && self.savedRevision != revision { try StateStore(self.store.files.directory(game)).save(0); self.savedRevision = revision }
                NECBridge.stop()
                DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = false; self.store.player = nil; self.store.reload() }
            } catch { DispatchQueue.main.async { self.closing = false; self.userPaused = true; self.error = "退出保存未完成：\(error.localizedDescription)"; self.timer = Timer.scheduledTimer(withTimeInterval: 0.7, repeats: true) { [weak self] _ in self?.tick() }; self.applyPause() } }
        }
    }
}
