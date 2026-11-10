package com.gamevoice.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen of the quick voice wheel (landscape, same layout as the "equipped messages" screen of a game):
 *   left   : every folder of the app (all sounds, voice folders, music, old packs)
 *   middle : the sounds of the chosen folder. Press and HOLD a sound, drag it onto a slot of the wheel and let go.
 *   right  : the wheel itself. Hold a filled slot to move it (onto another slot = swap, onto the hub = remove).
 * Nothing changes for the overlay until SAVE is pressed.
 */
public class WheelActivity extends Activity {
    private static final int REQ_AUDIO = 31;
    private static final int GOLD = WheelView.GOLD;
    private static final int PURPLE_BG = 0xFF150F1F, ROW_BG = 0xFF241B38, DARK_TEXT = 0xFF1B1226;

    private WheelView wheel;
    private LinearLayout folderCol, soundCol;
    private ScrollView soundScroll;
    private TextView title, countView, saveBtn, clearBtn, hint;

    private List<WheelStore.Source> sources = new ArrayList<>();
    private int srcIndex = 0;
    private String[] draft;
    private int draftCount;
    private boolean dirty;
    private String previewPath = null;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(PURPLE_BG);
        getWindow().setNavigationBarColor(PURPLE_BG);
        draftCount = WheelStore.count(this);
        draft = WheelStore.paths(this);
        build();
        reloadSources();
    }

    @Override protected void onPause() {
        TestPlayer.stop();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        new android.app.AlertDialog.Builder(this)
                .setMessage("সেভ না করা পরিবর্তন আছে।")
                .setPositiveButton("সেভ করে বের হন", (d, w) -> { save(); finish(); })
                .setNegativeButton("সেভ ছাড়া বের হন", (d, w) -> { dirty = false; finish(); })
                .setNeutralButton("থাকুন", null).show();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ layout

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PURPLE_BG);
        root.setPadding(dp(14), dp(8), dp(14), dp(10));
        setContentView(root);

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.pill(this, "‹", 0xFF2A2140, Ui.TEXT, 20);
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView t = text("🎮  কুইক ভয়েস", 18, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(12);
        bar.addView(t, tlp);
        TextView help = text("সাউন্ড চেপে ধরে টেনে চাকার ঘরে ছাড়ুন", 12, Ui.MUTED, false);
        bar.addView(help);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(6);
        root.addView(body, blp);

        // ---- left: folders
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        ScrollView fs = new ScrollView(this);
        fs.setVerticalScrollBarEnabled(false);
        folderCol = new LinearLayout(this);
        folderCol.setOrientation(LinearLayout.VERTICAL);
        fs.addView(folderCol);
        left.addView(fs, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView addFolder = Ui.pill(this, "＋ ফোল্ডার", 0xFF2A2140, Ui.GREEN, 12);
        addFolder.setOnClickListener(v -> newFolderDialog());
        LinearLayout.LayoutParams aflp = new LinearLayout.LayoutParams(-1, dp(34));
        aflp.topMargin = dp(6);
        left.addView(addFolder, aflp);
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 2.1f));

        // ---- middle: sounds of the chosen folder
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(0xFF000000);
        head.setPadding(dp(10), dp(4), dp(4), dp(4));
        title = text("", 13, GOLD, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView add = Ui.pill(this, "＋ সাউন্ড যোগ", GOLD, DARK_TEXT, 11);
        add.setPadding(dp(10), dp(5), dp(10), dp(5));
        add.setOnClickListener(v -> pickAudio());
        head.addView(add, new LinearLayout.LayoutParams(-2, -2));
        mid.addView(head, new LinearLayout.LayoutParams(-1, dp(36)));

        soundScroll = new ScrollView(this);
        soundScroll.setVerticalScrollBarEnabled(false);
        soundScroll.setBackgroundColor(0xFF1C1429);
        soundCol = new LinearLayout(this);
        soundCol.setOrientation(LinearLayout.VERTICAL);
        soundCol.setPadding(dp(4), dp(4), dp(4), dp(4));
        soundScroll.addView(soundCol);
        mid.addView(soundScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -1, 3.4f);
        mlp.leftMargin = dp(8);
        body.addView(mid, mlp);

        // dropping a wheel slot on the folder / sound lists removes it from the wheel
        View.OnDragListener clearDrop = (v, ev) -> {
            if (ev.getAction() == DragEvent.ACTION_DRAG_STARTED) return ev.getLocalState() instanceof Integer;
            if (ev.getAction() == DragEvent.ACTION_DROP && ev.getLocalState() instanceof Integer) {
                clearSlot((Integer) ev.getLocalState());
                return true;
            }
            return ev.getAction() != DragEvent.ACTION_DROP;
        };
        soundScroll.setOnDragListener(clearDrop);
        fs.setOnDragListener(clearDrop);

        // arrow between the lists and the wheel, like the reference screen
        TextView arrow = text("▶", 22, 0x88FFFFFF, false);
        arrow.setGravity(Gravity.CENTER);
        body.addView(arrow, new LinearLayout.LayoutParams(dp(26), -1));

        // ---- right: the wheel
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackgroundColor(0xFF1C1429);

        LinearLayout rh = new LinearLayout(this);
        rh.setOrientation(LinearLayout.HORIZONTAL);
        rh.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF6B5416, 0xFF2A2210});
        rh.setBackground(g);
        rh.setPadding(dp(12), dp(2), dp(6), dp(2));
        TextView rt = text("ওয়ালে সাজানো ভয়েস", 14, 0xFFFFFFFF, true);
        rh.addView(rt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView minus = Ui.pill(this, "－", 0xFF2A2140, Ui.TEXT, 16);
        minus.setOnClickListener(v -> changeCount(-1));
        rh.addView(minus, new LinearLayout.LayoutParams(dp(30), dp(30)));
        countView = text("", 13, GOLD, true);
        countView.setGravity(Gravity.CENTER);
        rh.addView(countView, new LinearLayout.LayoutParams(dp(54), -2));
        TextView plus = Ui.pill(this, "＋", 0xFF2A2140, Ui.GREEN, 16);
        plus.setOnClickListener(v -> changeCount(1));
        rh.addView(plus, new LinearLayout.LayoutParams(dp(30), dp(30)));
        right.addView(rh, new LinearLayout.LayoutParams(-1, dp(36)));

        FrameLayout wf = new FrameLayout(this);
        wheel = new WheelView(this);
        wheel.setEditMode(true);
        setupWheelTouch();
        wf.addView(wheel, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        right.addView(wf, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(8), 0, dp(8), dp(6));
        hint = text("", 11, Ui.MUTED, false);
        foot.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        clearBtn = Ui.pill(this, "🗑 খালি", 0xFF2A2140, Ui.RED, 12);
        clearBtn.setOnClickListener(v -> {
            int s = wheel.selected();
            if (s >= 0) clearSlot(s);
        });
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(dp(84), dp(34));
        cblp.rightMargin = dp(8);
        foot.addView(clearBtn, cblp);
        saveBtn = Ui.pill(this, "SAVE", 0xFF555560, 0xFFFFFFFF, 14);
        saveBtn.setOnClickListener(v -> { if (dirty) { save(); toast("সেভ হয়েছে ✔"); } });
        foot.addView(saveBtn, new LinearLayout.LayoutParams(dp(110), dp(36)));
        right.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        body.addView(right, new LinearLayout.LayoutParams(0, -1, 4.6f));
    }

    // ------------------------------------------------------------- wheel logic

    private void setupWheelTouch() {
        final float[] down = new float[2];
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { down[0] = e.getX(); down[1] = e.getY(); return true; }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                int i = wheel.indexAt(e.getX(), e.getY());
                if (i < 0) { wheel.setSelected(-1); updateBars(); return true; }
                wheel.setSelected(i);
                File f = WheelStore.fileAt(draft, i);
                if (f != null) preview(f);
                else toast("সাউন্ড চেপে ধরে এই ঘরে ছাড়ুন, অথবা লিস্টের ＋ চাপুন");
                updateBars();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                int i = wheel.indexAt(down[0], down[1]);
                File f = i >= 0 ? WheelStore.fileAt(draft, i) : null;
                if (f == null) return;
                wheel.setSelected(i);
                updateBars();
                TextView chip = text(WheelStore.labelOf(WheelActivity.this, f), 13, DARK_TEXT, true);
                chip.setBackground(Ui.round(GOLD, WheelActivity.this, 14));
                chip.setPadding(dp(12), dp(7), dp(12), dp(7));
                chip.setMaxWidth(dp(160));
                chip.setSingleLine(true);
                chip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                chip.layout(0, 0, chip.getMeasuredWidth(), chip.getMeasuredHeight());
                ClipData cd = ClipData.newPlainText("gv", f.getPath());
                if (wheel.startDragAndDrop(cd, new View.DragShadowBuilder(chip), Integer.valueOf(i), View.DRAG_FLAG_OPAQUE)) {
                    wheel.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            }
        });
        wheel.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });

        wheel.setOnDragListener((v, ev) -> {
            switch (ev.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    wheel.setDragging(true);
                    if (ev.getLocalState() instanceof Integer) wheel.setHub("🗑", "ছাড়লে মুছবে", Ui.RED);
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION: {
                    int i = wheel.indexAt(ev.getX(), ev.getY());
                    if (i >= draftCount) i = -1;
                    wheel.setHover(i);
                    return true;
                }
                case DragEvent.ACTION_DRAG_EXITED:
                    wheel.setHover(-1);
                    return true;
                case DragEvent.ACTION_DROP: {
                    float x = ev.getX(), y = ev.getY();
                    int i = wheel.indexAt(x, y);
                    wheel.setHover(-1);
                    Object ls = ev.getLocalState();
                    if (ls instanceof Integer) {
                        int from = (Integer) ls;
                        if (i >= 0 && i != from) swapSlots(from, i);
                        else if (i < 0 && wheel.inHub(x, y)) clearSlot(from);
                    } else if (i >= 0) {
                        CharSequence p = ev.getClipData().getItemAt(0).getText();
                        if (p != null) putSlot(i, p.toString());
                    }
                    return true;
                }
                case DragEvent.ACTION_DRAG_ENDED:
                    wheel.setDragging(false);
                    wheel.setHover(-1);
                    refreshWheel();
                    return true;
            }
            return false;
        });
    }

    private void markDirty() { dirty = true; updateBars(); }

    private void putSlot(int i, String path) {
        if (i < 0 || i >= draftCount) return;
        draft[i] = path;
        wheel.setSelected(i);
        markDirty();
        refreshWheel();
        refreshSounds();
    }

    private void swapSlots(int a, int b) {
        String t = draft[a];
        draft[a] = draft[b];
        draft[b] = t;
        wheel.setSelected(b);
        markDirty();
        refreshWheel();
    }

    private void clearSlot(int i) {
        if (i < 0 || i >= draft.length || draft[i] == null) return;
        draft[i] = null;
        markDirty();
        refreshWheel();
        refreshSounds();
    }

    private void changeCount(int d) {
        int n = Math.max(WheelStore.MIN_COUNT, Math.min(WheelStore.MAX_COUNT, draftCount + d));
        if (n == draftCount) return;
        draftCount = n;
        if (wheel.selected() >= n) wheel.setSelected(-1);
        markDirty();
        refreshWheel();
        refreshSounds();
    }

    private void refreshWheel() {
        wheel.setSlots(WheelStore.labels(this, draftCount, draft));
        wheel.setHub(String.valueOf(draftCount), "ঘর", GOLD);
        updateBars();
    }

    private void updateBars() {
        int filled = 0;
        for (int i = 0; i < draftCount; i++) if (WheelStore.fileAt(draft, i) != null) filled++;
        countView.setText(draftCount + " ঘর");
        hint.setText(filled + "/" + draftCount + " ঘরে সাউন্ড আছে");
        saveBtn.setBackground(Ui.round(dirty ? GOLD : 0xFF555560, this, 100));
        saveBtn.setTextColor(dirty ? DARK_TEXT : 0xFFB8B8C0);
        int s = wheel.selected();
        boolean can = s >= 0 && WheelStore.fileAt(draft, s) != null;
        clearBtn.setAlpha(can ? 1f : 0.35f);
    }

    private void save() {
        WheelStore.save(this, draftCount, draft);
        dirty = false;
        updateBars();
    }

    // --------------------------------------------------------------- library

    private void reloadSources() {
        sources = WheelStore.sources(this);
        if (srcIndex >= sources.size()) srcIndex = 0;
        folderCol.removeAllViews();
        for (int i = 0; i < sources.size(); i++) {
            final int idx = i;
            WheelStore.Source s = sources.get(i);
            boolean on = i == srcIndex;
            TextView t = text(s.emoji + "  " + s.name, 14, on ? DARK_TEXT : 0xFFE6E0F5, on);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(10), 0, dp(6), 0);
            t.setBackground(on ? Ui.round(GOLD, this, 4) : null);
            t.setOnClickListener(v -> { srcIndex = idx; reloadSources(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
            lp.bottomMargin = dp(4);
            folderCol.addView(t, lp);
        }
        refreshSounds();
        refreshWheel();
    }

    private boolean onWheel(File f) {
        for (int i = 0; i < draftCount; i++) if (f.getPath().equals(draft[i])) return true;
        return false;
    }

    private void refreshSounds() {
        if (soundCol == null || sources.isEmpty()) return;
        WheelStore.Source src = sources.get(srcIndex);
        List<File> files = WheelStore.files(this, src);
        title.setText(src.emoji + " " + src.name + "  (" + files.size() + ")");
        int keep = soundScroll.getScrollY();
        soundCol.removeAllViews();
        if (files.isEmpty()) {
            TextView e = text("এই ফোল্ডারে কোনো সাউন্ড নেই।\nউপরের “＋ সাউন্ড যোগ” চাপুন।", 12, Ui.MUTED, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(24), 0, 0);
            soundCol.addView(e);
        }
        for (File f : files) soundCol.addView(soundRow(f));
        soundScroll.post(() -> soundScroll.scrollTo(0, keep));
    }

    private View soundRow(final File f) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.round(ROW_BG, this, 6));
        r.setPadding(dp(10), 0, dp(4), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.bottomMargin = dp(4);
        r.setLayoutParams(lp);

        TextView n = text(WheelStore.labelOf(this, f), 14, 0xFFFFFFFF, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));

        if (onWheel(f)) {
            TextView ok = text("✓", 16, Ui.GREEN, true);
            ok.setPadding(dp(6), 0, dp(6), 0);
            r.addView(ok);
        }
        TextView play = Ui.pill(this, "▶", 0xFF33284D, Ui.GREEN, 12);
        play.setOnClickListener(v -> preview(f));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(32), dp(32));
        pl.leftMargin = dp(2);
        r.addView(play, pl);
        TextView put = Ui.pill(this, "＋", GOLD, DARK_TEXT, 15);
        put.setOnClickListener(v -> putOnWheel(f));
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(32), dp(32));
        ul.leftMargin = dp(4);
        r.addView(put, ul);

        r.setOnClickListener(v -> preview(f));
        r.setOnLongClickListener(v -> {
            ClipData cd = ClipData.newPlainText("gv", f.getPath());
            if (v.startDragAndDrop(cd, new View.DragShadowBuilder(v), "lib", View.DRAG_FLAG_OPAQUE)) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            return true;
        });
        return r;
    }

    /** "＋" on a sound: puts it into the selected slot, otherwise into the first empty slot. */
    private void putOnWheel(File f) {
        int target = wheel.selected();
        if (target < 0 || target >= draftCount) {
            target = -1;
            for (int i = 0; i < draftCount; i++) if (draft[i] == null || WheelStore.fileAt(draft, i) == null) { target = i; break; }
        }
        if (target < 0) { toast("চাকা ভর্তি। উপরের ＋ দিয়ে ঘর বাড়ান, অথবা একটা ঘর বেছে নিন।"); return; }
        putSlot(target, f.getPath());
        wheel.setSelected(-1);
        updateBars();
    }

    private void preview(File f) {
        if (TestPlayer.isPlaying() && f.getPath().equals(previewPath)) { TestPlayer.stop(); previewPath = null; return; }
        previewPath = f.getPath();
        TestPlayer.play(this, f, 100);
    }

    // ----------------------------------------------------------- add / folder

    private void newFolderDialog() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("ফোল্ডারের নাম");
        new android.app.AlertDialog.Builder(this)
                .setTitle("নতুন ফোল্ডার")
                .setView(et)
                .setPositiveButton("তৈরি করুন", (d, w) -> {
                    SoundStore.createFolder(this, et.getText().toString(), SoundStore.DEFAULT_EMOJI);
                    srcIndex = WheelStore.sources(this).size();   // placeholder, fixed below
                    List<WheelStore.Source> l = WheelStore.sources(this);
                    for (int i = 0; i < l.size(); i++) if (l.get(i).kind == WheelStore.Source.FOLDER) srcIndex = i;
                    reloadSources();
                })
                .setNegativeButton("বাতিল", null).show();
    }

    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_AUDIO); }
        catch (Throwable e) { toast("ফাইল বাছাইয়ের অ্যাপ পাওয়া যায়নি"); }
    }

    @Override protected void onActivityResult(int rc, int res, Intent d) {
        super.onActivityResult(rc, res, d);
        if (rc != REQ_AUDIO || res != RESULT_OK || d == null || sources.isEmpty()) return;
        final List<Uri> uris = new ArrayList<>();
        if (d.getClipData() != null) {
            for (int i = 0; i < d.getClipData().getItemCount(); i++) uris.add(d.getClipData().getItemAt(i).getUri());
        } else if (d.getData() != null) {
            uris.add(d.getData());
        }
        if (uris.isEmpty()) return;
        final WheelStore.Source src = sources.get(srcIndex);
        toast("সাউন্ড যোগ হচ্ছে…");
        new Thread(() -> {   // copying / decoding can take a moment: never on the UI thread
            int ok = 0;
            String err = null;
            for (Uri u : uris) {
                try { WheelStore.add(this, src, u); ok++; }
                catch (Throwable e) { err = e.getMessage(); }
            }
            final int n = ok;
            final String er = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (er != null) toast(er);
                else if (n > 0) toast(n + "টি সাউন্ড যোগ হয়েছে");
                refreshSounds();
            });
        }, "gv-wheel-add").start();
    }
}
