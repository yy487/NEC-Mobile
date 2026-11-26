package org.necmobile;

import android.content.Context;
import android.text.format.Formatter;
import java.io.File;
import java.io.IOException;
import java.util.*;

final class AppStorage {
    static final class Usage {
        long disks, states, resources, temporary, other, cache, uncompressedStates;
        String describe(Context context) {
            return "游戏磁盘  " + size(context, disks) + "\n即时存档  " + size(context, states)
                    + "\n字库、音源与系统模板  " + size(context, resources)
                    + "\n其他应用文件  " + size(context, other)
                    + "\n可清理的导入与存档残留  " + size(context, temporary)
                    + "\n缓存  " + size(context, cache)
                    + "\n\n合计  " + size(context, disks + states + resources + temporary + other + cache);
        }
    }
    private static String size(Context context, long bytes) { return Formatter.formatFileSize(context, bytes); }
    private static List<File> temporary(Context context) throws Exception {
        List<File> result = new ArrayList<>();
        for (int platform : new int[]{88, 98}) {
            GameLibrary library = new GameLibrary(context, platform);
            Set<String> ids = new HashSet<>();
            for (GameLibrary.Game game : library.list()) {
                ids.add(game.id);
                File directory = library.directory(game);
                StateSlots slots = new StateSlots(directory);
                result.addAll(slots.unused());
                result.addAll(slots.temporaryFiles());
                if (game.kind.equals("dos-directory") && game.disk("dos-game.hdi") != null && game.disk("dos-game.hdi").file.isFile()) {
                    result.add(new File(directory, "dos-source"));
                    result.add(new File(directory, "import.zip"));
                }
                File recovery = new File(directory, "states/recovery");
                if (recovery.isDirectory()) result.add(recovery);
            }
            File[] imports = new File(library.legacy.root, "games").listFiles();
            if (imports != null) for (File folder : imports)
                if (folder.isDirectory() && folder.getName().matches("[0-9a-f-]{36}") && !ids.contains(folder.getName())) result.add(folder);
        }
        return result;
    }
    static Usage inspect(Context context) throws Exception {
        Usage result = new Usage();
        List<File> temporary = temporary(context);
        scan(context.getFilesDir(), temporary, result);
        result.other += bytes(new File(context.getApplicationInfo().dataDir, "shared_prefs"));
        result.other += bytes(new File(context.getApplicationInfo().dataDir, "databases"));
        result.cache = bytes(context.getCacheDir()) + bytes(context.getCodeCacheDir());
        for (int platform : new int[]{88, 98}) {
            GameLibrary library = new GameLibrary(context, platform);
            for (GameLibrary.Game game : library.list()) result.uncompressedStates += new StateSlots(library.directory(game)).uncompressedBytes();
        }
        return result;
    }
    private static void scan(File file, List<File> temporary, Usage usage) throws IOException {
        if (temporary.contains(file)) { usage.temporary += bytes(file); return; }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) scan(child, temporary, usage);
            return;
        }
        String path = file.getPath().replace(File.separatorChar, '/');
        if (path.contains("/states/")) usage.states += file.length();
        else if (path.contains("/media/") || path.contains("/disks/")) usage.disks += file.length();
        else if (path.contains("/dos-templates/") || path.contains("/boot-resources/") || path.contains("/firmware-sources/")
                || path.matches("(?i).*\\.(rom|fnt|bmp|wav|sf2|fdi|hdi)$")) usage.resources += file.length();
        else usage.other += file.length();
    }
    static long bytes(File file) {
        if (!file.isDirectory()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += bytes(child);
        return total;
    }
    static long clean(Context context) throws Exception {
        requireStopped(context);
        long removed = 0;
        File allowed = context.getFilesDir().getCanonicalFile();
        for (File file : temporary(context)) {
            long before = bytes(file);
            remove(file, allowed);
            removed += Math.max(0, before - bytes(file));
        }
        return removed;
    }
    static long compressStates(Context context) throws Exception {
        requireStopped(context);
        long removed = 0;
        for (int platform : new int[]{88, 98}) {
            GameLibrary library = new GameLibrary(context, platform);
            for (GameLibrary.Game game : library.list()) removed += new StateSlots(library.directory(game)).compressExisting();
        }
        return removed;
    }
    private static void requireStopped(Context context) throws IOException {
        android.app.ActivityManager manager = (android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null && processes.stream().anyMatch(process -> process.processName.equals(context.getPackageName() + ":emulation")))
            throw new IOException("请先退出正在运行的游戏，再整理存储数据");
    }
    private static void remove(File file, File allowed) throws IOException {
        File actual = file.getCanonicalFile();
        if (!actual.getPath().startsWith(allowed.getPath() + File.separator)) throw new IOException("清理目录超出应用文件范围");
        File[] children = actual.listFiles();
        if (children != null) for (File child : children) remove(child, allowed);
        if (actual.exists() && !actual.delete()) throw new IOException("无法清理临时文件：" + actual.getName());
    }
}
