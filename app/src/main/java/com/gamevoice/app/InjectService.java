package com.gamevoice.app;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Runs in the Shizuku (shell-uid) process. Injects PCM into every app's mic capture. */
public class InjectService extends IUserService.Stub {
    private static final int RATE = 48000;
    private AudioTrack track;
    private volatile Thread player;
    private volatile boolean cancel;
    private volatile int generation;
    private volatile String lastError = "";

    public InjectService() {}

    private Object keepPolicy; // strong ref so the registered policy is never GC'd

    private synchronized void init() throws Exception {
        if (track != null) return;
        // We run inside a Binder transaction from the app (uid != shell). AudioPolicy checks
        // MODIFY_AUDIO_ROUTING against the *calling* uid, so reset identity to the shell process.
        long tok = Binder.clearCallingIdentity();
        try { initInner(); } finally { Binder.restoreCallingIdentity(tok); }
    }

    private void initInner() throws Exception {
        if (track != null) return;
        try { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L"); } catch (Throwable ignored) {}
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
        try { ruleB.getMethod("setTargetMixRole", int.class).invoke(rb, 1); } catch (NoSuchMethodException ignored) {}
        Method addRule = ruleB.getMethod("addMixRule", int.class, Object.class);
        int[] presets = {MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER,
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
        mixB.getMethod("setRouteFlags", int.class).invoke(mb, 2 /* ROUTE_FLAG_LOOP_BACK */);
        Object mix = mixB.getMethod("build").invoke(mb);

        HandlerThread ht = new HandlerThread("gv-policy");
        ht.start();
        Class<?> polB = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Class<?> polC = Class.forName("android.media.audiopolicy.AudioPolicy");
        Object pb = polB.getConstructor(Context.class).newInstance(ctx);
        try { polB.getMethod("setLooper", Looper.class).invoke(pb, ht.getLooper()); } catch (Throwable ignored) {}
        polB.getMethod("addMix", mixC).invoke(pb, mix);
        Object policy = polB.getMethod("build").invoke(pb);

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int r = (Integer) AudioManager.class.getMethod("registerAudioPolicy", polC).invoke(am, policy);
        if (r != 0) throw new IllegalStateException("registerAudioPolicy=" + r);
        keepPolicy = policy;
        AudioTrack t = (AudioTrack) polC.getMethod("createAudioTrackSource", mixC).invoke(policy, mix);
        if (t == null) throw new IllegalStateException("createAudioTrackSource returned null (check logcat tag AudioPolicy)");
        t.play();
        track = t;
    }

    @Override public void destroy() { stop(); System.exit(0); }

    @Override public void stop() {
        cancel = true;
        generation++;
        Thread t = player;
        if (t != null) t.interrupt();
        try { if (track != null) track.flush(); } catch (Throwable ignored) {}
    }

    /**
     * Return immediately to the UI/Binder caller. Decoding compressed audio can take
     * hundreds of milliseconds (or seconds); doing it inside this Binder call froze
     * the overlay and could trigger Android's "Wait / Close app" ANR dialog.
     */
    @Override public String play(ParcelFileDescriptor pfd) {
        if (pfd == null) return "ERR: audio file is unavailable";
        final int request = ++generation;
        cancel = false;
        lastError = "";
        Thread old = player;
        if (old != null) old.interrupt();
        Thread t = new Thread(() -> {
            try (ParcelFileDescriptor owned = pfd) {
                init();
                if (request != generation || cancel) return;
                short[] pcm = decode(owned.getFileDescriptor());
                if (request != generation || cancel || pcm.length == 0) return;
                int off = 0;
                while (request == generation && !cancel && off < pcm.length) {
                    int n = Math.min(1920, pcm.length - off);
                    int written = track.write(pcm, off, n, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IllegalStateException("AudioTrack.write=" + written);
                    if (written == 0) Thread.yield(); else off += written;
                }
                if (request == generation && cancel) {
                    try { track.flush(); } catch (Throwable ignored) {}
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

    private short[] decode(FileDescriptor fd) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(fd);
        MediaFormat f = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat tf = ex.getTrackFormat(i);
            String m = tf.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) { ex.selectTrack(i); f = tf; break; }
        }
        if (f == null) throw new Exception("no audio track");
        MediaCodec c = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
        c.configure(f, null, null, 0);
        c.start();
        int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        short[] buf = new short[RATE * 4];
        int len = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        while (!outDone) {
            if (!inDone) {
                int ii = c.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer ib = c.getInputBuffer(ii);
                    int n = ex.readSampleData(ib, 0);
                    if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                    else { c.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                }
            }
            int oi = c.dequeueOutputBuffer(info, 10000);
            if (oi >= 0) {
                ByteBuffer ob = c.getOutputBuffer(oi);
                ob.order(ByteOrder.LITTLE_ENDIAN);
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                ShortBuffer sb = ob.asShortBuffer();
                int n = sb.remaining();
                if (len + n > buf.length) {
                    short[] nb = new short[Math.max(buf.length * 2, len + n)];
                    System.arraycopy(buf, 0, nb, 0, len);
                    buf = nb;
                }
                sb.get(buf, len, n);
                len += n;
                c.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = c.getOutputFormat();
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
        }
        try { c.stop(); } catch (Throwable ignored) {}\n        c.release(); ex.release();\n        int frames = len / ch;
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int s = 0;
            for (int k = 0; k < ch; k++) s += buf[i * ch + k];
            mono[i] = (short) (s / ch);
        }
        if (rate == RATE) return mono;
        int outLen = (int) ((long) frames * RATE / rate);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = (double) i * rate / RATE;
            int a = (int) pos;
            int b = Math.min(a + 1, frames - 1);
            double fr = pos - a;
            out[i] = (short) (mono[Math.min(a, frames - 1)] * (1 - fr) + mono[b] * fr);
        }
        return out;
    }
}
