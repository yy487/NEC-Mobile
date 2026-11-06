package org.necmobile;

import java.util.HashMap;
import java.util.Map;

// Relative movement and gesture state are independent of Android event delivery.
final class MousePad {
    interface Sink {
        void move(int x, int y);
        void buttons(int mask);
        void click(int mask);
    }
    private final Sink sink;
    private final float slop;
    private final Map<Integer, float[]> pointers = new HashMap<>();
    private int primary, fingers;
    private float lastX, lastY, scaleX, scaleY, remainderX, remainderY;
    private long began;
    private boolean moved, dragging;

    MousePad(float slop, Sink sink) { this.slop = slop; this.sink = sink; }
    void down(int id, float x, float y, long time, float sx, float sy) {
        cancel();
        primary = id; lastX = x; lastY = y; began = time;
        scaleX = sx; scaleY = sy; fingers = 1;
        pointers.put(id, new float[]{x, y});
    }
    void another(int id, float x, float y) {
        pointers.put(id, new float[]{x, y}); fingers++;
        if (dragging) { sink.buttons(0); dragging = false; moved = true; }
    }
    void move(int id, float x, float y) {
        float[] start = pointers.get(id);
        if (start == null) return;
        if (Math.hypot(x - start[0], y - start[1]) > slop) moved = true;
        if (id == primary && fingers == 1) {
            remainderX += (x - lastX) * scaleX; remainderY += (y - lastY) * scaleY;
            int dx = (int) remainderX, dy = (int) remainderY;
            remainderX -= dx; remainderY -= dy;
            if (dx != 0 || dy != 0) sink.move(dx, dy);
            lastX = x; lastY = y;
        }
    }
    void longPress() {
        if (fingers == 1 && !moved && !pointers.isEmpty()) { dragging = true; sink.buttons(1); }
    }
    void up(int id, float x, float y, long time, boolean last) {
        move(id, x, y); pointers.remove(id);
        if (!last) return;
        if (dragging) sink.buttons(0);
        else if (!moved && time - began <= 300 && (fingers == 1 || fingers == 2)) sink.click(fingers == 2 ? 2 : 1);
        clear();
    }
    void cancel() { if (dragging) sink.buttons(0); clear(); }
    private void clear() {
        pointers.clear(); fingers = 0; moved = dragging = false; remainderX = remainderY = 0;
    }
}
