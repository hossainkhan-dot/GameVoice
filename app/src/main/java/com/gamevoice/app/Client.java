package com.gamevoice.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import rikka.shizuku.Shizuku;

public class Client {
    public static volatile IUserService svc;
    public static Runnable onChange;

    private static MediaPlayer mon;     // local "self-monitor" player (your own speaker)
    private static int monPct = 30;

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = IUserService.Stub.asInterface(b);
            if (onChange != null) onChange.run();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            svc = null;
            if (onChange != null) onChange.run();
        }
    };

    public static void bind(Context ctx) {
        if (svc != null) return;
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(ctx.getPackageName(), InjectService.class.getName()))
                .daemon(false).processNameSuffix("inject").version(1);
        Shizuku.bindUserService(args, conn);
    }

    /** Sends the sound to everyone (mic injection) and, if enabled, also to your own speaker. */
    public static String play(Context ctx, File f) {
        IUserService s = svc;
        if (s == null) return "Shizuku সংযুক্ত নয়";
        String r;
        try {
            r = s.play(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY));
        } catch (Throwable e) { return "ERR: " + e; }
        // play() returns once decoding is done and streaming has started, so start the local copy now.
        if ("ok".equals(r)) startMonitor(ctx, f); else stopMonitor();
        return r;
    }

    public static void stop() {
        try { if (svc != null) svc.stop(); } catch (Throwable ignored) {}
        stopMonitor();
    }

    /** Apply a new self-monitor level (0 = off) - also affects a sound that is already playing. */
    public static synchronized void setMonitor(Context ctx, int pct) {
        monPct = pct;
        SoundStore.setMonitor(ctx, pct);
        MediaPlayer m = mon;
        if (m == null) return;
        if (pct <= 0) { stopMonitor(); return; }
        try { m.setVolume(pct / 100f, pct / 100f); } catch (Throwable ignored) {}
    }

    public static synchronized void loadMonitor(Context ctx) { monPct = SoundStore.getMonitor(ctx); }
    public static int getMonitor() { return monPct; }

    private static synchronized void startMonitor(Context ctx, File f) {
        stopMonitor();
        if (monPct <= 0) return;
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(f.getPath());
            mp.setVolume(monPct / 100f, monPct / 100f);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> release(m));
            mp.setOnErrorListener((m, w, e) -> { release(m); return true; });
            mon = mp;
            mp.prepareAsync();
        } catch (Throwable ignored) { mon = null; }
    }

    private static synchronized void release(MediaPlayer m) {
        try { m.release(); } catch (Throwable ignored) {}
        if (mon == m) mon = null;
    }

    private static synchronized void stopMonitor() {
        MediaPlayer m = mon;
        mon = null;
        if (m != null) {
            try { m.stop(); } catch (Throwable ignored) {}
            try { m.release(); } catch (Throwable ignored) {}
        }
    }
}
