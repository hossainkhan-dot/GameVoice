package com.gamevoice.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private LinearLayout status, list, monitorRow, folderBar;
    private TextView countLabel;
    private String curFolder = SoundStore.ALL;

    private final Shizuku.OnRequestPermissionResultListener permL = (code, result) -> {
        if (result == PackageManager.PERMISSION_GRANTED) Client.bind(this);
        refresh();
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Client.loadMonitor(this);
        curFolder = SoundStore.selected(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);

        TextView title = text("GameVoice", 28, Ui.TEXT, true);
        root.addView(title);
        TextView sub = text("গেমের ভিতরে এক ট্যাপে ভয়েস প্যাক", 13, Ui.MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(16));
        root.addView(sub);

        // status card
        status = card();
        root.addView(status);

        // setup
        root.addView(section("সেটআপ"));
        root.addView(step("১", "Shizuku অনুমতি ও সংযোগ", v -> connect()));
        root.addView(step("২", "ওভারলে পারমিশন", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())))));

        // overlay on / off
        LinearLayout onoff = new LinearLayout(this);
        onoff.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = dp(10);
        root.addView(onoff, olp);
        TextView on = bigBtn("🎤  ভাসমান বাটন চালু", Ui.ACCENT, v -> {
            if (!Settings.canDrawOverlays(this)) { toast("আগে ওভারলে পারমিশন দাও"); return; }
            startForegroundService(new Intent(this, FloatingService.class));
        });
        TextView off = bigBtn("বন্ধ", 0xFF2A3050, v -> stopService(new Intent(this, FloatingService.class)));
        onoff.addView(on, new LinearLayout.LayoutParams(0, dp(48), 3f));
        LinearLayout.LayoutParams offp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        offp.leftMargin = dp(8);
        onoff.addView(off, offp);

        // self monitor
        root.addView(section("নিজে শুনুন (আপনার স্পিকারে)"));
        TextView mh = text("ভয়েস বাজালে অন্যরা তো শুনবেই; আপনি নিজে কত জোরে শুনতে চান তা বেছে নিন।", 12, Ui.MUTED, false);
        mh.setPadding(0, 0, 0, dp(8));
        root.addView(mh);
        monitorRow = new LinearLayout(this);
        monitorRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(monitorRow);
        int[] lv = SoundStore.MONITOR_LEVELS;
        for (int pct : lv) {
            TextView p = Ui.pill(this, pct == 0 ? "বন্ধ" : pct + "%", 0, Ui.TEXT, 14);
            p.setTag(pct);
            p.setOnClickListener(v -> { Client.setMonitor(this, pct); styleMonitor(); });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (pct != lv[0]) plp.leftMargin = dp(8);
            monitorRow.addView(p, plp);
        }
        styleMonitor();

        // sounds
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(22);
        root.addView(head, hlp);
        countLabel = text("আমার ভয়েস", 15, Ui.TEXT, true);
        head.addView(countLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView add = Ui.pill(this, "＋ যোগ করো", Ui.GREEN, 0xFF06210F, 13);
        add.setPadding(dp(14), dp(8), dp(14), dp(8));
        add.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("audio/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, 10);
        });
        head.addView(add);

        // folders: [📂 সব] [😂 ফানি] ... [＋ নতুন ফোল্ডার]
        HorizontalScrollView fs = new HorizontalScrollView(this);
        fs.setHorizontalScrollBarEnabled(false);
        folderBar = new LinearLayout(this);
        folderBar.setOrientation(LinearLayout.HORIZONTAL);
        folderBar.setGravity(Gravity.CENTER_VERTICAL);
        fs.addView(folderBar);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(10);
        root.addView(fs, flp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(8);
        root.addView(list, llp);

        Shizuku.addRequestPermissionResultListener(permL);
        Client.onChange = () -> runOnUiThread(this::refresh);
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        refresh();
    }

    // ------------------------------------------------------------- helpers

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(Ui.CARD, this, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private TextView section(String s) {
        TextView t = text(s, 13, Ui.MUTED, true);
        t.setPadding(0, dp(22), 0, dp(8));
        return t;
    }

    private View step(String num, String label, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.round(Ui.CARD, this, 14));
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        TextView n = Ui.pill(this, num, Ui.ACCENT, 0xFFFFFFFF, 13);
        r.addView(n, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = text(label, 15, Ui.TEXT, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(12);
        r.addView(t, tlp);
        r.addView(text("›", 20, Ui.MUTED, false));
        r.setOnClickListener(l);
        return r;
    }

    private TextView bigBtn(String s, int bg, View.OnClickListener l) {
        TextView t = Ui.pill(this, s, bg, 0xFFFFFFFF, 14);
        t.setBackground(Ui.round(bg, this, 14));
        t.setOnClickListener(l);
        return t;
    }

    private void styleMonitor() {
        int cur = Client.getMonitor();
        for (int i = 0; i < monitorRow.getChildCount(); i++) {
            TextView p = (TextView) monitorRow.getChildAt(i);
            boolean on = ((Integer) p.getTag()) == cur;
            p.setBackground(Ui.round(on ? Ui.ACCENT : Ui.CARD, this, 100));
            p.setTextColor(on ? 0xFFFFFFFF : Ui.MUTED);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void showErr(String s) {
        new android.app.AlertDialog.Builder(this).setMessage(s).setPositiveButton("OK", null).show();
    }

    private void connect() {
        try {
            if (!Shizuku.pingBinder()) { toast("Shizuku অ্যাপ চালু নেই"); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1);
            else Client.bind(this);
        } catch (Throwable e) { toast("Shizuku নেই: " + e); }
        refresh();
    }

    private void statusRow(String label, boolean ok) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(3), 0, dp(3));
        View dot = new View(this);
        dot.setBackground(Ui.oval(ok ? Ui.GREEN : Ui.RED));
        r.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView t = text(label + (ok ? " — সংযুক্ত ✓" : " — নেই"), 14, Ui.TEXT, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(10);
        r.addView(t, tlp);
        status.addView(r);
    }

    private void refresh() {
        status.removeAllViews();
        statusRow("Shizuku", Client.svc != null);
        statusRow("ওভারলে পারমিশন", Settings.canDrawOverlays(this));

        curFolder = SoundStore.selected(this); // a deleted folder falls back to "সব"
        buildFolderBar();

        list.removeAllViews();
        java.util.List<File> files = SoundStore.list(this, curFolder);
        SoundStore.Folder cur = SoundStore.folder(this, curFolder);
        countLabel.setText((cur == null ? "আমার ভয়েস" : cur.emoji + " " + cur.name) + " (" + files.size() + ")");
        if (files.isEmpty()) {
            TextView e = text(cur == null
                    ? "এখনো কোনো ভয়েস নেই। উপরের “যোগ করো” চাপুন।"
                    : "এই ফোল্ডারে কোনো ভয়েস নেই। “যোগ করো” চাপলে এখানেই যোগ হবে।", 13, Ui.MUTED, false);
            e.setPadding(0, dp(12), 0, 0);
            list.addView(e);
        }
        for (File f : files) list.addView(row(f));
    }

    // ------------------------------------------------------------- folders

    private void buildFolderBar() {
        folderBar.removeAllViews();
        folderBar.addView(folderTab("📂", "সব", curFolder.isEmpty(), v -> selectFolder(SoundStore.ALL), null));
        for (SoundStore.Folder f : SoundStore.folders(this)) {
            folderBar.addView(folderTab(f.emoji, f.name, f.id.equals(curFolder),
                    v -> selectFolder(f.id), v -> folderDialog(f)));
        }
        TextView add = Ui.pill(this, "＋ নতুন ফোল্ডার", Ui.GREEN, 0xFF06210F, 13);
        add.setPadding(dp(14), dp(9), dp(14), dp(9));
        add.setOnClickListener(v -> folderDialog(null));
        folderBar.addView(add);

        SoundStore.Folder cur = SoundStore.folder(this, curFolder);
        if (cur != null) {
            TextView edit = Ui.pill(this, "✎ এডিট", 0xFF2A3050, Ui.TEXT, 13);
            edit.setPadding(dp(14), dp(9), dp(14), dp(9));
            edit.setOnClickListener(v -> folderDialog(cur));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
            elp.leftMargin = dp(8);
            folderBar.addView(edit, elp);
        }
    }

    private TextView folderTab(String emoji, String name, boolean on, View.OnClickListener click,
                               View.OnClickListener longClick) {
        TextView t = Ui.folderChip(this, emoji, name, on, 14, 14, 9);
        t.setOnClickListener(click);
        if (longClick != null) t.setOnLongClickListener(v -> { longClick.onClick(v); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void selectFolder(String id) {
        curFolder = id;
        SoundStore.setSelected(this, id);
        refresh();
    }

    /** Create (fold == null) or edit a folder: name + emoji icon (from the grid or your own). */
    private void folderDialog(final SoundStore.Folder fold) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        final EditText name = new EditText(this);
        name.setHint("ফোল্ডারের নাম, যেমন: ফানি");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        if (fold != null) name.setText(fold.name);
        box.addView(name);

        TextView lab = text("ইমোজি বেছে নিন (এটাই ফোল্ডারের আইকন)", 12, Ui.MUTED, false);
        lab.setPadding(0, dp(14), 0, dp(6));
        box.addView(lab);

        final EditText custom = new EditText(this);
        final Ui.EmojiPicker pick = new Ui.EmojiPicker(this,
                fold == null ? SoundStore.DEFAULT_EMOJI : fold.emoji, 7, 40);
        pick.onPick = () -> custom.setText("");
        box.addView(pick);

        custom.setHint("অথবা কীবোর্ড থেকে নিজের ইমোজি দিন");
        custom.setSingleLine(true);
        custom.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(custom);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(fold == null ? "নতুন ফোল্ডার" : "ফোল্ডার এডিট")
                .setView(sv)
                .setPositiveButton("সেভ", (d, w) -> {
                    String emoji = Ui.firstGrapheme(custom.getText().toString());
                    if (emoji.isEmpty()) emoji = pick.value;
                    String nm = name.getText().toString();
                    if (fold == null) {
                        SoundStore.Folder nf = SoundStore.createFolder(this, nm, emoji);
                        curFolder = nf.id;
                        SoundStore.setSelected(this, nf.id);
                    } else {
                        SoundStore.updateFolder(this, fold.id, nm, emoji);
                    }
                    refresh();
                })
                .setNegativeButton("বাতিল", null);
        if (fold != null) {
            b.setNeutralButton("মুছুন", (d, w) -> new android.app.AlertDialog.Builder(this)
                    .setMessage("“" + fold.name + "” ফোল্ডার মুছবেন?\nভেতরের ভয়েসগুলো মুছবে না, “সব”-এ থেকে যাবে।")
                    .setPositiveButton("মুছুন", (d2, w2) -> {
                        SoundStore.deleteFolder(this, fold.id);
                        refresh();
                    })
                    .setNegativeButton("না", null).show());
        }
        b.show();
    }

    /** Move one voice into a folder (or out of all folders). */
    private void moveDialog(final File f) {
        final java.util.List<SoundStore.Folder> fl = SoundStore.folders(this);
        if (fl.isEmpty()) { toast("আগে একটি ফোল্ডার বানান"); return; }
        String[] items = new String[fl.size() + 1];
        items[0] = "📄 কোনো ফোল্ডার নয়";
        for (int i = 0; i < fl.size(); i++) items[i + 1] = fl.get(i).emoji + " " + fl.get(i).name;
        new android.app.AlertDialog.Builder(this)
                .setTitle("কোন ফোল্ডারে রাখবেন?")
                .setItems(items, (d, w) -> {
                    SoundStore.setFolder(this, f, w == 0 ? SoundStore.ALL : fl.get(w - 1).id);
                    refresh();
                }).show();
    }

    private View row(File f) {
        String label = SoundStore.label(this, f);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.round(Ui.CARD, this, 14));
        r.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);

        TextView g = Ui.pill(this, Ui.glyph(label), Ui.colorFor(f.getName()), 0xFFFFFFFF, 17);
        g.setBackground(Ui.oval(Ui.colorFor(f.getName())));
        final boolean fav = SoundStore.isFav(this, f);
        FrameLayout gf = new FrameLayout(this);
        gf.addView(g, new FrameLayout.LayoutParams(-1, -1));
        if (fav) gf.addView(Ui.star(this), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        r.addView(gf, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView n = text(label, 15, Ui.TEXT, false);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(n);
        SoundStore.Folder fo = SoundStore.folder(this, SoundStore.folderOf(this, f));
        if (fo != null && curFolder.isEmpty()) { // in "সব" show which folder each voice is in
            TextView sub = text(fo.emoji + " " + fo.name, 11, Ui.MUTED, false);
            sub.setMaxLines(1);
            txt.addView(sub);
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.leftMargin = dp(12);
        r.addView(txt, nlp);

        TextView st = Ui.pill(this, fav ? "★" : "☆", 0xFF2A3050, fav ? Ui.GOLD : Ui.MUTED, 15);
        st.setOnClickListener(v -> { SoundStore.setFav(this, f, !fav); refresh(); });
        r.addView(st, small());
        TextView mv = Ui.pill(this, "📁", 0xFF2A3050, Ui.TEXT, 14);
        mv.setOnClickListener(v -> moveDialog(f));
        r.addView(mv, small());
        TextView edit = Ui.pill(this, "✎", 0xFF2A3050, Ui.TEXT, 15);
        edit.setOnClickListener(v -> rename(f));
        r.addView(edit, small());
        TextView del = Ui.pill(this, "✕", 0xFF2A3050, Ui.RED, 14);
        del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("“" + label + "” মুছে ফেলবেন?")
                .setPositiveButton("মুছুন", (d, w) -> { SoundStore.delete(this, f); refresh(); })
                .setNegativeButton("না", null).show());
        r.addView(del, small());

        r.setOnClickListener(v -> {
            String res = Client.play(this, f);
            if (!"ok".equals(res)) showErr(res);
        });
        return r;
    }

    private LinearLayout.LayoutParams small() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(32), dp(32));
        p.leftMargin = dp(4);
        return p;
    }

    private void rename(File f) {
        EditText et = new EditText(this);
        et.setText(SoundStore.label(this, f));
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new android.app.AlertDialog.Builder(this)
                .setTitle("নাম বদলান")
                .setView(et)
                .setPositiveButton("সেভ", (d, w) -> { SoundStore.setLabel(this, f, et.getText().toString()); refresh(); })
                .setNegativeButton("বাতিল", null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        // Recover the Shizuku user-service binding when returning to the app.
        try {
            if (Client.svc == null && Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Client.bind(getApplicationContext());
            }
        } catch (Throwable ignored) {}
        refresh();
    }

    @Override protected void onActivityResult(int rc, int res, Intent d) {
        super.onActivityResult(rc, res, d);
        if (rc == 10 && res == RESULT_OK && d != null) {
            try {
                // New voices go straight into the folder that is open right now.
                if (d.getClipData() != null)
                    for (int i = 0; i < d.getClipData().getItemCount(); i++) SoundStore.add(this, d.getClipData().getItemAt(i).getUri(), curFolder);
                else if (d.getData() != null) SoundStore.add(this, d.getData(), curFolder);
            } catch (Throwable e) { toast("ERR: " + e); }
            refresh();
        }
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permL);
        super.onDestroy();
    }
}
