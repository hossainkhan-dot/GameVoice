package com.gamevoice.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Gaming Voice" packs: user-made packs of voice commands (each command = one audio file).
 *
 * Everything is stored permanently and separately from the older folder system:
 *  - metadata (packs, command names, active pack, button position) in its own SharedPreferences file "gv_packs"
 *  - audio files copied into app storage: files/vpacks/&lt;packId&gt;/...
 * so closing / restarting the app (or the phone) never loses a pack. Nothing here touches {@link SoundStore}.
 */
public final class PackStore {
    private PackStore() {}

    /** A new pack starts with this many empty command slots (the user can add more, no upper limit). */
    public static final int INITIAL_SLOTS = 12;
    public static final String DEFAULT_EMOJI = "🎮";

    public static final class Slot {
        public final String id;
        public final String label;   // user-given name ("" = use file name)
        public final String file;    // file name inside the pack folder, null = empty slot
        Slot(String id, String label, String file) { this.id = id; this.label = label; this.file = file; }
        public boolean empty() { return file == null; }
    }

    public static final class Pack {
        public final String id, name, emoji;
        public final int vol;        // how loud the OTHER side hears this pack, 1..100 %
        public final List<Slot> slots;
        Pack(String id, String name, String emoji, int vol, List<Slot> slots) {
            this.id = id; this.name = name; this.emoji = emoji; this.vol = vol; this.slots = slots;
        }
        public int filled() { int n = 0; for (Slot s : slots) if (!s.empty()) n++; return n; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("gv_packs", Context.MODE_PRIVATE);
    }

    public static File root(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "vpacks");
        d.mkdirs();
        return d;
    }

    public static File packDir(Context c, String packId) {
        File d = new File(root(c), packId);
        d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------ load / save

    public static synchronized List<Pack> list(Context c) {
        List<Pack> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("packs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                List<Slot> slots = new ArrayList<>();
                JSONArray sa = o.optJSONArray("slots");
                if (sa != null) for (int j = 0; j < sa.length(); j++) {
                    JSONObject so = sa.getJSONObject(j);
                    String f = so.optString("file", "");
                    slots.add(new Slot(so.getString("id"), so.optString("label", ""), f.isEmpty() ? null : f));
                }
                out.add(new Pack(o.getString("id"), o.optString("name", "প্যাক"),
                        o.optString("emoji", DEFAULT_EMOJI), clampVol(o.optInt("vol", 100)), slots));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void save(Context c, List<Pack> packs) {
        JSONArray a = new JSONArray();
        try {
            for (Pack p : packs) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("emoji", p.emoji);
                o.put("vol", p.vol);
                JSONArray sa = new JSONArray();
                for (Slot s : p.slots) {
                    JSONObject so = new JSONObject();
                    so.put("id", s.id);
                    so.put("label", s.label);
                    so.put("file", s.file == null ? "" : s.file);
                    sa.put(so);
                }
                o.put("slots", sa);
                a.put(o);
            }
        } catch (Exception ignored) {}
        // commit(): packs are precious, write them to disk right now (the list is tiny).
        prefs(c).edit().putString("packs", a.toString()).commit();
    }

    public static synchronized Pack get(Context c, String id) {
        if (id == null) return null;
        for (Pack p : list(c)) if (p.id.equals(id)) return p;
        return null;
    }

    private static int clampVol(int v) { return Math.max(1, Math.min(100, v)); }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String cleanName(String n, String fallback) {
        n = n == null ? "" : n.trim();
        return n.isEmpty() ? fallback : n;
    }

    private static String cleanEmoji(String e) {
        String g = Ui.firstGrapheme(e == null ? "" : e);
        return g.isEmpty() ? DEFAULT_EMOJI : g;
    }

    // ------------------------------------------------------------------ packs

    public static synchronized Pack create(Context c, String name, String emoji) {
        List<Pack> l = list(c);
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < INITIAL_SLOTS; i++) slots.add(new Slot(newId("s"), "", null));
        Pack p = new Pack(newId("p"), cleanName(name, "নতুন প্যাক"), cleanEmoji(emoji), 100, slots);
        l.add(p);
        save(c, l);
        packDir(c, p.id);
        return p;
    }

    /** Rename / change icon / change the "other side" volume. Slots are kept as they are. */
    public static synchronized void update(Context c, String id, String name, String emoji, int vol) {
        List<Pack> l = list(c);
        for (int i = 0; i < l.size(); i++) {
            Pack p = l.get(i);
            if (p.id.equals(id)) l.set(i, new Pack(id, cleanName(name, p.name), cleanEmoji(emoji), clampVol(vol), p.slots));
        }
        save(c, l);
    }

    public static synchronized void delete(Context c, String id) {
        List<Pack> l = list(c);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        save(c, l);
        deleteRecursive(new File(root(c), id));
        if (id.equals(prefs(c).getString("active", ""))) prefs(c).edit().remove("active").commit();
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    // ------------------------------------------------------------------ slots

    private static void replaceSlots(Context c, String packId, List<Slot> slots) {
        List<Pack> l = list(c);
        for (int i = 0; i < l.size(); i++) {
            Pack p = l.get(i);
            if (p.id.equals(packId)) l.set(i, new Pack(p.id, p.name, p.emoji, p.vol, slots));
        }
        save(c, l);
    }

    /** "Add Voice": one more empty command slot. No upper limit. */
    public static synchronized void addSlot(Context c, String packId) {
        Pack p = get(c, packId);
        if (p == null) return;
        List<Slot> s = new ArrayList<>(p.slots);
        s.add(new Slot(newId("s"), "", null));
        replaceSlots(c, packId, s);
    }

    /** Removes the slot completely (and its audio file). */
    public static synchronized void removeSlot(Context c, String packId, String slotId) {
        Pack p = get(c, packId);
        if (p == null) return;
        List<Slot> s = new ArrayList<>();
        for (Slot x : p.slots) {
            if (x.id.equals(slotId)) { if (x.file != null) new File(packDir(c, packId), x.file).delete(); }
            else s.add(x);
        }
        replaceSlots(c, packId, s);
    }

    /** Keeps the slot, removes only its audio (the button stays, empty). */
    public static synchronized void clearSlot(Context c, String packId, String slotId) {
        Pack p = get(c, packId);
        if (p == null) return;
        List<Slot> s = new ArrayList<>();
        for (Slot x : p.slots) {
            if (x.id.equals(slotId)) {
                if (x.file != null) new File(packDir(c, packId), x.file).delete();
                s.add(new Slot(x.id, "", null));
            } else s.add(x);
        }
        replaceSlots(c, packId, s);
    }

    public static synchronized void setSlotLabel(Context c, String packId, String slotId, String label) {
        Pack p = get(c, packId);
        if (p == null) return;
        List<Slot> s = new ArrayList<>();
        for (Slot x : p.slots) {
            s.add(x.id.equals(slotId) ? new Slot(x.id, label == null ? "" : label.trim(), x.file) : x);
        }
        replaceSlots(c, packId, s);
    }

    /**
     * Copies a picked audio file (MP3, WAV, M4A, OGG, ... anything Android can decode) into the pack and
     * puts it into the given slot (replacing its old audio). slotId == null: first empty slot, or a new slot
     * when all are used. Returns the slot id that received the file.
     */
    public static synchronized String assign(Context c, String packId, String slotId, Uri uri) throws IOException {
        Pack p = get(c, packId);
        if (p == null) throw new IOException("প্যাক পাওয়া যায়নি");

        String display = displayName(c, uri);
        File out = copyIn(c, uri, packDir(c, packId), display);
        if (!decodable(out)) {
            out.delete();
            throw new IOException("এই ফাইলটি অডিও হিসেবে পড়া যায়নি: " + display);
        }

        List<Slot> s = new ArrayList<>(p.slots);
        int idx = -1;
        for (int i = 0; i < s.size(); i++) {
            if (slotId != null ? s.get(i).id.equals(slotId) : s.get(i).empty()) { idx = i; break; }
        }
        String label = nameWithoutExt(display);
        if (idx < 0) {
            s.add(new Slot(newId("s"), label, out.getName()));
            idx = s.size() - 1;
        } else {
            Slot old = s.get(idx);
            if (old.file != null && !old.file.equals(out.getName())) new File(packDir(c, packId), old.file).delete();
            // a replaced voice keeps the name the user already gave it
            s.set(idx, new Slot(old.id, old.label.isEmpty() ? label : old.label, out.getName()));
        }
        replaceSlots(c, packId, s);
        return s.get(idx).id;
    }

    /** The audio file of a slot, or null when the slot is empty / the file vanished. */
    public static File fileOf(Context c, Pack p, Slot s) {
        if (s == null || s.file == null) return null;
        File f = new File(packDir(c, p.id), s.file);
        return f.isFile() ? f : null;
    }

    public static String slotLabel(Slot s, int index) {
        if (!s.label.isEmpty()) return s.label;
        if (s.file != null) return nameWithoutExt(s.file);
        return "ভয়েস " + (index + 1);
    }

    // ---------------------------------------------------------------- settings

    public static String activeId(Context c) {
        String id = prefs(c).getString("active", "");
        return get(c, id) == null ? "" : id;
    }

    public static void setActive(Context c, String id) {
        prefs(c).edit().putString("active", id == null ? "" : id).commit();
    }

    /** The pack whose commands the floating panel shows (null when no pack exists at all). */
    public static Pack active(Context c) {
        Pack p = get(c, activeId(c));
        if (p != null) return p;
        List<Pack> l = list(c);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Floating Gaming Voice button position, 0..1 of the free screen area. */
    public static float bubbleX(Context c) { return prefs(c).getFloat("bx", 0f); }
    public static float bubbleY(Context c) { return prefs(c).getFloat("by", 0.5f); }
    public static void setBubblePos(Context c, float fx, float fy) {
        prefs(c).edit().putFloat("bx", fx).putFloat("by", fy).apply();
    }

    /** Panel stays open while you tap the game (instead of closing on any outside touch). */
    public static boolean pinned(Context c) { return prefs(c).getBoolean("pin", false); }
    public static void setPinned(Context c, boolean on) { prefs(c).edit().putBoolean("pin", on).apply(); }

    // ---------------------------------------------------------------- file utils

    private static String displayName(Context c, Uri uri) {
        String name = null;
        try (Cursor q = c.getContentResolver().query(uri, null, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                int i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = q.getString(i);
            }
        } catch (Throwable ignored) {}
        if (name == null || name.trim().isEmpty()) name = "voice_" + System.currentTimeMillis();
        return name.replaceAll("[/\\\\]", "_");
    }

    private static String nameWithoutExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static File copyIn(Context c, Uri uri, File dir, String name) throws IOException {
        File out = unique(dir, name);
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("ফাইল পড়া যায়নি");
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    /** True when Android can find an audio track in the file (so playback will work later). */
    private static boolean decodable(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getPath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        } finally {
            try { ex.release(); } catch (Throwable ignored) {}
        }
    }

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
