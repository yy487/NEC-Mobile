package org.necmobile;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.AtomicFile;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

final class MemoryEditorUi {
    private static final int TEXT=0xffeeeeee, MUTED=0xffa0aaa4;
    private static final int[] WIDTHS={1,2,4,1,2,4,1,2,4,2,4};
    private static final String[] TYPES = {"8 位整数", "16 位整数", "32 位整数", "8 位有符号", "16 位有符号", "32 位有符号",
            "BCD · 1 字节", "BCD · 2 字节（低位在前）", "BCD · 4 字节（低位在前）", "BCD · 2 字节（高位在前）", "BCD · 4 字节（高位在前）"};
    private static final String[] RELATIONS = {"等于", "未知初始值", "增加", "减少", "变化", "未变", "大于", "小于", "范围内", "增加了指定值", "减少了指定值"};
    private final Activity activity;
    private final Supplier<AlertDialog.Builder> dialogs;
    private final Executor io;
    private final Runnable changed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicFile file;
    private List<Entry> entries = new ArrayList<>();
    private JSONArray regions = new JSONArray(), saved = new JSONArray();
    private JSONObject state = new JSONObject();
    private String storageError = "", valueText = "", upperText = "";
    private boolean busy, table;
    private long epoch, selectedRegion;
    private int typeChoice, relationChoice;
    private AlertDialog main;
    private LinearLayout body;
    private long page;

    private static final class Entry {
        String id = "", name = "";
        long region, size, offset, value;
        int type;
        Entry copy() {
            Entry e = new Entry(); e.id=id; e.name=name; e.region=region; e.size=size; e.offset=offset; e.value=value; e.type=type; return e;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("id",id).put("name",name).put("region",region).put("size",size).put("offset",offset).put("type",type).put("value",value);
        }
        static Entry read(JSONObject row, boolean stored) throws Exception {
            Entry e = new Entry();
            e.region=row.getLong("region"); e.size=row.getLong("size"); e.offset=row.getLong("offset"); e.type=row.getInt("type"); e.value=row.optLong("value");
            if (e.region<0 || e.region>0xffffffffL || e.type<0 || e.type>=TYPES.length || e.size<=0 || e.offset<0 || e.offset>e.size-width(e.type)) throw new IOException("修改项地址无效");
            if (stored) {
                e.id=row.getString("id"); UUID.fromString(e.id); e.name=row.getString("name").trim();
                if (e.name.isEmpty() || e.name.length()>80) throw new IOException("修改项名称无效");
                checkValue(e.type,e.value);
            }
            return e;
        }
    }
    MemoryEditorUi(Activity activity, Supplier<AlertDialog.Builder> dialogs, Executor io, File directory, Runnable changed) {
        this.activity=activity; this.dialogs=dialogs; this.io=io; this.changed=changed;
        file=new AtomicFile(new File(directory,"memory-edits.json"));
    }
    void open() {
        if (main!=null && main.isShowing()) return;
        body=Ui.column(activity);
        int p=Ui.dp(activity,14); body.setPadding(p,p,p,p);
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); scroll.addView(body);
        main=dialogs.get().setTitle("数值修改器").setView(scroll).setNegativeButton("返回游戏",null).show();
        fitForm(main);
        body.addView(Ui.text(activity,"正在读取游戏内存…",16,TEXT));
        if (busy) return;
        busy=true;
        io.execute(()-> {
            List<Entry> loaded=new ArrayList<>(); String error="";
            try {
                if (file.getBaseFile().exists() || new File(file.getBaseFile()+".bak").exists()) {
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    try (InputStream in=file.openRead()) { Library.copy(in,bytes,128*1024); }
                    JSONObject root=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                    if (root.getInt("version")!=1) throw new IOException("修改表版本不支持");
                    JSONArray array=root.getJSONArray("entries");
                    if (array.length()>128) throw new IOException("修改表项目过多");
                    Set<String> ids=new HashSet<>();
                    for (int i=0;i<array.length();++i) { Entry e=Entry.read(array.getJSONObject(i),true); if (!ids.add(e.id)) throw new IOException("修改项重复"); loaded.add(e); }
                }
            } catch (Exception problem) { error=problem.getMessage(); }
            List<Entry> result=loaded; String failure=error;
            ui.post(()-> { busy=false; storageError=failure==null ? "修改表读取失败" : failure; entries=storageError.isEmpty()?result:new ArrayList<>(); if (visible()) refresh(0,null); });
        });
    }
    private boolean visible() { return main!=null && main.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }
    private void error(String text) { if (!activity.isFinishing() && !activity.isDestroyed()) dialogs.get().setTitle("数值修改器").setMessage(text).setPositiveButton("知道了",null).show(); }
    private static JSONObject response(String text) throws Exception {
        JSONObject row;
        try { row=new JSONObject(text); } catch (JSONException error) { throw new IOException("修改器会话已结束，请重新打开"); }
        if (row.has("error")) throw new IOException(row.getString("error"));
        return row;
    }
    private long[] savedRefs(List<Entry> list) {
        long[] out=new long[list.size()*4]; int i=0;
        for (Entry e:list) { out[i++]=e.region; out[i++]=e.size; out[i++]=e.offset; out[i++]=e.type; }
        return out;
    }
    private void refresh(long requestedPage, String notice) { request(0,new long[]{requestedPage},notice,null); }
    private void request(int action, long[] args, String notice, List<Entry> persist) {
        if (busy || !visible()) return;
        busy=true; render("正在处理…");
        List<Entry> selected=new ArrayList<>(entries);
        io.execute(()-> {
            JSONObject result=null; JSONArray values=new JSONArray(); String failure=null; boolean stored=false;
            try {
                result=response(Native.memory(action,args));
                if (persist!=null) { writeTable(persist);stored=true; }
                values=response(Native.memory(6,savedRefs(persist!=null ? persist : selected))).getJSONArray("saved");
            } catch (Exception problem) { failure=(result!=null && persist!=null && !stored ? "数值操作已完成，但修改表未保存：" : "")+problem.getMessage(); }
            JSONObject completed=result; JSONArray current=values; String message=failure; boolean savedTable=stored;
            ui.post(()-> {
                busy=false;
                if (savedTable) { entries=new ArrayList<>(persist);storageError=""; }
                if (completed!=null && (action==3 || action==5 || (action==4 && args[6]!=0))) changed.run();
                if (!visible()) return;
                long previous=epoch;
                if (completed!=null) state=completed;
                epoch=state.optLong("epoch"); regions=state.optJSONArray("regions");
                if (regions==null) regions=new JSONArray();
                saved=current; page=state.optLong("page");
                if (state.optBoolean("searched")) { selectedRegion=state.optLong("region"); int mask=state.optInt("mask"); typeChoice=Integer.bitCount(mask)==1 ? Integer.numberOfTrailingZeros(mask)+1 : 0; }
                String note=notice;
                if (previous!=0 && previous!=epoch) note="读档或复位后，候选地址和锁定已清空；修改表仍保留。";
                render(note);
                if (message!=null) error(message);
            });
        });
    }
    private TextView text(String value) { TextView t=Ui.text(activity,value,14,TEXT); t.setPadding(0,Ui.dp(activity,5),0,Ui.dp(activity,5)); return t; }
    private ScrollView formView(LinearLayout form) {
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);return scroll;
    }
    private void fitForm(AlertDialog dialog) {
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        // The main dialog gains its text editors after the initial loading view.
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }
    private EditText input(String hint,String value,boolean number) {
        EditText e=new EditText(activity); e.setHint(hint); e.setSingleLine(true); e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setText(value);
        if (number) {
            e.setKeyListener(DigitsKeyListener.getInstance("0123456789-+xXaAbBcCdDeEfF"));
            e.setRawInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);
            e.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        }
        else e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }
    private void remember(EditText input,Consumer<String> changed) {
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { changed.accept(s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
    }
    private Spinner spinner(List<String> values,int selected,IntConsumer choose) {
        Spinner spinner=new Spinner(activity);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,values) {
            @Override public View getView(int position,View old,ViewGroup parent) {
                TextView view=(TextView)super.getView(position,old,parent);view.setTextSize(13);return view;
            }
            @Override public View getDropDownView(int position,View old,ViewGroup parent) {
                TextView view=(TextView)super.getDropDownView(position,old,parent);view.setTextSize(14);return view;
            }
        };
        spinner.setAdapter(adapter); spinner.setSelection(Math.max(0,selected));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { if (spinner.isShown()&&!busy) choose.accept(position); }
        });
        return spinner;
    }
    private JSONObject region(long id) {
        for (int i=0;i<regions.length();++i) { JSONObject r=regions.optJSONObject(i); if (r!=null && r.optLong("id")==id) return r; }
        return null;
    }
    private String regionLabel(JSONObject r) {
        String name=r.optString("name").replace("PC-98 Main RAM","PC-98 主 RAM").replace("PC-98 Extended RAM 16M+","PC-98 扩展 RAM 16M+")
                .replace("PC-98 Extended RAM","PC-98 扩展 RAM").replace("PC-88 Main RAM","PC-88 主 RAM").replace("PC-88 Disk CPU RAM","PC-88 副 CPU RAM").replace("PC-88 RAM Bank","PC-88 内存 Bank");
        return name+" · "+(r.optLong("size")/1024)+" KB";
    }
    private String address(Entry e) {
        JSONObject r=region(e.region);
        return (r==null ? "区域 "+e.region : regionLabel(r))+" · 0x"+Long.toHexString((r==null ? 0 : r.optLong("base"))+e.offset).toUpperCase(Locale.ROOT)+" · "+TYPES[e.type];
    }
    private void button(LinearLayout row,String title,Runnable action,boolean enabled) {
        Button b=Ui.button(activity,title,action); b.setEnabled(enabled && !busy); row.addView(b,new LinearLayout.LayoutParams(0,Ui.dp(activity,36),1));
    }
    private void render(String notice) {
        if (!visible()) return;
        body.removeAllViews();
        LinearLayout tabs=Ui.row(activity); body.addView(tabs);
        button(tabs,table ? "搜索数值" : "● 搜索数值",()->{table=false;render(null);},true);
        button(tabs,table ? "● 修改表（"+entries.size()+"）" : "修改表（"+entries.size()+"）",()->{table=true;refresh(page,null);},true);
        if (notice!=null) body.addView(text(notice));
        if (!storageError.isEmpty()) body.addView(text("修改表读取失败："+storageError+"。搜索和临时修改仍可使用。"));
        if (state.optBoolean("undo") || state.optInt("locks")>0) {
            LinearLayout tools=Ui.row(activity); body.addView(tools);
            button(tools,"撤销上次修改",()->request(5,new long[]{epoch},"已撤销上次修改，并停止对应锁定",null),state.optBoolean("undo"));
            button(tools,"停止锁定（"+state.optInt("locks")+"）",()->request(7,new long[0],"全部锁定已停止",null),state.optInt("locks")>0);
        }
        if (table) renderTable(); else renderSearch();
    }
    private void renderSearch() {
        boolean searched=state.optBoolean("searched");
        List<String> names=new ArrayList<>(); List<Long> ids=new ArrayList<>();
        for (int i=0;i<regions.length();++i) { JSONObject r=regions.optJSONObject(i); names.add(regionLabel(r)); ids.add(r.optLong("id")); }
        names.add("全部游戏 RAM"); ids.add(-1L);
        LinearLayout choices=Ui.row(activity);body.addView(choices);
        Spinner area=spinner(names,ids.indexOf(selectedRegion),index->selectedRegion=ids.get(index)); area.setEnabled(!busy&&!searched); choices.addView(area,new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));
        List<String> formats=new ArrayList<>(); formats.add("自动 · 整数与 BCD"); formats.addAll(Arrays.asList(TYPES));
        Spinner type=spinner(formats,typeChoice,index->typeChoice=index); type.setEnabled(!busy&&!searched); choices.addView(type,new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));
        EditText value=input("当前数值／变化量",valueText,true),upper=input("范围上限",upperText,true);value.setTextSize(14);upper.setTextSize(14);value.setEnabled(!busy);upper.setEnabled(!busy);
        LinearLayout filter=Ui.row(activity);body.addView(filter);
        Spinner relation=spinner(Arrays.asList(RELATIONS),relationChoice,index->{relationChoice=index;value.setVisibility(index==0||index>=6?View.VISIBLE:View.GONE);upper.setVisibility(index==8?View.VISIBLE:View.GONE);});
        relation.setEnabled(!busy);filter.addView(relation,new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));filter.addView(value,new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));body.addView(upper);
        value.setVisibility(relationChoice==0||relationChoice>=6?View.VISIBLE:View.GONE);upper.setVisibility(relationChoice==8?View.VISIBLE:View.GONE);
        remember(value,s->valueText=s); remember(upper,s->upperText=s);
        LinearLayout controls=Ui.row(activity); body.addView(controls);
        button(controls,searched ? "再次筛选" : "开始搜索",()-> {
            valueText=value.getText().toString().trim(); upperText=upper.getText().toString().trim();
            try {
                boolean number=relationChoice==0 || relationChoice>=6;
                long target=number ? number(valueText) : 0, maximum=relationChoice==8 ? number(upperText) : 0;
                int mask=searched ? state.optInt("mask") : typeChoice==0 ? 0x7ff : 1<<(typeChoice-1);
                request(1,new long[]{epoch,relationChoice,mask,selectedRegion,target,maximum},null,null);
            } catch (Exception error) { error("请填写有效数值。十进制或 0x 开头的十六进制均可。 "); }
        },regions.length()>0);
        button(controls,"重新搜索",()->{valueText=value.getText().toString();upperText=upper.getText().toString();request(2,new long[]{epoch},"已清除搜索，可重新选择区域和格式",null);},searched);
        body.addView(text(searched ? "候选地址："+state.optLong("count")+" · 第 "+(page+1)+" 页" : "输入游戏当前数值；不知道初始数值时选择“未知初始值”。返回游戏使数值变化后，再次筛选。"));
        if (searched) {
            JSONArray hits=state.optJSONArray("hits");
            if (hits!=null) for (int i=0;i<hits.length();++i) {
                try {
                    JSONObject hit=hits.getJSONObject(i); Entry e=Entry.read(hit,false);
                    Button row=Ui.button(activity,(hit.isNull("value") ? "当前值不可解码" : hit.getLong("value"))+ (hit.optBoolean("locked") ? " · 锁定中" : "")+"\n"+address(e),()->edit(e,hit.optBoolean("locked")));
                    row.setAllCaps(false); row.setEnabled(!busy); body.addView(row);
                } catch (Exception ignored) { }
            }
            LinearLayout pages=Ui.row(activity); body.addView(pages);
            button(pages,"上一页",()->refresh(page-1,null),page>0);
            button(pages,"下一页",()->refresh(page+1,null),(page+1)*50<state.optLong("count"));
        }
        body.addView(text("默认搜索主 RAM。BCD 用两个十进制数字组成一个字节，部分老游戏采用此格式。部分游戏在下次行动或切换页面后刷新画面数值。修改前可先保存即时档。"));
    }
    private void renderTable() {
        LinearLayout controls=Ui.row(activity); body.addView(controls);
        button(controls,"手动添加地址",this::manual,storageError.isEmpty() && regions.length()>0 && entries.size()<128);
        button(controls,"刷新当前值",()->refresh(page,null),true);
        if (!storageError.isEmpty()) body.addView(Ui.button(activity,"重建修改表",()->dialogs.get().setTitle("重建修改表？").setMessage("将替换当前无法读取的修改表。游戏进度和即时档不受影响。")
                .setPositiveButton("重建",(d,w)->saveOnly(new ArrayList<>(),"修改表已重建")).setNegativeButton("取消",null).show()));
        if (entries.isEmpty()) body.addView(text("搜索到数值后，可在修改时勾选保存；也可以手动添加已知地址。"));
        for (int i=0;i<entries.size();++i) {
            Entry e=entries.get(i); JSONObject live=saved.optJSONObject(i);
            boolean valid=live!=null && live.optBoolean("valid"), locked=live!=null && live.optBoolean("locked");
            String current=valid ? live.optString("value") : "地址或格式已失效";
            Button row=Ui.button(activity,e.name+" · 当前 "+current+(locked ? " · 锁定中" : "")+"\n目标 "+e.value+" · "+address(e),()-> {
                dialogs.get().setTitle(e.name).setItems(new String[]{"修改数值／名称",locked ? "停止锁定" : "应用并锁定目标值","删除修改项"},(d,which)-> {
                    if (which==0) edit(e,locked);
                    else if (which==1) {
                        if (!valid && !locked) { error("地址或数值格式已改变，请重新搜索"); return; }
                        request(4,args(e,e.value,!locked),locked ? "已停止锁定" : "已应用并锁定",null);
                    } else dialogs.get().setTitle("删除“"+e.name+"”？").setMessage("同时停止这个地址的锁定。")
                            .setPositiveButton("删除",(x,y)->{List<Entry> list=new ArrayList<>(entries);list.removeIf(item->item.id.equals(e.id));request(4,args(e,e.value,false),"修改项已删除",list);})
                            .setNegativeButton("取消",null).show();
                }).setNegativeButton("返回",null).show();
            });
            row.setAllCaps(false); row.setEnabled(!busy); body.addView(row);
        }
        body.addView(text("修改表按游戏保存。每次启动、读档或复位后，锁定保持关闭，需要时再启用；保存的地址不保证在所有进度中都指向同一个数值。"));
    }
    private long[] args(Entry e,long value,boolean lock) { return new long[]{epoch,e.region,e.size,e.offset,e.type,value,lock?1:0}; }
    private void edit(Entry source,boolean locked) {
        Entry e=source.copy(); boolean stored=!e.id.isEmpty();
        LinearLayout form=Ui.column(activity); int p=Ui.dp(activity,16);form.setPadding(p,0,p,0);form.addView(text(address(e)));
        EditText value=input("新数值",Long.toString(e.value),true),name=input("名称，例如金钱、主角 HP",e.name,false);form.addView(value);
        CheckBox freeze=new CheckBox(activity);freeze.setText("锁定此值");freeze.setTextColor(TEXT);freeze.setChecked(locked);form.addView(freeze);
        CheckBox keep=new CheckBox(activity);keep.setText("保存到本游戏修改表");keep.setTextColor(TEXT);keep.setChecked(stored);keep.setEnabled(!stored && storageError.isEmpty() && entries.size()<128);form.addView(keep);form.addView(name);name.setEnabled(keep.isChecked());
        keep.setOnCheckedChangeListener((button,checked)->name.setEnabled(checked));
        AlertDialog editor=dialogs.get().setTitle(stored ? e.name : "修改数值").setView(formView(form)).setPositiveButton("应用",null).setNeutralButton("仅保存修改项",null).setNegativeButton("取消",null).show();
        fitForm(editor);
        Runnable prepare=()-> {
            try {
                e.value=number(value.getText().toString());checkValue(e.type,e.value);
                e.name=name.getText().toString().trim();if (e.name.isEmpty()) e.name="修改项 "+(entries.size()+1);
                if (e.name.length()>80) throw new IOException("名称最多 80 个字符");
                if (e.id.isEmpty()) e.id=UUID.randomUUID().toString();
            } catch (Exception problem) { throw new IllegalArgumentException(problem instanceof NumberFormatException ? "请填写有效整数" : problem.getMessage()==null ? "数值无效" : problem.getMessage()); }
        };
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v-> {
            try { prepare.run(); } catch (Exception problem) { error(problem.getMessage());return; }
            editor.dismiss();
            long[] request=freeze.isChecked() ? args(e,e.value,true) : new long[]{epoch,e.region,e.size,e.offset,e.type,e.value};
            request(freeze.isChecked()?4:3,request,freeze.isChecked()?"数值已修改并锁定":"数值已修改",keep.isChecked()?updated(e):null);
        });
        editor.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(storageError.isEmpty() && (stored || entries.size()<128));
        editor.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v-> {
            try { prepare.run(); } catch (Exception problem) { error(problem.getMessage());return; }
            editor.dismiss();replace(e,"修改项已保存，尚未应用到游戏");
        });
    }
    private void replace(Entry e,String notice) {
        saveOnly(updated(e),notice);
    }
    private List<Entry> updated(Entry e) {
        List<Entry> list=new ArrayList<>(entries);int at=-1;
        for (int i=0;i<list.size();++i) if (list.get(i).id.equals(e.id)) at=i;
        if (at<0) list.add(e.copy()); else list.set(at,e.copy());
        return list;
    }
    private void writeTable(List<Entry> list) throws Exception {
        if (list.size()>128) throw new IOException("最多保存 128 个修改项");
        JSONArray rows=new JSONArray();for (Entry e:list) rows.put(e.json());
        byte[] data=new JSONObject().put("version",1).put("entries",rows).toString(2).getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream=file.startWrite();
        try { stream.write(data);file.finishWrite(stream); }
        catch (Exception problem) { file.failWrite(stream);throw problem; }
    }
    private void saveOnly(List<Entry> list,String notice) {
        if (busy || !visible()) return;
        busy=true;render("正在保存修改表…");
        io.execute(()-> {
            String failure=null;
            try {
                writeTable(list);
            } catch (Exception problem) { failure=problem.getMessage(); }
            String message=failure;
            ui.post(()-> {
                busy=false;if (message==null) { entries=new ArrayList<>(list);storageError=""; }
                if (!visible()) return;
                if (message!=null) { render(null);error("修改表保存失败："+message); }
                else refresh(page,notice);
            });
        });
    }
    private void manual() {
        LinearLayout form=Ui.column(activity);int p=Ui.dp(activity,16);form.setPadding(p,0,p,0);
        List<String> areas=new ArrayList<>();for (int i=0;i<regions.length();++i) {JSONObject r=regions.optJSONObject(i);areas.add(regionLabel(r)+" · 起始 0x"+Long.toHexString(r.optLong("base")).toUpperCase(Locale.ROOT));}
        Spinner area=spinner(areas,0,index->{}),type=spinner(Arrays.asList(TYPES),1,index->{});form.addView(area);form.addView(type);
        EditText address=input("地址（十六进制，例如 1A2B）","",false),value=input("目标数值","0",true),name=input("名称","",false);form.addView(address);form.addView(value);form.addView(name);
        AlertDialog add=dialogs.get().setTitle("手动添加地址").setView(formView(form)).setPositiveButton("保存修改项",null).setNegativeButton("取消",null).show();
        fitForm(add);
        add.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v-> {
            try {
                JSONObject r=regions.getJSONObject(area.getSelectedItemPosition());Entry e=new Entry();e.id=UUID.randomUUID().toString();e.region=r.getLong("id");e.size=r.getLong("size");e.type=type.getSelectedItemPosition();
                String text=address.getText().toString().trim();if (text.startsWith("0x") || text.startsWith("0X")) text=text.substring(2);
                e.offset=Long.parseLong(text,16)-r.getLong("base");e.value=number(value.getText().toString());checkValue(e.type,e.value);
                if (e.offset<0 || e.offset>e.size-width(e.type)) throw new IOException("地址超出所选内存区域");
                e.name=name.getText().toString().trim();if (e.name.isEmpty()) e.name="修改项 "+(entries.size()+1);if (e.name.length()>80) throw new IOException("名称最多 80 个字符");
                add.dismiss();replace(e,"修改项已保存，尚未应用到游戏");
            } catch (Exception problem) { error(problem.getMessage()==null?"地址或数值无效":problem.getMessage()); }
        });
    }
    private static int width(int type) { return WIDTHS[type]; }
    private static long number(String value) {
        String s=value.trim();boolean negative=s.startsWith("-");int start=(negative || s.startsWith("+"))?1:0;
        if (s.regionMatches(true,start,"0x",0,2)) { long n=Long.parseLong(s.substring(start+2),16);return negative?-n:n; }
        return Long.parseLong(s,10);
    }
    private static void checkValue(int type,long value) throws IOException {
        int bytes=width(type);long high=(1L<<(bytes*8))-1,low=0;
        if (type>=3 && type<6) {high>>=1;low=-high-1;}
        if (type>=6) {high=1;for (int i=0;i<bytes;++i) high*=100;--high;}
        if (value<low || value>high) throw new IOException("数值超出所选格式的范围");
    }
}
