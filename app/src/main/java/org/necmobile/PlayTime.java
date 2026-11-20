package org.necmobile;

final class PlayTime {
    private long accumulated, started = -1;

    void running(boolean running, long now) {
        if (running && started < 0) started = now;
        else if (!running && started >= 0) {
            accumulated += Math.max(0, now - started);
            started = -1;
        }
    }

    long elapsed(long now) {
        return accumulated + (started < 0 ? 0 : Math.max(0, now - started));
    }

    static String format(long milliseconds) {
        long minutes = Math.max(0, milliseconds) / 60000;
        if (minutes == 0) return milliseconds > 0 ? "不足 1 分钟" : "0 分钟";
        if (minutes < 60) return minutes + " 分钟";
        return minutes / 60 + " 小时 " + minutes % 60 + " 分钟";
    }
}
