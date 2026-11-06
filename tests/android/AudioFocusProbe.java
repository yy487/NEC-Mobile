package org.necmobile;

import android.content.*;
import android.media.*;
import android.os.*;
import android.util.Log;

public final class AudioFocusProbe extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        PendingResult pending=goAsync();
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        AudioFocusRequest focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(ignored->{}).build();
        Log.i("NECAudioProbe","temporary focus request="+audio.requestAudioFocus(focus));
        new Handler(Looper.getMainLooper()).postDelayed(()-> { audio.abandonAudioFocusRequest(focus); Log.i("NECAudioProbe","temporary focus released"); pending.finish(); },8000);
    }
}
