package com.gamevoice.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;

/**
 * Runs in the Shizuku (shell) process. While mic injection is active Android sends EVERY app's microphone
 * capture to our mix, so your real voice would never reach the game. This reads the real microphone
 * with a capture source the mix does not match (VOICE_PERFORMANCE) and hands it to the mixer, which
 * adds it to the injected sounds. Result: your voice + voice packs + music + chosen apps, all together.
 */
final class MicPass {
    static final int RATE = 48000;
    private static final int RING = RATE / 2;     // 0.5 s
    private static final int CHUNK = 960;         // 20 ms
    private static final int VOICE_PERFORMANCE = 10;

    private AudioRecord rec;
    private Thread reader;
    private volatile boolean alive = true;
    private final short[] ring = new short[RING];
    private int rd, cnt;
    private boolean primed;

    private MicPass() {}

    /** Opens the real microphone. Must run with the shell identity (the caller clears the Binder identity). */
    static MicPass open() throws Exception {
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
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord.Builder b = new AudioRecord.Builder()
                .setAudioSource(VOICE_PERFORMANCE)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(min * 2, CHUNK * 2 * 4));
        // Android 12+ wants to know which package is recording; there is no app context in this process.
        try { AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(b, ctx); }
        catch (Throwable ignored) {}
        AudioRecord r = b.build();
        try {
            if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord not initialised");
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("microphone did not start");
        } catch (Throwable e) {
            try { r.release(); } catch (Throwable ignored) {}
            throw e instanceof Exception ? (Exception) e : new IllegalStateException(e);
        }
        MicPass m = new MicPass();
        m.rec = r;
        m.reader = new Thread(m::readLoop, "gv-mic");
        m.reader.setPriority(Thread.MAX_PRIORITY);
        m.reader.start();
        return m;
    }

    private void readLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
        short[] in = new short[CHUNK];
        while (alive) {
            int n;
            try { n = rec.read(in, 0, in.length, AudioRecord.READ_BLOCKING); }
            catch (Throwable e) { break; }
            if (n <= 0) {
                if (!alive) break;
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                continue;
            }
            push(in, n);
        }
    }

    private synchronized void push(short[] m, int n) {
        if (cnt + n > RING) {
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

    /** Adds the next n samples of your voice into acc (called by the mixer every 20 ms). */
    synchronized void mixInto(int[] acc, int n) {
        if (!primed) {
            if (cnt >= 2 * n) primed = true; else return;
        }
        if (cnt > 6 * n) {                // fell behind: jump ahead so your voice stays in real time
            int drop = cnt - 3 * n;
            rd = (rd + drop) % RING;
            cnt -= drop;
        }
        int take = Math.min(cnt, n);
        for (int i = 0; i < take; i++) {
            acc[i] += ring[rd];
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
    }
}
