package org.necmobile;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;

final class StateSlots {
    static final int COUNT=6;
    private final File root;
    StateSlots(File gameDirectory) { root=new File(gameDirectory,"states"); root.mkdirs(); }
    private AtomicFile pointer(int slot) {
        if (slot<1 || slot>COUNT) throw new IllegalArgumentException("无效存档槽");
        return new AtomicFile(new File(root,"slot-"+slot+".txt"));
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
