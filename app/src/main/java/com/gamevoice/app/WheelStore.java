package com.gamevoice.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The "quick voice wheel": ONE round wheel with N slots (default 12, 4..24). Every slot points at one sound file
 * that already lives somewhere inside the app (a voice folder, the music folder or an old voice pack).
 *
 * Saved in its own SharedPreferences file "gv_wheel": slot count, the file path of every slot, button position.
 * Nothing here touches the other stores. The wheel is only saved when the settings screen's SAVE is pressed,
 * and the floating overlay always reads the last saved state.
 */
public final class WheelStore {
    private WheelStore() {}

    public static final int DEFAULT_COUNT = 12, MIN_COUNT = 4, MAX_COUNT = 24;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("gv_wheel", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ the wheel

    public static int count(Context c) {
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, prefs(c).getInt("count", DEFAULT_COUNT)));
    }

    /** File paths of all MAX_COUNT slots (null = empty). Slots beyond count() are kept so growing the wheel again restores them. */
    public static String[] paths(Context c) {
        String[] out = new String[MAX_COUNT];
        String json = prefs(c).getString("slots", null);
        if (json == null) return migrate(c, out);
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length() && i < MAX_COUNT; i++) {
                String p = a.optString(i, "");
                out[i] = p.isEmpty() ? null : p;
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** First start only: fill the wheel with the voices of the old "active pack", so nothing looks lost. */
    private static String[] migrate(Context c, String[] out) {
        try {
            PackStore.Pack p = PackStore.active(c);
            if (p == null) return out;
            int n = 0;
            for (PackStore.Slot s : p.slots) {
                File f = PackStore.fileOf(c, p, s);
                if (f != null && n < DEFAULT_COUNT) out[n++] = f.getPath();
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void save(Context c, int count, String[] paths) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < MAX_COUNT; i++) a.put(i < paths.length && paths[i] != null ? paths[i] : "");
        prefs(c).edit().putInt("count", Math.max(MIN_COUNT, Math.min(MAX_COUNT, count)))
                .putString("slots", a.toString()).commit();
    }

    /** The file of slot i, or null when the slot is empty / the file was deleted meanwhile. */
    public static File fileAt(String[] paths, int i) {
        if (i < 0 || i >= paths.length || paths[i] == null) return null;
        File f = new File(paths[i]);
        return f.isFile() ? f : null;
    }

    /** Names for the wheel to draw: one per slot (null = empty). */
    public static List<String> labels(Context c, int count, String[] paths) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            File f = fileAt(paths, i);
            l.add(f == null ? null : labelOf(c, f));
        }
        return l;
    }

    // ---------------------------------------------------- floating button pos

    public static float bubbleX(Context c) { return prefs(c).getFloat("bx", 0f); }
    public static float bubbleY(Context c) { return prefs(c).getFloat("by", 0.5f); }
    public static void setBubblePos(Context c, float fx, float fy) {
        prefs(c).edit().putFloat("bx", fx).putFloat("by", fy).apply();
    }

    // ----------------------------------------------------------- the library

    /** One "folder" in the settings screen's left column. */
    public static final class Source {
        public static final int ALL = 0, FOLDER = 1, MUSIC = 2, PACK = 3;
        public final int kind;
        public final String id, emoji, name;
        Source(int kind, String id, String emoji, String name) {
            this.kind = kind; this.id = id; this.emoji = emoji; this.name = name;
        }
    }

    /** Every folder of the app: all sounds, the voice folders, music and the old voice packs. */
    public static List<Source> sources(Context c) {
        List<Source> l = new ArrayList<>();
        l.add(new Source(Source.ALL, "", "📂", "সব সাউন্ড"));
        for (SoundStore.Folder f : SoundStore.folders(c)) l.add(new Source(Source.FOLDER, f.id, f.emoji, f.name));
        l.add(new Source(Source.MUSIC, SoundStore.MUSIC, SoundStore.MUSIC_EMOJI, SoundStore.MUSIC_NAME));
        for (PackStore.Pack p : PackStore.list(c)) l.add(new Source(Source.PACK, p.id, p.emoji, p.name));
        return l;
    }

    /** Sound files inside one folder. */
    public static List<File> files(Context c, Source s) {
        List<File> out = new ArrayList<>();
        switch (s.kind) {
            case Source.ALL:
                out.addAll(SoundStore.list(c));
                out.addAll(SoundStore.listMusic(c));
                for (PackStore.Pack p : PackStore.list(c)) addPackFiles(c, p, out);
                break;
            case Source.FOLDER:
                out.addAll(SoundStore.list(c, s.id));
                break;
            case Source.MUSIC:
                out.addAll(SoundStore.listMusic(c));
                break;
            default:
                PackStore.Pack p = PackStore.get(c, s.id);
                if (p != null) addPackFiles(c, p, out);
        }
        return out;
    }

    private static void addPackFiles(Context c, PackStore.Pack p, List<File> out) {
        for (PackStore.Slot s : p.slots) {
            File f = PackStore.fileOf(c, p, s);
            if (f != null) out.add(f);
        }
    }

    /** Copies a picked file into the given folder (call from a background thread). */
    public static File add(Context c, Source s, Uri uri) throws IOException {
        switch (s.kind) {
            case Source.MUSIC: return SoundStore.addMusic(c, uri);
            case Source.FOLDER: return SoundStore.add(c, uri, s.id);
            case Source.PACK: {
                String slot = PackStore.assign(c, s.id, null, uri);
                PackStore.Pack p = PackStore.get(c, s.id);
                if (p != null) for (PackStore.Slot x : p.slots) if (x.id.equals(slot)) return PackStore.fileOf(c, p, x);
                throw new IOException("ফাইল যোগ হয়নি");
            }
            default: return SoundStore.add(c, uri, "");
        }
    }

    // --------------------------------------------------------- name / volume

    private static boolean under(File f, File dir) {
        File p = f.getParentFile();
        return p != null && p.equals(dir);
    }

    public static boolean isMusic(Context c, File f) { return under(f, SoundStore.musicDir(c)); }

    private static PackStore.Pack packOf(Context c, File f) {
        File dir = f.getParentFile();
        if (dir == null || dir.getParentFile() == null || !dir.getParentFile().equals(PackStore.root(c))) return null;
        return PackStore.get(c, dir.getName());
    }

    /** Display name of a sound, whichever folder it lives in. */
    public static String labelOf(Context c, File f) {
        if (isMusic(c, f)) return SoundStore.musicLabel(c, f);
        PackStore.Pack p = packOf(c, f);
        if (p != null) {
            for (int i = 0; i < p.slots.size(); i++) {
                PackStore.Slot s = p.slots.get(i);
                if (f.getName().equals(s.file)) return PackStore.slotLabel(s, i);
            }
        }
        return SoundStore.label(c, f);
    }

    /** How loud (1..100 %) the OTHER side hears this sound. */
    public static int sendVolume(Context c, File f) {
        if (isMusic(c, f)) return SoundStore.sendVolume(c, SoundStore.MUSIC);
        PackStore.Pack p = packOf(c, f);
        if (p != null) return p.vol;
        return SoundStore.sendVolume(c, SoundStore.folderOf(c, f));
    }

    /** Plays a wheel sound to the other side (and on your own speaker if "নিজে শুনুন" is on). */
    public static String play(Context c, File f) {
        if (isMusic(c, f)) { MusicPlayer.play(c, f); return "ok"; }
        return Client.play(c, f, sendVolume(c, f));
    }
}
