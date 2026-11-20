package org.necmobile;

final class SwipeKeys {
    interface Sink { void key(int code, boolean down); }
    static final long HOLD_DELAY_MS = 420;
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    private static final int[] KEYPAD_CODES = {264, 258, 260, 262};
    private static final int[] ARROW_CODES = {273, 274, 276, 275};
    private final Sink sink;
    private final float threshold;
    private float x, y;
    private int activeCode;
    private long selectedAt;
    private boolean held;
    private boolean keypad = true;

    SwipeKeys(float threshold, Sink sink) { this.threshold = threshold; this.sink = sink; }
    void keypad(boolean value) { keypad = value; reset(); }
    void reset() {
        if (held) sink.key(activeCode, false);
        x = y = 0; activeCode = 0; held = false;
    }
    boolean move(int dx, int dy, long now) {
        x = Math.max(-threshold * 2, Math.min(threshold * 2, x + dx));
        y = Math.max(-threshold * 2, Math.min(threshold * 2, y + dy));
        if (Math.max(Math.abs(x), Math.abs(y)) < threshold) return false;
        int direction;
        if (Math.abs(x) > Math.abs(y)) direction = x > 0 ? RIGHT : LEFT;
        else direction = y > 0 ? DOWN : UP;
        int[] codes = keypad ? KEYPAD_CODES : ARROW_CODES;
        int code = codes[direction];
        if (code == activeCode) return false;
        if (held) sink.key(activeCode, false);
        activeCode = code; selectedAt = now; held = false;
        sink.key(code, true); sink.key(code, false);
        return true;
    }
    void hold(long now) {
        if (activeCode != 0 && !held && now - selectedAt >= HOLD_DELAY_MS) {
            held = true;
            sink.key(activeCode, true);
        }
    }
}
