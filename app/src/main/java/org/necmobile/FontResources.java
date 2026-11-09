package org.necmobile;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class FontResources {
    static final class Candidate {
        final String id, name, source, error;
        Candidate(String id, String name, String source, String error) {
            this.id = id; this.name = name; this.source = source; this.error = error;
        }
    }
    private final Context context;
    private final File gameDirectory, runtime, originals, global;
    FontResources(Context context, File gameDirectory) {
        this.context = context; this.gameDirectory = gameDirectory;
        runtime = new File(gameDirectory, "np2kai");
        originals = new File(gameDirectory, "firmware-sources");
        global = new Library(context, 98).roms;
    }
    static boolean name(String name) { return name.equalsIgnoreCase("font.bmp") || name.equalsIgnoreCase("freecg98.bmp") || name.equalsIgnoreCase("font.rom"); }
    void retainLegacy() throws IOException {
        for (String name : new String[]{"font.bmp", "freecg98.bmp", "font.rom"}) {
            File file = new File(runtime, name);
            if (!file.isFile() || new File(runtime, ".bundled-firmware/" + name).isFile() || new File(runtime, ".prepared-fonts/" + name).isFile()) continue;
            File saved = new File(originals, "legacy/" + name);
            if (!saved.isFile()) {
                saved.getParentFile().mkdirs();
                try (InputStream input = new FileInputStream(file)) { write(input, saved); }
            }
        }
    }
    List<Candidate> list() throws IOException {
        retainLegacy();
        List<Candidate> result = new ArrayList<>();
        for (String source : new String[]{"manual", "game", "legacy"}) {
            File folder = new File(originals, source);
            File[] files = folder.listFiles(file -> file.isFile() && file.getName().matches("(?i).+\\.(bmp|rom)"));
            if (files == null) continue;
            Arrays.sort(files, Comparator.comparingInt((File file) -> file.getName().equalsIgnoreCase("freecg98.bmp") ? 0 : file.getName().equalsIgnoreCase("font.bmp") ? 1 : 2).thenComparing(File::getName));
            for (File file : files) {
                String display = file.getName();
                File label = new File(file + ".label");
                if (label.isFile() && label.length() <= 1024) display = new String(read(label), StandardCharsets.UTF_8);
                result.add(candidate(source + "/" + file.getName(), display,
                        source.equals("manual") ? "游戏手选" : source.equals("game") ? "游戏包自带" : "已有游戏字库"));
            }
        }
        for (String name : new String[]{"freecg98.bmp", "font.bmp", "font.rom"})
            if (new File(global, name).isFile()) result.add(candidate("global/" + name, name, "全局导入"));
        result.add(candidate("bundled/font.rom", "font.rom", "内置字库"));
        return result;
    }
    private Candidate candidate(String id, String name, String source) {
        String error = null;
        try { normalized(id); } catch (IOException failure) { error = failure.getMessage(); }
        return new Candidate(id, name, source, error);
    }
    private static byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Library.copy(input, out, 16 * 1024 * 1024); return out.toByteArray();
        }
    }
    private byte[] bytes(String id) throws IOException {
        if (id.equals("bundled/font.rom")) {
            try (InputStream input = context.getAssets().open("firmware/pc98/font.rom"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Library.copy(input, out, 16 * 1024 * 1024); return out.toByteArray();
            }
        }
        File file;
        if (id.startsWith("global/")) file = new File(global, id.substring(7));
        else file = new File(originals, id);
        File allowed = id.startsWith("global/") ? global : originals;
        if (!file.getCanonicalPath().startsWith(allowed.getCanonicalPath() + File.separator)) throw new IOException("字库路径无效");
        return read(file);
    }
    private byte[] normalized(String id) throws IOException {
        byte[] data = bytes(id);
        if (id.toLowerCase(Locale.ROOT).endsWith(".rom")) {
            if (data.length < 0x46800) throw new IOException("FONT.ROM 数据不足");
            return data;
        }
        if (data.length < 54) throw new IOException("BMP 字库头不完整");
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long pixels = Integer.toUnsignedLong(header.getInt(10));
        long dib = Integer.toUnsignedLong(header.getInt(14));
        if (data[0] != 'B' || data[1] != 'M' || dib < 40 || pixels < 14 + dib ||
                header.getInt(18) != 2048 || header.getInt(22) != 2048 || header.getShort(26) != 1 ||
                header.getShort(28) != 1 || header.getInt(30) != 0 || pixels + 524288 > data.length)
            throw new IOException("需使用完整的 2048 × 2048、1 位未压缩 BMP 字库");
        header.putInt(34, 524288); header.putInt(2, data.length);
        return data;
    }
    private static void write(InputStream input, File target) throws IOException {
        AtomicFile storage = new AtomicFile(target); FileOutputStream out = storage.startWrite();
        try { Library.copy(input, out, 16 * 1024 * 1024); storage.finishWrite(out); }
        catch (IOException | RuntimeException error) { storage.failWrite(out); throw error; }
    }
    String importManual(Uri uri) throws IOException {
        String label = new Library(context, 98).name(uri);
        String suffix = label.toLowerCase(Locale.ROOT).endsWith(".rom") ? ".rom" : ".bmp";
        String id = "manual/" + UUID.randomUUID() + suffix;
        File file = new File(originals, id); file.getParentFile().mkdirs();
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取字库");
                write(input, file);
            }
            normalized(id);
            try (FileOutputStream out = new FileOutputStream(new File(file + ".label"))) { out.write(label.getBytes(StandardCharsets.UTF_8)); }
            return id;
        } catch (IOException error) { file.delete(); throw error; }
    }
    String prepare(String selected) throws IOException {
        List<Candidate> candidates = list();
        Candidate chosen = null;
        String warning = "";
        if (selected != null && !selected.isEmpty()) {
            for (Candidate candidate : candidates) if (candidate.id.equals(selected)) {
                if (candidate.error == null) chosen = candidate;
                else warning = "所选字库不可用，已使用可用字库：" + candidate.error;
            }
            if (chosen == null && warning.isEmpty()) warning = "所选字库文件已移除，已使用可用字库";
        }
        if (chosen == null) for (Candidate candidate : candidates)
            if (!candidate.id.startsWith("manual/") && candidate.error == null) { chosen = candidate; break; }
        if (chosen == null) throw new IOException("没有可用的 PC-98 字库");
        String filename = chosen.id.endsWith(".rom") ? "font.rom" : "font.bmp";
        runtime.mkdirs();
        byte[] data = normalized(chosen.id);
        write(new ByteArrayInputStream(data), new File(runtime, filename));
        File markers = new File(runtime, ".prepared-fonts"); markers.mkdirs();
        new File(markers, filename).createNewFile();
        String other = filename.equals("font.rom") ? "font.bmp" : "font.rom";
        File conflicting = new File(runtime, other);
        if (conflicting.isFile() && !conflicting.delete()) throw new IOException("无法切换运行字库，请重试");
        new File(markers, other).delete();
        return warning;
    }
}
