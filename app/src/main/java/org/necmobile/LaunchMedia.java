package org.necmobile;

import java.io.IOException;

final class LaunchMedia {
    static final class Ref {
        final String file;
        final int index;
        Ref(String file, int index) { this.file = file; this.index = index; }
    }
    final Ref floppyA, floppyB, hardDisk;
    LaunchMedia(Ref floppyA, Ref floppyB, Ref hardDisk) {
        this.floppyA = floppyA; this.floppyB = floppyB; this.hardDisk = hardDisk;
    }
    static LaunchMedia defaults(GameLibrary.Game game) {
        Ref first = game.first.isEmpty() ? null : new Ref(game.first, game.firstIndex);
        Ref second = game.second.isEmpty() ? null : new Ref(game.second, game.secondIndex);
        Library.Disk disk = game.disk(game.first);
        return disk != null && GameLibrary.hardDisk(disk) ? new LaunchMedia(null, null, first) : new LaunchMedia(first, second, null);
    }
    private static Library.Disk resolve(GameLibrary.Game game, Ref ref, String slot, boolean hard) throws IOException {
        if (ref == null) return null;
        Library.Disk disk = game.disk(ref.file);
        if (disk == null || !disk.file.isFile()) throw new IOException(slot + "的磁盘文件不存在，请重新选择");
        if (GameLibrary.hardDisk(disk) != hard) throw new IOException(slot + "的磁盘类型不匹配");
        if (ref.index < 0 || ref.index >= disk.titles.size()) throw new IOException(slot + "的内嵌盘号无效，请重新选择");
        return disk;
    }
    void validate(GameLibrary.Game game, int platform) throws IOException {
        validate(game, platform, false);
    }
    void validate(GameLibrary.Game game, int platform, boolean allowEmpty) throws IOException {
        resolve(game, floppyA, "软驱 A", false);
        resolve(game, floppyB, "软驱 B", false);
        resolve(game, hardDisk, "硬盘槽", true);
        if (hardDisk != null) {
            if (platform != 98) throw new IOException("PC-88 使用软盘启动");
            if (floppyA != null || floppyB != null) throw new IOException("请先选择硬盘单独启动，或改用软盘组合");
        } else if (floppyA == null && !allowEmpty) throw new IOException("请为软驱 A 选择启动盘");
        if (floppyA != null && floppyB != null && floppyA.file.equals(floppyB.file) && floppyA.index == floppyB.index)
            throw new IOException("同一张盘已在软驱 A，请为软驱 B 选择其他盘或留空");
    }
    void applyDefaults(GameLibrary.Game game) {
        Ref first = hardDisk != null ? hardDisk : floppyA;
        game.first = first == null ? "" : first.file;
        game.firstIndex = first == null ? 0 : first.index;
        game.second = floppyB == null ? "" : floppyB.file;
        game.secondIndex = floppyB == null ? 0 : floppyB.index;
    }
}
