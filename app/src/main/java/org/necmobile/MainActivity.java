package org.necmobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.widget.*;
import androidx.activity.ComponentActivity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MainActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int platform;
    private GameLibrary games;
    private LibraryUi ui;
    private List<GameLibrary.Game> entries = new ArrayList<>();
    private Map<String, Bitmap> covers = new HashMap<>();
    private Set<String> sessions = new HashSet<>();
    private String progress = "";
    private boolean busy;
    private Library.Disk exporting;
    private String resourceGameId;
    private List<Uri> pendingImages = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new LibraryUi(this);
        setContentView(ui.createView());
        selectPlatform(getSharedPreferences("library-ui", MODE_PRIVATE).getInt("platform", 98));
    }
    int currentPlatform() { ui.observe(); return platform; }
    boolean isWorking() { ui.observe(); return busy; }
    String progressText() { ui.observe(); return progress; }
    List<GameLibrary.Game> gameEntries() { ui.observe(); return entries; }
    Bitmap artwork(String id) { ui.observe(); return covers.get(id); }
    boolean hasSession(String id) { ui.observe(); return sessions.contains(id); }
    void selectPlatform(int value) {
        if (busy) return;
        if (value != 88 && value != 98) value = 98;
        platform = value;
        getSharedPreferences("library-ui", MODE_PRIVATE).edit().putInt("platform", value).apply();
        games = new GameLibrary(this, value);
        entries = new ArrayList<>(); covers = new HashMap<>(); progress = "";
        ui.changed(); refresh();
    }
    private void setProgress(String text) { progress = text; ui.changed(); }
    private void refresh() {
        GameLibrary selected = games;
        if (selected == null) return;
        io.execute(() -> {
            try {
                List<GameLibrary.Game> loaded = selected.list();
                loaded.sort(Comparator.comparingLong((GameLibrary.Game game) -> game.played).reversed());
                Map<String, Bitmap> images = new HashMap<>();
                Set<String> resumable = new HashSet<>();
                for (GameLibrary.Game game : loaded) {
                    if (new StateSlots(selected.directory(game)).hasAuto()) resumable.add(game.id);
                    File file = selected.cover(game);
                    if (file == null) continue;
                    BitmapFactory.Options size = new BitmapFactory.Options();
                    size.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getAbsolutePath(), size);
                    size.inSampleSize = 1;
                    while (size.outWidth / size.inSampleSize > 256 || size.outHeight / size.inSampleSize > 256) size.inSampleSize *= 2;
                    size.inJustDecodeBounds = false;
                    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), size);
                    if (bitmap != null) images.put(game.id, bitmap);
                }
                runOnUiThread(() -> {
                    if (games == selected && !isFinishing()) {
                        entries = loaded; covers = images; sessions = resumable;
                        ui.entriesChanged(loaded); ui.changed();
                    }
                });
            } catch (Exception exception) { runOnUiThread(() -> { if (games == selected) error(exception); }); }
        });
    }
    private <T> void perform(String text, Callable<T> operation, Consumer<T> complete) {
        if (busy) return;
        busy = true; setProgress(text);
        io.execute(() -> {
            try {
                T result = operation.call();
                runOnUiThread(() -> {
                    busy = false;
                    if (isFinishing()) return;
                    setProgress(""); refresh(); complete.accept(result); ui.changed();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> { busy = false; if (!isFinishing()) { setProgress("操作未完成"); error(exception); } });
            }
        });
    }
    void start(GameLibrary.Game game) {
        if (busy) return;
        if (hasSession(game.id)) {
            StateSlots slots = new StateSlots(games.directory(game));
            ui.showChoices("继续上次游戏？", new String[]{"继续游戏 · " + slots.label(StateSlots.AUTO), "重新启动"}, -1,
                    which -> { if (which == 0) continueSession(game); else startFresh(game); });
        } else startFresh(game);
    }
    private void startFresh(GameLibrary.Game game) {
        if (game.startupReady) launch(game, LaunchMedia.defaults(game), false);
        else ui.openLaunch(game, false);
    }
    void prepareStartup(GameLibrary.Game game, String system, String memory, String source, String entry, boolean defaults) {
        GameLibrary selected = games;
        perform("正在准备启动环境…", () -> selected.prepareStartup(game.id, system, memory, source, entry), prepared -> ui.openLaunch(prepared, defaults));
    }
    void launch(GameLibrary.Game displayed, LaunchMedia media, boolean remember) {
        launch(displayed, media, remember, -1);
    }
    void continueSession(GameLibrary.Game game) { launch(game, null, false, StateSlots.AUTO); }
    private void launch(GameLibrary.Game displayed, LaunchMedia requestedMedia, boolean remember, int restoreSlot) {
        boolean resume = restoreSlot >= 0;
        if (busy) return;
        GameLibrary selected = games;
        int launchPlatform = platform;
        busy = true; setProgress("正在准备游戏…");
        io.execute(() -> {
            try {
                GameLibrary.Game game = selected.find(displayed.id);
                if (game == null) throw new IOException("找不到本游戏记录");
                LaunchMedia media = resume ? new StateSlots(selected.directory(game)).restore(game, launchPlatform, restoreSlot) : requestedMedia;
                PreparedLaunch prepared = selected.prepare(game, media, resume);
                runOnUiThread(() -> {
                    busy = false; setProgress("");
                    Intent intent = prepared.intent(this);
                    intent.putExtra("continueSession", resume);
                    intent.putExtra("restoreSlot", restoreSlot);
                    intent.putExtra("startupReceiver", new ResultReceiver(new Handler(Looper.getMainLooper())) {
                        @Override protected void onReceiveResult(int result, Bundle data) {
                            if (result == RESULT_OK) {
                                ui.launchSucceeded();
                                io.execute(() -> {
                                    try { selected.recordStarted(game.id, media, remember && !resume, data == null ? "" : data.getString("machineInfo", "")); }
                                    catch (Exception exception) { runOnUiThread(() -> error(exception)); }
                                });
                            } else if (resume) setProgress(data == null ? "继续会话失败" : data.getString("error", "继续会话失败"));
                            else ui.launchFailed(data == null ? "启动失败" : data.getString("error", "启动失败"));
                        }
                    });
                    startActivityForResult(intent, 30);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> { busy = false; setProgress(""); if (resume) error(exception); else ui.launchFailed(exception.getMessage()); });
            }
        });
    }
    void saveDefaultMedia(GameLibrary.Game game, LaunchMedia media) {
        GameLibrary selected = games;
        perform("正在保存默认盘位…", () -> { selected.saveLaunchDefaults(game.id, media); return true; }, ignored -> ui.launchSucceeded());
    }
    void saveSettings(GameLibrary.Game displayed, int mode, Map<String, String> settings, String fontSelection) {
        GameLibrary selected = games;
        int selectedPlatform = platform;
        perform("正在保存系统设置…", () -> {
            selected.updateGame(displayed.id, game -> {
            game.mode = MachineOptions.bootMode(selectedPlatform, mode);
            game.fontSelection = fontSelection;
            game.machineOptions.clear();
            for (MachineOptions.Option option : MachineOptions.all(selectedPlatform)) {
                String value = settings.get(option.key);
                if (option.accepts(value)) game.machineOptions.put(option.key, value);
            }
            });
            if (selectedPlatform == 98 && BootEnvironment.hasMemoryDrivers(this)) {
                GameLibrary.Game saved = selected.find(displayed.id);
                if (saved != null && saved.startupPolicy.equals("auto") && !saved.startupMemory.equals("none"))
                    selected.prepareStartup(saved.id, saved.startupSystem);
            }
            return true;
        }, ignored -> { ui.closeSettings(); setProgress("系统设置已保存，下次启动生效"); });
    }
    void saveProfile(GameLibrary.Game displayed, String name, int mode, String model, boolean muted, boolean launchAfter) {
        String title = name.trim();
        if (title.isEmpty() || title.length() > 80) { error(new IOException("名称需为 1–80 个字符")); return; }
        GameLibrary selected = games;
        int selectedPlatform = platform;
        perform("正在保存游戏配置…", () -> {
            selected.updateGame(displayed.id, game -> {
                game.name = title; game.mode = MachineOptions.bootMode(selectedPlatform, mode);
                if (selectedPlatform == 98) {
                    MachineOptions.Option option = MachineOptions.all(98).stream().filter(item -> item.key.equals("np2kai_model")).findFirst().get();
                    if (!option.accepts(model)) throw new IOException("机型设置无效");
                    game.machineOptions.put("np2kai_model", model);
                }
                if (muted) game.machineOptions.put("output_volume", "0");
                else if ("0".equals(game.machineOptions.get("output_volume"))) game.machineOptions.put("output_volume", "100");
            });
            return selected.find(displayed.id);
        }, saved -> {
            if (launchAfter) start(saved);
            else setProgress("游戏配置已保存");
        });
    }
    void loadFonts(GameLibrary.Game game) {
        GameLibrary selected = games;
        io.execute(() -> {
            try {
                List<FontResources.Candidate> fonts = new FontResources(this, selected.directory(game)).list();
                runOnUiThread(() -> ui.fontsLoaded(game.id, fonts));
            } catch (Exception exception) { runOnUiThread(() -> error(exception)); }
        });
    }
    void chooseGameFont(GameLibrary.Game game) { resourceGameId = game.id; chooseFile(60, false); }
    private void fontSettings(GameLibrary.Game game) {
        GameLibrary selected = games;
        perform("正在读取字库…", () -> new FontResources(this, selected.directory(game)).list(), fonts -> {
            List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
            ids.add(""); labels.add("自动：游戏字库 → 全局字库 → 内置字库");
            for (FontResources.Candidate font : fonts) {
                ids.add(font.id);
                labels.add(font.name + " · " + font.source + (font.error == null ? "" : " · " + font.error));
            }
            labels.add("导入字库…");
            ui.showChoices("PC-98 字库 · 下次启动生效", labels.toArray(new String[0]), Math.max(0, ids.indexOf(game.fontSelection)), index -> {
                        if (index == ids.size()) { chooseGameFont(game); return; }
                        if (index > 0 && fonts.get(index - 1).error != null) { error(new IOException(fonts.get(index - 1).error)); return; }
                        perform("正在选择字库…", () -> { selected.updateGame(game.id, current -> current.fontSelection = ids.get(index)); return true; },
                                ignored -> setProgress("字库已选择，下次启动生效"));
                    });
        });
    }
    private void repairNames(GameLibrary.Game game) {
        Map<String, String> repairs = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        for (Library.Disk disk : game.media) {
            String decoded = FilenameEncoding.fromGbkToSjis(disk.name);
            if (!decoded.equals(disk.name)) { repairs.put(disk.file.getName(), decoded); labels.add(disk.name + "\n→ " + decoded); }
        }
        if (repairs.isEmpty()) { error(new IOException("没有找到可按 GBK → Shift_JIS 恢复的磁盘名称")); return; }
        GameLibrary selected = games;
        boolean[] checked = new boolean[repairs.size()]; Arrays.fill(checked, true);
        new AlertDialog.Builder(this).setTitle("恢复磁盘名称 · GBK → Shift_JIS")
                .setMultiChoiceItems(labels.toArray(new String[0]), checked, (dialog, which, enabled) -> checked[which] = enabled)
                .setPositiveButton("应用所选名称", (dialog, which) -> {
                    Map<String, String> chosen = new LinkedHashMap<>(); int index = 0;
                    for (Map.Entry<String, String> repair : repairs.entrySet()) if (checked[index++]) chosen.put(repair.getKey(), repair.getValue());
                    perform("正在保存磁盘名称…", () -> { selected.repairDiskNames(game.id, chosen); return true; }, ignored -> setProgress("磁盘名称已恢复"));
                }).setNegativeButton("取消", null).show();
    }
    void chooseGameResources(GameLibrary.Game game) { resourceGameId = game.id; chooseFile(61, true); }
    void addGame() {
        if (busy) return;
        cancelImageImport(); ui.openImport();
    }
    void chooseImport(int which) {
        if (busy || which < 0 || which > (platform == 98 ? 3 : 1)) return;
        cancelImageImport();
        if (which == 0 || which == 2) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent, which == 2 ? 66 : 21);
        } else chooseFile(which == 3 ? 65 : 20, false);
    }
    void chooseImages() { chooseFile(22, true); }
    void cancelImageImport() { pendingImages = new ArrayList<>(); }
    void importImages(String name) {
        GameLibrary selected = games;
        List<Uri> images = new ArrayList<>(pendingImages);
        perform("正在导入磁盘镜像…", () -> selected.importImages(images, name), imported -> {
            cancelImageImport(); ui.importFinished(); setProgress("已添加到游戏库");
        });
    }
    private void chooseFile(int request, boolean multiple) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(request == 20 || request == 65 ? "application/zip" : "*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request == 20 || multiple); startActivityForResult(intent, request);
    }
    void manage(GameLibrary.Game game) {
        if (busy) return;
        ui.openManagement(game);
    }
    void gameAction(GameLibrary.Game game, String action) {
        if (busy) return;
        switch (action) {
            case "font": fontSettings(game); break;
            case "files": files(game); break;
            case "encoding": repairNames(game); break;
            case "remove": remove(game); break;
        }
    }
    private void files(GameLibrary.Game game) {
        String[] labels = game.media.stream().map(disk -> disk.name + (disk.titles.size() > 1 ? "（含 " + disk.titles.size() + " 张盘）" : "") +
                (disk.file.getName().equals(game.first) ? " · 默认启动盘 " + (game.firstIndex + 1) : "") +
                (disk.file.getName().equals(game.second) ? " · 默认第二盘 " + (game.secondIndex + 1) : "")).toArray(String[]::new);
        List<String> choices = new ArrayList<>(Arrays.asList(labels));
        if (platform == 98) choices.add("添加内置磁盘");
        ui.showChoices("游戏磁盘文件", choices.toArray(new String[0]), -1, index -> {
            if (index < game.media.size()) { diskActions(game, game.media.get(index)); return; }
            ui.showChoices("添加内置磁盘", new String[]{"新建 1.2 MB 存档盘", "DOS 维护工具盘"}, -1, kind -> {
            GameLibrary selected = games;
            perform("正在创建 1.2 MB 存档盘…", () -> {
                GameLibrary.Game current = selected.find(game.id);
                if (current == null) throw new IOException("找不到本游戏记录");
                int number = 1;
                for (GameLibrary.GeneratedMedia item : current.generatedMedia.values()) if (item.role.equals("save")) ++number;
                if (kind == 0) selected.createSaveDisk(game.id, "存档盘 " + number);
                else selected.createToolDisk(game.id);
                return selected.find(game.id);
            }, this::files);
            });
        });
    }
    void diskActions(GameLibrary.Game game, Library.Disk disk) {
        if (busy) return;
        ui.showChoices(disk.name, new String[]{"导出磁盘副本", "移除这个文件"}, -1, choice -> {
            if (choice == 0) export(disk);
            else new AlertDialog.Builder(this).setTitle("移除磁盘副本？").setMessage("此副本中的游戏存档也会删除。")
                    .setPositiveButton("移除", (confirm, which) -> perform("正在移除文件…", () -> games.removeDisk(game, disk), cleaned -> {}))
                    .setNegativeButton("取消", null).show();
        });
    }
    private void remove(GameLibrary.Game game) {
        new AlertDialog.Builder(this).setTitle("移除“" + game.name + "”？").setMessage("将删除这款游戏的应用内磁盘和即时档，需要的游戏内存档可先导出磁盘。")
                .setPositiveButton("移除游戏", (dialog, which) -> perform("正在移除…", () -> games.remove(game), cleaned -> {}))
                .setNeutralButton("先导出磁盘", (dialog, which) -> files(game)).setNegativeButton("取消", null).show();
    }
    private void export(Library.Disk disk) {
        exporting = disk; Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, disk.name); startActivityForResult(intent, 40);
    }
    void settings() {
        if (busy) return;
        ui.openPlatformSettings();
    }
    void openPlatformSetting(String key) {
        if (busy) return;
        switch (key) {
            case "rom": chooseFile(10, true); break;
            case "midi": soundFonts(); break;
            case "guide": help(); break;
            case "license": licenses(); break;
            case "audio": HostAudio.settings(this); break;
            case "storage": storage(); break;
            case "dos": chooseDosTemplate(); break;
        }
    }
    private void storage() {
        perform("正在统计应用文件…", () -> AppStorage.inspect(this), ui::showStorage);
    }
    void cleanStorage() {
        perform("正在清理残留…", () -> AppStorage.clean(this), removed -> {
            setProgress("已释放 " + android.text.format.Formatter.formatFileSize(this, removed));
            storage();
        });
    }
    void chooseDosTemplate() { chooseFile(70, false); }
    String preferredDosTemplate() { return getSharedPreferences("dos", MODE_PRIVATE).getString("template", ""); }
    void discardDos(DosLibrary.Draft draft) {
        io.execute(() -> { try { new DosLibrary(this).discard(draft); } catch (Exception ignored) {} });
    }
    void finishDos(DosLibrary.Draft draft, DosLibrary.Template template, String entry, String directory, String drive, String initialization) {
        if (busy) return;
        busy = true; setProgress("正在建立游戏专用硬盘…");
        io.execute(() -> {
            try {
                GameLibrary.Game game = new DosLibrary(this).commit(draft, template, entry, directory, drive, initialization);
                runOnUiThread(() -> { busy = false; setProgress("DOS 游戏硬盘已建立"); ui.dosFinished(); refresh(); });
            } catch (Exception error) {
                runOnUiThread(() -> { busy = false; setProgress(""); ui.dosFailed(error.getMessage()); });
            }
        });
    }
    private void soundFonts() {
        SoundFonts fonts = new SoundFonts(this); List<File> files = fonts.list(); File active = fonts.selected();
        List<String> names = new ArrayList<>();
        for (File file : files) names.add(fonts.name(file) + (file.equals(active) ? " · 当前" : ""));
        names.add("导入 SF2 音色库…");
        ui.showChoices("MIDI 音色库", names.toArray(new String[0]), files.indexOf(active), index -> {
            if (index == files.size()) { chooseFile(50, false); return; }
            File selected = files.get(index);
            ui.showChoices(fonts.name(selected), new String[]{"设为当前音色库", "移除音色库"}, -1, choice -> {
                if (choice == 0) perform("正在选择音色库…", () -> { fonts.select(selected); return true; }, ignored -> setProgress("已选择，下次启动生效"));
                else new AlertDialog.Builder(this).setTitle("移除此音色库？").setMessage("启用 MIDI 的游戏需选择另一份音色库。")
                        .setPositiveButton("移除", (confirm, which) -> perform("正在移除音色库…", () -> { fonts.remove(selected); return true; }, ignored -> {}))
                        .setNegativeButton("取消", null).show();
            });
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 30) {
            ui.playerReturned(); refresh();
            if (data != null && data.hasExtra("restoreSlot")) {
                String id = data.getStringExtra("gameId"); int slot = data.getIntExtra("restoreSlot", -1);
                if (slot >= 0 && slot <= StateSlots.QUICK) {
                    Handler handler = new Handler(Looper.getMainLooper());
                    long deadline = android.os.SystemClock.uptimeMillis() + 5000;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            android.app.ActivityManager manager = (android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
                            List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
                            boolean alive = processes != null && processes.stream().anyMatch(process -> process.processName.equals(getPackageName() + ":emulation"));
                            if (alive && android.os.SystemClock.uptimeMillis() < deadline) { handler.postDelayed(this, 100); return; }
                            if (alive) { error(new IOException("游玩进程仍在退出，请重新进入游戏读档")); return; }
                            try {
                                GameLibrary.Game game = games.find(id);
                                if (game == null) throw new IOException("找不到本游戏记录");
                                launch(game, null, false, slot);
                            } catch (Exception failure) { error(failure); }
                        }
                    });
                }
                return;
            }
            if (data != null && data.hasExtra("sessionError")) error(new IOException("退出时会话保存未完成：" + data.getStringExtra("sessionError")));
            return;
        }
        if (result != RESULT_OK || data == null) return;
        GameLibrary selected = games;
        if (request == 22) {
            List<Uri> locations = uris(data);
            perform("正在读取镜像名称…", () -> {
                if (locations.size() > 512) throw new IOException("请选择单款游戏的磁盘镜像");
                List<String> names = new ArrayList<>();
                for (Uri uri : locations) names.add(selected.legacy.name(uri));
                return names;
            }, names -> { pendingImages = locations; ui.imagesSelected(names); });
        } else if (request == 20 || request == 21) {
            List<Uri> locations = uris(data);
            if (locations.isEmpty()) return;
            perform("正在添加游戏…", () -> {
                GameLibrary.Game last = null;
                for (Uri uri : locations) last = request == 20 ? selected.importZip(uri) : selected.importFolder(uri);
                return last;
            }, game -> { ui.importFinished(); setProgress("已添加到游戏库"); });
        } else if (request == 10) {
            List<Uri> locations = uris(data);
            perform("正在导入系统资源…", () -> { int count = 0; for (Uri uri : locations) count += selected.legacy.importRoms(uri); return count; },
                    imported -> setProgress("已导入 " + imported + " 个系统资源文件"));
        } else if (request == 50 && data.getData() != null) {
            Uri uri = data.getData();
            perform("正在导入 SF2…", () -> new SoundFonts(this).importFile(uri), font -> setProgress("音色库已选择，可在游戏系统设置中启用 MIDI"));
        } else if (request == 65 || request == 66) {
            if (data.getData() == null) return;
            Uri location = data.getData();
            perform("正在准备 DOS 游戏目录…", () -> {
                DosLibrary library = new DosLibrary(this);
                DosLibrary.Draft draft = library.stage(location, request == 65);
                return new Object[]{draft, library.templates()};
            }, ready -> {
                @SuppressWarnings("unchecked") List<DosLibrary.Template> templates = (List<DosLibrary.Template>) ready[1];
                ui.openDos((DosLibrary.Draft) ready[0], templates);
            });
        } else if (request == 70 && data.getData() != null) {
            Uri location = data.getData();
            perform("正在导入 DOS 系统模板…", () -> {
                DosLibrary library = new DosLibrary(this); DosLibrary.Template selectedTemplate = library.importTemplate(location);
                getSharedPreferences("dos", MODE_PRIVATE).edit().putString("template", selectedTemplate.file.getName()).apply();
                return library.templates();
            }, templates -> { ui.dosTemplatesLoaded(templates); setProgress("DOS 模板已导入"); });
        } else if (request == 60 && data.getData() != null && resourceGameId != null) {
            String id = resourceGameId; Uri uri = data.getData();
            perform("正在导入游戏字库…", () -> {
                GameLibrary.Game game = selected.find(id);
                if (game == null) throw new IOException("找不到本游戏记录");
                String font = new FontResources(this, selected.directory(game)).importManual(uri);
                selected.updateGame(id, current -> current.fontSelection = font);
                return font;
            }, font -> { ui.fontImported(id, font); setProgress("字库已导入并选用，下次启动生效"); });
        } else if (request == 61 && resourceGameId != null) {
            String id = resourceGameId; List<Uri> files = uris(data);
            perform("正在导入本游戏固件…", () -> {
                GameLibrary.Game game = selected.find(id);
                if (game == null) throw new IOException("找不到本游戏记录");
                for (Uri uri : files) selected.importGameResource(game, uri);
                selected.updateGame(id, current -> current.firmwareRevision = game.firmwareRevision); return true;
            }, ignored -> setProgress("游戏固件已保存，下次启动生效"));
        } else if (request == 40 && data.getData() != null && exporting != null) {
            Uri uri = data.getData(); Library.Disk disk = exporting;
            perform("正在导出磁盘…", () -> { selected.legacy.export(disk, uri); return true; }, ignored -> setProgress("磁盘副本已导出"));
        }
    }
    private List<Uri> uris(Intent data) {
        List<Uri> values = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) values.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) values.add(data.getData());
        return values;
    }
    private void error(Exception exception) {
        if (!isFinishing()) ui.showError(exception.getMessage());
    }
    private void help() {
        ui.showDocument("开始游玩", "在顶部切换 PC-88 或 PC-98，通过“添加游戏”导入镜像、游戏文件夹或 ZIP。多盘游戏可一次选择多张镜像。PC-98 也支持 DOS 游戏目录与 ZIP。\n\n最近启动卡片可继续上次会话。列表播放按钮启动游戏，已有会话时可选择继续或重新启动。点击游戏名称或省略号进入游戏管理，调整名称、机器、音量、启动环境与盘位，或导出磁盘。\n\n展开游玩工具球可使用 Esc、快速存档和快速读档。菜单提供六个手动槽，退出和切到后台时自动保存独立会话槽。滑动先输入一次方向，保持触摸 420 毫秒后持续按住，松手释放。\n\n游戏库显示累计游玩时间，暂停、菜单和后台时间排除。设置中的“数据占用与清理”可查看空间明细并清理导入残留。");
    }
    private void licenses() {
        try {
            String[] list = getAssets().list("licenses"); if (list == null) return;
            ui.showChoices("来源与许可", list, -1, index -> perform("正在读取许可…", () -> {
                try (InputStream input = getAssets().open("licenses/" + list[index]); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    Library.copy(input, bytes, 1024 * 1024);
                    String license = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    if (license.indexOf('\ufffd') >= 0) license = new String(bytes.toByteArray(), java.nio.charset.Charset.forName("windows-31j"));
                    return license;
                }
            }, license -> ui.showDocument(list[index], license)));
        } catch (Exception exception) { error(exception); }
    }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
}
