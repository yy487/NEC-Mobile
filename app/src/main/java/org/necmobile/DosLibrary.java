package org.necmobile;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DosLibrary {
    static final Charset OEM = Charset.forName("windows-31j");
    static final class Template {
        final File file;
        final String name, info, autoexec;
        final boolean builtin;
        Template(File file, String name, String info, String autoexec) {
            this(file, name, info, autoexec, false);
        }
        Template(File file, String name, String info, String autoexec, boolean builtin) {
            this.file = file; this.name = name; this.info = info; this.autoexec = autoexec;
            this.builtin = builtin;
        }
    }
    static final class Draft {
        final GameLibrary.Game game;
        final File directory, source;
        final List<File> entries = new ArrayList<>();
        final List<String> executables = new ArrayList<>();
        long bytes;
        Draft(GameLibrary.Game game, File directory) {
            this.game = game; this.directory = directory; source = new File(directory, "dos-source");
        }
    }
    private final Context context;
    private final GameLibrary games;
    private final File templates;
    DosLibrary(Context context) {
        this.context = context; games = new GameLibrary(context, 98);
        templates = new File(games.legacy.root, "dos-templates"); templates.mkdirs();
    }
    List<Template> templates() throws IOException {
        installBuiltin();
        List<Template> result = new ArrayList<>();
        File[] files = templates.listFiles(file -> file.isFile() && file.getName().endsWith(".hdi"));
        if (files == null) return result;
        for (File file : files) {
            boolean builtin = file.getName().equals("builtin-dos-v1.hdi");
            String name = builtin ? "内置 DOS 系统" : file.getName();
            File label = new File(file + ".label");
            if (label.isFile() && label.length() < 1024) try (InputStream in = new FileInputStream(label); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Library.copy(in, out, 1024); name = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            try { result.add(new Template(file, name, Native.hdiInfo(file.getAbsolutePath()), new String(Native.dosAutoexec(file.getAbsolutePath()), OEM).replace("\u001a", ""), builtin)); }
            catch (IOException failure) { result.add(new Template(file, name, "不可用 · " + failure.getMessage(), "")); }
        }
        result.sort(Comparator.comparing((Template template) -> !template.builtin).thenComparing(template -> template.name)); return result;
    }
    private void installBuiltin() throws IOException {
        String[] bundled = context.getAssets().list("dos");
        if (bundled == null || !Arrays.asList(bundled).contains("default.hdi")) return;
        File target = new File(templates, "builtin-dos-v1.hdi");
        if (target.isFile()) return;
        File temporary = File.createTempFile("builtin-dos-", ".pending", templates);
        try {
            // AAPT expands a .gz asset and removes its suffix when packaging it.
            try (InputStream input = context.getAssets().open("dos/default.hdi");
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (Library.copy(input, output, 256L * 1024 * 1024) == 0) throw new IOException("内置 DOS 系统为空");
                output.getFD().sync();
            }
            Native.hdiInfo(temporary.getAbsolutePath());
            android.system.Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
        } catch (android.system.ErrnoException error) { throw new IOException("无法安装内置 DOS 系统", error); }
        finally { temporary.delete(); }
    }
    Template importTemplate(Uri uri) throws Exception {
        String name = games.legacy.name(uri);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".hdi")) throw new IOException("请选择 PC-98 DOS 的 HDI 模板");
        File output = new File(templates, UUID.randomUUID() + ".hdi"), temporary = new File(output + ".pending");
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(temporary)) {
                if (in == null || Library.copy(in, out, 4L * 1024 * 1024 * 1024) == 0) throw new IOException("DOS 模板为空");
                out.getFD().sync();
            }
            String info = Native.hdiInfo(temporary.getAbsolutePath());
            String startup = new String(Native.dosAutoexec(temporary.getAbsolutePath()), OEM).replace("\u001a", "");
            android.system.Os.rename(temporary.getAbsolutePath(), output.getAbsolutePath());
            try (FileOutputStream out = new FileOutputStream(new File(output + ".label"))) { out.write(name.getBytes(StandardCharsets.UTF_8)); }
            return new Template(output, name, info, startup);
        } catch (Exception error) { temporary.delete(); output.delete(); throw error; }
    }
    private static void component(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 ||
                name.chars().anyMatch(value -> value <= 32 || "\"*+,/:;<=>?[\\]|&%()!^".indexOf(value) >= 0))
            throw new IOException("DOS 文件名不兼容：" + name);
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot), extension = dot < 0 ? "" : name.substring(dot + 1);
        if (stem.contains(".") || name.endsWith(".") || stem.isEmpty() || stem.getBytes(OEM).length > 8 || extension.getBytes(OEM).length > 3 ||
                !new String(name.getBytes(OEM), OEM).equals(name) || stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
            throw new IOException("需使用代码页 932 的 DOS 8.3 文件名：" + name);
    }
    private File destination(Draft draft, String path) throws IOException {
        String normalized = path.replace('\\', '/');
        for (String part : normalized.split("/", -1)) component(part);
        if (normalized.getBytes(OEM).length > 220) throw new IOException("DOS 目录层级过长：" + path);
        File file = new File(draft.source, normalized);
        if (!file.getCanonicalPath().startsWith(draft.source.getCanonicalPath() + File.separator)) throw new IOException("游戏目录路径无效");
        if (file.exists()) throw new IOException("DOS 文件重名：" + path);
        return file;
    }
    private void addFile(Draft draft, String path, InputStream input) throws IOException {
        File file = destination(draft, path); file.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(file)) {
            draft.bytes += Library.copy(input, output, 4L * 1024 * 1024 * 1024);
            if (draft.bytes > 8L * 1024 * 1024 * 1024) throw new IOException("游戏目录超过导入上限");
        }
    }
    Draft stage(Uri uri, boolean zipped) throws Exception {
        for (GameLibrary.Game existing : games.list())
            if (existing.kind.equals("dos-directory") && existing.source.equals(uri.toString()))
                throw new IOException("这款 DOS 游戏已在游戏库中，请从已有卡片启动");
        GameLibrary.Game game = new GameLibrary.Game(); game.kind = "dos-directory"; game.source = uri.toString();
        Draft draft = new Draft(game, games.directory(game)); draft.source.mkdirs();
        try {
            if (zipped) {
                game.name = games.legacy.name(uri).replaceFirst("(?i)\\.zip$", "");
                File archive = new File(draft.directory, "import.zip");
                try (InputStream input = context.getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(archive)) {
                    if (input == null) throw new IOException("无法读取游戏 ZIP");
                    Library.copy(input, output, 4L * 1024 * 1024 * 1024);
                }
                try (ZipFile zip = new ZipFile(archive, OEM)) {
                    List<? extends ZipEntry> entries = Collections.list(zip.entries());
                    if (entries.size() > 2048) throw new IOException("ZIP 文件项目过多");
                    List<String> files = new ArrayList<>();
                    for (ZipEntry entry : entries) if (!entry.isDirectory()) files.add(entry.getName().replace('\\', '/'));
                    String wrapper = files.isEmpty() ? "" : files.get(0).split("/")[0] + "/";
                    for (String path : files) if (!path.startsWith(wrapper)) { wrapper = ""; break; }
                    for (ZipEntry entry : entries) {
                        if (entry.isDirectory()) continue;
                        String path = entry.getName().replace('\\', '/');
                        if (path.startsWith("/") || path.contains("../") || path.contains(":")) throw new IOException("ZIP 路径无效：" + path);
                        if (!wrapper.isEmpty()) path = path.substring(wrapper.length());
                        try (InputStream input = zip.getInputStream(entry)) { addFile(draft, path, input); }
                    }
                }
                archive.delete();
            } else {
                String rootId = DocumentsContract.getTreeDocumentId(uri);
                game.name = games.legacy.name(DocumentsContract.buildDocumentUriUsingTree(uri, rootId));
                walk(draft, uri, rootId, "", 0, new int[]{0});
            }
            collect(draft, draft.source);
            if (draft.executables.isEmpty()) throw new IOException("没有找到 BAT、EXE 或 COM 启动文件");
            draft.executables.sort(Comparator.comparingInt((String path) -> path.equalsIgnoreCase("LIENA.BAT") ? 0 : path.toUpperCase(Locale.ROOT).endsWith(".BAT") ? 1 : 2).thenComparing(String::compareToIgnoreCase));
            return draft;
        } catch (Exception error) { discard(draft); throw error; }
    }
    private void walk(Draft draft, Uri tree, String document, String parent, int depth, int[] count) throws Exception {
        if (depth > 12) throw new IOException("游戏目录层级过深");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, document);
        try (Cursor cursor = context.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取游戏目录");
            while (cursor.moveToNext()) {
                if (++count[0] > 2048) throw new IOException("目录文件项目过多");
                String id = cursor.getString(0), name = cursor.getString(1), path = parent + name;
                component(name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    File directory = destination(draft, path); directory.mkdirs();
                    walk(draft, tree, id, path + "/", depth + 1, count);
                } else try (InputStream input = context.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))) {
                    if (input == null) throw new IOException("无法读取：" + path);
                    addFile(draft, path, input);
                }
            }
        }
    }
    private void collect(Draft draft, File directory) throws IOException {
        File[] files = directory.listFiles(); if (files == null) throw new IOException("无法读取已导入目录");
        Arrays.sort(files, Comparator.comparing(File::getName));
        Set<String> names = new HashSet<>();
        for (File file : files) {
            if (!names.add(file.getName().toUpperCase(Locale.ROOT))) throw new IOException("DOS 文件重名：" + file.getName());
            draft.entries.add(file);
            if (file.isDirectory()) collect(draft, file);
            else if (file.getName().matches("(?i).+\\.(bat|exe|com)$")) draft.executables.add(draft.source.toURI().relativize(file.toURI()).getPath());
        }
    }
    GameLibrary.Game commit(Draft draft, Template template, String entry, String workingDirectory, String drive, String initialization) throws Exception {
        if (games.find(draft.game.id) != null) throw new IOException("游戏已建立，请从已有卡片启动");
        if (!draft.executables.contains(entry)) throw new IOException("启动文件不在本次游戏目录内");
        if (!drive.matches("[A-F]:")) throw new IOException("DOS 启动盘符无效");
        String directory = workingDirectory.replace('\\', '/');
        if (!directory.isEmpty()) {
            for (String part : directory.split("/")) component(part);
            File selected = new File(draft.source, directory);
            if (!selected.getCanonicalPath().startsWith(draft.source.getCanonicalPath() + File.separator) || !selected.isDirectory()) throw new IOException("工作目录不存在");
        }
        String guestEntry = "\\NECGAME\\" + entry.replace('/', '\\');
        String startup = "@ECHO OFF\r\n" + drive + "\r\nCD \\NECGAME" + (directory.isEmpty() ? "" : "\\" + directory.replace('/', '\\')) + "\r\n" +
                (entry.toUpperCase(Locale.ROOT).endsWith(".BAT") ? "CALL " : "") + guestEntry + "\r\n";
        int end = initialization.indexOf(26);
        String original = end < 0 ? initialization : initialization.substring(0, end);
        original = original.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
        if (!new String(original.getBytes(OEM), OEM).equals(original)) throw new IOException("初始化命令包含代码页 932 无法保存的字符");
        String autoexec = original + "\r\nCALL " + drive + "\\NECMOB\\START.BAT\r\n";
        File media = new File(draft.directory, "media"); media.mkdirs();
        File temporary = new File(media, "dos-import.pending.hdi"), target = new File(media, "dos-game.hdi");
        List<String> hosts = new ArrayList<>(); List<byte[]> names = new ArrayList<>();
        for (File file : draft.entries) {
            hosts.add(file.getAbsolutePath());
            names.add(draft.source.toURI().relativize(file.toURI()).getPath().replaceAll("/$", "").getBytes(OEM));
        }
        try {
            Native.buildDosDisk(template.file.getAbsolutePath(), temporary.getAbsolutePath(), hosts.toArray(new String[0]), names.toArray(new byte[0][]), startup.getBytes(OEM), autoexec.getBytes(OEM));
            android.system.Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            for (File file : draft.entries) if (file.isFile() && FontResources.name(file.getName())) {
                File font = new File(draft.directory, "firmware-sources/game/" + file.getName().toLowerCase(Locale.ROOT));
                font.getParentFile().mkdirs();
                if (!font.exists()) try (InputStream input = new FileInputStream(file); FileOutputStream output = new FileOutputStream(font)) { Library.copy(input, output, 16 * 1024 * 1024); }
            }
            draft.game.media.clear();
            draft.game.media.add(new Library.Disk("DOS 游戏硬盘", target));
            draft.game.first = target.getName(); draft.game.second = "";
            JSONObject launch = new JSONObject(); launch.put("template", template.name); launch.put("entry", entry); launch.put("workingDirectory", directory); launch.put("drive", drive);
            AtomicFile config = new AtomicFile(new File(draft.directory, "dos-launch.json")); FileOutputStream out = config.startWrite();
            try { out.write(launch.toString(2).getBytes(StandardCharsets.UTF_8)); config.finishWrite(out); }
            catch (Exception error) { config.failWrite(out); throw error; }
            games.save(draft.game);
            return draft.game;
        } catch (Exception error) { temporary.delete(); throw error; }
    }
    void discard(Draft draft) throws IOException {
        File allowed = new File(games.legacy.root, "games").getCanonicalFile();
        if (!draft.directory.getCanonicalFile().getParentFile().equals(allowed)) throw new IOException("临时目录范围无效");
        try { if (games.find(draft.game.id) != null) return; } catch (Exception failure) { throw new IOException(failure); }
        remove(draft.directory);
    }
    private static void remove(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) remove(child);
        directory.delete();
    }
}
