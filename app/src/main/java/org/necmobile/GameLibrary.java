package org.necmobile;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class GameLibrary {
    private static final Object storageMutex = new Object();
    private interface StoredOperation<T> { T run() throws Exception; }
    interface GameChange { void apply(Game game) throws Exception; }
    final Library legacy;
    private final Context context;
    private final int platform;
    private static final long DISK_LIMIT = 4L * 1024 * 1024 * 1024;
    static final class Game {
        String id = UUID.randomUUID().toString(), name, source = "", first = "", second = "";
        long played;
        String fontSelection = "", firmwareRevision = "", lastMachineInfo = "", kind = "disk";
        boolean resumeEnabled;
        int mode, firstIndex, secondIndex;
        final Map<String,String> machineOptions = new LinkedHashMap<>();
        final List<Library.Disk> media = new ArrayList<>();
        Library.Disk disk(String file) {
            for (Library.Disk disk : media) if (disk.file.getName().equals(file)) return disk;
            return null;
        }
    }
    static final class Media {
        final Library.Disk disk;
        final int index;
        Media(Library.Disk disk, int index) { this.disk = disk; this.index = index; }
        String label() { return disk.label(index); }
        boolean same(Media other) { return other != null && index == other.index && disk.file.equals(other.disk.file); }
    }
    static List<Media> media(Game game) {
        List<Media> result = new ArrayList<>();
        for (Library.Disk disk : game.media) for (int index = 0; index < disk.titles.size(); index++) result.add(new Media(disk, index));
        return result;
    }
    private Library.Disk readDisk(String name, File file) throws IOException {
        return new Library.Disk(name, file, platform == 88 ? D88.titles(file) : Collections.singletonList(""));
    }
    GameLibrary(Context context, int platform) {
        this.context = context; this.platform = platform; legacy = new Library(context, platform);
    }
    private AtomicFile storage() { return new AtomicFile(new File(legacy.root, "games.json")); }
    private static String stem(String name) {
        int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name;
    }
    static boolean hardDisk(Library.Disk disk) { return disk.file.getName().matches("(?i).*\\.(hdi|nhd|hdd|thd)$"); }
    private boolean image(String name) {
        return name.matches(platform == 88 ? "(?i).*\\.(d88|88d)$" : "(?i).*\\.(d88|88d|fdi|hdi|nhd|hdd|thd|xdf|hdm)$");
    }
    private static boolean resource(String name) { return name.matches("[a-z0-9][a-z0-9_.-]{0,95}\\.(rom|fnt|bmp|wav|bin)"); }
    private static boolean coverName(String name) { return name.matches("(?i)(cover|folder|front|title)\\.(png|jpe?g|webp)"); }
    File cover(Game game) {
        File captured = new File(directory(game), "cover.png");
        if (captured.isFile()) return captured;
        File[] images = directory(game).listFiles(file -> coverName(file.getName()));
        if (images == null || images.length == 0) return null;
        Arrays.sort(images, Comparator.comparing(File::getName)); return images[0];
    }
    File directory(Game game) { return new File(legacy.root, "games/" + game.id); }
    private File resources(Game game) { return platform == 98 ? new File(directory(game), "np2kai") : directory(game); }
    private File resolve(String relative) throws IOException {
        File path = new File(relative);
        File file = (path.isAbsolute() ? path : new File(legacy.root, relative)).getCanonicalFile();
        if (!file.getPath().startsWith(legacy.root.getCanonicalPath() + File.separator)) throw new IOException("游戏文件路径无效");
        return file;
    }
    private String relative(File file) throws IOException {
        return legacy.root.getCanonicalFile().toURI().relativize(file.getCanonicalFile().toURI()).getPath();
    }
    private <T> T stored(StoredOperation<T> operation) throws Exception {
        synchronized (storageMutex) {
            try (RandomAccessFile file = new RandomAccessFile(new File(legacy.root, "games.lock"), "rw");
                 FileLock lock = file.getChannel().lock()) { return operation.run(); }
        }
    }
    List<Game> list() throws Exception { return stored(this::readGames); }
    private List<Game> readGames() throws Exception {
        File file = new File(legacy.root, "games.json");
        if (!file.exists() && !new File(file + ".bak").exists()) return migrate();
        JSONArray array = new JSONArray(new String(storage().readFully(), StandardCharsets.UTF_8));
        List<Game> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.getJSONObject(i);
            Game game = new Game(); game.id = row.getString("id");
            if (!game.id.matches("[0-9a-f-]{36}")) throw new IOException("游戏记录无效");
            game.name = row.getString("name"); game.source = row.optString("source");
            game.first = row.optString("first"); game.second = row.optString("second");
            game.firstIndex = row.optInt("firstIndex"); game.secondIndex = row.optInt("secondIndex");
            game.played = row.optLong("played"); game.mode = MachineOptions.bootMode(platform, row.optInt("mode"));
            game.fontSelection = row.optString("fontSelection");
            game.firmwareRevision = row.optString("firmwareRevision");
            game.lastMachineInfo = row.optString("lastMachineInfo");
            game.kind = row.optString("kind", "disk"); game.resumeEnabled = row.optBoolean("resumeEnabled");
            JSONObject settings=row.optJSONObject("machineOptions");
            if (settings!=null) for (MachineOptions.Option option : MachineOptions.all(platform)) {
                String value=settings.optString(option.key,null);
                if (option.accepts(value)) game.machineOptions.put(option.key,value);
            }
            JSONArray media = row.getJSONArray("media");
            for (int n = 0; n < media.length(); n++) {
                JSONObject disk = media.getJSONObject(n);
                File image = resolve(disk.getString("path"));
                JSONArray stored = disk.optJSONArray("titles");
                List<String> titles = new ArrayList<>();
                if (platform == 88 && stored != null && stored.length() > 0 && stored.length() <= 64)
                    for (int j = 0; j < stored.length(); j++) titles.add(stored.getString(j));
                else titles.addAll(platform == 88 && image.isFile() ? D88.titles(image) : Collections.singletonList(""));
                game.media.add(new Library.Disk(disk.getString("name"), image, titles));
            }
            // Older records could not express a second disk inside the same file.
            if (platform == 88 && !row.has("firstIndex") && game.second.isEmpty() && game.disk(game.first) != null && game.disk(game.first).titles.size() > 1) {
                game.second = game.first; game.secondIndex = 1;
            }
            result.add(game);
        }
        return result;
    }
    private void write(List<Game> games) throws Exception {
        JSONArray array = new JSONArray();
        for (Game game : games) {
            JSONObject row = new JSONObject();
            row.put("id", game.id); row.put("name", game.name); row.put("source", game.source);
            row.put("first", game.first); row.put("second", game.second);
            row.put("firstIndex", game.firstIndex); row.put("secondIndex", game.secondIndex);
            row.put("played", game.played); row.put("mode", game.mode);
            row.put("machineOptions",new JSONObject(game.machineOptions));
            row.put("fontSelection", game.fontSelection); row.put("firmwareRevision", game.firmwareRevision);
            row.put("lastMachineInfo", game.lastMachineInfo); row.put("kind", game.kind); row.put("resumeEnabled", game.resumeEnabled);
            JSONArray media = new JSONArray();
            for (Library.Disk disk : game.media) {
                JSONObject entry = new JSONObject(); entry.put("name", disk.name); entry.put("path", relative(disk.file));
                entry.put("titles", new JSONArray(disk.titles)); media.put(entry);
            }
            row.put("media", media); array.put(row);
        }
        AtomicFile target = storage(); FileOutputStream out = target.startWrite();
        try { out.write(array.toString(2).getBytes(StandardCharsets.UTF_8)); target.finishWrite(out); }
        catch (Exception error) { target.failWrite(out); throw error; }
    }
    void save(Game game) throws Exception {
        stored(() -> {
            List<Game> games = readGames();
            games.removeIf(entry -> entry.id.equals(game.id)); games.add(game); write(games); return null;
        });
    }
    void updateGame(String id, GameChange change) throws Exception {
        stored(() -> {
            List<Game> games = readGames();
            Game current = null;
            for (Game game : games) if (game.id.equals(id)) { current = game; break; }
            if (current == null) throw new IOException("找不到本游戏记录");
            change.apply(current); write(games); return null;
        });
    }
    Game find(String id) throws Exception {
        for (Game game : list()) if (game.id.equals(id)) return game;
        return null;
    }
    private Game source(String uri) throws Exception {
        for (Game game : list()) if (game.source.equals(uri)) return game;
        return null;
    }
    private void arrange(Game game) {
        game.media.sort(Comparator.comparing(disk -> disk.name.toLowerCase(Locale.ROOT)));
        if (game.media.isEmpty()) return;
        Library.Disk boot = game.media.get(0);
        if (platform == 98 && !hardDisk(boot)) {
            for (Library.Disk disk : game.media) {
                if (!hardDisk(disk) && stem(disk.name).matches("(?i)(?:.*[ ._-])?(?:s|system|boot)")) {
                    boot = disk;
                    break;
                }
            }
        }
        game.first = boot.file.getName();
        game.firstIndex = game.secondIndex = 0; game.second = "";
        if (boot.titles.size() > 1) {
            game.second = game.first; game.secondIndex = 1; return;
        }
        if (!hardDisk(boot)) for (Library.Disk disk : game.media) {
            if (!hardDisk(disk) && !disk.name.equalsIgnoreCase(boot.name)) {
                game.second = disk.file.getName(); break;
            }
        }
    }
    private List<Game> migrate() throws Exception {
        Map<String,List<Library.Disk>> candidates = new LinkedHashMap<>();
        for (Library.Disk disk : legacy.list()) {
            disk = readDisk(disk.name, disk.file);
            String key = stem(disk.name).toLowerCase(Locale.ROOT).replaceFirst("[ _.-]*(?:disk|disc|盘)?[ _.-]*[ab12]$", "");
            candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(disk);
        }
        List<Game> games = new ArrayList<>();
        for (List<Library.Disk> group : candidates.values()) {
            Map<String,List<Library.Disk>> names = new LinkedHashMap<>();
            for (Library.Disk disk : group) names.computeIfAbsent(disk.name.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(disk);
            boolean paired = names.keySet().stream().anyMatch(name -> stem(name).matches(".*[a1]$")) &&
                    names.keySet().stream().anyMatch(name -> stem(name).matches(".*[b2]$"));
            if (paired) {
                Game game = new Game(); game.media.addAll(group); arrange(game);
                game.name = stem(game.disk(game.first).name).replaceFirst("(?i)[ _.-]*(?:disk|disc|盘)?[ _.-]*[a1]$", "");
                if (game.name.isEmpty()) game.name = stem(game.disk(game.first).name);
                games.add(game);
            } else for (List<Library.Disk> copies : names.values()) {
                Game game = new Game(); game.name = stem(copies.get(0).name); game.media.addAll(copies); arrange(game); games.add(game);
            }
        }
        write(games); return games;
    }
    private static String basename(String path) {
        String normalized = path.replace('\\', '/'); return normalized.substring(normalized.lastIndexOf('/') + 1);
    }
    private long addFile(Game game, String path, InputStream input, Set<String> seen) throws Exception {
        String name = basename(path);
        String normalized = name.toLowerCase(Locale.ROOT);
        File target;
        if (image(name)) {
            File folder = new File(directory(game), "media"); folder.mkdirs();
            target = new File(folder, UUID.randomUUID() + normalized.substring(normalized.lastIndexOf('.')));
        } else if (coverName(normalized)) {
            if (cover(game) != null) return 0;
            directory(game).mkdirs(); target = new File(directory(game), normalized);
        } else if (resource(normalized)) {
            if (!seen.add(normalized)) throw new IOException("游戏包内有重名资源：" + name);
            File folder = platform == 98 && FontResources.name(normalized) ? new File(directory(game), "firmware-sources/game") : resources(game);
            folder.mkdirs(); target = new File(folder, normalized);
        } else return 0;
        long count;
        try (FileOutputStream out = new FileOutputStream(target)) {
            count = Library.copy(input, out, image(name) ? DISK_LIMIT : 16 * 1024 * 1024);
            if (count == 0) throw new IOException("游戏文件为空：" + name);
            out.getFD().sync();
        }
        if (image(name)) game.media.add(readDisk(name, target));
        return count;
    }
    private Game finish(Game game) throws Exception {
        if (game.media.isEmpty()) throw new IOException("没有找到此平台支持的磁盘镜像");
        arrange(game); save(game); return game;
    }
    Game importZip(Uri uri) throws Exception {
        Game existing = source(uri.toString()); if (existing != null) return existing;
        Game game = new Game(); game.source = uri.toString(); game.name = stem(legacy.name(uri));
        Set<String> seen = new HashSet<>(); int files = 0; long bytes = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("无法打开游戏包");
            try (ZipInputStream zip = new ZipInputStream(raw, java.nio.charset.Charset.forName("windows-31j"))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++files > 512) throw new IOException("游戏包文件过多");
                    if (entry.isDirectory()) continue;
                    String path = entry.getName().replace('\\', '/');
                    if (path.startsWith("/") || path.contains("../") || path.contains(":")) throw new IOException("游戏包路径无效");
                    bytes += addFile(game, path, zip, seen);
                    if (bytes > 8L * 1024 * 1024 * 1024) throw new IOException("游戏包过大");
                }
            }
            return finish(game);
        } catch (Exception error) { deleteTree(directory(game)); throw error; }
    }
    Game importFolder(Uri uri) throws Exception {
        Game existing = source(uri.toString()); if (existing != null) return existing;
        Game game = new Game(); game.source = uri.toString();
        String document = DocumentsContract.getTreeDocumentId(uri);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(uri, document);
        game.name = legacy.name(root);
        try {
            walk(game, uri, document, new HashSet<>(), new long[2], 0);
            return finish(game);
        } catch (Exception error) { deleteTree(directory(game)); throw error; }
    }
    private void walk(Game game, Uri tree, String document, Set<String> seen, long[] budget, int depth) throws Exception {
        if (depth > 6) throw new IOException("游戏文件夹层级过深");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, document);
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取游戏文件夹");
            while (cursor.moveToNext()) {
                if (++budget[0] > 512) throw new IOException("请选择单个游戏的文件夹");
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) { walk(game, tree, id, seen, budget, depth + 1); continue; }
                if (!image(name) && !resource(name.toLowerCase(Locale.ROOT)) && !coverName(name)) continue;
                try (InputStream input = context.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))) {
                    if (input == null) throw new IOException("无法读取：" + name);
                    budget[1] += addFile(game, name, input, seen);
                    if (budget[1] > 8L * 1024 * 1024 * 1024) throw new IOException("游戏文件夹过大");
                }
            }
        }
    }
    private static void copyFirmware(InputStream input, File target) throws IOException {
        AtomicFile storage = new AtomicFile(target);
        FileOutputStream output = storage.startWrite();
        try {
            if (Library.copy(input, output, 16 * 1024 * 1024) == 0)
                throw new IOException("固件文件为空：" + target.getName());
            storage.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            storage.failWrite(output);
            throw error;
        }
    }
    private void prepareFirmware(File dest) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("无法准备游戏固件目录");
        // Mark installed defaults so a later user import can replace them safely.
        File defaults = new File(dest, ".bundled-firmware");
        File[] base = legacy.roms.listFiles(file -> file.isFile() && resource(file.getName().toLowerCase(Locale.ROOT)));
        if (base == null) base = new File[0];
        for (File file : base) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (platform == 98 && FontResources.name(name)) continue;
            File target = new File(dest, name), marker = new File(defaults, name);
            boolean soundBios = platform == 98 && Library.soundBiosName(name);
            if (file.length() == 0 || (soundBios && file.length() != Library.SOUND_BIOS_BYTES)) continue;
            boolean repairSoundBios = soundBios && target.length() != Library.SOUND_BIOS_BYTES;
            if (target.isFile() && !marker.isFile() && !repairSoundBios) continue;
            try (InputStream input = new FileInputStream(file)) { copyFirmware(input, target); }
            if (marker.isFile() && !marker.delete()) throw new IOException("无法更新固件来源：" + name);
        }
        String assetDirectory = "firmware/pc" + platform;
        String[] assets = context.getAssets().list(assetDirectory);
        if (assets == null || assets.length == 0) throw new IOException("安装包缺少内置固件");
        for (String name : assets) {
            File target = new File(dest, name);
            boolean soundBios = platform == 98 && Library.soundBiosName(name);
            if (target.isFile() && target.length() > 0 && (!soundBios || target.length() == Library.SOUND_BIOS_BYTES)) continue;
            if (!defaults.isDirectory() && !defaults.mkdirs()) throw new IOException("无法记录内置固件来源");
            new File(defaults, name).createNewFile();
            try (InputStream input = context.getAssets().open(assetDirectory + "/" + name)) { copyFirmware(input, target); }
        }
    }
    void prepare(Game game) throws Exception { prepare(game, LaunchMedia.defaults(game)); }
    PreparedLaunch prepare(Game game, LaunchMedia launchMedia) throws Exception {
        return prepare(game, launchMedia, false);
    }
    PreparedLaunch prepare(Game game, LaunchMedia launchMedia, boolean resume) throws Exception {
        launchMedia.validate(game, platform, resume);
        if (platform == 88) {
            for (LaunchMedia.Ref ref : new LaunchMedia.Ref[]{launchMedia.floppyA, launchMedia.floppyB})
                if (ref != null && ref.index >= D88.titles(game.disk(ref.file).file).size())
                    throw new IOException("内嵌盘号已变化，请重新选择磁盘");
        }
        FontResources fonts = platform == 98 ? new FontResources(context, directory(game)) : null;
        if (fonts != null) fonts.retainLegacy();
        prepareFirmware(resources(game));
        String resourceWarning = fonts != null ? fonts.prepare(game.fontSelection) : "";
        AtomicFile settings = new AtomicFile(new File(directory(game),"machine.cfg"));
        String configuration=MachineOptions.encode(platform,game.machineOptions,game.mode);
        configuration += HostAudio.encode(context);
        if (!game.firmwareRevision.isEmpty()) configuration += "firmware_revision=" + game.firmwareRevision + "\n";
        if (configuration.contains("midi_enabled=ON\n")) {
            File soundfont=new SoundFonts(context).selected();
            if (soundfont==null) throw new IOException("此游戏启用了 MIDI，请先从“设置 → MIDI 音色库”导入并选择 SF2，或关闭本游戏的 MIDI。");
            configuration+="midi_soundfont="+soundfont.getAbsolutePath()+"\n";
        }
        FileOutputStream output=settings.startWrite();
        try { output.write(configuration.getBytes(StandardCharsets.UTF_8)); settings.finishWrite(output); }
        catch (Exception error) { settings.failWrite(output); throw error; }
        return new PreparedLaunch(game, platform, directory(game), launchMedia, resourceWarning);
    }
    void saveLaunchDefaults(String id, LaunchMedia media) throws Exception {
        updateGame(id, current -> { media.validate(current, platform); media.applyDefaults(current); });
    }
    void repairDiskNames(String id, Map<String, String> selected) throws Exception {
        updateGame(id, game -> {
            for (int i = 0; i < game.media.size(); i++) {
                Library.Disk disk = game.media.get(i);
                String name = selected.get(disk.file.getName());
                if (name != null && name.equals(FilenameEncoding.fromGbkToSjis(disk.name)))
                    game.media.set(i, new Library.Disk(name, disk.file, disk.titles));
            }
        });
    }
    void recordStarted(String id, LaunchMedia media, boolean remember, String info) throws Exception {
        updateGame(id, current -> {
            if (remember) { media.validate(current, platform); media.applyDefaults(current); }
            current.lastMachineInfo = info == null ? "" : info;
            current.played = System.currentTimeMillis();
        });
    }
    void importGameResource(Game game, Uri uri) throws Exception {
        String name = legacy.name(uri).toLowerCase(Locale.ROOT);
        if (!resource(name)) throw new IOException("请选择以标准文件名保存的 ROM、FNT 或字库文件");
        if (platform == 98 && FontResources.name(name)) throw new IOException("PC-98 字库请使用“导入并手选字库”");
        File target = new File(resources(game), name); target.getParentFile().mkdirs();
        if (platform == 98 && Library.soundBiosName(name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取固件");
                Library.copy(input, bytes, Library.SOUND_BIOS_BYTES);
            }
            if (bytes.size() != Library.SOUND_BIOS_BYTES) throw new IOException("声卡 BIOS 必须为 16 KB");
            copyFirmware(new ByteArrayInputStream(bytes.toByteArray()), target);
        } else try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取固件");
            copyFirmware(input, target);
        }
        new File(resources(game), ".bundled-firmware/" + name).delete();
        if (name.endsWith(".rom") && !name.startsWith("kanji") && !name.equals("font.rom")) game.firmwareRevision = UUID.randomUUID().toString();
    }
    boolean remove(Game game) throws Exception {
        return stored(() -> {
        List<Game> games = readGames();
        Game removed = null;
        for (Game entry : games) if (entry.id.equals(game.id)) { removed = entry; break; }
        if (removed == null) return true;
        games.remove(removed); write(games);
        Set<String> kept = new HashSet<>();
        for (Game other : games) for (Library.Disk disk : other.media) kept.add(disk.file.getCanonicalPath());
        boolean cleaned = true;
        for (Library.Disk disk : removed.media) if (!kept.contains(disk.file.getCanonicalPath()) && disk.file.exists()) cleaned &= disk.file.delete();
        cleaned &= deleteTree(directory(removed)); return cleaned;
        });
    }
    boolean removeDisk(Game game, Library.Disk disk) throws Exception {
        return stored(() -> {
        List<Game> games = readGames();
        for (Game current : games) if (current.id.equals(game.id)) {
            String file = disk.file.getName();
            if (file.equals(current.first) || file.equals(current.second)) throw new IOException("这张盘正在用于启动，请先更换默认盘位，再移除文件。");
            current.media.removeIf(item -> item.file.equals(disk.file));
        }
        write(games);
        for (Game other : games) for (Library.Disk kept : other.media)
            if (kept.file.getCanonicalFile().equals(disk.file.getCanonicalFile())) return true;
        return !disk.file.exists() || disk.file.delete();
        });
    }
    private boolean deleteTree(File file) throws IOException {
        File actual = file.getCanonicalFile();
        File allowed = new File(legacy.root, "games").getCanonicalFile();
        if (!actual.getPath().startsWith(allowed.getPath() + File.separator)) throw new IOException("清理范围无效");
        boolean ok = true;
        if (actual.isDirectory()) {
            File[] files = actual.listFiles();
            if (files == null) return false;
            for (File child : files) ok &= deleteTree(child);
        }
        return (!actual.exists() || actual.delete()) && ok;
    }
}
