package org.necmobile;

import java.util.*;

final class MachineOptions {
    static final int MACHINE = 0, SOUND = 1, MIXER = 2, ADVANCED_SOUND = 3;
    static final class Option {
        final String key, label, fallback;
        final int group, min, max;
        final String[] values, names;
        Option(int group, String key, String label, String fallback, String... values) {
            this(group, key, label, fallback, values, values, 0, 0);
        }
        Option(int group, String key, String label, String fallback, String[] values, String[] names, int min, int max) {
            this.group=group; this.key=key; this.label=label; this.fallback=fallback;
            this.values=values; this.names=names; this.min=min; this.max=max;
        }
        boolean range() { return values.length == 0; }
        boolean accepts(String value) {
            if (value == null) return false;
            if (!range()) return Arrays.asList(values).contains(value);
            try { int n=Integer.parseInt(value); return n>=min && n<=max; } catch (NumberFormatException error) { return false; }
        }
        String value(Map<String,String> settings, int mode) {
            String value = settings.get(key);
            if (accepts(value)) return value;
            return key.equals("np2kai_clk_mult") ? (mode==1 ? "8" : mode==2 ? "16" : "4") : fallback;
        }
        String display(String value) {
            if (range()) {
                int n=Integer.parseInt(value);
                return min<0 ? (n==min ? "静音" : n+" dB") : Math.round(n*100f/max)+"%";
            }
            return names[Arrays.asList(values).indexOf(value)];
        }
    }
    private static Option range(String key, String label, int fallback, int min, int max) {
        return new Option(MIXER,key,label,String.valueOf(fallback),new String[0],new String[0],min,max);
    }
    private static Option choice(int group, String key, String label, String fallback, String[] values, String... names) {
        return new Option(group,key,label,fallback,values,names,0,0);
    }
    static List<Option> all(int platform) {
        List<Option> items = new ArrayList<>();
        if (platform==98) {
            items.add(new Option(MACHINE,"np2kai_model","机型","PC-9801VX","PC-9801VX","PC-9801VM","PC-286"));
            items.add(new Option(MACHINE,"np2kai_clk_base","CPU 基础频率","2.4576 MHz","2.4576 MHz","1.9968 MHz"));
            items.add(new Option(MACHINE,"np2kai_clk_mult","CPU 倍率","4","2","4","5","6","8","10","12","16","20","24","30","40","64","100"));
            items.add(new Option(MACHINE,"np2kai_cpu_feature","CPU 型号 / FPU","Intel 80386","Intel 80386","Intel i486SX","Intel i486DX","Intel Pentium","Intel MMX Pentium","Intel Pentium II","Intel Pentium III"));
            items.add(new Option(MACHINE,"np2kai_ExMemory","扩展内存（MB）","3","1","3","7","11","13","16","32","64","120"));
            items.add(choice(MACHINE,"np2kai_FastMC","快速内存检查","OFF",new String[]{"OFF","ON"},"关闭","开启"));
            items.add(new Option(SOUND,"np2kai_SNDboard","声卡","PC9801-86",
                    "PC9801-86","PC9801-26K","PC9801-26K + 86","PC9801-118","None",
                    "PC9801-14","Mate-X PCM","PC9801-86 + Mate-X PCM(B460)","PC9801-86 + 118(B460)",
                    "Speak Board","PC9801-86 + Speak Board","Spark Board","Sound Orchestra","Sound Orchestra-V","Little Orchestra L","Multimedia Orchestra",
                    "Sound Blaster 16","PC9801-86 + Sound Blaster 16","Mate-X PCM + Sound Blaster 16","PC9801-118 + Sound Blaster 16",
                    "PC9801-86 + Mate-X PCM(B460) + Sound Blaster 16","PC9801-86 + 118(B460) + Sound Blaster 16",
                    "AMD-98","WaveStar","Otomi-chanx2","Otomi-chanx2 + 86"));
            items.add(choice(SOUND,"np2kai_BEEP_vol","BEEP 蜂鸣器","3",new String[]{"0","1","2","3"},"关闭","低","中","高"));
            items.add(range("np2kai_volume_F","FM",64,0,128));
            items.add(range("np2kai_volume_S","SSG / PSG",28,0,128));
            items.add(range("np2kai_volume_A","ADPCM",64,0,128));
            items.add(range("np2kai_volume_P","PCM",92,0,128));
            items.add(range("np2kai_volume_R","节奏音源",64,0,128));
            items.add(new Option(ADVANCED_SOUND,"np2kai_usefmgen","FM 合成引擎","fmgen","fmgen","Default"));
            items.add(choice(ADVANCED_SOUND,"np2kai_118ROM","118 声卡 ROM","ON",new String[]{"ON","OFF"},"启用","禁用"));
            items.add(choice(ADVANCED_SOUND,"np2kai_jast_snd","JastSound PCM","OFF",new String[]{"OFF","ON"},"关闭","开启"));
            items.add(choice(ADVANCED_SOUND,"np2kai_Seek_Snd","软驱寻道音效","OFF",new String[]{"OFF","ON"},"关闭","开启"));
            items.add(range("np2kai_Seek_Vol","软驱寻道音量",0,0,128));
        } else {
            items.add(choice(MACHINE,"m88_clock","CPU 频率","40",new String[]{"40","80"},"4 MHz","8 MHz"));
            items.add(choice(MACHINE,"m88_erambanks","扩展内存","0",new String[]{"0","4","16","32"},"关闭","128 KB","512 KB","1 MB"));
            items.add(choice(SOUND,"m88_board","音源硬件","OPNA",new String[]{"OPNA","OPN"},"OPNA（YM2608）","OPN（YM2203）"));
            items.add(choice(SOUND,"m88_beep","BEEP 蜂鸣器","ON",new String[]{"ON","OFF"},"开启","关闭"));
            items.add(range("m88_fm","FM",0,-40,20));
            items.add(range("m88_ssg","SSG / PSG",0,-40,20));
            items.add(range("m88_adpcm","ADPCM（OPNA）",0,-40,20));
            items.add(range("m88_rhythm","节奏音源（OPNA）",0,-40,20));
        }
        items.add(choice(SOUND,"midi_enabled",platform==98 ? "MIDI（MPU-98）" : "MIDI（C2/C3 串行接口）","OFF",new String[]{"OFF","ON"},"关闭","SF2 本机合成"));
        items.add(range("midi_volume","MIDI 音量",80,0,100));
        items.add(range("output_volume","总输出音量",100,0,100));
        return items;
    }
    static String encode(int platform, Map<String,String> settings, int mode) {
        StringBuilder result = new StringBuilder();
        for (Option option : all(platform)) result.append(option.key).append('=').append(option.value(settings,mode)).append('\n');
        return result.toString();
    }
}
