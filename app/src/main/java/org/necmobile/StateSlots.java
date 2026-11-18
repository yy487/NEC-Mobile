package org.necmobile;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;

final class StateSlots {
    static final int COUNT=6;
    static final int AUTO=0;
    private final File root;
    StateSlots(File gameDirectory) { root=new File(gameDirectory,"states"); root.mkdirs(); }
    private AtomicFile pointer(int slot) {
        if (slot<0 || slot>COUNT) throw new IllegalArgumentException("无效存档槽");
        return new AtomicFile(new File(root,slot == AUTO ? "auto.txt" : "slot-"+slot+".txt"));
    }
    boolean hasAuto() { return new File(root,"auto.txt").isFile() || new File(root,"auto.txt.bak").isFile(); }
    private static long number(DataInputStream input) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt())); }
    private static String text(DataInputStream input, int limit) throws IOException {
        long size = number(input); if (size > limit) throw new IOException("会话记录损坏");
        byte[] bytes = new byte[(int)size]; input.readFully(bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.indexOf('\0') >= 0) throw new IOException("会话记录损坏");
        return value;
    }
    private LaunchMedia.Ref media(GameLibrary.Game game, String path, int index) throws IOException {
        if (path.isEmpty()) return null;
        for (Library.Disk disk : game.media) if (disk.file.getCanonicalPath().equals(new File(path).getCanonicalPath()))
            return new LaunchMedia.Ref(disk.file.getName(), index);
        throw new IOException("上次会话使用的磁盘已移除，请恢复文件或正常启动游戏");
    }
    private static final class Snapshot {
        int mode;
        String configuration;
        LaunchMedia media;
    }
    static final class RestartRequired extends IOException {
        RestartRequired() { super("此档需要恢复保存时的机器环境"); }
    }
    private Snapshot snapshot(GameLibrary.Game game, int platform, int slot) throws Exception {
        File folder = folder(slot); if (folder == null) throw new IOException("此槽没有存档");
        try (DataInputStream input = new DataInputStream(new FileInputStream(new File(folder, "host.bin")))) {
            byte[] magic = new byte[11]; input.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("NECSTATE024") || number(input) != platform)
                throw new IOException("会话不完整或平台不匹配");
            long mode = number(input);
            if (mode > (platform == 88 ? 5 : 2)) throw new IOException("存档机型设置损坏");
            if (!text(input, 4096).equals(root.getParentFile().getAbsolutePath())) throw new IOException("会话不属于当前游戏副本");
            String configuration = text(input, 16384);
            String first = text(input, 4096); int firstIndex = Math.toIntExact(number(input));
            String second = text(input, 4096); int secondIndex = Math.toIntExact(number(input));
            if (input.read() != -1) throw new IOException("会话记录损坏");
            LaunchMedia.Ref hard = null;
            String bootDevice = "auto";
            for (String line : configuration.split("\n")) if (line.startsWith("host_hard_disk=")) hard = media(game, line.substring(15), 0);
            for (String line : configuration.split("\n")) if (line.startsWith("startup_boot_device=")) bootDevice = line.substring(20);
            LaunchMedia result = new LaunchMedia(media(game, first, firstIndex), media(game, second, secondIndex), hard, bootDevice);
            result.validate(game, platform, true);
            Snapshot snapshot = new Snapshot(); snapshot.mode = (int)mode;
            snapshot.configuration = configuration; snapshot.media = result; return snapshot;
        }
    }
    LaunchMedia sessionMedia(GameLibrary.Game game, int platform) throws Exception { return restore(game, platform, AUTO); }
    LaunchMedia restore(GameLibrary.Game game, int platform, int slot) throws Exception {
        Snapshot saved = snapshot(game, platform, slot);
        Map<String,String> settings = new HashMap<>();
        for (String line : saved.configuration.split("\n")) {
            int equals = line.indexOf('=');
            if (equals >= 0) settings.put(line.substring(0, equals), line.substring(equals + 1));
        }
        game.mode = saved.mode;
        for (MachineOptions.Option option : MachineOptions.all(platform)) {
            String value = settings.get(option.key);
            if (option.accepts(value)) game.machineOptions.put(option.key, value);
            else game.machineOptions.remove(option.key);
        }
        if (settings.containsKey("startup_font")) game.fontSelection = settings.get("startup_font");
        game.firmwareRevision = settings.getOrDefault("firmware_revision", "");
        return saved.media;
    }
    boolean references(GameLibrary.Game game, int platform, File file) throws Exception {
        String name = file.getCanonicalPath();
        for (int slot = 0; slot <= COUNT; ++slot) {
            if (folder(slot) == null) continue;
            LaunchMedia saved = snapshot(game, platform, slot).media;
            for (LaunchMedia.Ref ref : new LaunchMedia.Ref[]{saved.floppyA, saved.floppyB, saved.hardDisk})
                if (ref != null && game.disk(ref.file).file.getCanonicalPath().equals(name)) return true;
        }
        return false;
    }
    File folder(int slot) throws IOException {
        String value;
        try { value=new String(pointer(slot).readFully(),StandardCharsets.UTF_8); }
        catch (FileNotFoundException error) { return null; }
        String[] fields=value.split("\n");
        if (fields.length!=2 || !fields[0].matches("[0-9a-f-]{36}") || !fields[1].matches("[0-9]{1,16}")) throw new IOException("存档槽记录损坏，可删除此槽后重新保存");
        File folder=new File(root,fields[0]);
        if (!folder.isDirectory()) throw new IOException("存档文件缺失，可删除此槽后重新保存");
        return folder;
    }
    String label(int slot) {
        try {
            if (folder(slot)==null) return "槽 "+slot+" · 空";
            String[] fields=new String(pointer(slot).readFully(),StandardCharsets.UTF_8).split("\n");
            return "槽 "+slot+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(Long.parseLong(fields[1])));
        } catch (Exception error) { return "槽 "+slot+" · 无法读取"; }
    }
    void save(int slot) throws Exception {
        File old;
        try { old=folder(slot); } catch (IOException damaged) { old=null; }
        File fresh=new File(root,UUID.randomUUID().toString());
        if (!fresh.mkdir()) throw new IOException("无法创建存档目录");
        try {
            String result=Native.state(fresh.getAbsolutePath(),false);
            if (!result.isEmpty()) throw new IOException(translate(result));
            AtomicFile storage=pointer(slot); FileOutputStream out=storage.startWrite();
            try {
                out.write((fresh.getName()+"\n"+System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)); storage.finishWrite(out);
            } catch (Exception error) { storage.failWrite(out); throw error; }
        } catch (Exception error) { clean(fresh); throw error; }
        if (old!=null) clean(old);
    }
    void load(int slot) throws Exception {
        File selected=folder(slot); if (selected==null) throw new IOException("此槽没有存档");
        String result=Native.state(selected.getAbsolutePath(),true);
        if (result.equals("State machine settings do not match; restore the saved settings first")) throw new RestartRequired();
        if (!result.isEmpty()) throw new IOException(translate(result));
    }
    void remove(int slot) throws IOException {
        File folder;
        try { folder=folder(slot); } catch (IOException damaged) { folder=null; }
        pointer(slot).delete(); if (folder!=null) clean(folder);
    }
    private void clean(File folder) throws IOException {
        if (!folder.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()) || !folder.getName().matches("[0-9a-f-]{36}")) throw new IOException("存档清理范围无效");
        for (String name:new String[]{"machine.bin","host.bin","midi.bin"}) new File(folder,name).delete();
        folder.delete();
    }
    static String translate(String message) {
        switch (message) {
            case "State is incomplete or incompatible": return "存档不完整或与当前内核不兼容，当前进度保留。";
            case "State machine settings do not match; restore the saved settings first": return "此档的机器硬件配置或 MIDI 开关与本次启动不同。请恢复保存时的设置后重新启动，再读取；音量和 SF2 音色库可以不同。";
            case "State disk is missing": return "存档使用的磁盘已被移除，请恢复该游戏的磁盘文件。";
            case "Cannot protect the current session; load cancelled": return "无法暂存当前进度，已取消读档。请检查剩余空间。";
            case "State recovery failed; restart the game": return "读档失败且未能恢复当前会话，请退出后重新启动；原存档槽保留。";
            case "State load failed; current session restored": return "读档失败，已恢复读档前的进度。";
            case "State save failed; previous slot preserved": return "保存失败，旧存档槽已保留。请检查剩余空间和磁盘写入状态。";
            case "Game session has stopped": return "游戏已经停止，请重新启动后操作。";
            default: return "即时存档操作未完成："+message;
        }
    }
}
