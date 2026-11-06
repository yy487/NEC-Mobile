package org.necmobile;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class Library {
    final Context context;
    final int platform;
    final File root, roms, disks;
    static final class Disk {
        final String name;
        final File file;
        final List<String> titles;
        Disk(String name, File file) { this(name, file, Collections.singletonList("")); }
        Disk(String name, File file, List<String> titles) {
            this.name = name; this.file = file; this.titles = Collections.unmodifiableList(new ArrayList<>(titles));
        }
        String label(int index) {
            if (titles.size() == 1) return name;
            return name + " · " + (index + 1) + "/" + titles.size() +
                    (index >= 0 && index < titles.size() && !titles.get(index).isEmpty() ? " " + titles.get(index) : " 盘 " + (index + 1));
        }
        public String toString() { return name; }
    }
    Library(Context context, int platform) {
        this.context = context;
        this.platform = platform;
        root = new File(context.getFilesDir(), "pc" + platform);
        roms = platform == 98 ? new File(root, "np2kai") : root;
        disks = new File(root, "disks");
        roms.mkdirs(); disks.mkdirs();
    }
    String name(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        return "imported";
    }
    private File index() { return new File(root, "library.json"); }
    List<Disk> list() throws Exception {
        List<Disk> result = new ArrayList<>();
        AtomicFile storage = new AtomicFile(index());
        if (!index().exists() && !new File(index() + ".bak").exists()) return result;
        String content = new String(storage.readFully(), StandardCharsets.UTF_8);
        JSONArray entries = new JSONArray(content);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.getJSONObject(i);
            String filename = item.getString("file");
            if (!filename.matches("[a-zA-Z0-9_.-]+")) throw new IOException("磁盘库文件名无效");
            File file = new File(disks, filename);
            if (file.isFile()) result.add(new Disk(item.getString("name"), file));
        }
        return result;
    }
    private void save(List<Disk> entries) throws Exception {
        JSONArray array = new JSONArray();
        for (Disk disk : entries) {
            JSONObject item = new JSONObject();
            item.put("name", disk.name); item.put("file", disk.file.getName());
            array.put(item);
        }
        AtomicFile storage = new AtomicFile(index());
        FileOutputStream output = storage.startWrite();
        try {
            output.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
            storage.finishWrite(output);
        } catch (Exception error) { storage.failWrite(output); throw error; }
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        int size;
        while ((size = input.read(buffer)) >= 0) {
            total += size;
            if (total > limit) throw new IOException("文件超过此版本的导入上限");
            output.write(buffer, 0, size);
        }
        return total;
    }
    Disk importDisk(Uri uri) throws Exception {
        String label = name(uri);
        String ext = extension(label);
        List<String> allowed = platform == 88 ? Arrays.asList("d88", "88d") : Arrays.asList("d88", "88d", "fdi", "hdi", "nhd", "hdd", "thd", "xdf", "hdm");
        if (!allowed.contains(ext)) throw new IOException("当前平台不接收此文件。游戏 ZIP 请先解压，再导入磁盘镜像。");
        File file = new File(disks, UUID.randomUUID() + "." + ext);
        AtomicFile storage = new AtomicFile(file);
        FileOutputStream output = storage.startWrite();
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null || copy(input, output, 4L * 1024 * 1024 * 1024) == 0) throw new IOException("磁盘文件为空");
            storage.finishWrite(output);
        } catch (Exception error) { storage.failWrite(output); throw error; }
        List<Disk> entries = list();
        Disk disk = new Disk(label, file);
        entries.add(disk);
        try { save(entries); }
        catch (Exception error) { file.delete(); throw error; }
        return disk;
    }
    private boolean romName(String name) {
        return name.matches("[a-z0-9][a-z0-9_.-]{0,95}") && Arrays.asList("rom", "fnt", "bmp", "wav", "bin").contains(extension(name));
    }
    private void rom(InputStream input, String name) throws Exception {
        AtomicFile storage = new AtomicFile(new File(roms, name));
        FileOutputStream output = storage.startWrite();
        try {
            if (copy(input, output, 16 * 1024 * 1024) == 0) throw new IOException("ROM 文件为空");
            storage.finishWrite(output);
        } catch (Exception error) { storage.failWrite(output); throw error; }
    }
    int importRoms(Uri uri) throws Exception {
        String label = name(uri).toLowerCase(Locale.ROOT);
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取文件");
            if (!label.endsWith(".zip")) {
                if (!romName(label)) throw new IOException("请选择 ROM、FNT、BMP、WAV，或这些文件的 ZIP 包");
                rom(input, label); return 1;
            }
            int count = 0, inspected = 0;
            Set<String> seen = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++inspected > 512) throw new IOException("ZIP 文件项目过多");
                    String path = entry.getName().replace('\\', '/');
                    if (entry.isDirectory()) continue;
                    if (path.startsWith("/") || path.contains("../") || path.contains(":")) throw new IOException("ZIP 内含无效路径");
                    String basename = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                    if (!romName(basename)) continue;
                    if (!seen.add(basename)) throw new IOException("ZIP 内有重名 ROM：" + basename);
                    if (++count > 128) throw new IOException("ZIP 内 ROM 文件过多");
                    rom(zip, basename);
                }
            }
            if (count > 0) return count;
            throw new IOException("ZIP 中没有可导入的 ROM 或字库");
        }
    }
    int romCount() {
        File[] files = roms.listFiles(file -> file.isFile() && romName(file.getName()));
        return files == null ? 0 : files.length;
    }
    void export(Disk disk, Uri uri) throws Exception {
        try (InputStream input = new FileInputStream(disk.file);
             OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("无法写入导出位置");
            copy(input, output, 4L * 1024 * 1024 * 1024);
        }
    }
}
