package org.necmobile;

import android.view.Surface;

final class Native {
    static { System.loadLibrary("nec_host"); }
    static native boolean start(int platform, String directory, String disk, String secondDisk, int mode, int firstIndex, int secondIndex, boolean mouseEnabled);
    static native void surface(Surface surface);
    static native void key(int code, boolean down);
    static native void mouse(int dx, int dy, int buttons);
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
    static native String mountResult(int drive, String path, int index);
    static native String status();
    static native String takeStartupWarning();
    static native int startState();
    static native boolean mouseActivity();
    static native String startError();
    static native String machineInfo();
    static native String audioInfo();
    static native int audioDevice();
    static native int videoGeometry();
    static native String hdiInfo(String path) throws java.io.IOException;
    static native String inspectDisk(String path);
    static native void prepareFloppy(String template, String output, byte[] autoexec, byte[] config,
                                     String[] driverFiles, byte[][] driverNames, byte[] label) throws java.io.IOException;
    static native byte[] dosAutoexec(String path) throws java.io.IOException;
    static native void buildDosDisk(String template, String output, String[] hostFiles, byte[][] dosNames, byte[] startup, byte[] autoexec) throws java.io.IOException;
    static native void stop();
}
