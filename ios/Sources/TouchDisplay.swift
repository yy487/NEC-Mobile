import UIKit

final class TouchDisplay: NECDisplayView {
    weak var session: PlayerSession?
    private var points: [ObjectIdentifier: CGPoint] = [:]
    private var originals: [ObjectIdentifier: CGPoint] = [:]
    private var primary: ObjectIdentifier?
    private var began: TimeInterval = 0
    private var fingers = 0
    private var moved = false
    private var dragging = false
    private var x = 0.0, y = 0.0, remainderX = 0.0, remainderY = 0.0
    private var direction = 0
    private var held = false
    private var hold: DispatchWorkItem?
    private var hoverPoint: CGPoint?
    private var hardwareMouse = false
    init(session: PlayerSession) {
        self.session = session; super.init(frame: .zero)
        isMultipleTouchEnabled = true; isUserInteractionEnabled = true
        session.display = self; session.cancelTouches = { [weak self] in self?.cancelInput() }
        addGestureRecognizer(UIHoverGestureRecognizer(target: self, action: #selector(hover(_:))))
    }
    required init?(coder: NSCoder) { return nil }
    override var canBecomeFirstResponder: Bool { true }
    override func didMoveToWindow() { super.didMoveToWindow(); if window != nil { becomeFirstResponder() } }
    func cancelInput() {
        hold?.cancel(); hold = nil
        if held { NECBridge.key(direction, down: false) }
        points.removeAll(); originals.removeAll(); primary = nil; fingers = 0; moved = false; dragging = false; direction = 0; held = false; x = 0; y = 0; remainderX = 0; remainderY = 0
        session?.gestureButtons = 0; NECBridge.mouseX(0, y: 0, buttons: session?.mouseButtons ?? 0)
        hardwareMouse = false; hoverPoint = nil
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let session = session, session.interactive else { return }
        becomeFirstResponder()
        if session.mouseMode && touches.contains(where: { $0.type == .indirectPointer }) {
            hardwareMouse = true; hoverPoint = touches.first?.location(in: self)
            session.gestureButtons = event?.buttonMask.contains(.secondary) == true ? 2 : 1
            NECBridge.mouseX(0, y: 0, buttons: session.mouseButtons | session.gestureButtons); return
        }
        if points.isEmpty { cancelInput(); began = ProcessInfo.processInfo.systemUptime }
        for touch in touches { let id = ObjectIdentifier(touch), point = touch.location(in: self); points[id] = point; originals[id] = point; if primary == nil { primary = id }; fingers += 1 }
        if fingers > 1 { hold?.cancel(); if held { NECBridge.key(direction, down: false); held = false }; if dragging { session.gestureButtons = 0; NECBridge.mouseX(0, y: 0, buttons: session.mouseButtons); dragging = false; moved = true } }
        else if session.mouseMode {
            let item = DispatchWorkItem { [weak self] in
                guard let self = self, let session = self.session, session.interactive, !self.points.isEmpty, self.fingers == 1, !self.moved else { return }
                self.dragging = true; session.gestureButtons = 1; NECBridge.mouseX(0, y: 0, buttons: session.mouseButtons | 1)
            }
            hold = item; DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: item)
        }
    }
    private func move(_ touch: UITouch) {
        guard let session = session, session.interactive else { cancelInput(); return }
        let id = ObjectIdentifier(touch), point = touch.location(in: self)
        guard let previous = points[id], let original = originals[id] else { return }
        if hypot(point.x - original.x, point.y - original.y) > 8 { moved = true }
        points[id] = point
        guard id == primary, fingers == 1 else { return }
        let speed = session.mouseMode ? session.game.input.mouseSpeed : 1
        let dx = Double(point.x - previous.x) * 640 / max(1, Double(bounds.width)) * speed
        let dy = Double(point.y - previous.y) * 400 / max(1, Double(bounds.height)) * speed
        if session.mouseMode {
            remainderX += dx; remainderY += dy; let mx = Int(remainderX), my = Int(remainderY); remainderX -= Double(mx); remainderY -= Double(my)
            NECBridge.mouseX(mx, y: my, buttons: session.mouseButtons | session.gestureButtons)
        } else {
            x = max(-48, min(48, x + dx)); y = max(-48, min(48, y + dy)); guard max(abs(x), abs(y)) >= 24 else { return }
            let codes = session.game.input.keypad ? [264, 258, 260, 262] : [273, 274, 276, 275]
            let code = abs(x) > abs(y) ? codes[x > 0 ? 3 : 2] : codes[y > 0 ? 1 : 0]
            guard code != direction else { return }
            if held { NECBridge.key(direction, down: false) }; held = false; direction = code
            NECBridge.key(code, down: true); NECBridge.key(code, down: false); hold?.cancel()
            let item = DispatchWorkItem { [weak self] in guard let self = self, self.session?.interactive == true, !self.points.isEmpty, self.fingers == 1 else { return }; self.held = true; NECBridge.key(self.direction, down: true) }
            hold = item; DispatchQueue.main.asyncAfter(deadline: .now() + 0.42, execute: item)
        }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if hardwareMouse, let point = touches.first?.location(in: self), let old = hoverPoint, let session = session {
            NECBridge.mouseX(Int((point.x-old.x)*640/max(1,bounds.width)), y: Int((point.y-old.y)*400/max(1,bounds.height)), buttons: session.mouseButtons | session.gestureButtons); hoverPoint = point; return
        }
        for touch in touches { move(touch) }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let session = session else { cancelInput(); return }
        if hardwareMouse { session.gestureButtons = 0; NECBridge.mouseX(0, y: 0, buttons: session.mouseButtons); hardwareMouse = false; hoverPoint = nil; return }
        for touch in touches { move(touch); points.removeValue(forKey: ObjectIdentifier(touch)) }
        guard points.isEmpty else { return }
        hold?.cancel()
        if !moved && !dragging && ProcessInfo.processInfo.systemUptime - began <= 0.3 && session.interactive {
            if session.mouseMode { session.clickMouse(fingers > 1 ? 2 : 1) }
            else { let code = fingers > 1 ? (session.game.input.tap == 13 ? 32 : 13) : session.game.input.tap; NECBridge.key(code, down: true); NECBridge.key(code, down: false) }
        }
        cancelInput()
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelInput() }
    @objc private func hover(_ recognizer: UIHoverGestureRecognizer) {
        let point = recognizer.location(in: self)
        if let previous = hoverPoint, !hardwareMouse, session?.mouseMode == true, session?.interactive == true {
            NECBridge.mouseX(Int((point.x - previous.x) * 640 / max(1, bounds.width)), y: Int((point.y - previous.y) * 400 / max(1, bounds.height)), buttons: session?.mouseButtons ?? 0)
        }
        hoverPoint = recognizer.state == .ended ? nil : point
    }
    private func code(_ key: UIKey) -> Int? {
        let value = key.keyCode.rawValue
        if value >= 4 && value <= 29 { return Int(value) - 4 + 97 }
        if value >= 30 && value <= 38 { return Int(value) - 30 + 49 }
        if value >= 58 && value <= 69 { return Int(value) - 58 + 282 }
        if value >= 89 && value <= 97 { return Int(value) - 89 + 257 }
        let special: [Int: Int] = [39:48,40:13,41:27,42:8,43:9,44:32,79:275,80:276,81:274,82:273,98:256,224:306,225:304,226:308,228:305,229:303,230:307]
        if let code = special[Int(value)] { return code }
        return key.charactersIgnoringModifiers.lowercased().unicodeScalars.first.flatMap { $0.value >= 33 && $0.value <= 126 ? Int($0.value) : nil }
    }
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        guard session?.interactive == true else { super.pressesBegan(presses, with: event); return }
        for press in presses { if let key = press.key, let code = code(key) { NECBridge.key(code, down: true) } }
    }
    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) { for press in presses { if let key = press.key, let code = code(key) { NECBridge.key(code, down: false) } } }
    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) { NECBridge.releaseKeys() }
}
