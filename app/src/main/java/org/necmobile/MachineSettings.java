package org.necmobile;

import android.app.*;
import android.graphics.Color;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class MachineSettings {
    private final Activity activity;
    private final GameLibrary.Game game;
    private final int platform;
    private final Supplier<AlertDialog.Builder> dialogs;
    private final Consumer<GameLibrary.Game> save;
    MachineSettings(Activity activity, GameLibrary.Game game, int platform, Supplier<AlertDialog.Builder> dialogs, Consumer<GameLibrary.Game> save) {
        this.activity=activity; this.game=game; this.platform=platform; this.dialogs=dialogs; this.save=save;
    }
    private List<MachineOptions.Option> group(int group) {
        List<MachineOptions.Option> items = new ArrayList<>();
        for (MachineOptions.Option option : MachineOptions.all(platform)) if (option.group==group) items.add(option);
        return items;
    }
    void machine() { show(MachineOptions.MACHINE); }
    void sound() { show(MachineOptions.SOUND); }
    private void show(int group) {
        List<MachineOptions.Option> items=group(group);
        List<String> names=new ArrayList<>();
        if (group==MachineOptions.MACHINE && platform==88) names.add("BASIC 模式："+new String[]{"N88-V2","N88-V1(S)","N88-V1(H)"}[game.mode]);
        for (MachineOptions.Option option : items) names.add(option.label+"："+option.display(option.value(game.machineOptions,game.mode)));
        int extras=names.size();
        if (group==MachineOptions.SOUND) { names.add("分项音量…"); if (platform==98) names.add("高级音源设置…"); }
        names.add("恢复此类默认设置");
        dialogs.get().setTitle((group==0 ? "机器设置" : group==1 ? "音源设置" : "高级音源设置")+" · 下次启动生效")
                .setItems(names.toArray(new String[0]),(dialog,index)-> {
                    int offset=group==0 && platform==88 ? 1 : 0;
                    if (offset==1 && index==0) {
                        dialogs.get().setTitle("BASIC 模式 · 下次启动生效").setSingleChoiceItems(new String[]{"N88-V2","N88-V1(S)","N88-V1(H)"},game.mode,(picker,value)-> {
                            game.mode=value; picker.dismiss(); save.accept(game);
                        }).setNegativeButton("取消",null).show();
                    } else if (index<extras) choose(items.get(index-offset));
                    else if (group==1 && index==extras) mixer();
                    else if (group==1 && platform==98 && index==extras+1) show(MachineOptions.ADVANCED_SOUND);
                    else {
                        for (MachineOptions.Option option : MachineOptions.all(platform))
                            if (option.group==group || (group==1 && option.group!=0)) game.machineOptions.remove(option.key);
                        if (group==0) game.mode=0;
                        save.accept(game);
                    }
                }).setNegativeButton("关闭",null).show();
    }
    private void choose(MachineOptions.Option option) {
        int selected=Arrays.asList(option.values).indexOf(option.value(game.machineOptions,game.mode));
        String[] labels=option.names.clone();
        if (option.key.equals("np2kai_SNDboard")) for (int i=0;i<labels.length;i++) if (labels[i].equals("None")) labels[i]="关闭声卡（BEEP 单独设置）";
        dialogs.get().setTitle(option.label+" · 下次启动生效").setSingleChoiceItems(labels,selected,(picker,index)-> {
            if (option.key.equals("midi_enabled") && option.values[index].equals("ON") && new SoundFonts(activity).selected()==null) {
                picker.dismiss(); dialogs.get().setTitle("先导入 MIDI 音色库").setMessage("请返回游戏库，从“设置 → MIDI 音色库”导入 SF2 文件，再启用本游戏的 MIDI。游戏本身也需支持并选择 MIDI 音乐。")
                        .setPositiveButton("知道了",null).show(); return;
            }
            game.machineOptions.put(option.key,option.values[index]); picker.dismiss(); save.accept(game);
        }).setNegativeButton("取消",null).show();
    }
    private void mixer() {
        LinearLayout content=Ui.column(activity); int padding=Ui.dp(activity,16); content.setPadding(padding,0,padding,0);
        content.addView(Ui.text(activity,"音量按游戏保存，修改下次启动生效。分项音量只对当前声卡具备的相应声道生效。",13,Color.WHITE));
        Map<String,String> draft=new HashMap<>();
        for (MachineOptions.Option option : group(MachineOptions.MIXER)) {
            int current=Integer.parseInt(option.value(game.machineOptions,game.mode)); draft.put(option.key,String.valueOf(current));
            TextView label=Ui.text(activity,option.label+"："+option.display(String.valueOf(current)),14,Color.WHITE); content.addView(label);
            SeekBar slider=new SeekBar(activity); slider.setMax(option.max-option.min); slider.setProgress(current-option.min);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar bar) { }
                public void onStopTrackingTouch(SeekBar bar) { }
                public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                    String value=String.valueOf(progress+option.min); draft.put(option.key,value); label.setText(option.label+"："+option.display(value));
                }
            }); content.addView(slider);
        }
        ScrollView scroll=new ScrollView(activity); scroll.addView(content);
        dialogs.get().setTitle("分项音量 · 下次启动生效").setView(scroll).setPositiveButton("保存",(dialog,which)-> {
            game.machineOptions.putAll(draft); save.accept(game);
        }).setNegativeButton("取消",null).show();
    }
}
