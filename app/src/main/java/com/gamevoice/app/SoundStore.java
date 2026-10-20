package com.gamevoice.app;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.io.*;
import java.util.*;

public class SoundStore {
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
