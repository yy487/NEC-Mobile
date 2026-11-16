package org.necmobile;

import java.text.Normalizer;
import java.util.Locale;

final class InputPresets {
    static final class Preset {
        final String id, title;
        final boolean mouse, keypad, fullKeyboard;
        final int tap;

        Preset(String id, String title, boolean mouse, boolean keypad, int tap, boolean fullKeyboard) {
            this.id = id;
            this.title = title;
            this.mouse = mouse;
            this.keypad = keypad;
            this.tap = tap;
            this.fullKeyboard = fullKeyboard;
        }
    }

    static final Preset[] ALL = {
        new Preset("keypad-enter", "小键盘方向 + Enter", false, true, 13, false),
        new Preset("keypad-space", "小键盘方向 + 空格", false, true, 32, false),
        new Preset("arrows-enter", "方向键 + Enter", false, false, 13, false),
        new Preset("arrows-space", "方向键 + 空格", false, false, 32, false),
        new Preset("mouse", "鼠标触控板", true, true, 13, false),
        new Preset("text", "文字命令输入", false, false, 13, true)
    };

    static Preset find(String id) {
        for (Preset preset : ALL) if (preset.id.equals(id)) return preset;
        return null;
    }

    // Titles suggest a preset. Applying one explicitly binds it to this imported game.
    static Preset candidate(int platform, String name) {
        if (name == null) return null;
        String title = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|\\[[^]]*\\]|【[^】]*】", "").replaceAll("[\\s_☆・·]", "");
        if (platform == 98 && (title.equals("妖刀伝説") || title.equals("妖刀传说") || title.equals("youtoudensetsu")))
            return find("keypad-enter");
        if (platform == 88 && (title.equals("カサブランカに愛を") || title.equals("casablanca")))
            return find("text");
        return null;
    }
}
