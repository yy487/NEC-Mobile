package org.necmobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class BootEnvironment {
    static final String[] SYSTEMS = {"auto", "existing", "freedos", "dos33", "dos50", "dos62", "dos21"};
    static final String[] LABELS = {"自动准备", "使用现有盘位", "FreeDOS(98)", "MS-DOS 3.3D", "MS-DOS 5.0A", "MS-DOS 6.2", "MS-DOS 2.1"};
    static final String[] MEMORIES = {"none", "xms", "ems"};
    static final String[] MEMORY_LABELS = {"保留默认环境", "XMS · HIMEM", "XMS + EMS · HIMEM / EMM386"};
    private final Context context;
    private final GameLibrary library;
    BootEnvironment(Context context, GameLibrary library) { this.context = context; this.library = library; }
    static String label(String id) {
        int index = Arrays.asList(SYSTEMS).indexOf(id);
        return index < 0 ? LABELS[0] : LABELS[index];
    }
    static String selected(GameLibrary.Game game) {
        return "existing".equals(game.startupPolicy) ? "existing" : game.startupSystem;
    }
    static boolean hasAsset(Context context, String directory, String name) {
        try {
            String[] files = context.getAssets().list("dos/" + directory);
            return files != null && Arrays.asList(files).contains(name);
        } catch (IOException ignored) { return false; }
    }
    static boolean hasMemoryDrivers(Context context) {
        return hasAsset(context, "drivers", "HIMEM.SYS") && hasAsset(context, "drivers", "EMM386.EXE")
                && hasAsset(context, "boot", "dos62-v1.fdi");
    }
    static boolean hasSystem(Context context, String id) {
        return !id.startsWith("dos") || hasAsset(context, "boot", id + "-v1.fdi");
    }
    private static String oem(String value) {
        return new String(value.getBytes(StandardCharsets.ISO_8859_1), DosLibrary.OEM);
    }
    File asset(String path, String filename) throws IOException {
        File directory = new File(library.legacy.root, "dos-resources/v1");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备启动资源目录");
        File target = new File(directory, filename);
        if (target.isFile()) return target;
        File temporary = File.createTempFile("resource-", ".pending", directory);
        try {
            try (InputStream input = context.getAssets().open("dos/" + path); FileOutputStream output = new FileOutputStream(temporary)) {
                if (Library.copy(input, output, 256L * 1024 * 1024) == 0) throw new IOException("内置启动资源为空");
                output.getFD().sync();
            }
            android.system.Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            return target;
        } catch (android.system.ErrnoException error) { throw new IOException("无法保存启动资源", error); }
        finally { temporary.delete(); }
    }
    private static List<String> programs(JSONObject facts) throws Exception {
        List<String> result = new ArrayList<>();
        JSONArray entries = facts.optJSONArray("programs");
        if (entries != null) for (int i = 0; i < entries.length(); ++i) result.add(oem(entries.getString(i)));
        return result;
    }
    private static String entry(JSONObject facts) throws Exception {
        List<String> batches = new ArrayList<>(), programs = new ArrayList<>();
        for (String path : programs(facts)) {
            if (path.equalsIgnoreCase("AUTOEXEC.BAT")) return path;
            if (path.contains("/")) continue;
            if (path.toUpperCase(Locale.ROOT).endsWith(".BAT")) batches.add(path);
            else if (!path.equalsIgnoreCase("COMMAND.COM")) programs.add(path);
        }
        if (batches.size() == 1) return batches.get(0);
        return batches.isEmpty() && programs.size() == 1 ? programs.get(0) : "";
    }
    private JSONObject directoryLaunch(GameLibrary.Game game) throws Exception {
        File file = new File(library.directory(game), "dos-launch.json");
        if (!game.kind.equals("dos-directory") || !file.isFile()) return null;
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Library.copy(input, output, 65536);
            return new JSONObject(output.toString("UTF-8"));
        }
    }
    void prepare(GameLibrary.Game game, boolean force) throws Exception {
        if ("existing".equals(game.startupPolicy)) {
            game.startupReady = !game.first.isEmpty() || !game.hardDisk.isEmpty();
            return;
        }
        if (!Arrays.asList(MEMORIES).contains(game.startupMemory)) throw new IOException("内存驱动方案无效");
        Map<Library.Disk, JSONObject> facts = new LinkedHashMap<>();
        Library.Disk hard = null;
        for (Library.Disk disk : game.media) {
            if (game.generatedMedia.containsKey(disk.file.getName())) continue;
            if (GameLibrary.hardDisk(disk) && (hard == null || disk.file.getName().equals(game.hardDisk))) hard = disk;
            facts.put(disk, new JSONObject(Native.inspectDisk(disk.file.getAbsolutePath())));
        }
        if (hard != null) game.hardDisk = hard.file.getName();
        boolean automatic = game.startupSystem.equals("auto") && game.startupMemory.equals("none") && game.startupSource.isEmpty();
        List<Library.Disk> order = new ArrayList<>(facts.keySet());
        Library.Disk preferred = game.disk(game.first);
        if (preferred != null && order.remove(preferred)) order.add(0, preferred);
        if (automatic) for (Library.Disk disk : order) {
            String boot = facts.get(disk).optString("boot");
            if (boot.equals("ready") || boot.equals("custom")) {
                if (GameLibrary.hardDisk(disk)) {
                    game.hardDisk = disk.file.getName();
                    game.bootDevice = "hdd";
                } else {
                    game.first = disk.file.getName(); game.firstIndex = 0; game.bootDevice = "floppy";
                    game.second = ""; game.secondIndex = 0;
                    for (Library.Disk other : order) if (other != disk && !GameLibrary.hardDisk(other)) { game.second = other.file.getName(); break; }
                }
                game.startupReady = true; game.startupHint = "游戏自带启动环境";
                return;
            }
        }
        Library.Disk data = null;
        String command = "";
        JSONObject dosLaunch = directoryLaunch(game);
        for (Map.Entry<Library.Disk, JSONObject> item : facts.entrySet()) {
            Library.Disk disk = item.getKey(); JSONObject info = item.getValue();
            if (!game.startupSource.isEmpty() && !game.startupSource.equals(disk.file.getName())) continue;
            if (!info.optBoolean("supported")) continue;
            if (!force && !info.optString("boot").equals("missing-dos")) continue;
            String candidate = game.startupSource.isEmpty() ? "" : game.startupEntry;
            if (candidate.isEmpty() && GameLibrary.hardDisk(disk) && dosLaunch != null) candidate = "NECGAME/" + dosLaunch.getString("entry");
            if (candidate.isEmpty()) candidate = entry(info);
            if (candidate.isEmpty()) continue;
            candidate = candidate.replace('\\', '/');
            for (String part : candidate.split("/", -1)) DosLibrary.component(part);
            boolean found = false;
            for (String path : programs(info)) if (path.equalsIgnoreCase(candidate)) { candidate = path; found = true; break; }
            if (!found) throw new IOException("所选磁盘中没有启动文件：" + candidate);
            if (data != null) { game.startupReady = false; game.startupHint = "请选择游戏数据盘和启动文件，再准备环境"; return; }
            data = disk; command = candidate;
        }
        if (data == null) {
            game.startupReady = false; game.startupHint = "请确认现有盘位，或选择游戏数据盘与启动文件";
            return;
        }
        boolean dataHard = GameLibrary.hardDisk(data);
        String system = game.startupSystem;
        if (system.equals("auto")) {
            system = game.startupMemory.equals("none") ? (dataHard ? "dos50" : "dos33") : "dos62";
            if (!hasSystem(context, system) && game.startupMemory.equals("none")) system = "freedos";
        }
        int version = system.equals("freedos") ? 2 : 1;
        if (!game.startupMemory.equals("none") && !system.equals("dos62")) throw new IOException("HIMEM / EMM386 组合使用 MS-DOS 6.2，请选择自动准备或 MS-DOS 6.2");
        String drive = dataHard ? "C:" : "B:";
        String working = command.contains("/") ? command.substring(0, command.lastIndexOf('/')) : "";
        if (dosLaunch != null && dataHard) {
            String directory = dosLaunch.optString("workingDirectory").replace('\\', '/').replaceAll("/+$", "");
            working = "NECGAME" + (directory.isEmpty() ? "" : "/" + directory);
        }
        String startup = "ECHO OFF\r\n" + drive + "\r\nCD \\" + working.replace('/', '\\') + "\r\n" + drive + "\\" + command.replace('/', '\\') + "\r\n";
        String config = "FILES=30\r\nBUFFERS=10\r\n";
        List<String> drivers = new ArrayList<>(); List<byte[]> names = new ArrayList<>();
        if (!game.startupMemory.equals("none")) {
            drivers.add(asset("drivers/HIMEM.SYS", "HIMEM62.SYS").getAbsolutePath()); names.add("HIMEM.SYS".getBytes(DosLibrary.OEM));
            config += "DEVICE=A:\\NECMOB\\HIMEM.SYS /TESTMEM:OFF\r\nDOS=HIGH\r\n";
            if (game.startupMemory.equals("ems")) {
                drivers.add(asset("drivers/EMM386.EXE", "EMM38662.EXE").getAbsolutePath()); names.add("EMM386.EXE".getBytes(DosLibrary.OEM));
                int extended = Integer.parseInt(game.machineOptions.getOrDefault("np2kai_ExMemory", "3"));
                int ems = Math.max(512, Math.min(32768, (extended - 1) * 1024));
                config += "DEVICE=A:\\NECMOB\\EMM386.EXE /EM=" + ems + "\r\n";
            }
        }
        String signature = data.file.getName() + "\n" + startup + config;
        for (Library.Disk disk : game.media) {
            GameLibrary.GeneratedMedia generated = game.generatedMedia.get(disk.file.getName());
            if (generated != null && generated.role.equals("boot") && generated.template.equals(system) && generated.version == version &&
                    generated.configuration.equals(signature) && disk.file.isFile()) {
                apply(game, disk, data, command, system); return;
            }
        }
        String filename = system + "-v" + version + ".fdi";
        File template = asset("boot/" + filename, filename);
        Library.Disk disk = generate(game, "系统盘 · " + label(system), template, startup.getBytes(DosLibrary.OEM), config.getBytes(DosLibrary.OEM),
                drivers.toArray(new String[0]), names.toArray(new byte[0][]), null);
        game.media.add(disk);
        game.generatedMedia.put(disk.file.getName(), new GameLibrary.GeneratedMedia("boot", system, version, signature));
        apply(game, disk, data, command, system);
    }
    private void apply(GameLibrary.Game game, Library.Disk boot, Library.Disk data, String command, String system) {
        game.first = boot.file.getName();
        if (GameLibrary.hardDisk(data)) {
            game.hardDisk = data.file.getName();
            game.second = "";
        } else game.second = data.file.getName();
        game.firstIndex = game.secondIndex = 0; game.bootDevice = "floppy";
        game.startupEntry = command; game.startupInstance = boot.file.getName();
        game.startupReady = true; game.startupHint = label(system) + " · " + command;
    }
    private Library.Disk generate(GameLibrary.Game game, String name, File template, byte[] startup, byte[] config,
                                  String[] files, byte[][] names, byte[] label) throws Exception {
        File directory = new File(library.directory(game), "media");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建游戏磁盘目录");
        String id = UUID.randomUUID().toString();
        File temporary = new File(directory, id + ".pending.fdi"), target = new File(directory, id + ".fdi");
        try {
            Native.prepareFloppy(template.getAbsolutePath(), temporary.getAbsolutePath(), startup, config, files, names, label);
            android.system.Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
        } finally { temporary.delete(); }
        return new Library.Disk(name, target, Collections.singletonList(""));
    }
    Library.Disk createSave(GameLibrary.Game game, String name) throws Exception {
        Library.Disk disk = generate(game, name, asset("blank/fat12-1200-v2.fdi", "fat12-1200-v2.fdi"), null, null,
                new String[0], new byte[0][], "SAVE".getBytes(StandardCharsets.US_ASCII));
        return retain(game, disk, "save", "fat12-1200");
    }
    Library.Disk createTools(GameLibrary.Game game) throws Exception {
        if (!hasAsset(context, "tools", "dos62-v1.fdi") || !hasAsset(context, "drivers", "MEM.EXE")) {
            String startup = "ECHO OFF\r\nPROMPT $P$G\r\nECHO PC-98 FreeDOS tools\r\nECHO DIR / COPY / TYPE / SYS\r\n";
            Library.Disk disk = generate(game, "FreeDOS(98) 工具盘", asset("tools/freedos-v2.fdi", "tools-freedos-v2.fdi"),
                    startup.getBytes(DosLibrary.OEM), "FILES=30\r\n".getBytes(DosLibrary.OEM), new String[0], new byte[0][], null);
            return retain(game, disk, "tools", "tools-freedos");
        }
        File mem = asset("drivers/MEM.EXE", "MEM62.EXE");
        String startup = "ECHO OFF\r\nPATH A:\\;A:\\NECMOB\r\nPROMPT $P$G\r\n" +
                "ECHO.\r\nECHO PC-98 DOS tools\r\nECHO DIR / COPY / MEM / SEDIT / FORMAT / HDFORMAT / SYS\r\nECHO.\r\n";
        Library.Disk disk = generate(game, "DOS 维护工具盘", asset("tools/dos62-v1.fdi", "tools-dos62-v1.fdi"),
                startup.getBytes(DosLibrary.OEM), "FILES=30\r\n".getBytes(DosLibrary.OEM),
                new String[]{mem.getAbsolutePath()}, new byte[][]{"MEM.EXE".getBytes(DosLibrary.OEM)}, null);
        return retain(game, disk, "tools", "tools-dos62");
    }
    private Library.Disk retain(GameLibrary.Game game, Library.Disk disk, String role, String template) throws Exception {
        try {
            library.updateGame(game.id, current -> {
                current.media.add(disk);
                current.generatedMedia.put(disk.file.getName(), new GameLibrary.GeneratedMedia(role, template, 1));
            });
        } catch (Exception error) { disk.file.delete(); throw error; }
        return disk;
    }
}
