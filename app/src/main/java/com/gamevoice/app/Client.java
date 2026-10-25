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
    /** null = not checked yet, TRUE = mic injection works, FALSE = this phone refuses it (use speaker mode). */
    public static volatile Boolean injectOk;
    private static boolean speakerMode, warned;

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = IUserService.Stub.asInterface(b);
            injectOk = null;
            if (onChange != null) onChange.run();
            // Find out in the background whether mic injection is possible at all on this phone.
            new Thread(() -> {
                try { injectOk = svc.ready(); } catch (Throwable e) { injectOk = null; }
                if (onChange != null) onChange.run();
            }, "gv-ready").start();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            svc = null;
            injectOk = null;
            if (onChange != null) onChange.run();
        }
    };

    public static void bind(Context ctx) {
        if (svc != null) return;
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(ctx.getPackageName(), InjectService.class.getName()))
                .daemon(false).processNameSuffix("inject").version(4); // bumped: the AIDL interface changed (music player methods)
        Shizuku.bindUserService(args, conn);
    }

    /** Sends the sound to everyone (mic injection) and, if enabled, also to your own speaker. */
    public static String play(Context ctx, File f) {
        IUserService s = svc;
        if (Boolean.FALSE.equals(injectOk)) return playSpeaker(ctx, f);
        if (s == null) return "Shizuku সংযুক্ত নয়";
        // Why did the previous sound fail? (decode / mic-injection errors happen on a background thread)
        try {
            String prev = s.status();
            if (prev != null && !prev.isEmpty())
                android.widget.Toast.makeText(ctx, prev, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
        String r;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            r = s.play(pfd, SoundStore.sendVolume(ctx, SoundStore.folderOf(ctx, f)));
        } catch (Throwable e) { return "ERR: " + e; }
        finally {
            // The remote side got its own duplicate; close ours so descriptors don't leak on every tap.
            if (pfd != null) { try { pfd.close(); } catch (Throwable ignored) {} }
        }
        // play() returns once decoding is done and streaming has started, so start the local copy now.
        if ("ok".equals(r)) startMonitor(ctx, f); else stopMonitor();
        return r;
    }

    /**
     * Fallback for phones whose Android refuses mic injection (e.g. Android 11 on some ROMs):
     * play the sound out loud at the folder's "other side" volume so the microphone picks it up.
     * Quality is lower and voice-chat echo cancellation may reduce it.
     */
    private static String playSpeaker(Context ctx, File f) {
        int pct = SoundStore.sendVolume(ctx, SoundStore.folderOf(ctx, f));
        if (!warned) {
            warned = true;
            android.widget.Toast.makeText(ctx,
                    "এই ফোনে সরাসরি মাইকে ঢোকানো সম্ভব নয়; স্পিকারে বাজছে, মাইক ধরলে অপর পাশ শুনবে। ফোনের ভলিউম বাড়ান।",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        startPlayer(ctx, f, Math.max(1, pct), true);
        return "ok";
    }

    public static void stop() {
        try { if (svc != null) svc.stop(); } catch (Throwable ignored) {}
        stopMonitor();
    }

    /** Apply a new self-monitor level (0 = off) - also affects a sound that is already playing. */
    public static synchronized void setMonitor(Context ctx, int pct) {
        monPct = pct;
        SoundStore.setMonitor(ctx, pct);
        MusicPlayer.onMonitorChanged(); // a running song follows the same "নিজে শুনুন" level
        MediaPlayer m = mon;
        if (m == null || speakerMode) return;
        if (pct <= 0) { stopMonitor(); return; }
        try { m.setVolume(pct / 100f, pct / 100f); } catch (Throwable ignored) {}
    }

    public static synchronized void loadMonitor(Context ctx) { monPct = SoundStore.getMonitor(ctx); }
    public static int getMonitor() { return monPct; }

    private static void startMonitor(Context ctx, File f) { startPlayer(ctx, f, monPct, false); }

    private static synchronized void startPlayer(Context ctx, File f, int pct, boolean speaker) {
        stopMonitor();
        if (pct <= 0) return;
        speakerMode = speaker;
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(f.getPath());
            mp.setVolume(pct / 100f, pct / 100f);
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
