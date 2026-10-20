package com.gamevoice.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.io.*;
import java.util.*;

public class SoundStore {
    /** Self-monitor choices (percent). 0 = off. */
    public static final int[] MONITOR_LEVELS = {0, 30, 60, 100};

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gv", Context.MODE_PRIVATE);
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sounds");
        d.mkdirs();
        return d;
    }
    public static List<File> list(Context c) {
        File[] fs = dir(c).listFiles();
        List<File> l = fs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(fs));
        Collections.sort(l);
        return l;
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
        prefs(c).edit().remove("name:" + f.getName()).apply();
    }

    public static int getMonitor(Context c) { return prefs(c).getInt("monitor", 30); }
    public static void setMonitor(Context c, int pct) { prefs(c).edit().putInt("monitor", pct).apply(); }

    public static void add(Context c, Uri uri) throws IOException {
        String name = "sound_" + System.currentTimeMillis();
        try (Cursor q = c.getContentResolver().query(uri, null, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                int i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = q.getString(i);
            }
        }
        name = name.replaceAll("[/\\\\]", "_");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(new File(dir(c), name))) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }
}
