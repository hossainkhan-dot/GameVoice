package com.gamevoice.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Gaming Voice pack manager: create / rename / customise / delete packs, and fill each pack with voice
 * commands from the phone's storage (MP3, WAV, M4A, OGG ... anything Android can decode).
 * Same look and the same helpers ({@link Ui}) as the main screen.
 */
public class PackActivity extends Activity {
    private static final int REQ_AUDIO = 21;

    private LinearLayout list;
    private TextView title, topBtn;
    private String openPackId = null;       // null = pack list, otherwise the pack being edited
    private String pendingSlot = null;      // slot that is waiting for the file picker

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        if (b != null) {
            openPackId = b.getString("open");
            pendingSlot = b.getString("slot");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(root);
        setContentView(sv);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        topBtn = Ui.pill(this, "‹", 0xFF2A3050, Ui.TEXT, 20);
        topBtn.setOnClickListener(v -> onBackPressed());
        head.addView(topBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        title = text("", 22, Ui.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(12);
        head.addView(title, tlp);
        root.addView(head);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(14);
        root.addView(list, llp);
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        o.putString("open", openPackId);
        o.putString("slot", pendingSlot);
    }

    @Override protected void onPause() {
        TestPlayer.stop();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (openPackId != null) { openPackId = null; refresh(); }
        else super.onBackPressed();
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

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private LinearLayout.LayoutParams small() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(34), dp(34));
        p.leftMargin = dp(4);
        return p;
    }

    private LinearLayout card() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.round(Ui.CARD, this, 14));
        r.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private void refresh() {
        list.removeAllViews();
        PackStore.Pack open = openPackId == null ? null : PackStore.get(this, openPackId);
        if (open == null) { openPackId = null; showPacks(); }
        else showPack(open);
    }

    // ------------------------------------------------------------ pack list

    private void showPacks() {
        title.setText("🎮 ভয়েস প্যাক");
        topBtn.setVisibility(View.VISIBLE);

        TextView hint = text("নিজের ইচ্ছামতো প্যাক বানান, প্রতিটায় ফোন থেকে অডিও (MP3, WAV ইত্যাদি) যোগ করুন। "
                + "গেমে ভাসমান 🎮 বাটন চাপলে বা তার থেকে টেনে কোনো প্যাকে ছাড়লে সেই প্যাক সক্রিয় (Active) হয়। "
                + "বাটন সরাতে ২ সেকেন্ড চেপে ধরে টানুন।", 12, Ui.MUTED, false);
        hint.setPadding(0, 0, 0, dp(10));
        list.addView(hint);

        TextView add = Ui.pill(this, "＋ নতুন প্যাক", Ui.GREEN, 0xFF06210F, 14);
        add.setPadding(dp(14), dp(11), dp(14), dp(11));
        add.setOnClickListener(v -> packDialog(null));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.bottomMargin = dp(12);
        list.addView(add, alp);

        List<PackStore.Pack> packs = PackStore.list(this);
        if (packs.isEmpty()) {
            TextView e = text("এখনো কোনো প্যাক নেই। উপরের “＋ নতুন প্যাক” চাপুন।", 13, Ui.MUTED, false);
            e.setPadding(0, dp(8), 0, 0);
            list.addView(e);
            return;
        }
        String active = PackStore.active(this).id;
        for (PackStore.Pack p : packs) list.addView(packRow(p, p.id.equals(active)));
    }

    private View packRow(final PackStore.Pack p, final boolean active) {
        LinearLayout r = card();
        if (active) {
            android.graphics.drawable.GradientDrawable bg = Ui.round(Ui.CARD, this, 14);
            bg.setStroke(dp(1.5f), Ui.ACCENT);
            r.setBackground(bg);
        }
        TextView g = Ui.pill(this, p.emoji, Ui.colorFor(p.id), 0xFFFFFFFF, 20);
        g.setBackground(Ui.oval(Ui.colorFor(p.id)));
        r.addView(g, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView n = text(p.name, 15, active ? Ui.ACCENT : Ui.TEXT, active);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(n);
        txt.addView(text(p.filled() + "টি ভয়েস · " + p.slots.size() + "টি স্লট" + (p.vol < 100 ? " · " + p.vol + "%" : ""),
                11, Ui.MUTED, false));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.leftMargin = dp(12);
        r.addView(txt, nlp);

        TextView act = Ui.pill(this, active ? "● Active" : "Active করুন", active ? Ui.ACCENT : 0xFF2A3050,
                active ? 0xFFFFFFFF : Ui.MUTED, 11);
        act.setPadding(dp(10), dp(8), dp(10), dp(8));
        act.setOnClickListener(v -> { PackStore.setActive(this, p.id); refresh(); });
        r.addView(act, new LinearLayout.LayoutParams(-2, -2));

        TextView edit = Ui.pill(this, "✎", 0xFF2A3050, Ui.TEXT, 15);
        edit.setOnClickListener(v -> packDialog(p));
        r.addView(edit, small());
        TextView del = Ui.pill(this, "✕", 0xFF2A3050, Ui.RED, 14);
        del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("“" + p.name + "” প্যাক মুছে ফেলবেন?\nএর সব ভয়েস ফাইলও মুছে যাবে।")
                .setPositiveButton("মুছুন", (d, w) -> { PackStore.delete(this, p.id); refresh(); })
                .setNegativeButton("না", null).show());
        r.addView(del, small());

        r.setOnClickListener(v -> { openPackId = p.id; refresh(); });
        return r;
    }

    /** Create (p == null) or customise a pack: name, emoji icon, how loud the other side hears it. */
    private void packDialog(final PackStore.Pack p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        final EditText name = new EditText(this);
        name.setHint("প্যাকের নাম, যেমন: রাশিয়ান কমান্ড");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        if (p != null) name.setText(p.name);
        box.addView(name);

        TextView lab = text("ইমোজি বেছে নিন (প্যাকের আইকন)", 12, Ui.MUTED, false);
        lab.setPadding(0, dp(14), 0, dp(6));
        box.addView(lab);

        final EditText custom = new EditText(this);
        final Ui.EmojiPicker pick = new Ui.EmojiPicker(this, p == null ? PackStore.DEFAULT_EMOJI : p.emoji, 7, 40);
        pick.onPick = () -> custom.setText("");
        box.addView(pick);
        custom.setHint("অথবা কীবোর্ড থেকে নিজের ইমোজি দিন");
        custom.setSingleLine(true);
        custom.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(custom);

        TextView vh = text("অপর প্রান্তের মানুষ এই প্যাকের ভয়েস কত জোরে শুনবে।", 12, Ui.MUTED, false);
        vh.setPadding(0, dp(16), 0, dp(2));
        box.addView(vh);
        final int[] vol = {p == null ? 100 : p.vol};
        box.addView(Ui.volumeSlider(this, "🔊 ", vol[0], false, false, false, pct -> vol[0] = pct));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new android.app.AlertDialog.Builder(this)
                .setTitle(p == null ? "নতুন প্যাক" : "প্যাক কাস্টমাইজ")
                .setView(sv)
                .setPositiveButton("সেভ", (d, w) -> {
                    String emoji = Ui.firstGrapheme(custom.getText().toString());
                    if (emoji.isEmpty()) emoji = pick.value;
                    String nm = name.getText().toString();
                    if (p == null) {
                        PackStore.Pack np = PackStore.create(this, nm, emoji);
                        PackStore.update(this, np.id, np.name, np.emoji, vol[0]);
                        if (PackStore.list(this).size() == 1) PackStore.setActive(this, np.id);
                        openPackId = np.id;   // go straight in and add voices
                    } else {
                        PackStore.update(this, p.id, nm, emoji, vol[0]);
                    }
                    refresh();
                })
                .setNegativeButton("বাতিল", null).show();
    }

    // ------------------------------------------------------------ one pack

    private void showPack(final PackStore.Pack p) {
        title.setText(p.emoji + " " + p.name);
        topBtn.setVisibility(View.VISIBLE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView add = Ui.pill(this, "＋ Add Voice", Ui.GREEN, 0xFF06210F, 14);
        add.setPadding(dp(14), dp(11), dp(14), dp(11));
        add.setOnClickListener(v -> {
            // one more command slot (no upper limit), and straight to the file picker for it
            PackStore.addSlot(this, p.id);
            PackStore.Pack np = PackStore.get(this, p.id);
            pickAudio(np.slots.get(np.slots.size() - 1).id);
            refresh();
        });
        bar.addView(add, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView edit = Ui.pill(this, "✎ কাস্টমাইজ", 0xFF2A3050, Ui.TEXT, 13);
        edit.setPadding(dp(14), dp(11), dp(14), dp(11));
        edit.setOnClickListener(v -> packDialog(p));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = dp(8);
        bar.addView(edit, elp);
        list.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = text(p.filled() + "টি ভয়েস, " + p.slots.size() + "টি স্লট। খালি স্লটে চাপ দিয়ে অডিও বাছুন। "
                + "ভয়েসে চাপলে বাজবে (মাইকে যাবে), ▶ চাপলে শুধু আপনি শুনবেন। স্লট ও ভয়েসের সংখ্যায় কোনো সীমা নেই।", 12, Ui.MUTED, false);
        hint.setPadding(0, dp(10), 0, dp(10));
        list.addView(hint);

        for (int i = 0; i < p.slots.size(); i++) list.addView(slotRow(p, p.slots.get(i), i));
    }

    private View slotRow(final PackStore.Pack p, final PackStore.Slot s, final int index) {
        final File f = PackStore.fileOf(this, p, s);
        final boolean filled = f != null;
        final String label = PackStore.slotLabel(s, index);
        LinearLayout r = card();

        TextView g = Ui.pill(this, filled ? Ui.glyph(label) : String.valueOf(index + 1),
                filled ? Ui.colorFor(s.id) : 0xFF252B45, filled ? 0xFFFFFFFF : Ui.MUTED, 16);
        g.setBackground(Ui.oval(filled ? Ui.colorFor(s.id) : 0xFF252B45));
        r.addView(g, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView n = text(label, 15, filled ? Ui.TEXT : Ui.MUTED, false);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(n);
        TextView sub = text(filled ? s.file : (s.file != null ? "⚠ ফাইল পাওয়া যায়নি, আবার বাছুন" : "খালি — চাপ দিয়ে অডিও বাছুন"),
                11, Ui.MUTED, false);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(sub);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.leftMargin = dp(12);
        r.addView(txt, nlp);

        if (filled) {
            TextView pv = Ui.pill(this, "▶", 0xFF2A3050, Ui.GREEN, 13);
            pv.setOnClickListener(v -> {
                if (TestPlayer.isPlaying()) TestPlayer.stop(); else TestPlayer.play(this, f, 100);
            });
            r.addView(pv, small());
        }
        TextView edit = Ui.pill(this, "✎", 0xFF2A3050, Ui.TEXT, 15);
        edit.setOnClickListener(v -> slotMenu(p, s, index, filled));
        r.addView(edit, small());
        TextView del = Ui.pill(this, "✕", 0xFF2A3050, Ui.RED, 14);
        del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("“" + label + "” স্লটটি মুছে ফেলবেন?")
                .setPositiveButton("মুছুন", (d, w) -> { PackStore.removeSlot(this, p.id, s.id); refresh(); })
                .setNegativeButton("না", null).show());
        r.addView(del, small());

        r.setOnClickListener(v -> {
            if (!filled) { pickAudio(s.id); return; }
            String res = Client.play(this, f, p.vol);
            if (!"ok".equals(res)) toast(res);
        });
        return r;
    }

    private void slotMenu(final PackStore.Pack p, final PackStore.Slot s, final int index, boolean filled) {
        final List<String> items = new ArrayList<>();
        items.add("✎ নাম বদলান");
        items.add(filled ? "🔁 অন্য অডিও দিয়ে বদলান" : "🎵 অডিও বাছুন");
        if (filled) items.add("🧹 অডিও সরান (স্লট থাকবে)");
        new android.app.AlertDialog.Builder(this)
                .setTitle(PackStore.slotLabel(s, index))
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w == 0) renameSlot(p, s, index);
                    else if (w == 1) pickAudio(s.id);
                    else { PackStore.clearSlot(this, p.id, s.id); refresh(); }
                }).show();
    }

    private void renameSlot(final PackStore.Pack p, final PackStore.Slot s, int index) {
        final EditText et = new EditText(this);
        et.setText(PackStore.slotLabel(s, index));
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new android.app.AlertDialog.Builder(this)
                .setTitle("নাম বদলান")
                .setView(et)
                .setPositiveButton("সেভ", (d, w) -> {
                    PackStore.setSlotLabel(this, p.id, s.id, et.getText().toString());
                    refresh();
                })
                .setNegativeButton("বাতিল", null).show();
    }

    // ----------------------------------------------------------- audio picker

    private void pickAudio(String slotId) {
        pendingSlot = slotId;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*");   // MP3, WAV, M4A, OGG, FLAC ... whatever the phone's file picker calls audio
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_AUDIO); }
        catch (Throwable e) { toast("ফাইল বাছাইয়ের অ্যাপ পাওয়া যায়নি"); }
    }

    @Override protected void onActivityResult(int rc, int res, Intent d) {
        super.onActivityResult(rc, res, d);
        if (rc != REQ_AUDIO || res != RESULT_OK || d == null || openPackId == null) return;
        final List<Uri> uris = new ArrayList<>();
        if (d.getClipData() != null) {
            for (int i = 0; i < d.getClipData().getItemCount(); i++) uris.add(d.getClipData().getItemAt(i).getUri());
        } else if (d.getData() != null) {
            uris.add(d.getData());
        }
        if (uris.isEmpty()) return;
        final String packId = openPackId, firstSlot = pendingSlot;
        toast("অডিও যোগ হচ্ছে…");
        // Copying a big file can take a moment: never on the UI thread.
        new Thread(() -> {
            int ok = 0;
            String err = null;
            for (int i = 0; i < uris.size(); i++) {
                try {
                    // the first file goes into the slot that was tapped, the rest into the next empty / new slots
                    PackStore.assign(this, packId, i == 0 ? firstSlot : null, uris.get(i));
                    ok++;
                } catch (Throwable e) { err = e.getMessage(); }
            }
            final int n = ok;
            final String er = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (er != null) toast(er);
                else if (n > 0) toast(n + "টি ভয়েস যোগ হয়েছে");
                refresh();
            });
        }, "gv-pack-add").start();
    }
}
