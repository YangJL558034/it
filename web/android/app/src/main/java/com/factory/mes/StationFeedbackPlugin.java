package com.factory.mes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "StationFeedback")
public class StationFeedbackPlugin extends Plugin {
    @PluginMethod public void alarm(PluginCall call) {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400);
            new Handler(Looper.getMainLooper()).postDelayed(tone::release, 600);
            call.resolve();
        } catch (Exception ex) { call.reject("无法播放提示音"); }
    }
}
