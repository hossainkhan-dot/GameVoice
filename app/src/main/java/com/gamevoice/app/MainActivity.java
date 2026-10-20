package com.gamevoice.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.io.File;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private TextView status;
    private LinearLayout list;

    private final Shizuku.OnRequestPermissionResultListener permL = (code, result) -> {
        if (result == PackageManager.PERMISSION_GRANTED) Client.bind(this);
        refresh();
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        status = new TextView(this);
        root.addView(status);
        root.addView(btn("1) Shizuku অনুমতি ও সংযোগ", v -> connect()));
        root.addView(btn("2) ওভারলে পারমিশন", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())))));
        root.addView(btn("3) ভয়েস যোগ করো", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("audio/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, 10);
        }));
        root.addView(btn("4) ভাসমান বাটন চালু", v -> {
            if (!Settings.canDrawOverlays(this)) { toast("আগে ওভারলে পারমিশন দাও"); return; }
            startForegroundService(new Intent(this, FloatingService.class));
        }));
        root.addView(btn("ভাসমান বাটন বন্ধ", v -> stopService(new Intent(this, FloatingService.class))));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        Shizuku.addRequestPermissionResultListener(permL);
        Client.onChange = () -> runOnUiThread(this::refresh);
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        refresh();
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void connect() {
        try {
            if (!Shizuku.pingBinder()) { toast("Shizuku অ্যাপ চালু নেই"); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1);
            else Client.bind(this);
        } catch (Throwable e) { toast("Shizuku নেই: " + e); }
        refresh();
    }

    private void refresh() {
        status.setText("Shizuku: " + (Client.svc != null ? "সংযুক্ত ✓" : "সংযুক্ত নয়")
                + "\nওভারলে: " + (Settings.canDrawOverlays(this) ? "✓" : "✗"));
        list.removeAllViews();
        for (File f : SoundStore.list(this)) {
            Button bt = btn("▶ " + f.getName(), v -> {
                String r = Client.play(f);
                if (!"ok".equals(r)) toast(r);
            });
            bt.setOnLongClickListener(v -> { f.delete(); refresh(); return true; });
            list.addView(bt);
        }
    }

    @Override protected void onActivityResult(int rc, int res, Intent d) {
        super.onActivityResult(rc, res, d);
        if (rc == 10 && res == RESULT_OK && d != null) {
            try {
                if (d.getClipData() != null)
                    for (int i = 0; i < d.getClipData().getItemCount(); i++) SoundStore.add(this, d.getClipData().getItemAt(i).getUri());
                else if (d.getData() != null) SoundStore.add(this, d.getData());
            } catch (Throwable e) { toast("ERR: " + e); }
            refresh();
        }
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permL);
        super.onDestroy();
    }
}
