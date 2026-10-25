package com.gamevoice.app;

import android.content.Context;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pre-decoded copies of the songs in the 🎵 folder.
 * A song is decoded ONCE (when it is added, or the first time the app sees it) and stored as raw PCM.
 * Playing it later just reads that file, so a tap starts the song almost at once.
 */
public final class PcmCache {
    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gv-pcm-cache");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();

    private PcmCache() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "musiccache");
        d.mkdirs();
        return d;
    }

    public static File fileFor(Context c, File song) {
        return new File(dir(c), song.getName() + ".gvpcm");
    }

    /** The cache file when it exists and is newer than the song, else null. */
    public static File ready(Context c, File song) {
        File f = fileFor(c, song);
        return (f.isFile() && f.length() > 8 && f.lastModified() >= song.lastModified()) ? f : null;
    }

    /** Builds the cache for one song in the background (does nothing when it is already there). */
    public static void warm(Context c, final File song) {
        final Context app = c.getApplicationContext();
        if (song == null || ready(app, song) != null) return;
        final String key = song.getName();
        if (!pending.add(key)) return;
        EX.execute(() -> {
            try { build(app, song); } catch (Throwable ignored) {
            } finally { pending.remove(key); }
        });
    }

    public static void warmAll(Context c) {
        for (File f : SoundStore.listMusic(c)) warm(c, f);
    }

    public static void remove(Context c, File song) {
        try { fileFor(c, song).delete(); } catch (Throwable ignored) {}
    }

    private static void build(Context c, File song) throws Exception {
        File out = fileFor(c, song);
        File tmp = new File(out.getPath() + ".tmp");
        short[] pcm;
        try (FileInputStream in = new FileInputStream(song)) {
            pcm = PcmDecoder.decode(in.getFD(), () -> false);
        }
        if (pcm.length == 0) return;
        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            os.write(PcmDecoder.MAGIC);
            ByteBuffer bb = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            int i = 0;
            while (i < pcm.length) {
                bb.clear();
                int n = Math.min(pcm.length - i, bb.capacity() / 2);
                bb.asShortBuffer().put(pcm, i, n);
                os.write(bb.array(), 0, n * 2);
                i += n;
            }
        }
        out.delete();
        if (!tmp.renameTo(out)) tmp.delete();
    }
}
