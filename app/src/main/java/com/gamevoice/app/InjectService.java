package com.gamevoice.app;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Runs in the Shizuku (shell-uid) process. Injects PCM into every app's mic capture. */
public class InjectService extends IUserService.Stub {
    private static final int RATE = 48000;
    private AudioTrack track;
    private volatile Thread player;
    private volatile boolean cancel;
    private volatile int generation;
    private volatile String lastError = "";

    // ---- mixer: voice clip + music are summed and written by ONE thread into the mic track ----
    private final Object mixLock = new Object();
    private short[] clip;                 // current voice clip (mono 48k), null = none
    private int clipOff;
    private short[] music;                // current music track (mono 48k), null = none
    private int musicOff;                 // next sample the mixer will take
    private int musicState;               // 0 idle, 1 loading, 2 playing, 3 paused, 4 ended
    private volatile int musicGen;
    private volatile float musicGain = 1f;
    private Thread mixer;

    // ---- sound of chosen apps (captured by AppCapture, pulled by the mixer) ----
    private final AppCapture capture = new AppCapture(this::wakeMixer);
    private final java.util.concurrent.ExecutorService capExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "gv-capture-ctl");
                t.setDaemon(true);
                return t;
            });

    public InjectService() {}

    private void wakeMixer() {
        synchronized (mixLock) {
            startMixer();
            mixLock.notifyAll();
        }
    }

    private Object keepPolicy; // strong ref so the registered policy is never GC'd
    private Object keepMgr;    // AudioManager used to register it (needed to unregister on failure)
    private HandlerThread policyThread;
    private volatile String initReport = "";

    private synchronized void init() throws Exception {
        if (track != null) return;
        // We run inside a Binder transaction from the app (uid != shell). AudioPolicy checks
        // MODIFY_AUDIO_ROUTING against the *calling* uid, so reset identity to the shell process.
        long tok = Binder.clearCallingIdentity();
        try { initInner(); } finally { Binder.restoreCallingIdentity(tok); }
    }

    private static Throwable root(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e;
    }

    /**
     * Different Android versions accept different AudioPolicy mixes (Android 11 is stricter than 13+),
     * so try a few configurations and remember why each one failed.
     */
    private void initInner() throws Exception {
        if (track != null) return;
        try { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L"); } catch (Throwable ignored) {}
        StringBuilder log = new StringBuilder();
        for (int v = 0; v < 4; v++) {
            try {
                initVariant(v);
                initReport = "ok (config " + v + ")";
                return;
            } catch (Throwable e) {
                Throwable r = root(e);
                log.append("config ").append(v).append(": ").append(r).append('\n');
                unregisterQuiet();
            }
        }
        initReport = log.toString();
        throw new IllegalStateException(initReport);
    }

    private void unregisterQuiet() {
        try {
            if (keepPolicy != null && keepMgr != null) {
                Class<?> polC = Class.forName("android.media.audiopolicy.AudioPolicy");
                AudioManager.class.getMethod("unregisterAudioPolicy", polC).invoke(keepMgr, keepPolicy);
            }
        } catch (Throwable ignored) {}
        keepPolicy = null;
        keepMgr = null;
        if (policyThread != null) { try { policyThread.quitSafely(); } catch (Throwable ignored) {} policyThread = null; }
    }

    /**
     * v0: injector role + all capture presets (newest Android).
     * v1: no explicit role. v2: only the 3 common mic presets. v3: same but RENDER|LOOP_BACK flags.
     */
    private void initVariant(int v) throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object th = at.getMethod("currentActivityThread").invoke(null);
        if (th == null) th = at.getMethod("systemMain").invoke(null);
        Context sys = (Context) at.getMethod("getSystemContext").invoke(th);
        Context ctx;
        try { ctx = sys.createPackageContext("com.android.shell", 0); } catch (Throwable e) { ctx = sys; }

        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();

        Class<?> ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object rb = ruleB.getConstructor().newInstance();
        if (v == 0) {
            try { ruleB.getMethod("setTargetMixRole", int.class).invoke(rb, 1); } catch (NoSuchMethodException ignored) {}
        }
        Method addRule = ruleB.getMethod("addMixRule", int.class, Object.class);
        int[] presets = v >= 2
                ? new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION}
                : new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.UNPROCESSED};
        Method setPreset = AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class);
        for (int p : presets) {
            AudioAttributes.Builder ab = new AudioAttributes.Builder();
            setPreset.invoke(ab, p);
            addRule.invoke(rb, 2 /* RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET */, ab.build());
        }
        Object rule = ruleB.getMethod("build").invoke(rb);
        Class<?> ruleC = Class.forName("android.media.audiopolicy.AudioMixingRule");

        Class<?> mixB = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> mixC = Class.forName("android.media.audiopolicy.AudioMix");
        Object mb = mixB.getConstructor(ruleC).newInstance(rule);
        mixB.getMethod("setFormat", AudioFormat.class).invoke(mb, fmt);
        mixB.getMethod("setRouteFlags", int.class).invoke(mb, v == 3 ? 3 : 2 /* LOOP_BACK (3 = RENDER|LOOP_BACK) */);
        Object mix = mixB.getMethod("build").invoke(mb);

        policyThread = new HandlerThread("gv-policy");
        policyThread.start();
        Class<?> polB = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Class<?> polC = Class.forName("android.media.audiopolicy.AudioPolicy");
        Object pb = polB.getConstructor(Context.class).newInstance(ctx);
        try { polB.getMethod("setLooper", Looper.class).invoke(pb, policyThread.getLooper()); } catch (Throwable ignored) {}
        polB.getMethod("addMix", mixC).invoke(pb, mix);
        Object policy = polB.getMethod("build").invoke(pb);

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        keepPolicy = policy;
        keepMgr = am;
        int r = (Integer) AudioManager.class.getMethod("registerAudioPolicy", polC).invoke(am, policy);
        if (r != 0) throw new IllegalStateException("registerAudioPolicy=" + r + " (permission refused?)");
        AudioTrack t = (AudioTrack) polC.getMethod("createAudioTrackSource", mixC).invoke(policy, mix);
        if (t == null) throw new IllegalStateException("createAudioTrackSource returned null (check logcat tag AudioPolicy)");
        t.play();
        track = t;
    }

    /** Human readable health report for the app's "ডায়াগনস্টিক" button. */
    @Override public String diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n");
        try {
            init();
            sb.append("✔ মাইক ইনজেকশন প্রস্তুত: ").append(initReport);
        } catch (Throwable e) {
            sb.append("✘ মাইক ইনজেকশন চালু হয়নি:\n").append(initReport.isEmpty() ? String.valueOf(root(e)) : initReport);
        }
        if (!lastError.isEmpty()) sb.append("\n\nশেষ বাজানোর ত্রুটি: ").append(lastError);
        return sb.toString();
    }

    /** True when mic injection could be set up on this phone (Android may refuse the permission). */
    @Override public boolean ready() {
        try { init(); return true; } catch (Throwable e) { return false; }
    }

    /** Last playback error (cleared once read), so the app can show why nothing was heard. */
    @Override public String status() {
        String e = lastError;
        lastError = "";
        return e;
    }

    @Override public void destroy() {
        stop(); musicStop(); capture.closeAll();
        System.exit(0);
    }

    // ------------------------------------------------------------------ mixer

    private void startMixer() {
        synchronized (mixLock) {
            if (mixer != null) return;
            Thread t = new Thread(this::mixLoop, "gv-mixer");
            mixer = t;
            t.start();
        }
    }

    private boolean hasWork() { return clip != null || (music != null && musicState == 2) || capture.active(); }

    private void mixLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
        final int N = 960; // 20 ms @ 48 kHz
        short[] out = new short[N];
        int[] acc = new int[N];
        int quiet = 1000;               // chunks in a row where only silence came from the chosen apps
        while (true) {
            final boolean write;
            synchronized (mixLock) {
                while (!hasWork()) {
                    try { mixLock.wait(); } catch (InterruptedException e) { return; }
                }
                final short[] c = clip, m = (music != null && musicState == 2) ? music : null;
                final float g = musicGain;
                for (int i = 0; i < N; i++) {
                    int v = 0;
                    if (c != null && clipOff + i < c.length) v += c[clipOff + i];
                    if (m != null && musicOff + i < m.length) v += (int) (m[musicOff + i] * g);
                    acc[i] = v;
                }
                // sound of the apps switched on in the "অ্যাপ" folder (each already has its own percentage)
                for (AppCapture.Stream cs : capture.snapshot()) cs.mixInto(acc, N);
                for (int i = 0; i < N; i++) out[i] = (short) Math.max(-32768, Math.min(32767, acc[i]));
                // Clips and music are always written, exactly as before. Apps alone are written only while they
                // really make sound (+ 200 ms tail): a track that is fed silence all the time would cover your
                // own microphone, so when the apps are quiet nothing is written and your voice goes straight through.
                boolean audible = c != null || m != null;
                if (audible) {
                    quiet = 0;
                } else {
                    int pk = 0;
                    for (int i = 0; i < N; i++) { int a = out[i] < 0 ? -out[i] : out[i]; if (a > pk) pk = a; }
                    if (pk > 2) quiet = 0; else if (quiet < 1000) quiet++;
                    audible = quiet < 10;
                }
                write = audible;
                if (c != null) {
                    clipOff += N;
                    if (clipOff >= c.length) clip = null;
                }
                if (m != null) {
                    musicOff += N;
                    if (musicOff >= m.length) { musicOff = m.length; musicState = 4; }
                }
            }
            if (!write) { // only quiet apps: leave the injection track idle, keep time with the apps' stream
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                continue;
            }
            AudioTrack t = track;
            if (t == null) { // mic injection not ready: never spin
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                continue;
            }
            int off = 0;
            while (off < N) {
                int w = t.write(out, off, N - off, AudioTrack.WRITE_BLOCKING);
                if (w < 0) {
                    lastError = "ERR: AudioTrack.write=" + w;
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                    break;
                }
                off += w;
                if (w == 0) Thread.yield();
            }
        }
    }

    /** Drops audio already queued in the track (pause -> flush -> play: flush alone is a no-op while playing). */
    private void flushTrack() {
        AudioTrack t = track;
        if (t == null) return;
        try { t.pause(); t.flush(); t.play(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ voice clips

    @Override public void stop() {
        cancel = true;
        generation++;
        Thread t = player;
        if (t != null) t.interrupt();
        boolean musicRunning;
        synchronized (mixLock) {
            clip = null;
            musicRunning = music != null && musicState == 2;
        }
        if (!musicRunning && !capture.active()) flushTrack();
    }

    /**
     * Return immediately to the UI/Binder caller. Decoding compressed audio can take
     * hundreds of milliseconds (or seconds); doing it inside this Binder call froze
     * the overlay and could trigger Android's "Wait / Close app" ANR dialog.
     */
    @Override public String play(ParcelFileDescriptor pfd, final int volumePct) {
        if (pfd == null) return "ERR: audio file is unavailable";
        final int request = ++generation;
        cancel = false;
        lastError = "";
        Thread old = player;
        if (old != null) old.interrupt();
        Thread t = new Thread(() -> {
            try (ParcelFileDescriptor owned = pfd) {
                init();
                startMixer();
                if (request != generation || cancel) return;
                short[] pcm = decode(owned.getFileDescriptor(), () -> request != generation || cancel);
                if (request != generation || cancel || pcm.length == 0) return;
                // Per-folder "how loud the other side hears it" (linear gain, 0..100 %).
                if (volumePct < 100) {
                    float gain = Math.max(0, volumePct) / 100f;
                    for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (pcm[i] * gain);
                }
                synchronized (mixLock) {
                    if (request != generation || cancel) return;
                    clip = pcm;
                    clipOff = 0;
                    mixLock.notifyAll();
                }
            } catch (Throwable e) {
                if (request == generation && !cancel) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    lastError = "ERR: " + c;
                }
            }
        }, "gv-audio-" + request);
        t.setPriority(Thread.NORM_PRIORITY);
        player = t;
        t.start();
        return "ok";
    }

    // ------------------------------------------------------------------ music

    /** Loads (in the background) and starts a music track at startMs. Returns at once. */
    @Override public String musicPlay(ParcelFileDescriptor pfd, final int volumePct, final long startMs) {
        if (pfd == null) return "ERR: music file is unavailable";
        final int g;
        synchronized (mixLock) {
            g = ++musicGen;
            music = null;
            musicOff = 0;
            musicState = 1;
            musicGain = Math.max(0, Math.min(100, volumePct)) / 100f;
        }
        Thread t = new Thread(() -> {
            try (ParcelFileDescriptor owned = pfd) {
                init();
                startMixer();
                short[] pcm = loadMusic(owned.getFileDescriptor(), () -> g != musicGen);
                synchronized (mixLock) {
                    if (g != musicGen) return;
                    if (pcm.length == 0) { musicState = 0; lastError = "ERR: music is empty"; return; }
                    music = pcm;
                    musicOff = (int) Math.max(0, Math.min(pcm.length, startMs * RATE / 1000));
                    musicState = 2;
                    mixLock.notifyAll();
                }
            } catch (Throwable e) {
                synchronized (mixLock) { if (g == musicGen) musicState = 0; }
                lastError = "ERR: music: " + root(e);
            }
        }, "gv-music-load");
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
        return "ok";
    }

    @Override public void musicPause() {
        synchronized (mixLock) { if (musicState == 2) musicState = 3; }
    }

    @Override public void musicResume() {
        synchronized (mixLock) {
            if (music != null && (musicState == 3 || musicState == 4)) {
                if (musicState == 4) musicOff = 0;
                musicState = 2;
                mixLock.notifyAll();
            }
        }
    }

    @Override public void musicSeek(long ms) {
        synchronized (mixLock) {
            if (music == null) return;
            musicOff = (int) Math.max(0, Math.min(music.length - 1L, ms * RATE / 1000));
            if (musicState == 4) musicState = 3;
        }
        flushTrack(); // so the new position is heard at once, not after the queued audio
    }

    @Override public void musicStop() {
        boolean clipRunning;
        synchronized (mixLock) {
            musicGen++;
            music = null;
            musicOff = 0;
            musicState = 0;
            clipRunning = clip != null;
        }
        if (!clipRunning) flushTrack();
    }

    @Override public void musicVolume(int volumePct) {
        musicGain = Math.max(0, Math.min(100, volumePct)) / 100f;
    }

    @Override public long[] musicInfo() {
        synchronized (mixLock) {
            long pos = 0, dur = 0;
            if (music != null) {
                dur = music.length * 1000L / RATE;
                int p = musicOff;
                if (musicState == 2) {
                    // the mixer is ~one track buffer ahead of what is actually audible
                    int buf = 0;
                    try { buf = track == null ? 0 : track.getBufferSizeInFrames(); } catch (Throwable ignored) {}
                    p = Math.max(0, p - Math.min(buf, RATE / 2));
                }
                pos = p * 1000L / RATE;
                if (musicState == 4) pos = dur;
            }
            return new long[]{musicState, pos, dur};
        }
    }

    // ------------------------------------------------------------ app capture

    /** Switches the captured apps to exactly this set. Returns at once; the work runs on its own thread. */
    @Override public String captureSet(int[] uids, int[] volumesPct) {
        final int[] u = uids == null ? new int[0] : uids.clone();
        final int[] v = volumesPct == null ? new int[0] : volumesPct.clone();
        capExec.execute(() -> {
            try {
                if (u.length > 0) { init(); startMixer(); }
            } catch (Throwable e) {
                capture.fail("মাইকে ঢোকানো চালু হয়নি: " + root(e));
                return;
            }
            try {
                capture.set(u, v);
                if (capture.active()) tuneBuffer();
            } catch (Throwable e) {
                capture.fail("ERR: " + root(e));
            }
        });
        return "ok";
    }

    @Override public void captureVolume(int uid, int volumePct) { capture.setGain(uid, volumePct); }

    @Override public String captureStatus() { return capture.status(); }

    /** A smaller mic-track buffer keeps the apps' sound close to real time (about 120 ms). */
    private void tuneBuffer() {
        AudioTrack t = track;
        if (t == null) return;
        try { t.setBufferSizeInFrames(Math.min(t.getBufferCapacityInFrames(), RATE / 8)); } catch (Throwable ignored) {}
    }

    // ----------------------------------------------------------------- decode

    /** Decodes any audio file to mono 16-bit PCM at 48 kHz. Returns an empty array when cancelled. */
    private short[] decode(FileDescriptor fd, BooleanSupplier cancelled) throws Exception {
        return PcmDecoder.decode(fd, cancelled);
    }

    /** Music: use the pre-decoded cache file when we were given one (instant), otherwise decode. */
    private short[] loadMusic(FileDescriptor fd, BooleanSupplier cancelled) throws Exception {
        short[] cached = PcmDecoder.readCached(fd);
        return cached != null ? cached : PcmDecoder.decode(fd, cancelled);
    }
}
