package org.necmobile;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

final class HostAudio {
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences("host-audio", Context.MODE_PRIVATE); }
    static String encode(Context context) {
        SharedPreferences settings = preferences(context);
        return "audio_rate=" + settings.getInt("rate", 0) + "\naudio_buffer_ms=" + settings.getInt("buffer", 40) + "\n";
    }
    static void settings(Context context) {
        SharedPreferences settings = preferences(context);
        new AlertDialog.Builder(context).setTitle("手机音频输出 · 所有游戏")
            .setItems(new String[]{"采样率：" + (settings.getInt("rate", 0) == 0 ? "系统选择" : settings.getInt("rate", 0) + " Hz"),
                    "缓冲：" + settings.getInt("buffer", 40) + " ms"}, (dialog, index) -> {
                int[] values = index == 0 ? new int[]{0, 44100, 48000} : new int[]{20, 40, 80, 120};
                String[] names = index == 0 ? new String[]{"系统选择", "44100 Hz", "48000 Hz"} : new String[]{"20 ms", "40 ms", "80 ms", "120 ms"};
                int current = settings.getInt(index == 0 ? "rate" : "buffer", index == 0 ? 0 : 40), selected = 0;
                for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
                new AlertDialog.Builder(context).setTitle("下次启动生效").setSingleChoiceItems(names, selected, (picker, choice) -> {
                    settings.edit().putInt(index == 0 ? "rate" : "buffer", values[choice]).apply(); picker.dismiss();
                }).setNegativeButton("取消", null).show();
            }).setNegativeButton("关闭", null).show();
    }
    static String route(Context context, int id) {
        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if (manager != null) for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (device.getId() == id) return device.getProductName().toString();
        return "系统媒体输出";
    }
}
