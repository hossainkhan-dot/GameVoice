package com.gamevoice.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import java.io.File;

/** "টেস্ট" button of the folder volume dialogs: plays a file on YOUR speaker only (never sent to the mic). */
public final class TestPlayer {
    private static MediaPlayer mp;
    private static boolean ready;
    private static float vol = 1f;
    public static Runnable onChange;

    private TestPlayer() {}

    public static synchronized boolean isPlaying() { return mp != null; }

    public static synchronized void play(Context c, File f, int pct) {
        stop();
        vol = Math.max(1, Math.min(100, pct)) / 100f;
        try {
            final MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(f.getPath());
            p.setVolume(vol, vol);
            p.setOnPreparedListener(m -> { synchronized (TestPlayer.class) { if (mp == m) { ready = true; m.start(); } } });
            p.setOnCompletionListener(m -> { stopIf(m); });
            p.setOnErrorListener((m, w, e) -> { stopIf(m); return true; });
            mp = p;
            ready = false;
            p.prepareAsync();
        } catch (Throwable e) {
            mp = null;
        }
        fire();
    }

    /** Called while the slider moves: the loudness changes at once. */
    public static synchronized void setVolume(int pct) {
        vol = Math.max(1, Math.min(100, pct)) / 100f;
        try { if (mp != null) mp.setVolume(vol, vol); } catch (Throwable ignored) {}
    }

    private static synchronized void stopIf(MediaPlayer m) {
        if (mp == m) stop();
    }

    public static synchronized void stop() {
        MediaPlayer p = mp;
        mp = null;
        ready = false;
        if (p != null) {
            try { p.setOnCompletionListener(null); } catch (Throwable ignored) {}
            try { p.stop(); } catch (Throwable ignored) {}
            try { p.release(); } catch (Throwable ignored) {}
        }
        fire();
    }

    private static void fire() {
        final Runnable r = onChange;
        if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
