package com.gamevoice.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.ParcelFileDescriptor;
import java.io.OutputStream;

/**
 * Records your REAL microphone inside the app (a normal app is allowed to; the Shizuku shell process is not,
 * on Android 12+) and streams it to the Shizuku service, which mixes it into what the game hears.
 *
 * It only runs while the floating button service is on, because that foreground service is what lets
 * Android keep the microphone open while you are inside the game.
 * Capture source VOICE_PERFORMANCE is used on purpose: the injection mix only grabs the other sources,
 * so this recorder still gets the real microphone.
 */
public final class MicRelay {
    private MicRelay() {}

    private static final int RATE = 48000;
    private static final int VOICE_PERFORMANCE = 10;
    private static Thread th;
    private static volatile boolean run;
    /** true = your voice is muted: the relay keeps running but sends silence (so switching is instant and reliable). */
    public static volatile boolean muted;

    public static synchronized boolean isRunning() { return th != null && th.isAlive(); }

    /** Blocking setup; call from a background thread. Returns "ok" or the reason it did not start. */
    public static synchronized String start(Context c) {
        if (isRunning()) return "ok";
        if (!FloatingService.running) return "ভাসমান বাটন চালু নেই";
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "মাইক্রোফোন পারমিশন দেওয়া হয়নি (অ্যাপ সেটিংস > পারমিশন)";
        final IUserService s = Client.svc;
        if (s == null) return "Shizuku সংযুক্ত নয়";
        muted = !SoundStore.getMicOn(c);

        AudioRecord r = null;
        ParcelFileDescriptor[] p = null;
        try {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            r = new AudioRecord.Builder()
                    .setAudioSource(VOICE_PERFORMANCE)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min * 2, 960 * 2 * 4))
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord চালু হয়নি");
            p = ParcelFileDescriptor.createPipe();
            String res = s.micStream(p[0]);                 // the service keeps its own copy of the read end
            try { p[0].close(); } catch (Throwable ignored) {}
            if (!"ok".equals(res)) throw new IllegalStateException(res);
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("মাইক চালু হয়নি (অন্য কিছু মাইক ধরে আছে?)");
        } catch (Throwable e) {
            if (r != null) { try { r.release(); } catch (Throwable ignored) {} }
            if (p != null) { try { p[1].close(); } catch (Throwable ignored) {} }
            try { s.micStream(null); } catch (Throwable ignored) {}
            return String.valueOf(e);
        }
        final AudioRecord rec = r;
        final OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(p[1]);
        run = true;
        th = new Thread(() -> loop(rec, out), "gv-mic-relay");
        th.setPriority(Thread.MAX_PRIORITY);
        th.start();
        return "ok";
    }

    private static void loop(AudioRecord rec, OutputStream out) {
        short[] in = new short[960];
        byte[] by = new byte[960 * 2];
        try {
            while (run) {
                int n = rec.read(in, 0, in.length, AudioRecord.READ_BLOCKING);
                if (n < 0) break;
                if (n == 0) continue;
                final boolean mute = muted;
                for (int i = 0; i < n; i++) {
                    short v = mute ? 0 : in[i];
                    by[2 * i] = (byte) v;
                    by[2 * i + 1] = (byte) (v >> 8);
                }
                out.write(by, 0, n * 2);
            }
        } catch (Throwable ignored) {
        } finally {
            try { rec.stop(); } catch (Throwable ignored) {}
            try { rec.release(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
            synchronized (MicRelay.class) { if (th == Thread.currentThread()) th = null; }
        }
    }

    /** Stops recording and tells the service to stop mixing your voice. */
    public static void stop() {
        run = false;
        final IUserService s = Client.svc;
        if (s != null) {
            new Thread(() -> { try { s.micStream(null); } catch (Throwable ignored) {} }, "gv-mic-stop").start();
        }
    }
}
