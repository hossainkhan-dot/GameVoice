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
    private TextView countLabel, addBtn;
    private LinearLayout playerHolder;
    private String curFolder = SoundStore.ALL;
    private String lastMusicName;
    private final Runnable musicL = this::onMusicChange;

    private final Shizuku.OnRequestPermissionResultListener permL = (code, result) -> {
        if (result == PackageManager.PERMISSION_GRANTED) Client.bind(this);
        refresh();
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Client.loadMonitor(this);
        PcmCache.warmAll(this); // songs added earlier get their instant-start copy too
        curFolder = SoundStore.selected(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);

        // Screen = scrolling content + (only in the 🎵 folder) the music player pinned at the bottom.
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Ui.BG);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        playerHolder = new LinearLayout(this);
        playerHolder.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable pbg = new android.graphics.drawable.GradientDrawable();
        pbg.setColor(Ui.CARD);
        float rr = dp(22);
        pbg.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});
        playerHolder.setBackground(pbg);
        playerHolder.setPadding(dp(20), dp(14), dp(20), dp(14));
        playerHolder.addView(new MusicUi.Player(this, false), new LinearLayout.LayoutParams(-1, -2));
        playerHolder.setVisibility(View.GONE);
        outer.addView(playerHolder, new LinearLayout.LayoutParams(-1, -2));
        setContentView(outer);

        TextView title = text("GameVoice", 28, Ui.TEXT, true);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView aboutBtn = Ui.pill(this, "ℹ সম্পর্কে", 0xFF2A3050, Ui.TEXT, 13);
        aboutBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        aboutBtn.setOnClickListener(v -> showAbout());
        titleRow.addView(aboutBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(titleRow);
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
        root.addView(step("৩", "সমস্যা পরীক্ষা (ডায়াগনস্টিক)", v -> diagnose()));

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
        TextView mh = text("ভয়েস বাজালে অন্যরা তো শুনবেই; স্লাইডার টেনে ১–১০০% ঠিক করুন, একদম বামে নিলে বন্ধ।", 12, Ui.MUTED, false);
        mh.setPadding(0, 0, 0, dp(8));
        root.addView(mh);
        // 1..100 % slider (drag), 0 = off
        root.addView(Ui.volumeSlider(this, "🔊 ", Client.getMonitor(), false, true, false,
                pct -> Client.setMonitor(this, pct)));

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
        addBtn = add;
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
        MusicPlayer.addListener(musicL);
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

    // --------------------------------------------------------------- about

    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Throwable e) { toast("লিংকটি খোলা যায়নি"); }
    }

    private TextView linkRow(String label, final Runnable action) {
        TextView t = Ui.pill(this, label, 0xFF2A3050, Ui.TEXT, 13);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** Logo, copyright and ways to contact the developer. */
    private void showAbout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(8));

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.gv_logo);
        box.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView name = text("GameVoice", 22, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(10), 0, 0);
        box.addView(name);

        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        TextView v = text("সংস্করণ " + ver, 12, Ui.MUTED, false);
        v.setGravity(Gravity.CENTER);
        box.addView(v);

        TextView cr = text("© ২০২৬ হোসাইন (Hossain)\nসর্বস্বত্ব সংরক্ষিত", 14, Ui.TEXT, true);
        cr.setGravity(Gravity.CENTER);
        cr.setPadding(0, dp(14), 0, dp(4));
        box.addView(cr);

        TextView note = text("এই অ্যাপের সোর্স কোড, ডিজাইন ও লোগো লেখকের অনুমতি ছাড়া কপি, বিক্রি বা পুনর্বিতরণ করা যাবে না।", 11, Ui.MUTED, false);
        note.setGravity(Gravity.CENTER);
        box.addView(note);

        TextView hd = text("যোগাযোগ", 13, Ui.TEXT, true);
        hd.setPadding(0, dp(16), 0, 0);
        box.addView(hd);
        box.addView(linkRow("✉  hossen.khl1998@gmail.com", () -> {
            try {
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:hossen.khl1998@gmail.com"));
                i.putExtra(Intent.EXTRA_SUBJECT, "GameVoice");
                startActivity(i);
            } catch (Throwable e) { toast("ইমেইল অ্যাপ পাওয়া যায়নি"); }
        }));
        box.addView(linkRow("📘  Facebook: hossain.niloi", () -> openLink("https://www.facebook.com/hossain.niloi")));
        box.addView(linkRow("💻  GitHub: hossainkhan-dot/GameVoice", () -> openLink("https://github.com/hossainkhan-dot/GameVoice")));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new android.app.AlertDialog.Builder(this).setView(sv).setPositiveButton("ঠিক আছে", null).show();
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

    /** Asks the Shizuku-side service to start mic injection and shows exactly what failed, if anything. */
    private void diagnose() {
        final IUserService s = Client.svc;
        if (s == null) { toast("আগে ধাপ ১: Shizuku সংযোগ করুন"); return; }
        toast("পরীক্ষা চলছে…");
        new Thread(() -> {
            String rep;
            try { rep = s.diagnose(); } catch (Throwable e) { rep = "ERR: " + e; }
            final String out = rep;
            runOnUiThread(() -> {
                TextView tv = text(out, 13, Ui.TEXT, false);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(20), dp(12), dp(20), dp(4));
                ScrollView sv = new ScrollView(this);
                sv.addView(tv);
                new android.app.AlertDialog.Builder(this).setTitle("ডায়াগনস্টিক").setView(sv)
                        .setPositiveButton("OK", null).show();
            });
        }, "gv-diag").start();
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
        if (Client.svc != null && Client.injectOk != null) {
            statusRow("মাইকে সরাসরি শব্দ", Client.injectOk);
            if (!Client.injectOk) {
                TextView w = text("এই ফোনের Android এটা অনুমতি দিচ্ছে না, তাই “স্পিকার মোড” চলছে: ভয়েস স্পিকারে বাজবে, মাইক সেটা ধরবে। "
                        + "ফোনের মিডিয়া ভলিউম বাড়ান, ইয়ারফোন লাগাবেন না। (বিস্তারিত: ধাপ ৩)", 12, Ui.MUTED, false);
                w.setPadding(0, dp(6), 0, 0);
                status.addView(w);
            }
        }

        curFolder = SoundStore.selected(this); // a deleted folder falls back to "সব"
        buildFolderBar();

        list.removeAllViews();
        boolean music = SoundStore.MUSIC.equals(curFolder);
        playerHolder.setVisibility(music ? View.VISIBLE : View.GONE);
        addBtn.setText(music ? "＋ মিউজিক যোগ করো" : "＋ যোগ করো");
        if (music) {
            java.util.List<File> songs = SoundStore.listMusic(this);
            countLabel.setText(SoundStore.MUSIC_EMOJI + " " + SoundStore.MUSIC_NAME + " (" + songs.size() + ")");
            lastMusicName = MusicPlayer.cur == null ? null : MusicPlayer.cur.getName();
            buildMusicList(songs);
            return;
        }
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
        // Permanent music folder: always here, cannot be renamed or deleted. Long-press = volume only.
        int mv = SoundStore.sendVolume(this, SoundStore.MUSIC);
        folderBar.addView(folderTab(SoundStore.MUSIC_EMOJI,
                SoundStore.MUSIC_NAME + (mv < 100 ? " · " + mv + "%" : ""),
                SoundStore.MUSIC.equals(curFolder),
                v -> selectFolder(SoundStore.MUSIC), v -> musicVolumeDialog()));
        for (SoundStore.Folder f : SoundStore.folders(this)) {
            int fv = SoundStore.sendVolume(this, f.id); // show "· 50%" on folders that are turned down
            folderBar.addView(folderTab(f.emoji, f.name + (fv < 100 ? " · " + fv + "%" : ""), f.id.equals(curFolder),
                    v -> selectFolder(f.id), v -> folderDialog(f)));
        }
        TextView add = Ui.pill(this, "＋ নতুন ফোল্ডার", Ui.GREEN, 0xFF06210F, 13);
        add.setPadding(dp(14), dp(9), dp(14), dp(9));
        add.setOnClickListener(v -> folderDialog(null));
        folderBar.addView(add);

        SoundStore.Folder cur = SoundStore.folder(this, curFolder);
        if (SoundStore.MUSIC.equals(curFolder)) {
            TextView vb = Ui.pill(this, "🔊 ভলিউম", 0xFF2A3050, Ui.TEXT, 13);
            vb.setPadding(dp(14), dp(9), dp(14), dp(9));
            vb.setOnClickListener(v -> musicVolumeDialog());
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.leftMargin = dp(8);
            folderBar.addView(vb, vlp);
        }
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

        // How loud the person on the other end hears this folder's voices.
        final int[] vol = {fold == null ? 100 : SoundStore.sendVolume(this, fold.id)};
        final TextView volLab = text("", 13, Ui.TEXT, true);
        volLab.setPadding(0, dp(16), 0, dp(2));
        box.addView(volLab);
        TextView volHint = text("অপর প্রান্তের মানুষ এই ফোল্ডারের ভয়েস কত জোরে শুনবে। আপনার নিজের শোনা আলাদা (উপরের “নিজে শুনুন”)।", 11, Ui.MUTED, false);
        box.addView(volHint);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(100);      // 1% steps
        sb.setMin(1);        // 1% .. 100%
        sb.setProgress(Math.max(1, vol[0]));
        volLab.setText("🔊 অপর প্রান্তে: " + sb.getProgress() + "%");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                vol[0] = Math.max(1, p);
                TestPlayer.setVolume(vol[0]);
                volLab.setText("🔊 অপর প্রান্তে: " + vol[0] + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        box.addView(testRow(() -> { if (fold == null) return null; java.util.List<File> l = SoundStore.list(this, fold.id); return l.isEmpty() ? null : l.get(0); }, vol));

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
                        SoundStore.setSendVolume(this, nf.id, vol[0]);
                    } else {
                        SoundStore.updateFolder(this, fold.id, nm, emoji);
                        SoundStore.setSendVolume(this, fold.id, vol[0]);
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
        b.setOnDismissListener(d -> { TestPlayer.stop(); TestPlayer.onChange = null; });
        b.show();
    }

    /** "▶ টেস্ট": plays the folder's first item on YOUR speaker at the slider's level (moving the slider changes it live). */
    private View testRow(final java.util.function.Supplier<File> first, final int[] vol) {
        final TextView t = Ui.pill(this, "▶ টেস্ট", Ui.ACCENT, 0xFFFFFFFF, 13);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        TestPlayer.onChange = () -> t.setText(TestPlayer.isPlaying() ? "■ থামান" : "▶ টেস্ট");
        t.setOnClickListener(v -> {
            if (TestPlayer.isPlaying()) { TestPlayer.stop(); return; }
            File f = first.get();
            if (f == null) { toast("এই ফোল্ডারে টেস্ট করার মতো কিছু নেই"); return; }
            TestPlayer.play(this, f, vol[0]);
        });
        TextView hint = text("টেস্ট শুধু আপনার স্পিকারে বাজে, অপর পাশে যায় না।", 11, Ui.MUTED, false);
        hint.setPadding(dp(10), 0, 0, 0);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, dp(4));
        r.addView(t, new LinearLayout.LayoutParams(-2, -2));
        r.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    // --------------------------------------------------------------- music

    private void onMusicChange() {
        // The player view updates itself; only the song list needs a redraw when the current song changes.
        String n = MusicPlayer.cur == null ? null : MusicPlayer.cur.getName();
        if (java.util.Objects.equals(n, lastMusicName)) return;
        lastMusicName = n;
        if (SoundStore.MUSIC.equals(curFolder)) {
            list.removeAllViews();
            buildMusicList(SoundStore.listMusic(this));
        }
    }

    private void buildMusicList(java.util.List<File> songs) {
        if (songs.isEmpty()) {
            TextView e = text("এখনো কোনো মিউজিক নেই। উপরের “＋ মিউজিক যোগ করো” চাপুন, ফোন থেকে গান বেছে নিন, তারপর এখান থেকে বাজান। "
                    + "এই ফোল্ডারে শুধু মিউজিক থাকে, ভয়েসের সাথে মেশে না।", 13, Ui.MUTED, false);
            e.setPadding(0, dp(12), 0, 0);
            list.addView(e);
        }
        for (File f : songs) list.addView(musicRow(f));
    }

    private View musicRow(final File f) {
        final String label = SoundStore.musicLabel(this, f);
        boolean isCur = MusicPlayer.cur != null && MusicPlayer.cur.getName().equals(f.getName());
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg = Ui.round(Ui.CARD, this, 14);
        if (isCur) bg.setStroke(dp(1.5f), Ui.ACCENT);
        r.setBackground(bg);
        r.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);

        TextView g = Ui.pill(this, isCur ? "♫" : "♪", isCur ? Ui.ACCENT : 0xFF2A3050, 0xFFFFFFFF, 18);
        r.addView(g, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView n = text(label, 15, isCur ? Ui.ACCENT : Ui.TEXT, isCur);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.leftMargin = dp(12);
        r.addView(n, nlp);

        TextView edit = Ui.pill(this, "✎", 0xFF2A3050, Ui.TEXT, 15);
        edit.setOnClickListener(v -> renameMusic(f));
        r.addView(edit, small());
        TextView del = Ui.pill(this, "✕", 0xFF2A3050, Ui.RED, 14);
        del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("“" + label + "” মুছে ফেলবেন?")
                .setPositiveButton("মুছুন", (d, w) -> {
                    MusicPlayer.removeIfCurrent(f);
                    SoundStore.deleteMusic(this, f);
                    refresh();
                })
                .setNegativeButton("না", null).show());
        r.addView(del, small());

        r.setOnClickListener(v -> MusicPlayer.play(this, f));
        return r;
    }

    private void renameMusic(File f) {
        EditText et = new EditText(this);
        et.setText(SoundStore.musicLabel(this, f));
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new android.app.AlertDialog.Builder(this)
                .setTitle("নাম বদলান")
                .setView(et)
                .setPositiveButton("সেভ", (d, w) -> {
                    SoundStore.setMusicLabel(this, f, et.getText().toString());
                    refresh();
                })
                .setNegativeButton("বাতিল", null).show();
    }

    /** The music folder has no name / icon to edit; only how loud the other side hears it. */
    private void musicVolumeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        final int[] vol = {SoundStore.sendVolume(this, SoundStore.MUSIC)};
        final TextView volLab = text("", 14, Ui.TEXT, true);
        box.addView(volLab);
        TextView hint = text("অপর প্রান্তের মানুষ মিউজিক কত জোরে শুনবে। আপনার নিজের শোনা আলাদা (মূল পর্দার “নিজে শুনুন”)। "
                + "গান চলা অবস্থায় বদলালে সাথে সাথে কাজ করে।", 11, Ui.MUTED, false);
        hint.setPadding(0, dp(2), 0, dp(6));
        box.addView(hint);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setMin(1);
        sb.setProgress(Math.max(1, vol[0]));
        volLab.setText("🔊 অপর প্রান্তে: " + sb.getProgress() + "%");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                vol[0] = Math.max(1, p);
                TestPlayer.setVolume(vol[0]);
                volLab.setText("🔊 অপর প্রান্তে: " + vol[0] + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        box.addView(testRow(() -> { java.util.List<File> l = SoundStore.listMusic(this); return l.isEmpty() ? null : l.get(0); }, vol));
        new android.app.AlertDialog.Builder(this)
                .setTitle(SoundStore.MUSIC_EMOJI + " " + SoundStore.MUSIC_NAME + " ফোল্ডার")
                .setView(box)
                .setPositiveButton("সেভ", (d, w) -> {
                    SoundStore.setSendVolume(this, SoundStore.MUSIC, vol[0]);
                    MusicPlayer.onSendVolumeChanged(this);
                    refresh();
                })
                .setNegativeButton("বাতিল", null)
                .setOnDismissListener(d -> { TestPlayer.stop(); TestPlayer.onChange = null; })
                .show();
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
                // New voices go straight into the folder that is open right now (songs into 🎵).
                boolean music = SoundStore.MUSIC.equals(curFolder);
                if (d.getClipData() != null) {
                    for (int i = 0; i < d.getClipData().getItemCount(); i++) {
                        Uri u = d.getClipData().getItemAt(i).getUri();
                        if (music) SoundStore.addMusic(this, u); else SoundStore.add(this, u, curFolder);
                    }
                } else if (d.getData() != null) {
                    if (music) SoundStore.addMusic(this, d.getData()); else SoundStore.add(this, d.getData(), curFolder);
                }
            } catch (Throwable e) { toast("ERR: " + e); }
            refresh();
        }
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permL);
        MusicPlayer.removeListener(musicL);
        super.onDestroy();
    }
}
