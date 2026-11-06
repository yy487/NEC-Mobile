package org.necmobile;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SoundFonts {
    private final Context context;
    private final File root;
    SoundFonts(Context context) { this.context=context; root=new File(context.getFilesDir(),"midi"); root.mkdirs(); }
    File selected() {
        String name=context.getSharedPreferences("midi",0).getString("selected","");
        if (!name.matches("[0-9a-f-]{36}\\.sf2")) return null;
        File file=new File(root,name); return file.isFile() ? file : null;
    }
    List<File> list() {
        File[] files=root.listFiles(file -> file.isFile() && file.getName().matches("[0-9a-f-]{36}\\.sf2"));
        List<File> result=new ArrayList<>(files==null ? Collections.emptyList() : Arrays.asList(files));
        result.sort(Comparator.comparing(this::name)); return result;
    }
    String name(File file) {
        try {
            byte[] data=new AtomicFile(new File(root,file.getName()+".txt")).readFully();
            if (data.length<=1024) return new String(data,StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
        return "SF2 音色库";
    }
    void select(File file) throws IOException {
        if (!list().contains(file)) throw new IOException("音色库不存在");
        if (!context.getSharedPreferences("midi",0).edit().putString("selected",file.getName()).commit()) throw new IOException("音色库选择未能保存");
    }
    File importFile(Uri uri) throws Exception {
        String label=new Library(context,88).name(uri).replaceAll("[\\p{Cntrl}]","");
        if (!label.toLowerCase(Locale.ROOT).endsWith(".sf2")) throw new IOException("请选择 SF2 音色库，暂不支持 SF3、DLS 或 MT-32 ROM");
        if (label.length()>120) label=label.substring(0,116)+".sf2";
        File target=new File(root,UUID.randomUUID()+".sf2"), staging=new File(root,target.getName()+".import");
        try {
            try (InputStream input=context.getContentResolver().openInputStream(uri); FileOutputStream output=new FileOutputStream(staging)) {
                if (input==null || Library.copy(input,output,64L*1024*1024)==0) throw new IOException("音色库为空");
                output.getFD().sync();
            }
            if (!Native.checkSoundFont(staging.getAbsolutePath())) throw new IOException("此文件不是可用的 SF2 音色库，或包含当前合成器不支持的数据");
            AtomicFile title=new AtomicFile(new File(root,target.getName()+".txt")); FileOutputStream out=title.startWrite();
            try { out.write(label.getBytes(StandardCharsets.UTF_8)); title.finishWrite(out); }
            catch (Exception error) { title.failWrite(out); throw error; }
            android.system.Os.rename(staging.getAbsolutePath(),target.getAbsolutePath());
            select(target); return target;
        } catch (Exception error) {
            staging.delete(); target.delete(); new File(root,target.getName()+".txt").delete(); throw error;
        }
    }
    void remove(File file) throws IOException {
        if (!list().contains(file)) throw new IOException("音色库不存在");
        if (!file.delete()) throw new IOException("音色库未能删除");
        new File(root,file.getName()+".txt").delete();
        File active=selected();
        if (active==null) context.getSharedPreferences("midi",0).edit().remove("selected").apply();
    }
}
