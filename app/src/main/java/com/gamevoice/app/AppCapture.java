package com.gamevoice.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.HandlerThread;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Runs inside the Shizuku (shell-uid) process, next to {@link InjectService}.
 *
 * For every chosen app it registers its own AudioPolicy with ONE loop-back mix that matches only that
 * app's UID (this is exactly what Android's "AudioPlaybackCapture" does internally, but the shell uid
 * is allowed to do it without the screen-capture pop-up). The app keeps playing on your speaker
 * (RENDER flag) while a copy of its sound is read here, turned into mono, scaled by that app's own
 * percentage and handed to the InjectService mixer, which writes it into the microphone track.
 *
 * One policy per app, so switching one app on/off never disturbs the others.
 * Apps that are NOT switched on (the game you are playing, for example) are never captured.
 */
final class AppCapture {
    static final int RATE = 48000;
    private static final int RING = RATE;            // 1 s of mono samples per app
    private static final int CHUNK = 960;            // 20 ms of stereo frames

    private final Runnable wake;                      // tells the mixer there is something to do
    private final Map<Integer, Stream> streams = new LinkedHashMap<>();   // guarded by this
    private final Map<Integer, String> errors = new HashMap<>();           // guarded by this
    private volatile Stream[] snap = new Stream[0];
    private volatile String general = "";
    private HandlerThread policyThread;
    private boolean bypassed;

    AppCapture(Runnable wake) { this.wake = wake; }

    /** The streams the mixer should pull from right now (never null). */
    Stream[] snapshot() { return snap; }

    boolean active() { return snap.length > 0; }

    void fail(String msg) { general = msg == null ? "" : msg; }

    /** Applies a new gain (1..100 %) to a running app at once, without touching its capture. */
    void setGain(int uid, int pct) {
        for (Stream s : snap) if (s.uid == uid) s.gain = clampPct(pct) / 100f;
    }

    private static int clampPct(int p) { return Math.max(1, Math.min(100, p)); }

    /**
     * Makes the set of captured apps exactly {uids}. Apps that are already running only get their new
     * gain; apps that disappeared are closed; new ones are opened (and retried on the next call when
     * they failed before).
     */
    synchronized String set(int[] uids, int[] vols) {
        general = "";
        Set<Integer> want = new HashSet<>();
        for (int u : uids) want.add(u);
        for (Iterator<Map.Entry<Integer, Stream>> it = streams.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Stream> e = it.next();
            if (!want.contains(e.getKey())) { e.getValue().close(); it.remove(); }
        }
        errors.keySet().retainAll(want);
        for (int i = 0; i < uids.length; i++) {
            int uid = uids[i];
            float g = clampPct(i < vols.length ? vols[i] : 100) / 100f;
            Stream s = streams.get(uid);
            if (s != null) { s.gain = g; continue; }
            try {
                s = open(uid);
                s.gain = g;
                streams.put(uid, s);
                errors.remove(uid);
            } catch (Throwable e) {
                errors.put(uid, shorten(String.valueOf(root(e))));
            }
        }
        snap = streams.values().toArray(new Stream[0]);
        wake.run();
        return errors.isEmpty() ? "ok" : "ERR: " + errors;
    }

    synchronized void closeAll() {
        for (Stream s : streams.values()) s.close();
        streams.clear();
        snap = new Stream[0];
        if (policyThread != null) { try { policyThread.quitSafely(); } catch (Throwable ignored) {} policyThread = null; }
    }

    /** "uid|ok|peak%|info" or "uid|err|0|reason", one per line. Peak is "since the last call". */
    synchronized String status() {
        StringBuilder sb = new StringBuilder();
        if (!general.isEmpty()) sb.append("-1|err|0|").append(clean(general)).append('\n');
        for (Stream s : streams.values()) {
            int p = s.peak;
            s.peak = 0;
            sb.append(s.uid).append("|ok|").append(p * 100 / 32768).append('|').append(clean(s.info)).append('\n');
        }
        for (Map.Entry<Integer, String> e : errors.entrySet())
            sb.append(e.getKey()).append("|err|0|").append(clean(e.getValue())).append('\n');
        return sb.toString();
    }

    private static String clean(String s) { return s.replace('|', '/').replace('\n', ' '); }

    private static String shorten(String s) { return s.length() > 220 ? s.substring(0, 220) + "…" : s; }

    private static Throwable root(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e;
    }

    // ------------------------------------------------------------------ opening

    private Stream open(int uid) throws Exception {
        if (!bypassed) {
            bypassed = true;
            try { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L"); } catch (Throwable ignored) {}
        }
        StringBuilder log = new StringBuilder();
        for (int v = 0; v < 3; v++) {
            try {
                return openVariant(uid, v);
            } catch (Throwable e) {
                log.append('v').append(v).append(": ").append(root(e)).append("; ");
            }
        }
        throw new IllegalStateException(log.toString());
    }

    private synchronized Looper looper() {
        if (policyThread == null) {
            policyThread = new HandlerThread("gv-cap-policy");
            policyThread.start();
        }
        return policyThread.getLooper();
    }

    /**
     * v0: also allow "privileged" capture (apps that opted out of being recorded), sound still plays on your speaker.
     * v1: plain capture, sound still plays on your speaker.
     * v2: capture only (older Androids): the app may go silent on YOUR speaker while it is captured.
     */
    private Stream openVariant(int uid, int v) throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object th = at.getMethod("currentActivityThread").invoke(null);
        if (th == null) th = at.getMethod("systemMain").invoke(null);
        Context sys = (Context) at.getMethod("getSystemContext").invoke(th);
        Context ctx;
        try { ctx = sys.createPackageContext("com.android.shell", 0); } catch (Throwable e) { ctx = sys; }

        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();

        Class<?> ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> ruleC = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Object rb = ruleB.getConstructor().newInstance();
        try { ruleB.getMethod("setTargetMixRole", int.class).invoke(rb, 0 /* MIX_ROLE_PLAYERS */); }
        catch (NoSuchMethodException ignored) {}
        if (v == 0) {
            try { ruleB.getMethod("allowPrivilegedPlaybackCapture", boolean.class).invoke(rb, true); }
            catch (Throwable ignored) {}
        }
        Method addRule = ruleB.getMethod("addMixRule", int.class, Object.class);
        // Only the usages Android's own playback capture allows: media, game, unknown.
        int[] usages = {AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN};
        for (int u : usages) {
            addRule.invoke(rb, 1 /* RULE_MATCH_ATTRIBUTE_USAGE */, new AudioAttributes.Builder().setUsage(u).build());
        }
        addRule.invoke(rb, 4 /* RULE_MATCH_UID */, Integer.valueOf(uid));
        Object rule = ruleB.getMethod("build").invoke(rb);

        Class<?> mixB = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> mixC = Class.forName("android.media.audiopolicy.AudioMix");
        Object mb = mixB.getConstructor(ruleC).newInstance(rule);
        mixB.getMethod("setFormat", AudioFormat.class).invoke(mb, fmt);
        mixB.getMethod("setRouteFlags", int.class).invoke(mb, v == 2 ? 2 : 3 /* LOOP_BACK (+ RENDER) */);
        Object mix = mixB.getMethod("build").invoke(mb);

        Class<?> polB = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Class<?> polC = Class.forName("android.media.audiopolicy.AudioPolicy");
        Object pb = polB.getConstructor(Context.class).newInstance(ctx);
        try { polB.getMethod("setLooper", Looper.class).invoke(pb, looper()); } catch (Throwable ignored) {}
        polB.getMethod("addMix", mixC).invoke(pb, mix);
        Object policy = polB.getMethod("build").invoke(pb);

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean registered = false;
        AudioRecord rec = null;
        try {
            int r = (Integer) AudioManager.class.getMethod("registerAudioPolicy", polC).invoke(am, policy);
            if (r != 0) throw new IllegalStateException("registerAudioPolicy=" + r + " (permission refused?)");
            registered = true;
            rec = (AudioRecord) polC.getMethod("createAudioRecordSink", mixC).invoke(policy, mix);
            if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("createAudioRecordSink failed");
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("recording did not start");
        } catch (Throwable e) {
            if (rec != null) { try { rec.release(); } catch (Throwable ignored) {} }
            if (registered) {
                try { AudioManager.class.getMethod("unregisterAudioPolicy", polC).invoke(am, policy); }
                catch (Throwable ignored) {}
            }
            throw e instanceof Exception ? (Exception) e : new IllegalStateException(e);
        }
        String info = v == 2 ? "capture-only config (অ্যাপ আপনার স্পিকারে চুপ থাকতে পারে)" : "config " + v;
        Stream s = new Stream(uid, info, am, policy, polC, rec);
        s.start();
        return s;
    }

    // ------------------------------------------------------------------- stream

    /** One captured app: reader thread -> ring buffer -> pulled by the mixer every 20 ms. */
    static final class Stream {
        final int uid;
        final String info;
        volatile float gain = 1f;
        volatile int peak;                    // largest sample since status() last looked

        private final AudioManager am;
        private final Object policy;
        private final Class<?> polC;
        private final AudioRecord rec;
        private volatile boolean alive = true;
        private Thread reader;

        private final short[] ring = new short[RING];
        private int rd, cnt;
        private boolean primed;

        Stream(int uid, String info, AudioManager am, Object policy, Class<?> polC, AudioRecord rec) {
            this.uid = uid;
            this.info = info;
            this.am = am;
            this.policy = policy;
            this.polC = polC;
            this.rec = rec;
        }

        void start() {
            reader = new Thread(this::readLoop, "gv-cap-" + uid);
            reader.setPriority(Thread.MAX_PRIORITY);
            reader.start();
        }

        private void readLoop() {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
            short[] in = new short[CHUNK * 2];
            short[] mono = new short[CHUNK];
            while (alive) {
                int n;
                try { n = rec.read(in, 0, in.length, AudioRecord.READ_BLOCKING); }
                catch (Throwable e) { break; }
                if (n <= 0) {
                    if (!alive) break;
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }
                int frames = n / 2, pk = 0;
                for (int i = 0; i < frames; i++) {
                    int m = (in[2 * i] + in[2 * i + 1]) / 2;
                    mono[i] = (short) m;
                    int a = m < 0 ? -m : m;
                    if (a > pk) pk = a;
                }
                if (pk > peak) peak = pk;
                push(mono, frames);
            }
        }

        private synchronized void push(short[] m, int n) {
            if (n > RING) n = RING;
            if (cnt + n > RING) {                         // consumer is not keeping up: drop the oldest
                int drop = cnt + n - RING;
                rd = (rd + drop) % RING;
                cnt -= drop;
            }
            int wr = (rd + cnt) % RING;
            for (int i = 0; i < n; i++) {
                ring[wr] = m[i];
                if (++wr == RING) wr = 0;
            }
            cnt += n;
        }

        /** Adds the next n samples (times this app's gain) into acc. Called by the mixer thread. */
        synchronized void mixInto(int[] acc, int n) {
            if (!primed) {                                // wait for a little reserve so 20 ms hiccups stay silent
                if (cnt >= 2 * n) primed = true; else return;
            }
            if (cnt > 8 * n) {                            // too far behind (> 160 ms): jump ahead, keep latency low
                int drop = cnt - 3 * n;
                rd = (rd + drop) % RING;
                cnt -= drop;
            }
            final float g = gain;
            int take = Math.min(cnt, n);
            for (int i = 0; i < take; i++) {
                acc[i] += (int) (ring[rd] * g);
                if (++rd == RING) rd = 0;
            }
            cnt -= take;
            if (take < n) primed = false;
        }

        void close() {
            alive = false;
            try { rec.stop(); } catch (Throwable ignored) {}
            if (reader != null) reader.interrupt();
            try { rec.release(); } catch (Throwable ignored) {}
            try { AudioManager.class.getMethod("unregisterAudioPolicy", polC).invoke(am, policy); }
            catch (Throwable ignored) {}
        }
    }
}
