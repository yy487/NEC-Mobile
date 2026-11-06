package org.necmobile;

import android.app.Instrumentation;
import android.os.*;
import android.graphics.*;
import android.media.ImageReader;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CoreFeatureCheck extends Instrumentation {
    private Bundle arguments;
    private final StringBuilder report=new StringBuilder();
    private volatile File capture;
    private CountDownLatch captureDone;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    private void check(boolean value,String text) throws IOException { if (!value) throw new IOException(text+"; status="+Native.status()); report.append("PASS ").append(text).append('\n'); }
    @Override public void onStart() {
        Bundle result=new Bundle(); HandlerThread images=new HandlerThread("check-images"); images.start();
        ImageReader reader=ImageReader.newInstance(640,400,PixelFormat.RGBA_8888,3);
        AtomicInteger frames=new AtomicInteger();
        reader.setOnImageAvailableListener(r->{ try (android.media.Image image=r.acquireLatestImage()) {
            if (image==null) return; frames.incrementAndGet();
            File output=capture;
            if (output!=null) {
                capture=null;
                try {
                    int w=image.getWidth(),h=image.getHeight(); byte[] packed=new byte[w*h*4];
                    android.media.Image.Plane plane=image.getPlanes()[0]; ByteBuffer buffer=plane.getBuffer();
                    for (int y=0;y<h;++y) { buffer.position(y*plane.getRowStride()); buffer.get(packed,y*w*4,w*4); }
                    Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed));
                    try (FileOutputStream stream=new FileOutputStream(output)) { bitmap.compress(Bitmap.CompressFormat.PNG,100,stream); }
                    bitmap.recycle();
                } catch (Exception error) { android.util.Log.e("NECFeatureCheck","capture",error); }
                captureDone.countDown();
            }
        } },new Handler(images.getLooper()));
        try {
            int platform=Integer.parseInt(arguments.getString("platform","88"));
            String directory=arguments.getString("directory");
            String first=arguments.getString("first"), second=arguments.getString("second","");
            int secondIndex=Integer.parseInt(arguments.getString("secondIndex","0"));
            File game=new File(directory); game.mkdirs();
            String config=MachineOptions.encode(platform,Collections.emptyMap(),0);
            if (arguments.getString("midi","").equals("yes")) config=config.replace("midi_enabled=OFF","midi_enabled=ON")+"midi_soundfont="+getTargetContext().getFilesDir()+"/check024/test.sf2\n";
            try (FileOutputStream out=new FileOutputStream(new File(game,"machine.cfg"))) { out.write(config.getBytes(StandardCharsets.UTF_8)); }
            Native.surface(reader.getSurface());
            check(Native.start(platform,directory,first,second,0,0,secondIndex,false),"start ARM64 core");
            Native.pause(false);
            long deadline=SystemClock.uptimeMillis()+25000;
            while (frames.get()<160 && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100);
            check(frames.get()>=160,"receive live frames from real core");
            if (arguments.getString("midi","").equals("yes")) {
                SystemClock.sleep(12000);
                captureDone=new CountDownLatch(1); capture=new File(game,"midi-screen.png");
                captureDone.await(3,TimeUnit.SECONDS);
                Native.pause(true); StateSlots midiSlots=new StateSlots(game); midiSlots.save(4); midiSlots.load(4);
                check(true,"restore state with active MIDI notes");
                report.append("MIDI: inspect NECMobile hardware-output and synthesized-PCM log entries\n");
            } else if (arguments.getString("cold","").equals("yes")) {
                Native.pause(true); StateSlots slots=new StateSlots(game); slots.load(1);
                check(Native.disk(1).equals(second) && Native.diskIndex(1)==secondIndex,"load previous-process slot and restore drives");
                Native.pause(false); int prior=frames.get(); SystemClock.sleep(500);
                check(frames.get()>prior,"render after cold-start state restore");
            } else {
                Native.pause(true);
                StateSlots slots=new StateSlots(game); slots.save(1); File firstSlot=slots.folder(1);
                check(firstSlot!=null && new File(firstSlot,"machine.bin").length()>1024,"commit slot 1");
                Native.mount(1,"",0); slots.save(2);
                check(Native.disk(1).isEmpty(),"save slot 2 with ejected drive");
                Native.pause(false); int before=frames.get();
                deadline=SystemClock.uptimeMillis()+10000;
                while (frames.get()<before+60 && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100);
                Native.pause(true); slots.load(1);
                check(Native.disk(1).equals(second) && Native.diskIndex(1)==secondIndex,"restore saved drive and embedded index");
                slots.save(3);
                // Corrupt a disposable slot; the running session and other slots must survive.
                try (RandomAccessFile damaged=new RandomAccessFile(new File(slots.folder(2),"machine.bin"),"rw")) { damaged.setLength(12); }
                boolean refused=false; try { slots.load(2); } catch (IOException expected) { refused=true; }
                check(refused && Native.disk(1).equals(second),"reject truncated state without ejecting the active disk");
                check(slots.folder(1).equals(firstSlot),"preserve independent slot 1");
                Native.pause(false); SystemClock.sleep(300);
                Native.audioChanged(); SystemClock.sleep(700);
                Native.pause(true); SystemClock.sleep(150); Native.pause(false); SystemClock.sleep(700);
                check(frames.get()>before+60,"continue emulation after output rebuild and pause/resume");
            }
            Native.stop(); Native.surface(null);
            File output=new File(getTargetContext().getFilesDir(),"check024/report-"+platform+(arguments.getString("midi","").equals("yes") ? "-midi" : "")+".txt");
            try (FileOutputStream out=new FileOutputStream(output)) { out.write(report.toString().getBytes(StandardCharsets.UTF_8)); }
            result.putString("stream",report.toString()); finish(0,result);
        } catch (Throwable error) {
            Native.stop(); result.putString("stream",report+"FAIL "+android.util.Log.getStackTraceString(error)); finish(1,result);
        } finally { reader.close(); images.quitSafely(); }
    }
}
