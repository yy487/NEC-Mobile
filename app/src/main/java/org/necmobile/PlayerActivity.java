package org.necmobile;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class PlayerActivity extends Activity implements SurfaceHolder.Callback, AudioManager.OnAudioFocusChangeListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> modifiers = new ArrayList<>();
    private SurfaceView surface;
    private LinearLayout keyboard;
    private FrameLayout screen;
    private ScrollView controls;
    private LinearLayout mouseDock;
    private MousePad mousePad;
    private final List<Button> mouseKeys = new ArrayList<>();
    private final Runnable holdMouse = () -> { if (mouseAllowed()) mousePad.longPress(); };
    private boolean mouseEnabled;
    private String mousePreference;
    private int gestureButtons, mouseSpeed;
    private boolean fullKeyboard, userPaused, resumed, closing, nativeStarted;
    private final Map<String,String> mediaNames = new HashMap<>();
    private List<Library.Disk> gameDisks = new ArrayList<>();
    private String lastStatus = "", gameName;
    private int platform, mouseButtons;
    private int openDialogs;
    private int orientationMode;
    private AudioManager audio;
    private AudioFocusRequest audioFocus;
    private boolean focusGranted, appliedPause = true;
    private boolean stateBusy;
    private final Runnable rebuildAudio = () -> { if (nativeStarted && !closing) Native.audioChanged(); };
    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { routeChanged(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { routeChanged(); }
    };
    private final BroadcastReceiver noisyAudio = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            userPaused = true; applyPause();
            if (resumed) Toast.makeText(PlayerActivity.this, "耳机已断开，游戏已暂停，可从菜单继续", Toast.LENGTH_LONG).show();
        }
    };
    private final Runnable statusUpdate = new Runnable() {
        @Override public void run() {
            if (closing) return;
            String text = Native.status();
            if (text.equals(lastStatus)) { handler.postDelayed(this, 700); return; }
            lastStatus = text;
            switch (text) {
                case "Paused": text = userPaused ? "已暂停" : null; break;
                case "Disk changes flushed": text = "磁盘写入已提交"; break;
                case "Disk mounted": text = "磁盘已装入"; break;
                case "Disk ejected": text = "磁盘已取出"; break;
                case "Disk eject failed": text = "退盘失败，磁盘仍保留在驱动器中"; break;
                case "Disk mount failed": text = "装盘失败，请检查格式与驱动器"; break;
                case "Disk already in other drive": text = "这张盘已在另一驱动器中，请先取出"; break;
                case "Disk mount failed; drive empty": text = "装盘失败，磁盘槽现为空，请重新装入"; break;
                case "Disk write failed": text = "磁盘写入失败"; break;
                case "Reset": text = "已复位"; break;
                case "Audio unavailable; emulation continues muted": text = "音频暂不可用，正在尝试恢复"; break;
                default: text = text.contains("failed") ? text : null;
            }
            if (text != null) Toast.makeText(PlayerActivity.this, text, Toast.LENGTH_LONG).show();
            handler.postDelayed(this, 700);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        orientationMode = getSharedPreferences("player", MODE_PRIVATE).getInt("orientation", 0);
        applyOrientation();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        platform = getIntent().getIntExtra("platform", 88);
        mousePreference = "mouse." + getIntent().getStringExtra("gameId") + "." + platform;
        mouseEnabled = getSharedPreferences("player", MODE_PRIVATE).getBoolean(mousePreference, platform == 98);
        mouseSpeed = Math.max(0, Math.min(2, getSharedPreferences("player", MODE_PRIVATE).getInt("mouseSpeed", 1)));
        gameName = getIntent().getStringExtra("gameName");
        if (gameName == null) gameName = "PC-" + platform;
        try {
            String id = getIntent().getStringExtra("gameId");
            GameLibrary.Game game = id == null ? null : new GameLibrary(this, platform).find(id);
            gameDisks = game == null ? new Library(this, platform).list() : game.media;
            for (Library.Disk disk : gameDisks) mediaNames.put(disk.file.getAbsolutePath(), disk.name);
        } catch (Exception exception) { Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show(); }
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this, handler).build();
        audio.registerAudioDeviceCallback(audioDevices, handler);
        IntentFilter noisy = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyAudio, noisy, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyAudio, noisy);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        screen = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = View.MeasureSpec.getSize(widthSpec), height = View.MeasureSpec.getSize(heightSpec);
                int w = Math.min(width, height * 4 / 3), h = w * 3 / 4;
                surface.getLayoutParams().width = w;
                surface.getLayoutParams().height = h;
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        screen.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.setFocusable(true); surface.setFocusableInTouchMode(true);
        screen.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        mousePad = new MousePad(ViewConfiguration.get(this).getScaledTouchSlop(), new MousePad.Sink() {
            public void move(int x, int y) { if (mouseAllowed()) Native.mouse(x, y, mouseButtons | gestureButtons); }
            public void buttons(int mask) { gestureButtons = mask; if (mouseAllowed()) Native.mouse(0, 0, mouseButtons | gestureButtons); }
            public void click(int mask) {
                if (mouseAllowed()) {
                    clickMouse(mask);
                } else if (!mouseEnabled && mask == 1 && nativeStarted && !closing) {
                    Native.key(13, true); Native.key(13, false);
                }
            }
        });
        surface.setOnTouchListener(this::padTouch); screen.setOnTouchListener(this::padTouch);
        controls = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                int cap = Math.round(getResources().getDisplayMetrics().heightPixels * (landscape ? 0.86f : 0.48f));
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(Math.min(cap, View.MeasureSpec.getSize(height)), View.MeasureSpec.AT_MOST));
            }
        };
        keyboard = Ui.column(this); controls.addView(keyboard);
        controls.setBackground(Ui.background(0xe010151f, Ui.dp(this, 12)));
        controls.setVisibility(View.GONE); root.addView(controls);
        LinearLayout toolbar = Ui.row(this); toolbar.setAlpha(0.86f);
        toolbar.addView(key("确认", 13, false), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 38)));
        if (platform == 88) toolbar.addView(key("空格", 32, false), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 38)));
        toolbar.addView(Ui.button(this, "键盘", this::toggleKeyboard), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 38)));
        toolbar.addView(Ui.button(this, "菜单", this::menu), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 38)));
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        bar.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6)); root.addView(toolbar, bar);
        mouseDock = Ui.row(this);
        for (int mask : new int[]{1, 2}) {
            boolean[] touchClick = {false};
            Button button = Ui.button(this, mask == 1 ? "左键" : "右键", () -> { if (!touchClick[0] && mouseAllowed()) clickMouse(mask); });
            button.setTextSize(12); button.setPadding(0, 0, 0, 0);
            button.setOnTouchListener((view, event) -> {
                if (!mouseAllowed()) return true;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { mouseButtons |= mask; view.setPressed(true); }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mouseButtons &= ~mask; view.setPressed(false);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        touchClick[0] = true; view.performClick(); touchClick[0] = false;
                    }
                }
                Native.mouse(0, 0, mouseButtons | gestureButtons); return true;
            });
            mouseKeys.add(button); mouseDock.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 38)));
        }
        mouseDock.setAlpha(0.86f); mouseDock.setVisibility(mouseEnabled ? View.VISIBLE : View.GONE); root.addView(mouseDock);
        layoutPlayer();
        buildKeyboard();
        setContentView(root); immersive();
        try {
            nativeStarted = Native.start(platform, getIntent().getStringExtra("directory"), getIntent().getStringExtra("disk"), getIntent().getStringExtra("secondDisk"),
                    getIntent().getIntExtra("mode", 0), getIntent().getIntExtra("firstIndex", 0), getIntent().getIntExtra("secondIndex", 0), mouseEnabled);
            if (!nativeStarted) throw new IllegalStateException("已有运行中的内核");
            handler.post(statusUpdate);
        } catch (Throwable error) {
            dialog().setTitle("启动失败").setMessage(error.getMessage()).setPositiveButton("返回", (dialog, which) -> stop()).show();
        }
    }
    private boolean mouseAllowed() { return mouseEnabled && nativeStarted && resumed && !closing && !appliedPause && !stateBusy; }
    private void clickMouse(int mask) {
        Native.mouse(0, 0, mouseButtons | gestureButtons | mask); Native.mouse(0, 0, mouseButtons | gestureButtons);
    }
    private boolean padTouch(View view, MotionEvent event) {
        if (closing || !nativeStarted || appliedPause || stateBusy) return true;
        int action = event.getActionMasked(), pointer = event.getActionIndex();
        float speed = new float[]{0.6f, 1f, 1.6f}[mouseSpeed];
        if (action == MotionEvent.ACTION_DOWN) {
            mousePad.down(event.getPointerId(0), event.getX(), event.getY(), event.getEventTime(),
                    speed * 640f / Math.max(1, surface.getWidth()), speed * 400f / Math.max(1, surface.getHeight()));
            if (mouseEnabled) handler.postDelayed(holdMouse, 350);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            handler.removeCallbacks(holdMouse);
            mousePad.another(event.getPointerId(pointer), event.getX(pointer), event.getY(pointer));
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int history = 0; history < event.getHistorySize(); history++) for (int i = 0; i < event.getPointerCount(); i++)
                mousePad.move(event.getPointerId(i), event.getHistoricalX(i, history), event.getHistoricalY(i, history));
            for (int i = 0; i < event.getPointerCount(); i++) mousePad.move(event.getPointerId(i), event.getX(i), event.getY(i));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            handler.removeCallbacks(holdMouse);
            mousePad.up(event.getPointerId(pointer), event.getX(pointer), event.getY(pointer), event.getEventTime(), action == MotionEvent.ACTION_UP);
            if (action == MotionEvent.ACTION_UP) view.performClick();
        } else if (action == MotionEvent.ACTION_CANCEL) cancelMouse();
        return true;
    }
    private void cancelMouse() {
        handler.removeCallbacks(holdMouse);
        if (mousePad != null) mousePad.cancel();
        mouseButtons = gestureButtons = 0;
        for (Button button : mouseKeys) button.setPressed(false);
        if (nativeStarted) Native.mouse(0, 0, 0);
    }
    private void inputSettings() {
        dialog().setTitle("操作方式").setItems(new String[]{"虚拟鼠标（触控板）", "按键操作（轻触确认）", "鼠标速度", "操作说明"}, (dialog, choice) -> {
            if (choice < 2) {
                cancelMouse(); Native.releaseKeys();
                mouseEnabled = choice == 0;
                getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean(mousePreference, mouseEnabled).apply();
                mouseDock.setVisibility(mouseEnabled ? View.VISIBLE : View.GONE); Native.mouseMode(mouseEnabled);
                Toast.makeText(this, mouseEnabled ? "滑动移动 · 轻触左击 · 双指右击 · 长按拖动" : "使用数字、空格及确认操作", Toast.LENGTH_LONG).show();
            } else if (choice == 2) dialog().setTitle("鼠标速度").setSingleChoiceItems(new String[]{"慢速", "标准", "快速"}, mouseSpeed, (picker, speed) -> {
                mouseSpeed = speed; getSharedPreferences("player", MODE_PRIVATE).edit().putInt("mouseSpeed", speed).apply(); picker.dismiss();
            }).setNegativeButton("返回", null).show();
            else dialog().setTitle("虚拟鼠标").setMessage("在屏幕任意空白处或游戏画面上滑动，控制游戏自己的鼠标光标；抬手后可换位置继续滑动。\n\n轻触：左键点击\n双指轻触：右键点击\n长按后滑动：按住左键拖动\n左下角左右键：可直接点击，也可按住配合另一只手滑动。\n\n游戏需要支持鼠标；轻井泽等键盘游戏请用按键模式。")
                    .setPositiveButton("知道了", null).show();
        }).setNegativeButton("返回游戏", null).show();
    }
    private Button key(String name, int code, boolean modifier) {
        Button button = Ui.button(this, name, () -> {});
        button.setPadding(0, 0, 0, 0);
        button.setTextSize(12);
        if (modifier) {
            modifiers.add(button);
            button.setOnClickListener(view -> {
                boolean selected = !button.isSelected(); button.setSelected(selected);
                button.setTextColor(selected ? Ui.ACCENT : Color.WHITE);
                Native.key(code, selected);
            });
        } else {
            button.setOnTouchListener((view, event) -> {
                if (closing) return true;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) { Native.key(code, true); view.setPressed(true); }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    Native.key(code, false); view.setPressed(false);
                    if (action == MotionEvent.ACTION_UP) view.performClick();
                }
                return true;
            });
        }
        return button;
    }
    private void keyRow(String labels, int[] codes, boolean sticky) {
        String[] names = labels.split(" ");
        LinearLayout row = Ui.row(this);
        for (int i = 0; i < names.length; i++)
            row.addView(key(names[i], codes[i], sticky), new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        keyboard.addView(row);
    }
    private void letters(String letters) {
        LinearLayout row = Ui.row(this);
        for (int i = 0; i < letters.length(); i++) {
            char letter = letters.charAt(i);
            row.addView(key(String.valueOf(letter).toUpperCase(Locale.ROOT), letter, false), new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        }
        keyboard.addView(row);
    }
    private void buildKeyboard() {
        cancelMouse();
        keyboard.removeAllViews(); modifiers.clear();
        Native.releaseKeys();
        if (fullKeyboard) {
            letters("1234567890"); letters("qwertyuiop"); letters("asdfghjkl"); letters("zxcvbnm");
            keyRow("@ [ ] \\ - ^ : ; , . / _", new int[]{64, 91, 93, 92, 45, 94, 58, 59, 44, 46, 47, 95}, false);
        }
        keyRow("Esc F1 F2 F3 F4 F5", new int[]{27, 282, 283, 284, 285, 286}, false);
        if (platform == 88 && !fullKeyboard) letters("1234567890");
        if (platform == 98 && fullKeyboard) keyRow("F6 F7 F8 F9 F10", new int[]{287, 288, 289, 290, 291}, false);
        keyRow("← ↑ ↓ → 空格 Enter", new int[]{276, 273, 274, 275, 32, 13}, false);
        keyRow("SHIFT CTRL KANA", new int[]{304, 306, 302}, true);
        LinearLayout bottom = Ui.row(this);
        bottom.addView(key("BS", 8, false), new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        bottom.addView(key("TAB", 9, false), new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        bottom.addView(key("STOP", 19, false), new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        bottom.addView(Ui.button(this, fullKeyboard ? "精简键盘" : "完整键盘", () -> { fullKeyboard = !fullKeyboard; buildKeyboard(); }), new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 2));
        keyboard.addView(bottom);
    }
    private void applyPause() {
        cancelMouse();
        if (!nativeStarted || closing) return;
        boolean shouldPause = userPaused || !resumed || !focusGranted || openDialogs > 0 || stateBusy;
        if (shouldPause != appliedPause) { Native.pause(shouldPause); appliedPause = shouldPause; }
        for (Button modifier : modifiers) { modifier.setSelected(false); modifier.setTextColor(Color.WHITE); }
    }
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this) {
            @Override public AlertDialog show() {
                openDialogs++; applyPause();
                AlertDialog opened = super.show();
                opened.setOnDismissListener(ignored -> {
                    openDialogs = Math.max(0, openDialogs - 1); applyPause();
                });
                return opened;
            }
        };
    }
    private void menu() {
        dialog().setTitle(gameName).setItems(new String[]{"返回游戏", "即时存档 / 读档", "更换 / 取出磁盘", userPaused || !focusGranted ? "继续运行" : "暂停", "屏幕方向", "操作方式 / 鼠标", "本游戏音源设置", "更多设置", "退出游戏"}, (dialog, index) -> {
            if (index == 1) states();
            if (index == 2) disks();
            if (index == 3) { userPaused = !(userPaused || !focusGranted); if (!userPaused && !focusGranted) requestFocus(); applyPause(); }
            if (index == 4) {
                dialog().setTitle("屏幕方向")
                        .setSingleChoiceItems(new String[]{"自动旋转", "固定横屏", "固定竖屏"}, orientationMode, (picker, choice) -> {
                            orientationMode = choice;
                            getSharedPreferences("player", MODE_PRIVATE).edit().putInt("orientation", choice).apply();
                            applyOrientation(); picker.dismiss();
                        }).setNegativeButton("取消", null).show();
            }
            if (index == 5) inputSettings();
            if (index == 6) soundSettings();
            if (index == 7) dialog().setTitle("更多设置")
                    .setItems(new String[]{"提交磁盘写入", "复位机器", "将当前画面设为封面", "恢复声音输出"}, (settings, which) -> {
                        if (which == 0) Native.flush();
                        else if (which == 2) captureCover();
                        else if (which == 3) { Native.audioChanged(); requestFocus(); applyPause(); }
                        else dialog().setTitle("复位机器？").setMessage("尚未在游戏内保存的进度会丢失。")
                                .setPositiveButton("复位", (a, b) -> Native.reset()).setNegativeButton("取消", null).show();
                    }).setNegativeButton("关闭", null).show();
            if (index == 8) confirmExit();
        }).show();
    }
    private StateSlots stateSlots() { return new StateSlots(new java.io.File(getIntent().getStringExtra("directory"))); }
    private void states() {
        StateSlots slots=stateSlots();
        java.util.function.IntConsumer choose=slot -> {
            dialog().setTitle(slots.label(slot)).setItems(new String[]{"保存当前进度","读取此槽","删除此槽"},(actions,choice)-> {
                if (choice==0) {
                    boolean occupied=true;
                    try { occupied=slots.folder(slot)!=null; } catch (Exception ignored) { }
                    if (!occupied) stateTask(slot,false);
                    else dialog().setTitle("覆盖槽 "+slot+"？").setMessage("将用当前进度替换这个槽的即时档。")
                            .setPositiveButton("保存",(a,b)->stateTask(slot,false)).setNegativeButton("取消",null).show();
                } else if (choice==1) {
                    try { if (slots.folder(slot)==null) { Toast.makeText(this,"此槽没有存档",Toast.LENGTH_SHORT).show(); return; } }
                    catch (Exception error) { dialog().setTitle("无法读档").setMessage(error.getMessage()).setPositiveButton("知道了",null).show(); return; }
                    dialog().setTitle("读取槽 "+slot+"？").setMessage("将恢复机器状态及当时的装盘组合，当前未保存的进度会被替换。磁盘里的游戏内存档不会回退。")
                            .setPositiveButton("读取",(a,b)->stateTask(slot,true)).setNegativeButton("取消",null).show();
                } else dialog().setTitle("删除槽 "+slot+"？").setMessage("只删除此即时档，游戏磁盘及游戏内存档保留。")
                        .setPositiveButton("删除",(a,b)-> {
                            try { slots.remove(slot); } catch (Exception error) { Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show(); }
                        }).setNegativeButton("取消",null).show();
            }).setNegativeButton("返回游戏",null).show();
        };
        LinearLayout grid=Ui.column(this); grid.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),0);
        AlertDialog[] picker={null};
        for (int row=0;row<3;++row) {
            LinearLayout line=Ui.row(this);
            for (int column=0;column<2;++column) {
                int slot=row*2+column+1; String label=slots.label(slot);
                Button button=Ui.button(this,label.replace(" · ","\n"),()-> { choose.accept(slot); picker[0].dismiss(); });
                button.setContentDescription(label);
                line.addView(button,new LinearLayout.LayoutParams(0,Ui.dp(this,60),1));
            }
            grid.addView(line);
        }
        picker[0]=dialog().setTitle("即时存档 / 读档").setView(grid).setNeutralButton("说明",(a,b)->dialog().setTitle("手动即时存档").setMessage("每款游戏有 6 个槽，保存机器运行状态和装盘组合。覆盖时旧档会保留到新档写入成功；读档失败时尝试恢复读档前的会话。\n\n不自动恢复、不回退磁盘写入。仍建议使用游戏自己的存档，避免把不同时间的磁盘进度与即时档混用。\n\n仅支持本版本、同一游戏副本与相同机器 / 音源设置。卸载或移除游戏会一并删除这些槽。")
                .setPositiveButton("知道了",null).show()).setNegativeButton("返回游戏",null).show();
    }
    private void stateTask(int slot,boolean load) {
        if (stateBusy || closing) return;
        stateBusy=true; applyPause();
        AlertDialog progress=dialog().setTitle(load ? "正在读档…" : "正在保存…").setMessage("槽 "+slot).setCancelable(false).show();
        new Thread(()-> {
            String error=null;
            try { if (load) stateSlots().load(slot); else stateSlots().save(slot); }
            catch (Exception exception) { error=exception.getMessage(); }
            String result=error;
            handler.post(()-> {
                stateBusy=false;
                if (isFinishing() || closing) { progress.dismiss(); return; }
                if (result!=null) dialog().setTitle(load ? "读档未完成" : "保存未完成").setMessage(result).setPositiveButton("知道了",null).show();
                else Toast.makeText(this,load ? "已读取槽 "+slot : "已保存到槽 "+slot,Toast.LENGTH_SHORT).show();
                progress.dismiss(); applyPause();
            });
        },"state-slot").start();
    }
    private void soundSettings() {
        String id=getIntent().getStringExtra("gameId");
        new Thread(()-> {
            try {
                GameLibrary library=new GameLibrary(this,platform); GameLibrary.Game game=library.find(id);
                if (game==null) throw new java.io.IOException("找不到本游戏记录");
                handler.post(()-> { if (closing || isFinishing()) return;
                    new MachineSettings(this,game,platform,this::dialog,changed -> new Thread(()-> {
                        String message="音源设置已保存，下次启动生效";
                        try { library.save(changed); } catch (Exception error) { message=error.getMessage(); }
                        String result=message; handler.post(()-> { if (!closing) Toast.makeText(this,result,Toast.LENGTH_LONG).show(); });
                    },"save-machine-settings").start()).sound();
                });
            } catch (Exception error) { handler.post(()-> { if (!closing) Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show(); }); }
        },"read-machine-settings").start();
    }
    private void captureCover() {
        String id = getIntent().getStringExtra("gameId");
        if (id == null || !surface.getHolder().getSurface().isValid()) return;
        int width = Math.max(1, surface.getWidth()), height = Math.max(1, surface.getHeight());
        float scale = Math.min(1f, 640f / width);
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), android.graphics.Bitmap.Config.ARGB_8888);
        PixelCopy.request(surface, image, result -> {
            if (result != PixelCopy.SUCCESS) { image.recycle(); Toast.makeText(this, "未能取得当前画面", Toast.LENGTH_SHORT).show(); return; }
            new Thread(() -> {
                String message = "已设为游戏封面";
                android.util.AtomicFile output = null; java.io.FileOutputStream stream = null;
                try {
                    GameLibrary library = new GameLibrary(this, platform); GameLibrary.Game game = library.find(id);
                    if (game == null) throw new java.io.IOException("游戏记录不存在");
                    java.io.File folder = library.directory(game); folder.mkdirs();
                    output = new android.util.AtomicFile(new java.io.File(folder, "cover.png")); stream = output.startWrite();
                    if (!image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) throw new java.io.IOException("封面保存失败");
                    output.finishWrite(stream); stream = null;
                } catch (Exception exception) {
                    if (output != null && stream != null) output.failWrite(stream);
                    message = exception.getMessage();
                } finally { image.recycle(); }
                String text = message; handler.post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
            }, "cover-save").start();
        }, handler);
    }
    private void applyOrientation() {
        if (orientationMode < 0 || orientationMode > 2) orientationMode = 0;
        setRequestedOrientation(orientationMode == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE :
                orientationMode == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    }
    private void layoutPlayer() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int panelWidth = Math.min(Ui.dp(this, 320), getResources().getDisplayMetrics().widthPixels * 2 / 5);
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(landscape ? panelWidth : -1, -2, Gravity.BOTTOM | Gravity.END);
        panel.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 48));
        controls.setLayoutParams(panel);
        FrameLayout.LayoutParams mouse = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        int width = getResources().getDisplayMetrics().widthPixels;
        mouse.setMargins(Ui.dp(this, 6), 0, 0, Ui.dp(this, width < Ui.dp(this, 400) ? 48 : 6));
        mouseDock.setLayoutParams(mouse);
    }
    private void toggleKeyboard() {
        cancelMouse();
        controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (controls.getVisibility() != View.VISIBLE) Native.releaseKeys();
        for (Button modifier : modifiers) { modifier.setSelected(false); modifier.setTextColor(Color.WHITE); }
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (focused) immersive();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        cancelMouse();
        layoutPlayer(); immersive();
    }
    private void disks() {
        String[] labels = new String[2];
        for (int drive = 0; drive < 2; drive++) {
            String path = Native.disk(drive);
            String name = mediaNames.getOrDefault(path, new java.io.File(path).getName());
            int index = Native.diskIndex(drive);
            for (Library.Disk disk : gameDisks) if (disk.file.getAbsolutePath().equals(path)) name = disk.label(index);
            labels[drive] = "磁盘槽 " + (drive + 1) + "：" + (path.isEmpty() ? "空" : name);
        }
        dialog().setTitle("更换 / 取出磁盘").setItems(labels, (choose, drive) -> {
            boolean empty = Native.disk(drive).isEmpty();
            String[] operations = empty ? new String[]{"装入磁盘"} : new String[]{"换入其他盘", "取出当前盘"};
            dialog().setTitle(labels[drive]).setItems(operations, (operation, action) -> {
                if (!empty && action == 1) Native.mount(drive, "", 0);
                else chooseDisk(drive);
            }).setNegativeButton("取消", null).show();
        }).setNegativeButton("返回游戏", null).show();
    }
    private void chooseDisk(int drive) {
        List<GameLibrary.Media> available = new ArrayList<>();
        String otherPath = Native.disk(1 - drive); int otherIndex = Native.diskIndex(1 - drive);
        for (Library.Disk disk : gameDisks) if (!GameLibrary.hardDisk(disk))
            for (int index = 0; index < disk.titles.size(); index++)
                if (!disk.file.getAbsolutePath().equals(otherPath) || index != otherIndex) available.add(new GameLibrary.Media(disk, index));
        if (available.isEmpty()) { Toast.makeText(this, "没有可换入的软盘，请先取出另一槽中的盘", Toast.LENGTH_LONG).show(); return; }
        String[] names = available.stream().map(GameLibrary.Media::label).toArray(String[]::new);
        dialog().setTitle("选择换入的磁盘").setItems(names, (dialog, item) -> {
            GameLibrary.Media selected = available.get(item);
            Native.mount(drive, selected.disk.file.getAbsolutePath(), selected.index);
        }).setNegativeButton("取消", null).show();
    }
    private void confirmExit() {
        dialog().setTitle("退出游戏？")
                .setMessage("请先使用游戏内存档或手动即时存档。退出时会提交磁盘写入，下次冷启动后可从菜单读取即时档。")
                .setPositiveButton("退出", (dialog, which) -> stop()).setNegativeButton("继续游玩", null).show();
    }
    private void stop() {
        if (closing) return;
        cancelMouse();
        closing = true; handler.removeCallbacks(statusUpdate);
        Toast.makeText(this, "正在退出并提交磁盘写入…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            if (nativeStarted) Native.stop();
            runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
        }, "core-stop").start();
    }
    @Override public void onBackPressed() { confirmExit(); }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        requestFocus();
        applyPause();
    }
    private void requestFocus() {
        focusGranted = audio != null && audio.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
    private void routeChanged() {
        handler.removeCallbacks(rebuildAudio);
        handler.postDelayed(rebuildAudio, 400);
    }
    @Override protected void onPause() {
        resumed = false; focusGranted = false; applyPause();
        if (audio != null) audio.abandonAudioFocusRequest(audioFocus);
        super.onPause();
    }
    @Override public void onAudioFocusChange(int change) {
        focusGranted = change == AudioManager.AUDIOFOCUS_GAIN && resumed;
        if (focusGranted) routeChanged();
        applyPause();
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { Native.surface(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { Native.surface(holder.getSurface()); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { cancelMouse(); Native.surface(null); }
    @Override protected void onDestroy() {
        if (audio != null) audio.unregisterAudioDeviceCallback(audioDevices);
        unregisterReceiver(noisyAudio);
        handler.removeCallbacksAndMessages(null);
        if (nativeStarted && !closing) Native.stop();
        super.onDestroy();
        // Each player lives in its own process so native singleton state cannot leak into the next boot.
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }
    private int keyCode(KeyEvent event) {
        int code = event.getKeyCode();
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return 'a' + code - KeyEvent.KEYCODE_A;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return '0' + code - KeyEvent.KEYCODE_0;
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) return 282 + code - KeyEvent.KEYCODE_F1;
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) return 256 + code - KeyEvent.KEYCODE_NUMPAD_0;
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return 13;
            case KeyEvent.KEYCODE_SPACE: return 32;
            case KeyEvent.KEYCODE_ESCAPE: return 27;
            case KeyEvent.KEYCODE_DEL: return 8;
            case KeyEvent.KEYCODE_TAB: return 9;
            case KeyEvent.KEYCODE_DPAD_UP: return 273;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 274;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 275;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 276;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 304;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 303;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 306;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 305;
            case KeyEvent.KEYCODE_ALT_LEFT: return 308;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 307;
            case KeyEvent.KEYCODE_KANA: case KeyEvent.KEYCODE_SCROLL_LOCK: return 302;
            default: int character = event.getUnicodeChar(0); return character >= 33 && character <= 126 ? character : -1;
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = keyCode(event);
        if (code >= 0 && nativeStarted && !closing && !appliedPause && !stateBusy) {
            if (event.getAction() == KeyEvent.ACTION_UP) Native.key(code, false);
            else if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) Native.key(code, true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
