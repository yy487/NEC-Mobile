package org.necmobile;

final class SwipeKeys {
    interface Sink { void press(int code); }
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    private static final int[] KEYPAD_CODES = {264, 258, 260, 262};
    private static final int[] ARROW_CODES = {273, 274, 276, 275};
    private final Sink sink;
    private final float threshold;
    private float x, y;
    private boolean sent;
    private boolean keypad = true;

    SwipeKeys(float threshold, Sink sink) { this.threshold = threshold; this.sink = sink; }
    void keypad(boolean value) { keypad = value; reset(); }
    void reset() { x = y = 0; sent = false; }
    void move(int dx, int dy) {
        if (sent) return;
        x += dx; y += dy;
        if (Math.max(Math.abs(x), Math.abs(y)) < threshold) return;
        int direction;
        if (Math.abs(x) > Math.abs(y)) direction = x > 0 ? RIGHT : LEFT;
        else direction = y > 0 ? DOWN : UP;
        int[] codes = keypad ? KEYPAD_CODES : ARROW_CODES;
        sent = true;
        sink.press(codes[direction]);
    }
}
