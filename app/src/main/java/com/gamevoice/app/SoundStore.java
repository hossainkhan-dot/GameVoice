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
        return l;
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
        prefs(c).edit().remove("name:" + f.getName()).remove("folder:" + f.getName()).apply();
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
        e.apply();
        if (id.equals(p.getString("sel", ALL))) setSelected(c, ALL);
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
        String name = null;
        try (Cursor q = c.getContentResolver().query(uri, null, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                int i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = q.getString(i);
            }
        }
        if (name == null || name.trim().isEmpty()) name = "sound_" + System.currentTimeMillis();
        name = name.replaceAll("[/\\\\]", "_");
        File out = unique(dir(c), name);
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("ফাইল পড়া যায়নি");
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        }
        setFolder(c, out, folderId);
        return out;
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
