package com.gamevoice.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Audio decoding shared by the app (builds the music cache) and the Shizuku service (plays it). */
public final class PcmDecoder {
    public static final int RATE = 48000;
    /** First 4 bytes of a cached, already-decoded song (mono, 16-bit little endian, 48 kHz). */
    public static final byte[] MAGIC = {'G', 'V', 'P', '1'};

    private PcmDecoder() {}

    /**
     * Fast path: reads a cached .gvpcm file (see PcmCache). Returns null when the file is not a cache
     * file, so the caller can fall back to {@link #decode}.
     */
    public static short[] readCached(FileDescriptor fd) {
        try {
            FileChannel ch = new FileInputStream(fd).getChannel();
            long size = ch.size();
            if (size < 8) return null;
            ByteBuffer hdr = ByteBuffer.allocate(4);
            while (hdr.hasRemaining()) if (ch.read(hdr, hdr.position()) < 0) return null;
            for (int i = 0; i < 4; i++) if (hdr.get(i) != MAGIC[i]) return null;
            int n = (int) ((size - 4) / 2);
            short[] out = new short[n];
            ByteBuffer buf = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            long pos = 4;
            int done = 0;
            while (done < n) {
                buf.clear();
                int r = ch.read(buf, pos);
                if (r <= 1) break;
                buf.flip();
                int cnt = Math.min(r / 2, n - done);
                buf.asShortBuffer().get(out, done, cnt);
                done += cnt;
                pos += cnt * 2L;
            }
            return done == n ? out : Arrays.copyOf(out, done);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Decodes any audio file to mono 16-bit PCM at 48 kHz. Returns an empty array when cancelled. */
    public static short[] decode(FileDescriptor fd, BooleanSupplier cancelled) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec c = null;
        try {
            ex.setDataSource(fd);
            MediaFormat f = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat tf = ex.getTrackFormat(i);
                String m = tf.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { ex.selectTrack(i); f = tf; break; }
            }
            if (f == null) throw new Exception("no audio track");
            c = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            c.configure(f, null, null, 0);
            c.start();
            int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = Math.max(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            long durUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            // Pre-size from the duration so long songs do not need repeated big array copies.
            long guess = durUs > 0 ? durUs * rate / 1000000L + rate : (long) rate * 10;
            short[] mono = new short[(int) Math.min(guess, 200_000_000L)];
            int len = 0;
            short[] tmp = new short[8192];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone) {
                if (cancelled.getAsBoolean()) return new short[0];
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
                    if (tmp.length < n) tmp = new short[n];
                    sb.get(tmp, 0, n);
                    int frames = n / ch;
                    if (len + frames > mono.length) {
                        mono = Arrays.copyOf(mono, (int) Math.min(Integer.MAX_VALUE - 8L,
                                Math.max(mono.length * 3L / 2, (long) len + frames)));
                    }
                    for (int i = 0; i < frames; i++) {
                        int s = 0;
                        for (int k = 0; k < ch; k++) s += tmp[i * ch + k];
                        mono[len++] = (short) (s / ch);
                    }
                    c.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = c.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                }
            }
            if (rate == RATE) return len == mono.length ? mono : Arrays.copyOf(mono, len);
            int outLen = (int) ((long) len * RATE / rate);
            short[] out = new short[outLen];
            for (int i = 0; i < outLen; i++) {
                double pos = (double) i * rate / RATE;
                int a = (int) pos;
                int b = Math.min(a + 1, len - 1);
                double fr = pos - a;
                out[i] = (short) (mono[Math.min(a, len - 1)] * (1 - fr) + mono[b] * fr);
            }
            return out;
        } finally {
            if (c != null) {
                try { c.stop(); } catch (Throwable ignored) {}
                try { c.release(); } catch (Throwable ignored) {}
            }
            try { ex.release(); } catch (Throwable ignored) {}
        }
    }
}
