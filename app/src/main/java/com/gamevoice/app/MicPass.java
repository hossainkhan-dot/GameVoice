package com.gamevoice.app;

import android.os.ParcelFileDescriptor;
import java.io.InputStream;

/**
 * Runs in the Shizuku (shell) process. While mic injection is active Android sends EVERY app's microphone
 * capture to our mix, so your real voice would never reach the game. The GameVoice app records the real
 * microphone itself (see MicRelay) and streams it here through a pipe; the mixer adds it to the
 * injected sounds. Result: your voice + voice packs + music + chosen apps, all together.
 */
final class MicPass {
    private static final int RING = 48000 / 2;    // 0.5 s
    private static final int CHUNK = 960;         // 20 ms

    private InputStream in;
    private Thread reader;
    private Runnable onEnd;
    private volatile boolean alive = true;
    private final short[] ring = new short[RING];
    private int rd, cnt;
    private boolean primed;

    private MicPass() {}

    static MicPass fromPipe(ParcelFileDescriptor pfd, Runnable onEnd) {
        MicPass m = new MicPass();
        m.in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        m.onEnd = onEnd;
        m.reader = new Thread(m::readLoop, "gv-mic");
        m.reader.setDaemon(true);
        m.reader.setPriority(Thread.MAX_PRIORITY);
        m.reader.start();
        return m;
    }

    private void readLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
        byte[] buf = new byte[CHUNK * 2];
        short[] pcm = new short[CHUNK];
        try {
            while (alive) {
                int got = 0;
                while (got < buf.length) {
                    int r = in.read(buf, got, buf.length - got);
                    if (r < 0) throw new java.io.EOFException();
                    got += r;
                }
                for (int i = 0; i < CHUNK; i++) pcm[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
                push(pcm, CHUNK);
            }
        } catch (Throwable ignored) {
        } finally {
            if (alive) {                       // the app closed the pipe (or died): tell the service
                alive = false;
                Runnable r = onEnd;
                if (r != null) r.run();
            }
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
        try { in.close(); } catch (Throwable ignored) {}
    }
}
