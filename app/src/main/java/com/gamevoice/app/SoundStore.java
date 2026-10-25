package com.gamevoice.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public class SoundStore {
    /** Self-monitor choices (percent). 0 = off. */
    public static final int[] MONITOR_LEVELS = {0, 30, 60, 100};

    /** "Folder id" meaning: show every voice, whatever folder it is in. */
    public static final String ALL = "";
    public static final String DEFAULT_EMOJI = "📁";

    /** The permanent music folder: reserved id, cannot be renamed or deleted. Holds music only. */
    public static final String MUSIC = "music";
    public static final String MUSIC_EMOJI = "🎵";      // change this one line to use another logo
    public static final String MUSIC_NAME = "মিউজিক";
    /** Default "how loud the other side hears music" (%). Songs are much louder than short voices. */
    public static final int MUSIC_DEFAULT_VOL = 70;

    /** A voice-pack folder. The icon is an emoji, never a picture / letter. */
    public static class Folder {
        public final String id, name, emoji;
        Folder(String id, String name, String emoji) { this.id = id; this.name = name; this.emoji = emoji; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gv", Context.MODE_PRIVATE);
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sounds");
        d.mkdirs();
        return d;
    }

    /** Every voice file (all folders). */
    public static List<File> list(Context c) {
        File[] fs = dir(c).listFiles();
        List<File> l = fs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(fs));
        Collections.sort(l);
        // Favourites first (stable sort keeps the name order inside each group).
        final SharedPreferences p = prefs(c);
        Collections.sort(l, (a, b) -> Boolean.compare(
                p.getBoolean("fav:" + b.getName(), false), p.getBoolean("fav:" + a.getName(), false)));
        return l;
    }

    public static boolean isFav(Context c, File f) { return prefs(c).getBoolean("fav:" + f.getName(), false); }

    public static void setFav(Context c, File f, boolean on) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (on) e.putBoolean("fav:" + f.getName(), true); else e.remove("fav:" + f.getName());
        e.apply();
    }

    /** Voices inside one folder; {@link #ALL} returns everything. */
    public static List<File> list(Context c, String folderId) {
        List<File> all = list(c);
        if (folderId == null || folderId.isEmpty()) return all;
        List<File> out = new ArrayList<>();
        for (File f : all) if (folderId.equals(folderOf(c, f))) out.add(f);
        return out;
    }

    /** User-editable display name; falls back to the file name without extension. */
    public static String label(Context c, File f) {
        String s = prefs(c).getString("name:" + f.getName(), null);
        if (s != null && !s.trim().isEmpty()) return s;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public static void setLabel(Context c, File f, String name) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (name == null || name.trim().isEmpty()) e.remove("name:" + f.getName());
        else e.putString("name:" + f.getName(), name.trim());
        e.apply();
    }

    public static void delete(Context c, File f) {
        f.delete();
        prefs(c).edit().remove("name:" + f.getName()).remove("folder:" + f.getName())
                .remove("fav:" + f.getName()).apply();
    }

    public static int getMonitor(Context c) { return prefs(c).getInt("monitor", 30); }
    public static void setMonitor(Context c, int pct) { prefs(c).edit().putInt("monitor", pct).apply(); }

    // -------------------------------------------------------------- folders

    public static List<Folder> folders(Context c) {
        List<Folder> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("folders", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new Folder(o.getString("id"), o.optString("name", ""), o.optString("emoji", DEFAULT_EMOJI)));
            }
        } catch (Exception ignored) {}
        return l;
    }

    private static void saveFolders(Context c, List<Folder> l) {
        JSONArray a = new JSONArray();
        try {
            for (Folder f : l) {
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("emoji", f.emoji);
                a.put(o);
            }
        } catch (Exception ignored) {}
        prefs(c).edit().putString("folders", a.toString()).apply();
    }

    public static Folder folder(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Folder f : folders(c)) if (f.id.equals(id)) return f;
        return null;
    }

    private static String cleanName(String n) {
        n = n == null ? "" : n.trim();
        return n.isEmpty() ? "ফোল্ডার" : n;
    }

    private static String cleanEmoji(String e) {
        String g = Ui.firstGrapheme(e == null ? "" : e);
        return g.isEmpty() ? DEFAULT_EMOJI : g;
    }

    public static Folder createFolder(Context c, String name, String emoji) {
        List<Folder> l = folders(c);
        Folder f = new Folder("f" + System.currentTimeMillis(), cleanName(name), cleanEmoji(emoji));
        l.add(f);
        saveFolders(c, l);
        return f;
    }

    public static void updateFolder(Context c, String id, String name, String emoji) {
        List<Folder> l = folders(c);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(id)) l.set(i, new Folder(id, cleanName(name), cleanEmoji(emoji)));
        }
        saveFolders(c, l);
    }

    /** Removes the folder only. Its voices are NOT deleted - they stay available under "সব". */
    public static void deleteFolder(Context c, String id) {
        List<Folder> l = folders(c);
        for (Iterator<Folder> it = l.iterator(); it.hasNext(); ) if (it.next().id.equals(id)) it.remove();
        saveFolders(c, l);
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
            if (en.getKey().startsWith("folder:") && id.equals(en.getValue())) e.remove(en.getKey());
        }
        e.remove("vol:" + id);
        e.apply();
        if (id.equals(p.getString("sel", ALL))) setSelected(c, ALL);
    }

    /** How loud (0..100 %) the OTHER side hears voices of this folder. Voices outside folders: 100. */
    public static int sendVolume(Context c, String folderId) {
        if (folderId == null || folderId.isEmpty()) return 100;
        int def = MUSIC.equals(folderId) ? MUSIC_DEFAULT_VOL : 100;
        return Math.max(0, Math.min(100, prefs(c).getInt("vol:" + folderId, def)));
    }

    public static void setSendVolume(Context c, String folderId, int pct) {
        if (folderId == null || folderId.isEmpty()) return;
        prefs(c).edit().putInt("vol:" + folderId, Math.max(0, Math.min(100, pct))).apply();
    }

    /** Folder id a voice lives in, or "" when it is not in any folder. */
    public static String folderOf(Context c, File f) {
        return prefs(c).getString("folder:" + f.getName(), "");
    }

    public static void setFolder(Context c, File f, String folderId) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (folderId == null || folderId.isEmpty()) e.remove("folder:" + f.getName());
        else e.putString("folder:" + f.getName(), folderId);
        e.apply();
    }

    /** The folder that was open last (shared by the app and the overlay). */
    public static String selected(Context c) {
        String s = prefs(c).getString("sel", ALL);
        if (MUSIC.equals(s)) return MUSIC;
        return (s.isEmpty() || folder(c, s) == null) ? ALL : s;
    }

    public static void setSelected(Context c, String id) {
        prefs(c).edit().putString("sel", id == null ? ALL : id).apply();
    }

    // ------------------------------------------------------ bubble position

    /** Where the floating button was left, as 0..1 fractions of the free screen area. */
    public static float getBubbleX(Context c) { return prefs(c).getFloat("bx", 0f); }
    public static float getBubbleY(Context c) { return prefs(c).getFloat("by", 0.33f); }
    public static void setBubblePos(Context c, float fx, float fy) {
        prefs(c).edit().putFloat("bx", fx).putFloat("by", fy).apply();
    }

    // ------------------------------------------------------------------ add

    public static File add(Context c, Uri uri, String folderId) throws IOException {
        File out = copyIn(c, uri, dir(c));
        setFolder(c, out, folderId);
        return out;
    }

    /** Copies a picked file into app storage under its display name (never overwrites). */
    private static File copyIn(Context c, Uri uri, File dir) throws IOException {
        String name = null;
        try (Cursor q = c.getContentResolver().query(uri, null, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                int i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = q.getString(i);
            }
        }
        if (name == null || name.trim().isEmpty()) name = "sound_" + System.currentTimeMillis();
        name = name.replaceAll("[/\\\\]", "_");
        File out = unique(dir, name);
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("ফাইল পড়া যায়নি");
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        }
        return out;
    }

    // ---------------------------------------------------------------- music

    public static File musicDir(Context c) {
        File d = new File(c.getFilesDir(), "music");
        d.mkdirs();
        return d;
    }

    /** Every music file, A-Z. These never show up in the voice folders / "সব". */
    public static List<File> listMusic(Context c) {
        File[] fs = musicDir(c).listFiles();
        List<File> l = fs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(fs));
        Collections.sort(l, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return l;
    }

    public static File addMusic(Context c, Uri uri) throws IOException {
        return copyIn(c, uri, musicDir(c));
    }

    public static String musicLabel(Context c, File f) {
        String s = prefs(c).getString("mname:" + f.getName(), null);
        if (s != null && !s.trim().isEmpty()) return s;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public static void setMusicLabel(Context c, File f, String name) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (name == null || name.trim().isEmpty()) e.remove("mname:" + f.getName());
        else e.putString("mname:" + f.getName(), name.trim());
        e.apply();
    }

    public static void deleteMusic(Context c, File f) {
        f.delete();
        prefs(c).edit().remove("mname:" + f.getName()).apply();
    }

    /** Never overwrite an existing voice that happens to have the same file name. */
    private static File unique(File d, String name) {
        File f = new File(d, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(d, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }
}
