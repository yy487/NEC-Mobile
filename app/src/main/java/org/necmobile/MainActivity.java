package org.necmobile;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int platform = 88;
    private GameLibrary games;
    private LinearLayout cards;
    private TextView progress, count;
    private boolean busy;
    private Library.Disk exporting;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); platform = getPreferences(0).getInt("platform", 88); build();
    }
    private void build() {
        games = new GameLibrary(this, platform);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18)); scroll.addView(content);
        TextView title = Ui.text(this, "我的游戏", 30, Color.WHITE); Ui.title(title); content.addView(title);
        content.addView(Ui.text(this, "NEC Mobile  ·  0.2.4 alpha", 13, 0xff9aabbd));
        LinearLayout tabs = Ui.row(this);
        for (int value : new int[]{88, 98}) {
            Button tab = Ui.button(this, "PC-" + value, () -> {
                if (busy) return;
                platform = value; getPreferences(0).edit().putInt("platform", platform).apply(); build();
            });
            if (value == platform) tab.setTextColor(Ui.ACCENT);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        content.addView(tabs);
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.button(this, "+ 添加游戏", this::addGame), new LinearLayout.LayoutParams(0, -2, 2));
        actions.addView(Ui.button(this, "设置", this::settings), new LinearLayout.LayoutParams(0, -2, 1)); content.addView(actions);
        progress = Ui.text(this, "选择游戏文件夹或 ZIP，磁盘与配套字库会一起添加。", 13, 0xff9aabbd); content.addView(progress);
        count = Ui.text(this, "", 14, Ui.ACCENT); content.addView(count);
        cards = Ui.column(this); content.addView(cards);
        setContentView(scroll); Ui.insets(this, content, 18); refresh();
    }
    private void refresh() {
        try {
            List<GameLibrary.Game> entries = games.list();
            entries.sort(Comparator.comparingLong((GameLibrary.Game game) -> game.played).reversed());
            count.setText("PC-" + platform + "  ·  " + entries.size() + " 款游戏"); cards.removeAllViews();
            if (entries.isEmpty()) cards.addView(Ui.text(this, "还没有游戏\n点击“添加游戏”，选择一款游戏的文件夹或 ZIP。", 16, 0xffb7c3d0));
            for (GameLibrary.Game game : entries) {
                LinearLayout card = Ui.column(this);
                card.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
                card.setBackground(Ui.background(Ui.CARD, Ui.dp(this, 14)));
                LinearLayout heading = Ui.row(this);
                File artwork = games.cover(game);
                Bitmap bitmap = null;
                if (artwork != null) {
                    BitmapFactory.Options size = new BitmapFactory.Options(); size.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(artwork.getAbsolutePath(), size);
                    size.inSampleSize = 1;
                    while (size.outWidth / size.inSampleSize > 256 || size.outHeight / size.inSampleSize > 256) size.inSampleSize *= 2;
                    size.inJustDecodeBounds = false; bitmap = BitmapFactory.decodeFile(artwork.getAbsolutePath(), size);
                }
                android.view.View thumbnail;
                if (bitmap != null) {
                    ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    image.setBackgroundColor(Color.BLACK);
                    image.setContentDescription(game.name + "封面"); thumbnail = image;
                } else {
                    TextView placeholder = Ui.text(this, game.name.substring(0, Math.min(2, game.name.length())), 24, Ui.ACCENT);
                    placeholder.setGravity(android.view.Gravity.CENTER);
                    placeholder.setBackground(Ui.background(0xff263b45, Ui.dp(this, 10))); thumbnail = placeholder;
                }
                LinearLayout.LayoutParams cover = new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 78));
                cover.setMargins(0, 0, Ui.dp(this, 12), 0); heading.addView(thumbnail, cover);
                LinearLayout details = Ui.column(this);
                TextView name = Ui.text(this, game.name, 21, Color.WHITE); Ui.title(name); details.addView(name);
                details.addView(Ui.text(this, "PC-" + platform, 12, Ui.ACCENT));
                details.addView(Ui.text(this, game.played == 0 ? "已准备好 · " + game.media.size() + " 个磁盘文件" :
                        "上次游玩 " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(game.played)), 12, 0xff9aabbd));
                heading.addView(details, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(heading);
                LinearLayout buttons = Ui.row(this);
                buttons.addView(Ui.button(this, game.played == 0 ? "开始游戏" : "再次启动", () -> start(game)), new LinearLayout.LayoutParams(0, -2, 2));
                buttons.addView(Ui.button(this, "管理", () -> manage(game)), new LinearLayout.LayoutParams(0, -2, 1));
                card.addView(buttons); card.setOnClickListener(view -> start(game));
                card.setOnLongClickListener(view -> { manage(game); return true; });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6)); cards.addView(card, params);
            }
        } catch (Exception exception) { error(exception); }
    }
    private <T> void perform(String message, Callable<T> operation, Consumer<T> complete) {
        if (busy) return;
        busy = true; progress.setText(message);
        io.execute(() -> {
            try {
                T result = operation.call();
                runOnUiThread(() -> {
                    busy = false; if (isFinishing()) return;
                    progress.setText(""); refresh(); complete.accept(result);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> { busy = false; if (!isFinishing()) { progress.setText("操作未完成"); refresh(); error(exception); } });
            }
        });
    }
    private void start(GameLibrary.Game game) {
        GameLibrary selected = games;
        perform("正在准备游戏…", () -> {
            selected.prepare(game); game.played = System.currentTimeMillis(); selected.save(game); return game;
        }, ready -> {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("platform", platform); intent.putExtra("mode", ready.mode);
            intent.putExtra("gameId", ready.id); intent.putExtra("gameName", ready.name);
            intent.putExtra("directory", selected.directory(ready).getAbsolutePath());
            intent.putExtra("disk", ready.disk(ready.first).file.getAbsolutePath());
            Library.Disk second = ready.disk(ready.second);
            intent.putExtra("secondDisk", second == null ? "" : second.file.getAbsolutePath());
            intent.putExtra("firstIndex", ready.firstIndex); intent.putExtra("secondIndex", ready.secondIndex);
            startActivityForResult(intent, 30);
        });
    }
    private void addGame() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("添加一款游戏").setItems(new String[]{"选择游戏文件夹", "选择游戏 ZIP"}, (dialog, which) -> {
            if (which == 0) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent, 21);
            } else chooseFile(20, false);
        }).setNegativeButton("取消", null).show();
    }
    private void chooseFile(int request, boolean multiple) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(request == 20 ? "application/zip" : "*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request == 20 || multiple); startActivityForResult(intent, request);
    }
    private void manage(GameLibrary.Game game) {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle(game.name).setItems(new String[]{"重命名", "启动磁盘", "机器设置", "音源设置", "管理磁盘文件", "移除游戏"}, (dialog, choice) -> {
            if (choice == 0) rename(game); if (choice == 1) bootDisks(game); if (choice == 2) machine(game);
            if (choice == 3) settingsEditor(game).sound(); if (choice == 4) files(game); if (choice == 5) remove(game);
        }).setNegativeButton("关闭", null).show();
    }
    private void rename(GameLibrary.Game game) {
        EditText name = new EditText(this); name.setSingleLine(true); name.setText(game.name); name.selectAll();
        new AlertDialog.Builder(this).setTitle("游戏名称").setView(name).setPositiveButton("保存", (dialog, which) -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.length() > 80) { error(new IOException("名称需为 1–80 个字符")); return; }
            game.name = value; perform("正在保存…", () -> { games.save(game); return true; }, ignored -> {});
        }).setNegativeButton("取消", null).show();
    }
    private void bootDisks(GameLibrary.Game game) {
        List<GameLibrary.Media> available = GameLibrary.media(game);
        String[] names = available.stream().map(GameLibrary.Media::label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("选择启动盘").setItems(names, (dialog, firstIndex) -> {
            GameLibrary.Media first = available.get(firstIndex);
            if (GameLibrary.hardDisk(first.disk)) { saveBoot(game, first, null); return; }
            List<GameLibrary.Media> others = new ArrayList<>(available);
            others.removeIf(item -> item.same(first) || GameLibrary.hardDisk(item.disk));
            String[] secondNames = new String[others.size() + 1]; secondNames[0] = "不装第二张盘";
            for (int i = 0; i < others.size(); i++) secondNames[i + 1] = others.get(i).label();
            new AlertDialog.Builder(this).setTitle("同时装入的第二张盘").setItems(secondNames,
                    (picker, index) -> saveBoot(game, first, index == 0 ? null : others.get(index - 1))).setNegativeButton("取消", null).show();
        }).setNegativeButton("取消", null).show();
    }
    private void saveBoot(GameLibrary.Game game, GameLibrary.Media first, GameLibrary.Media second) {
        game.first = first.disk.file.getName(); game.firstIndex = first.index;
        game.second = second == null ? "" : second.disk.file.getName(); game.secondIndex = second == null ? 0 : second.index;
        perform("正在保存启动设置…", () -> { games.save(game); return true; }, ignored -> progress.setText("已记住，下次直接启动"));
    }
    private void machine(GameLibrary.Game game) {
        settingsEditor(game).machine();
    }
    private MachineSettings settingsEditor(GameLibrary.Game game) {
        GameLibrary selected=games;
        return new MachineSettings(this,game,platform,()->new AlertDialog.Builder(this),changed ->
            perform("正在保存设置…",()-> { selected.save(changed); return true; },ignored -> progress.setText("已保存，下次启动生效")));
    }
    private void files(GameLibrary.Game game) {
        String[] labels = game.media.stream().map(disk -> disk.name + (disk.titles.size() > 1 ? "（含 " + disk.titles.size() + " 张盘）" : "") +
                (disk.file.getName().equals(game.first) ? " · 启动盘 " + (game.firstIndex + 1) : "") +
                (disk.file.getName().equals(game.second) ? " · 第二盘 " + (game.secondIndex + 1) : "")).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("游戏磁盘文件").setItems(labels, (dialog, index) -> {
            Library.Disk disk = game.media.get(index);
            new AlertDialog.Builder(this).setTitle(disk.name).setItems(new String[]{"导出磁盘副本", "移除这个文件"}, (actions, choice) -> {
                if (choice == 0) export(disk);
                else new AlertDialog.Builder(this).setTitle("移除磁盘副本？")
                        .setMessage("此副本中的游戏存档也会删除，手机上的原文件会保留。")
                        .setPositiveButton("移除", (confirm, which) -> perform("正在移除文件…", () -> games.removeDisk(game, disk),
                                cleaned -> progress.setText(cleaned ? "磁盘文件已移除" : "已从游戏中移除，应用内文件未能清理")))
                        .setNegativeButton("取消", null).show();
            }).setNegativeButton("返回", null).show();
        })
                .setNegativeButton("关闭", null).show();
    }
    private void remove(GameLibrary.Game game) {
        new AlertDialog.Builder(this).setTitle("移除“" + game.name + "”？")
                .setMessage("将删除这款游戏的应用内副本及其中的游戏存档。手机里原来的文件夹或 ZIP 会保留；需要保留进度时，请先导出磁盘。")
                .setPositiveButton("移除游戏", (dialog, which) -> perform("正在移除…", () -> games.remove(game),
                        cleaned -> progress.setText(cleaned ? "游戏已移除，原文件保留" : "游戏已移除，部分应用内文件未能清理")))
                .setNeutralButton("先导出磁盘", (dialog, which) -> files(game)).setNegativeButton("取消", null).show();
    }
    private void export(Library.Disk disk) {
        exporting = disk; Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, disk.name); startActivityForResult(intent, 40);
    }
    private void settings() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("设置").setItems(new String[]{"导入系统 ROM / 字库", "MIDI 音色库", "使用说明", "来源与许可"}, (dialog, choice) -> {
            if (choice == 0) chooseFile(10, true); if (choice == 1) soundFonts(); if (choice == 2) help(); if (choice == 3) licenses();
        }).setNegativeButton("关闭", null).show();
    }
    private void soundFonts() {
        SoundFonts fonts=new SoundFonts(this); List<File> files=fonts.list(); File active=fonts.selected();
        List<String> names=new ArrayList<>();
        for (File file:files) names.add(fonts.name(file)+(file.equals(active) ? " · 当前" : ""));
        names.add("导入 SF2 音色库…"); names.add("MIDI 使用说明");
        new AlertDialog.Builder(this).setTitle("MIDI 音色库").setItems(names.toArray(new String[0]),(dialog,index)-> {
            if (index==files.size()) { chooseFile(50,false); return; }
            if (index>files.size()) {
                new AlertDialog.Builder(this).setTitle("MIDI 本机合成").setMessage("导入 SF2 后，在每款游戏的“音源设置”里启用 MIDI。PC-98 提供 MPU-98（E0D0，IRQ 6），PC-88 提供 C2/C3 串行输出；游戏内也需要选择 MIDI 音乐。\n\n支持标准 GM 音符、音色、音量、声像、延音及弯音。此合成器不模拟 MT-32，GS/XG 专有音色和效果不保证还原。单个 SF2 最大 64 MB，不附带音色库。\n\n切换音色库下次启动生效；即时档可使用当前选中的音色库，听感会随音色库改变。")
                        .setPositiveButton("知道了",null).show(); return;
            }
            File selected=files.get(index);
            new AlertDialog.Builder(this).setTitle(fonts.name(selected)).setItems(new String[]{"设为当前音色库","移除音色库"},(picker,choice)-> {
                if (choice==0) perform("正在选择音色库…",()-> { fonts.select(selected); return true; },ignored->progress.setText("已选择，下次启动生效"));
                else new AlertDialog.Builder(this).setTitle("移除此音色库？").setMessage("启用 MIDI 的游戏启动前需选择另一份音色库；读取 MIDI 即时档仍需开启 MIDI。游戏磁盘和即时档保留。")
                        .setPositiveButton("移除",(confirm,which)->perform("正在移除音色库…",()-> { fonts.remove(selected); return true; },ignored->{}))
                        .setNegativeButton("取消",null).show();
            }).setNegativeButton("返回",null).show();
        }).setNegativeButton("关闭",null).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 30) { refresh(); return; }
        if (result != RESULT_OK || data == null) return;
        GameLibrary selected = games;
        if (request == 20 || request == 21) {
            List<Uri> locations = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) locations.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) locations.add(data.getData());
            if (locations.isEmpty()) return;
            perform("正在添加游戏…", () -> {
                GameLibrary.Game last = null;
                for (Uri uri : locations) last = request == 20 ? selected.importZip(uri) : selected.importFolder(uri);
                return last;
            }, game -> progress.setText("已添加到游戏库，点击卡片即可开始"));
        } else if (request == 10) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            perform("正在导入系统资源…", () -> { int count = 0; for (Uri uri : uris) count += selected.legacy.importRoms(uri); return count; },
                    imported -> progress.setText("已导入 " + imported + " 个系统资源文件"));
        } else if (request==50 && data.getData()!=null) {
            Uri uri=data.getData();
            perform("正在导入并加载 SF2 音色库…",()->new SoundFonts(this).importFile(uri),font->progress.setText("音色库已选择，可在游戏音源设置中启用 MIDI"));
        } else if (request == 40 && data.getData() != null && exporting != null) {
            Uri uri = data.getData(); Library.Disk disk = exporting;
            perform("正在导出磁盘…", () -> { selected.legacy.export(disk, uri); return true; }, ignored -> progress.setText("磁盘副本已导出"));
        }
    }
    private void error(Exception exception) {
        if (!isFinishing()) new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(exception.getMessage()).setPositiveButton("知道了", null).show();
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("开始游玩")
                .setMessage("添加游戏时，选择单款游戏的文件夹或 ZIP；磁盘和配套字库会一起导入。首次自动安排启动盘，下次点击游戏即可直接启动。\n\n需要调整时，长按游戏或点“管理”，修改名称、启动盘、机器与音源设置，也可导出磁盘、移除游戏。\n\n运行菜单提供换盘、退盘、6 个手动即时存档槽和退出。再次启动仍是冷启动，可从菜单读取即时档；即时档不回退磁盘里的游戏内存档。移除或卸载前，需要保留的游戏内进度请先导出磁盘。\n\n蓝牙或耳机切换后会重建音频输出；耳机断开会暂停，可从菜单继续。MIDI 音乐需先导入 SF2 并在该游戏的音源设置中启用。")
                .setPositiveButton("知道了", null).show();
    }
    private void licenses() {
        try {
            String[] list = getAssets().list("licenses"); if (list == null) return;
            new AlertDialog.Builder(this).setTitle("来源与许可").setItems(list, (dialog, index) -> {
                try (InputStream input = getAssets().open("licenses/" + list[index]); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    Library.copy(input, bytes, 1024 * 1024);
                    String license = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    if (license.indexOf('\ufffd') >= 0) license = new String(bytes.toByteArray(), java.nio.charset.Charset.forName("windows-31j"));
                    TextView text = Ui.text(this, license, 12, Color.WHITE); text.setTextIsSelectable(true);
                    ScrollView scroll = new ScrollView(this); scroll.addView(text);
                    new AlertDialog.Builder(this).setTitle(list[index]).setView(scroll).setPositiveButton("关闭", null).show();
                } catch (Exception exception) { error(exception); }
            }).setNegativeButton("关闭", null).show();
        } catch (Exception exception) { error(exception); }
    }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
}
