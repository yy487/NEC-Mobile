import AppKit
let output = URL(fileURLWithPath: CommandLine.arguments[1])
for (name, size) in [("AppIcon60@2x.png",120),("AppIcon60@3x.png",180),("AppIcon76.png",76),("AppIcon76@2x.png",152),("AppIcon83.5@2x.png",167)] {
    guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0), let context = NSGraphicsContext(bitmapImageRep: bitmap) else { fatalError("App icon drawing failed") }
    NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = context
    let width = CGFloat(size)
    NSColor(calibratedRed: 22/255, green: 27/255, blue: 25/255, alpha: 1).setFill(); NSBezierPath(rect: NSRect(x: 0, y: 0, width: width, height: width)).fill()
    NSColor(calibratedRed: 199/255, green: 236/255, blue: 170/255, alpha: 1).setFill()
    let monitor = NSRect(x: width * 0.18, y: width * 0.29, width: width * 0.64, height: width * 0.48)
    NSBezierPath(roundedRect: monitor, xRadius: width * 0.05, yRadius: width * 0.05).fill()
    NSBezierPath(roundedRect: NSRect(x: width * 0.44, y: width * 0.18, width: width * 0.12, height: width * 0.16), xRadius: width * 0.02, yRadius: width * 0.02).fill()
    NSBezierPath(roundedRect: NSRect(x: width * 0.32, y: width * 0.15, width: width * 0.36, height: width * 0.06), xRadius: width * 0.02, yRadius: width * 0.02).fill()
    NSColor(calibratedRed: 22/255, green: 27/255, blue: 25/255, alpha: 1).setFill(); NSBezierPath(roundedRect: monitor.insetBy(dx: width * 0.045, dy: width * 0.045), xRadius: width * 0.02, yRadius: width * 0.02).fill()
    NSColor(calibratedRed: 199/255, green: 236/255, blue: 170/255, alpha: 1).setStroke()
    let cursor = NSBezierPath(); cursor.lineWidth = width * 0.035; cursor.lineCapStyle = .round; cursor.lineJoinStyle = .round
    cursor.move(to: NSPoint(x: width * 0.31, y: width * 0.59)); cursor.line(to: NSPoint(x: width * 0.40, y: width * 0.53)); cursor.line(to: NSPoint(x: width * 0.31, y: width * 0.47)); cursor.stroke()
    let line = NSBezierPath(); line.lineWidth = width * 0.035; line.move(to: NSPoint(x: width * 0.47, y: width * 0.47)); line.line(to: NSPoint(x: width * 0.64, y: width * 0.47)); line.stroke()
    NSGraphicsContext.restoreGraphicsState()
    try bitmap.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent(name))
}
