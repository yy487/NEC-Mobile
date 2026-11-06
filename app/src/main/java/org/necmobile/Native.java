package org.necmobile;

import android.view.Surface;

final class Native {
    static { System.loadLibrary("nec_host"); }
    static native boolean start(int platform, String directory, String disk, String secondDisk, int mode, int firstIndex, int secondIndex, boolean mouseEnabled);
    static native void surface(Surface surface);
    static native void key(int code, boolean down);
    static native void mouse(int dx, int dy, int buttons);
    static native void mouseMode(boolean enabled);
    static native void pause(boolean paused);
    static native void flush();
    static native void audioChanged();
    static native boolean checkSoundFont(String path);
    static native String state(String folder, boolean load);
    static native void reset();
    static native void releaseKeys();
    static native String disk(int drive);
    static native int diskIndex(int drive);
    static native void mount(int drive, String path, int index);
    static native String status();
    static native void stop();
}
