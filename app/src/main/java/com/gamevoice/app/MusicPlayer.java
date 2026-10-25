package com.gamevoice.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.widget.Toast;
import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The music player behind the permanent "🎵 মিউজিক" folder (used by the app screen and the overlay).
 *
 * Three ways a song can play, picked each time a song starts:
 *  - INJECT : Shizuku mic injection works -> the song goes into the mic (everybody on voice chat hears it),
 *             and your own speaker plays a copy at the "নিজে শুনুন" level, kept in sync.
 *  - SPEAKER: this phone refuses mic injection -> plays out loud at the folder's "অপর প্রান্তে %" level.
 *  - LOCAL  : Shizuku not connected -> only you hear it, at full volume.
 *
 * Everything here runs on the main thread; calls into the Shizuku service run on one background thread.
 */
public final class MusicPlayer {
    public static final int IDLE = 0, LOADING = 1, PLAYING = 2, PAUSED = 3;
    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static final int M_LOCAL = 0, M_INJECT = 1, M_SPEAKER = 2;

    // ---- state the UI reads (main thread only) ----
    public static File cur;              // current song (null = nothing chosen yet)
    public static int state = IDLE;
    public static long posMs, durMs;
    public static int repeat = REPEAT_OFF;
    public static boolean dragging;      // true while the user drags the seek bar

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gv-music-io");
        t.setDaemon(true);
        return t;
    });
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static Context app;
    private static int mode = M_LOCAL;
    private static int gen;                  // bumped whenever the song changes / stops, so stale callbacks are ignored
    private static MediaPlayer mp;           // local player (self-monitor, or the real player in SPEAKER/LOCAL mode)
    private static boolean mpReady, mpMaster;
    private static boolean ticking, polling, warnedLocal;
    private static volatile long lastCmdAt;  // poll results older than the last command are ignored

    private MusicPlayer() {}

    public static void addListener(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    public static void removeListener(Runnable r) { listeners.remove(r); }
    private static void fire() { for (Runnable r : listeners) r.run(); }

    private static void toast(String s) {
        if (app != null) Toast.makeText(app, s, Toast.LENGTH_LONG).show();
    }

    private interface Cmd { void run(IUserService s) throws Throwable; }

    /** Runs a command on the Shizuku service in the background (never blocks the UI). */
    private static void cmd(final Cmd c) {
        lastCmdAt = SystemClock.uptimeMillis();
        final IUserService s = Client.svc;
        if (s == null) return;
        IO.execute(() -> { try { c.run(s); } catch (Throwable ignored) {} });
    }

    // ------------------------------------------------------------ controls

    public static void play(Context ctx, File f) { play(ctx, f, 0); }

    private static void play(Context ctx, File f, final long startMs) {
        app = ctx.getApplicationContext();
        final int g = ++gen;
        lastCmdAt = SystemClock.uptimeMillis();
        releaseLocal();
        cur = f;
        posMs = startMs;
        durMs = 0;
        dragging = false;
        state = LOADING;
        mode = pickMode();
        final int vol = SoundStore.sendVolume(app, SoundStore.MUSIC);
        if (mode == M_INJECT) {
            final IUserService s = Client.svc;
            final File file = f;
            IO.execute(() -> {
                ParcelFileDescriptor pfd = null;
                String r;
                try {
                    pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    r = s.musicPlay(pfd, vol, startMs);
                } catch (Throwable e) {
                    r = "ERR: " + e;
                } finally {
                    if (pfd != null) { try { pfd.close(); } catch (Throwable ignored) {} }
                }
                final String res = r;
                if (!"ok".equals(res)) {
                    H.post(() -> {
                        if (g != gen) return;
                        state = IDLE;
                        toast(res);
                        fire();
                    });
                }
            });
        } else {
            if (mode == M_LOCAL && !warnedLocal) {
                warnedLocal = true;
                toast("Shizuku সংযুক্ত নয়, তাই মিউজিক শুধু আপনি শুনবেন। অন্যদের শোনাতে আগে Shizuku সংযুক্ত করুন।");
            } else if (mode == M_SPEAKER && !warnedLocal) {
                warnedLocal = true;
                toast("স্পিকার মোড: মিউজিক ফোনের স্পিকারে বাজছে, মাইক ধরলে অপর পাশ শুনবে।");
            }
            startLocal(f, startMs, mode == M_SPEAKER ? Math.max(1, vol) : 100, true, g);
        }
        schedule();
        fire();
    }

    private static int pickMode() {
        if (Client.svc != null && Boolean.TRUE.equals(Client.injectOk)) return M_INJECT;
        if (Boolean.FALSE.equals(Client.injectOk)) return M_SPEAKER;
        return M_LOCAL;
    }

    /** Play / pause button. */
    public static void toggle(Context ctx) {
        app = ctx.getApplicationContext();
        if (state == PLAYING) pause();
        else if (state == PAUSED) resume();
        else if (state == IDLE) {
            if (cur != null) { play(ctx, cur); return; }
            List<File> l = SoundStore.listMusic(app);
            if (l.isEmpty()) toast("আগে মিউজিক যোগ করুন (অ্যাপে “＋ মিউজিক যোগ করো”)");
            else play(ctx, l.get(0));
        }
    }

    public static void pause() {
        if (state != PLAYING) return;
        state = PAUSED;
        if (mode == M_INJECT) cmd(s -> s.musicPause());
        try { if (mp != null && mpReady && mp.isPlaying()) mp.pause(); } catch (Throwable ignored) {}
        fire();
    }

    public static void resume() {
        if (state != PAUSED) return;
        state = PLAYING;
        if (mode == M_INJECT) {
            cmd(s -> s.musicResume());
            syncMonitor();
        } else {
            try { if (mp != null && mpReady) mp.start(); } catch (Throwable ignored) {}
        }
        schedule();
        fire();
    }

    /** Jump to a position (the seek bar). */
    public static void seekTo(long ms) {
        if (cur == null || (state != PLAYING && state != PAUSED)) return;
        final long m = Math.max(0, durMs > 0 ? Math.min(ms, durMs - 1) : ms);
        posMs = m;
        if (mode == M_INJECT) cmd(s -> s.musicSeek(m));
        try { if (mp != null && mpReady) mp.seekTo((int) m); } catch (Throwable ignored) {}
        fire();
    }

    public static void next(Context ctx) {
        app = ctx.getApplicationContext();
        List<File> l = SoundStore.listMusic(app);
        if (l.isEmpty()) return;
        int i = indexOf(l, cur);
        play(ctx, l.get((i + 1) % l.size()));
    }

    /** Like most players: after 3 s into a song, "previous" restarts it; otherwise goes to the song before. */
    public static void prev(Context ctx) {
        app = ctx.getApplicationContext();
        List<File> l = SoundStore.listMusic(app);
        if (l.isEmpty()) return;
        if (cur != null && (state == PLAYING || state == PAUSED) && posMs > 3000) { seekTo(0); return; }
        int i = indexOf(l, cur);
        int n = l.size();
        play(ctx, l.get((((i - 1) % n) + n) % n));
    }

    public static void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        fire();
    }

    public static void stop() {
        gen++;
        releaseLocal();
        cmd(s -> s.musicStop());
        state = IDLE;
        posMs = 0;
        durMs = 0;
        dragging = false;
        fire();
    }

    /** Call when a song file is deleted. */
    public static void removeIfCurrent(File f) {
        if (cur != null && f != null && cur.getName().equals(f.getName())) {
            stop();
            cur = null;
            fire();
        }
    }

    /** "নিজে শুনুন" level changed: update (or start / stop) the local copy of a running song. */
    public static void onMonitorChanged() {
        if (state != IDLE) syncMonitor();
    }

    /** The folder's "অপর প্রান্তে %" changed: apply it to the song that is playing now. */
    public static void onSendVolumeChanged(Context ctx) {
        final int v = SoundStore.sendVolume(ctx.getApplicationContext(), SoundStore.MUSIC);
        if (mode == M_INJECT) cmd(s -> s.musicVolume(v));
        else if (mode == M_SPEAKER && mp != null && mpReady) {
            try { mp.setVolume(Math.max(1, v) / 100f, Math.max(1, v) / 100f); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------- queue

    private static int indexOf(List<File> l, File f) {
        if (f == null) return -1;
        for (int i = 0; i < l.size(); i++) if (l.get(i).getName().equals(f.getName())) return i;
        return -1;
    }

    private static void onEnded() {
        if (app == null) return;
        List<File> l = SoundStore.listMusic(app);
        int i = indexOf(l, cur);
        if (repeat == REPEAT_ONE && cur != null) { play(app, cur); return; }
        if (i >= 0 && i + 1 < l.size()) { play(app, l.get(i + 1)); return; }
        if (repeat == REPEAT_ALL && !l.isEmpty()) { play(app, l.get(0)); return; }
        stop(); // end of the list
    }

    // ------------------------------------------------------- local player

    private static void startLocal(File f, final long startMs, int pct, final boolean master, final int g) {
        releaseLocal();
        try {
            final MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(f.getPath());
            p.setVolume(pct / 100f, pct / 100f);
            p.setOnPreparedListener(m -> {
                if (g != gen || mp != m) return;
                mpReady = true;
                if (master) {
                    durMs = m.getDuration();
                    if (startMs > 0) m.seekTo((int) startMs);
                    m.start();
                    state = PLAYING;
                    fire();
                } else {
                    syncMonitor();
                }
            });
            p.setOnCompletionListener(m -> {
                if (g == gen && mp == m && master) onEnded();
            });
            p.setOnErrorListener((m, what, extra) -> {
                if (g == gen && mp == m) {
                    releaseLocal();
                    if (master) {
                        state = IDLE;
                        toast("এই মিউজিক ফাইলটি বাজানো যায়নি");
                        fire();
                    }
                }
                return true;
            });
            mp = p;
            mpReady = false;
            mpMaster = master;
            p.prepareAsync();
        } catch (Throwable e) {
            mp = null;
            mpReady = false;
            if (master) {
                state = IDLE;
                toast("মিউজিক ত্রুটি: " + e);
            }
        }
    }

    private static void releaseLocal() {
        MediaPlayer p = mp;
        mp = null;
        mpReady = false;
        if (p != null) {
            try { p.setOnCompletionListener(null); } catch (Throwable ignored) {}
            try { p.stop(); } catch (Throwable ignored) {}
            try { p.release(); } catch (Throwable ignored) {}
        }
    }

    /** INJECT mode: make the local copy (your own speaker) follow the service's state and position. */
    private static void syncMonitor() {
        if (mode != M_INJECT) return;
        int pct = Client.getMonitor();
        if (pct <= 0 || cur == null || state == IDLE) { releaseLocal(); return; }
        if (state == LOADING) return;
        if (mp == null) { startLocal(cur, posMs, pct, false, gen); return; }
        if (!mpReady) return;
        try {
            mp.setVolume(pct / 100f, pct / 100f);
            if (state == PAUSED) {
                if (mp.isPlaying()) mp.pause();
            } else if (state == PLAYING) {
                if (!mp.isPlaying()) { mp.seekTo((int) posMs); mp.start(); }
                else if (Math.abs(mp.getCurrentPosition() - posMs) > 600) mp.seekTo((int) posMs);
            }
        } catch (Throwable ignored) {}
    }

    // --------------------------------------------------------------- tick

    private static final Runnable TICK = () -> {
        ticking = false;
        if (state == IDLE) return;
        if (mode == M_INJECT) poll(); else readLocal();
        schedule();
    };

    private static void schedule() {
        if (ticking) return;
        ticking = true;
        H.postDelayed(TICK, 400);
    }

    private static void readLocal() {
        MediaPlayer p = mp;
        if (p == null || !mpReady || !mpMaster) return;
        try {
            if (!dragging) posMs = p.getCurrentPosition();
            durMs = p.getDuration();
        } catch (Throwable ignored) {}
        fire();
    }

    private static void poll() {
        if (polling) return;
        polling = true;
        final long t0 = SystemClock.uptimeMillis();
        final int g = gen;
        IO.execute(() -> {
            long[] info = null;
            try {
                IUserService s = Client.svc;
                if (s != null) info = s.musicInfo();
            } catch (Throwable ignored) {}
            final long[] in = info;
            H.post(() -> { polling = false; applyInfo(in, g, t0); });
        });
    }

    private static void applyInfo(long[] in, int g, long t0) {
        if (g != gen || state == IDLE) return;
        if (in == null) {
            if (Client.svc == null) { // Shizuku service went away
                releaseLocal();
                state = IDLE;
                fire();
            }
            return;
        }
        if (t0 < lastCmdAt) return; // answer to a question asked before our latest command
        int s = (int) in[0];
        if (s == 4) { onEnded(); return; }
        if (s == 0) { state = IDLE; releaseLocal(); fire(); return; }
        state = s;
        if (!dragging) posMs = in[1];
        durMs = in[2];
        syncMonitor();
        fire();
    }
}
