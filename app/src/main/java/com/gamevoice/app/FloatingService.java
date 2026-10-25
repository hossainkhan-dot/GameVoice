package com.gamevoice.app;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny floating mic bubble. Drag it ANYWHERE on the screen - it stays exactly where you leave it
 * (and is remembered). Tap -> a slim panel opens next to it with folder tabs on top and round
 * sound shortcuts below. Long-press a sound or a folder to edit it.
 */
public class FloatingService extends Service {
    private static final int COLS = 3;

    private WindowManager wm;
    private Ui.MicView bubble;
    private WindowManager.LayoutParams bp, pp;
    private LinearLayout panel;
    private Ui.MaxHeightScroll scroller;
    private HorizontalScrollView folderHs;
    private int folderScrollX;
    private boolean open, editing, panelAttached;
    private long outsideAt = -10000;
    private int sw, sh, bsz, pw;
    private float fx, fy;          // saved bubble position, 0..1 of the free screen area
    private int slide;             // -1: panel is right of bubble, 1: left of it, 0: above/below
    private String curFolder = SoundStore.ALL;
    private File shownMusic;       // the song that was highlighted the last time the music list was drawn
    private final Runnable musicL = () -> {
        // The player controls update themselves; only the song list needs a redraw when the song changes.
        if (!open || editing || panel == null || !SoundStore.MUSIC.equals(curFolder)) return;
        String a = MusicPlayer.cur == null ? null : MusicPlayer.cur.getName();
        String b = shownMusic == null ? null : shownMusic.getName();
        if (!java.util.Objects.equals(a, b)) refreshPanel();
    };
    private final TextView[] monPills = new TextView[SoundStore.MONITOR_LEVELS.length];

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel("gv", "GameVoice", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "gv")
                .setContentTitle("GameVoice চালু").setSmallIcon(android.R.drawable.ic_btn_speak_now).build();
        startForeground(1, n);

        Client.loadMonitor(this);
        wm = getSystemService(WindowManager.class);
        bsz = Ui.dp(this, 34);
        pw = Ui.dp(this, 232);
        readScreen();
        fx = SoundStore.getBubbleX(this);
        fy = SoundStore.getBubbleY(this);

        bubble = new Ui.MicView(this);
        bp = new WindowManager.LayoutParams(bsz, bsz,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        applyBubblePos();

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty; boolean moved; long downAt;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = bp.x; sy = bp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false;
                        downAt = SystemClock.uptimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - tx, dy = e.getRawY() - ty;
                        if (!moved && Math.abs(dx) < Ui.dp(FloatingService.this, 6)
                                && Math.abs(dy) < Ui.dp(FloatingService.this, 6)) return true;
                        if (!moved) { moved = true; if (open) closePanel(); }
                        // Free movement: any point of the screen, no snapping to an edge.
                        bp.x = clamp(sx + (int) dx, 0, Math.max(0, sw - bsz));
                        bp.y = clamp(sy + (int) dy, 0, Math.max(0, sh - bsz));
                        wm.updateViewLayout(bubble, bp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (moved) savePos();
                        else if (Math.abs(outsideAt - downAt) < 350) { /* this tap just closed the panel */ }
                        else if (open) closePanel();
                        else openPanel();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) savePos();
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bp);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }

    private void readScreen() {
        Rect r = wm.getCurrentWindowMetrics().getBounds();
        sw = r.width(); sh = r.height();
    }

    /** Turn the saved fractions into pixels for the current screen size. */
    private void applyBubblePos() {
        bp.x = clamp(Math.round(fx * Math.max(0, sw - bsz)), 0, Math.max(0, sw - bsz));
        bp.y = clamp(Math.round(fy * Math.max(0, sh - bsz)), 0, Math.max(0, sh - bsz));
    }

    /** Remember where the bubble was left, so it is pinned there next time too. */
    private void savePos() {
        fx = bp.x / (float) Math.max(1, sw - bsz);
        fy = bp.y / (float) Math.max(1, sh - bsz);
        SoundStore.setBubblePos(this, fx, fy);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // Screen rotated (games are often landscape): keep the bubble at the same relative spot.
        if (open) closePanel();
        readScreen();
        applyBubblePos();
        try { wm.updateViewLayout(bubble, bp); } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- panel

    private void openPanel() {
        if (open) return;
        readScreen();
        editing = false;
        curFolder = SoundStore.selected(this);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.PANEL, this, 20));
        int pad = Ui.dp(this, 10);
        panel.setPadding(pad, pad, pad, pad);
        panel.setElevation(Ui.dp(this, 8));

        pp = new WindowManager.LayoutParams(pw, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        pp.gravity = Gravity.TOP | Gravity.START;
        render();
        layoutPanel();

        panel.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && !editing) {
                outsideAt = SystemClock.uptimeMillis();
                closePanel();
            }
            return false;
        });

        panel.setAlpha(0f);
        panel.setTranslationX(slide * pw / 2f);
        wm.addView(panel, pp);
        panelAttached = true;
        MusicPlayer.addListener(musicL);
        panel.animate().alpha(1f).translationX(0f).setDuration(180).start();
        open = true;
        bubble.setActive(true);
    }

    private void closePanel() {
        if (!open) return;
        open = false;
        panelAttached = false;
        MusicPlayer.removeListener(musicL);
        bubble.setActive(false);
        hideKeyboard();
        final View p = panel;
        panel = null;
        scroller = null;
        folderHs = null;
        p.animate().alpha(0f).translationX(slide * pw / 3f).setDuration(140)
                .withEndAction(() -> { try { wm.removeView(p); } catch (Throwable ignored) {} }).start();
    }

    /**
     * Sizes and places the panel next to the bubble wherever it sits on the screen:
     * right of it, else left of it, else (narrow screens) below/above it. Always kept on-screen.
     */
    private void layoutPanel() {
        if (panel == null) return;
        readScreen();
        int margin = Ui.dp(this, 24), gap = Ui.dp(this, 6);
        int wSpec = View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST);
        panel.measure(wSpec, hSpec);
        int ph = panel.getMeasuredHeight();
        int avail = sh - 2 * margin;
        if (ph > avail && scroller != null) {
            // Short (landscape) screen: let only the sound list shrink and scroll.
            scroller.maxPx = Math.max(Ui.dp(this, 60), scroller.getMeasuredHeight() - (ph - avail));
            panel.measure(wSpec, hSpec);
            ph = panel.getMeasuredHeight();
        }

        boolean preferRight = bp.x + bsz / 2 < sw / 2;
        int rightX = bp.x + bsz + gap, leftX = bp.x - gap - pw;
        boolean rightOk = rightX + pw <= sw, leftOk = leftX >= 0;
        int mode; // 0 = right of bubble, 1 = left of bubble, 2 = above/below
        if (preferRight && rightOk) mode = 0;
        else if (!preferRight && leftOk) mode = 1;
        else if (rightOk) mode = 0;
        else if (leftOk) mode = 1;
        else mode = 2;

        if (mode == 0) { pp.x = rightX; slide = -1; }
        else if (mode == 1) { pp.x = leftX; slide = 1; }
        else { pp.x = clamp(bp.x + bsz / 2 - pw / 2, 0, Math.max(0, sw - pw)); slide = 0; }

        if (mode != 2) {
            pp.y = clamp(bp.y, margin, Math.max(margin, sh - ph - margin));
        } else {
            int below = bp.y + bsz + gap;
            pp.y = (below + ph <= sh - margin) ? below : Math.max(margin, bp.y - gap - ph);
        }
    }

    /** Re-measure/re-place after the content changed, and push it to the window. */
    private void applyPanel() {
        if (panel == null) return;
        layoutPanel();
        if (panelAttached) {
            try { wm.updateViewLayout(panel, pp); } catch (Throwable ignored) {}
        }
    }

    private void refreshPanel() {
        if (panel == null) return;
        render();
        applyPanel();
    }

    private void setFocusable(boolean on) {
        if (pp == null) return;
        if (on) {
            pp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            pp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        } else {
            pp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
    }

    private void hideKeyboard() {
        try {
            if (panel != null) getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(panel.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    /** Rebuilds the whole panel content (normal mode). */
    private void render() {
        panel.removeAllViews();
        panel.addView(header());
        panel.addView(monitorRow());
        panel.addView(folderBar());
        if (SoundStore.MUSIC.equals(curFolder)) { renderMusic(); return; }

        List<File> files = SoundStore.list(this, curFolder);
        scroller = new Ui.MaxHeightScroll(this);
        scroller.maxPx = (int) (sh * 0.5f);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(grid);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 8);
        panel.addView(scroller, slp);

        if (files.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(curFolder.isEmpty()
                    ? "কোনো ভয়েস নেই।\nঅ্যাপ খুলে ভয়েস যোগ করুন।"
                    : "এই ফোল্ডারে কোনো ভয়েস নেই।\nভয়েস চেপে ধরে এখানে সরান,\nঅথবা অ্যাপ থেকে যোগ করুন।");
            t.setTextColor(Ui.MUTED);
            t.setTextSize(12);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            grid.addView(t);
        }
        LinearLayout row = null;
        for (int i = 0; i < files.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(chip(files.get(i)), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        TextView hint = new TextView(this);
        hint.setText("ভয়েস বা ফোল্ডার চেপে ধরলে এডিট করা যায়");
        hint.setTextColor(Ui.MUTED);
        hint.setTextSize(9);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 6), 0, 0);
        panel.addView(hint);
    }

    /** Panel content for the 🎵 folder: compact player on top, song list below. */
    private void renderMusic() {
        MusicUi.Player pl = new MusicUi.Player(this, true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = Ui.dp(this, 8);
        panel.addView(pl, plp);

        shownMusic = MusicPlayer.cur;
        scroller = new Ui.MaxHeightScroll(this);
        scroller.maxPx = (int) (sh * 0.28f);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(col);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 6);
        panel.addView(scroller, slp);

        List<File> songs = SoundStore.listMusic(this);
        if (songs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("কোনো মিউজিক নেই।\nঅ্যাপ খুলে 🎵 ফোল্ডারে গান যোগ করুন।");
            t.setTextColor(Ui.MUTED);
            t.setTextSize(12);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            col.addView(t);
        }
        for (final File f : songs) {
            boolean isCur = shownMusic != null && shownMusic.getName().equals(f.getName());
            TextView t = new TextView(this);
            t.setText((isCur ? "♫  " : "♪  ") + SoundStore.musicLabel(this, f));
            t.setTextColor(isCur ? 0xFFFFFFFF : 0xFFDCE0EE);
            t.setTextSize(12);
            t.setTypeface(android.graphics.Typeface.DEFAULT, isCur ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_VERTICAL);
            int hp = Ui.dp(this, 10);
            t.setPadding(hp, 0, hp, 0);
            if (isCur) t.setBackground(Ui.round(Ui.ACCENT, this, 10));
            t.setOnClickListener(v -> MusicPlayer.play(this, f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 32));
            lp.bottomMargin = Ui.dp(this, 3);
            col.addView(t, lp);
        }
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("GameVoice");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(13);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        int s = Ui.dp(this, 28);
        TextView stop = Ui.pill(this, "■", Ui.RED, 0xFFFFFFFF, 12);
        stop.setOnClickListener(v -> Client.stop());
        h.addView(stop, new LinearLayout.LayoutParams(s, s));

        TextView gear = Ui.pill(this, "⚙", 0xFF2A3050, Ui.TEXT, 14);
        gear.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            closePanel();
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(s, s);
        glp.leftMargin = Ui.dp(this, 6);
        h.addView(gear, glp);
        return h;
    }

    // -------------------------------------------------------------- folders

    /** Folder tabs on top of the panel: [📂 সব] [😂 ফানি] ... [＋]. */
    private View folderBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        folderHs = hs;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(row);

        row.addView(tab("📂", "সব", curFolder.isEmpty(), v -> selectFolder(SoundStore.ALL), null));
        // Permanent music folder (no edit / delete here; its volume is set in the app).
        row.addView(tab(SoundStore.MUSIC_EMOJI, SoundStore.MUSIC_NAME, SoundStore.MUSIC.equals(curFolder),
                v -> selectFolder(SoundStore.MUSIC), null));
        for (SoundStore.Folder f : SoundStore.folders(this)) {
            row.addView(tab(f.emoji, f.name, f.id.equals(curFolder),
                    v -> selectFolder(f.id), v -> showFolderEditor(f)));
        }
        TextView add = Ui.pill(this, "＋", 0xFF2A3050, Ui.GREEN, 14);
        add.setOnClickListener(v -> showFolderEditor(null));
        row.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 28)));

        final int keep = folderScrollX;
        hs.post(() -> hs.scrollTo(keep, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 8);
        hs.setLayoutParams(lp);
        return hs;
    }

    private TextView tab(String emoji, String name, boolean on, View.OnClickListener click,
                         View.OnClickListener longClick) {
        TextView t = Ui.folderChip(this, emoji, name, on, 11, 10, 6);
        t.setOnClickListener(click);
        if (longClick != null) t.setOnLongClickListener(v -> { longClick.onClick(v); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 28));
        lp.rightMargin = Ui.dp(this, 5);
        t.setLayoutParams(lp);
        return t;
    }

    private void selectFolder(String id) {
        if (folderHs != null) folderScrollX = folderHs.getScrollX();
        curFolder = id;
        SoundStore.setSelected(this, id);
        refreshPanel();
    }

    /** Create (fold == null) or edit a folder: name + emoji icon. Also lets you delete it. */
    private void showFolderEditor(final SoundStore.Folder fold) {
        if (panel == null) return;
        editing = true;
        scroller = null;
        panel.removeAllViews();
        setFocusable(true);

        TextView t = new TextView(this);
        t.setText(fold == null ? "নতুন ফোল্ডার" : "ফোল্ডার এডিট");
        t.setTextColor(Ui.TEXT);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(t);

        final EditText et = new EditText(this);
        et.setText(fold == null ? "" : fold.name);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.MUTED);
        et.setHint("ফোল্ডারের নাম, যেমন: ফানি");
        et.setTextSize(14);
        et.setBackground(Ui.round(0xFF252B45, this, 12));
        int p = Ui.dp(this, 10);
        et.setPadding(p, p, p, p);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = Ui.dp(this, 8);
        panel.addView(et, elp);

        TextView lab = new TextView(this);
        lab.setText("ইমোজি (ফোল্ডারের আইকন)");
        lab.setTextColor(Ui.MUTED);
        lab.setTextSize(10);
        lab.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        panel.addView(lab);

        final Ui.EmojiPicker pick = new Ui.EmojiPicker(this,
                fold == null ? SoundStore.DEFAULT_EMOJI : fold.emoji, 6, 34);
        pick.onPick = this::hideKeyboard;
        Ui.MaxHeightScroll ps = new Ui.MaxHeightScroll(this);
        ps.maxPx = Ui.dp(this, 104);
        ps.addView(pick);
        panel.addView(ps, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 8);
        panel.addView(btns, blp);

        TextView cancel = Ui.pill(this, "বাতিল", 0xFF2A3050, Ui.TEXT, 12);
        TextView save = Ui.pill(this, "সেভ", Ui.ACCENT, 0xFFFFFFFF, 12);
        if (fold != null) {
            final TextView del = Ui.pill(this, "মুছুন", 0xFF3A2230, Ui.RED, 12);
            final boolean[] armed = {false};
            del.setOnClickListener(v -> {
                if (!armed[0]) { armed[0] = true; del.setText("নিশ্চিত?"); return; }
                SoundStore.deleteFolder(this, fold.id);
                curFolder = SoundStore.selected(this);
                closeEditor();
            });
            btns.addView(del, new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f));
        }
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f);
        a.leftMargin = fold != null ? Ui.dp(this, 6) : 0;
        b.leftMargin = Ui.dp(this, 6);
        btns.addView(cancel, a);
        btns.addView(save, b);

        cancel.setOnClickListener(v -> closeEditor());
        save.setOnClickListener(v -> {
            String name = et.getText().toString();
            if (fold == null) {
                SoundStore.Folder nf = SoundStore.createFolder(this, name, pick.value);
                curFolder = nf.id;
                SoundStore.setSelected(this, nf.id);
            } else {
                SoundStore.updateFolder(this, fold.id, name, pick.value);
            }
            closeEditor();
        });
        et.setOnEditorActionListener((v, id, ev) -> { save.performClick(); return true; });
        focusField(et);
        applyPanel();
    }

    /** Long-press on a sound: rename it and/or move it to another folder. */
    private void showSoundEditor(final File f) {
        if (panel == null) return;
        editing = true;
        scroller = null;
        panel.removeAllViews();
        setFocusable(true);

        TextView t = new TextView(this);
        t.setText("ভয়েস এডিট");
        t.setTextColor(Ui.TEXT);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(t);

        final EditText et = new EditText(this);
        et.setText(SoundStore.label(this, f));
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.MUTED);
        et.setHint("যেমন: 🔥 হাসি");
        et.setTextSize(14);
        et.setBackground(Ui.round(0xFF252B45, this, 12));
        int p = Ui.dp(this, 10);
        et.setPadding(p, p, p, p);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = Ui.dp(this, 8);
        panel.addView(et, elp);

        // Favourite toggle: favourites sit at the top of their folder with a tiny star.
        final boolean[] fav = {SoundStore.isFav(this, f)};
        final TextView favBtn = Ui.pill(this, "", 0, 0, 12);
        final Runnable styleFav = () -> {
            favBtn.setText(fav[0] ? "★ ফেভারিট (উপরে থাকবে)" : "☆ ফেভারিটে রাখুন");
            favBtn.setTextColor(fav[0] ? Ui.GOLD : Ui.MUTED);
            favBtn.setBackground(Ui.round(fav[0] ? 0xFF3A3320 : 0xFF252B45, this, 100));
        };
        favBtn.setOnClickListener(v -> { fav[0] = !fav[0]; styleFav.run(); });
        styleFav.run();
        LinearLayout.LayoutParams favlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 30));
        favlp.topMargin = Ui.dp(this, 8);
        panel.addView(favBtn, favlp);

        TextView lab = new TextView(this);
        lab.setText("কোন ফোল্ডারে থাকবে");
        lab.setTextColor(Ui.MUTED);
        lab.setTextSize(10);
        lab.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        panel.addView(lab);

        // Folder choices: [কোনোটি না] [😂 ফানি] ...
        final String[] sel = {SoundStore.folderOf(this, f)};
        final List<String> ids = new ArrayList<>();
        final List<TextView> tabs = new ArrayList<>();
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        final Runnable restyle = () -> {
            for (int i = 0; i < tabs.size(); i++) Ui.styleChip(tabs.get(i), ids.get(i).equals(sel[0]));
        };
        List<SoundStore.Folder> all = SoundStore.folders(this);
        for (int i = -1; i < all.size(); i++) {
            final String id = i < 0 ? "" : all.get(i).id;
            TextView c = tab(i < 0 ? "📄" : all.get(i).emoji, i < 0 ? "কোনোটি না" : all.get(i).name,
                    id.equals(sel[0]), v -> { sel[0] = id; restyle.run(); }, null);
            ids.add(id);
            tabs.add(c);
            row.addView(c);
        }
        panel.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 8);
        panel.addView(btns, blp);

        TextView cancel = Ui.pill(this, "বাতিল", 0xFF2A3050, Ui.TEXT, 12);
        TextView save = Ui.pill(this, "সেভ", Ui.ACCENT, 0xFFFFFFFF, 12);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f);
        b.leftMargin = Ui.dp(this, 8);
        btns.addView(cancel, a);
        btns.addView(save, b);

        cancel.setOnClickListener(v -> closeEditor());
        save.setOnClickListener(v -> {
            SoundStore.setLabel(this, f, et.getText().toString());
            SoundStore.setFolder(this, f, sel[0]);
            SoundStore.setFav(this, f, fav[0]);
            closeEditor();
        });
        et.setOnEditorActionListener((v, id, ev) -> { save.performClick(); return true; });
        focusField(et);
        applyPanel();
    }

    private void focusField(final EditText et) {
        et.post(() -> {
            et.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /** Leave an editor view and go back to the normal panel. */
    private void closeEditor() {
        hideKeyboard();
        editing = false;
        setFocusable(false);
        refreshPanel();
    }

    // -------------------------------------------------------------- sounds

    /** "নিজে শুনুন" - choose how loud YOU hear the sound on your own speaker. */
    private View monitorRow() {
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(this, 8);
        LinearLayout r = Ui.volumeSlider(this, "🔊 ", Client.getMonitor(), true, true, true,
                pct -> Client.setMonitor(this, pct));
        r.setLayoutParams(rlp);
        return r;
    }

    private static String bn(int n) {
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) sb.append((char) ('০' + (c - '0')));
        return sb.toString();
    }

    /** One round shortcut with its (editable) name underneath. */
    private View chip(File f) {
        String label = SoundStore.label(this, f);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));

        int d = Ui.dp(this, 44);
        TextView circle = new TextView(this);
        circle.setText(Ui.glyph(label));
        circle.setTextColor(0xFFFFFFFF);
        circle.setTextSize(17);
        circle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        circle.setGravity(Gravity.CENTER);
        circle.setIncludeFontPadding(false);
        circle.setBackground(Ui.oval(Ui.colorFor(f.getName())));
        circle.setElevation(Ui.dp(this, 2));
        FrameLayout badge = new FrameLayout(this);
        badge.addView(circle, new FrameLayout.LayoutParams(-1, -1));
        if (SoundStore.isFav(this, f)) {
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            slp.topMargin = Ui.dp(this, 1);
            slp.rightMargin = Ui.dp(this, 2);
            badge.addView(Ui.star(this), slp);
        }
        cell.addView(badge, new LinearLayout.LayoutParams(d, d));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xFFDCE0EE);
        name.setTextSize(10);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Ui.dp(this, 3);
        cell.addView(name, nlp);

        cell.setOnClickListener(v -> {
            circle.animate().scaleX(1.18f).scaleY(1.18f).setDuration(90)
                    .withEndAction(() -> circle.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            String r = Client.play(this, f);
            if (!"ok".equals(r)) Toast.makeText(this, r, Toast.LENGTH_LONG).show();
        });
        cell.setOnLongClickListener(v -> { showSoundEditor(f); return true; });
        return cell;
    }

    @Override public void onDestroy() {
        MusicPlayer.removeListener(musicL);
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
