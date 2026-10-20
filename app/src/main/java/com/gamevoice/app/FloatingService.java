package com.gamevoice.app;

import android.animation.ValueAnimator;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.File;
import java.util.List;

/**
 * Tiny floating mic bubble. Tap -> a slim side panel slides out with round sound shortcuts.
 * Long-press a shortcut to rename it.
 */
public class FloatingService extends Service {
    private static final int COLS = 3;

    private WindowManager wm;
    private Ui.MicView bubble;
    private WindowManager.LayoutParams bp, pp;
    private LinearLayout panel;
    private boolean open, editing;
    private long outsideAt = -10000;
    private int sw, sh, bsz, pw;
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

        bubble = new Ui.MicView(this);
        bp = new WindowManager.LayoutParams(bsz, bsz,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        bp.x = 0; bp.y = sh / 3;

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
                        bp.x = sx + (int) dx; bp.y = sy + (int) dy;
                        wm.updateViewLayout(bubble, bp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (moved) snapToEdge();
                        else if (Math.abs(outsideAt - downAt) < 350) { /* this tap just closed the panel */ }
                        else if (open) closePanel();
                        else openPanel();
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bp);
    }

    private void readScreen() {
        Rect r = wm.getCurrentWindowMetrics().getBounds();
        sw = r.width(); sh = r.height();
    }

    private void snapToEdge() {
        readScreen();
        final int from = bp.x;
        final int to = (bp.x + bsz / 2 < sw / 2) ? 0 : sw - bsz;
        bp.y = Math.max(Ui.dp(this, 24), Math.min(bp.y, sh - bsz - Ui.dp(this, 24)));
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(160);
        a.addUpdateListener(v -> {
            bp.x = (Integer) v.getAnimatedValue();
            try { wm.updateViewLayout(bubble, bp); } catch (Throwable ignored) {}
        });
        a.start();
    }

    // ---------------------------------------------------------------- panel

    private void openPanel() {
        if (open) return;
        readScreen();
        editing = false;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.PANEL, this, 20));
        int pad = Ui.dp(this, 10);
        panel.setPadding(pad, pad, pad, pad);
        panel.setElevation(Ui.dp(this, 8));
        render();

        boolean left = bp.x + bsz / 2 < sw / 2;
        pp = new WindowManager.LayoutParams(pw, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        pp.gravity = Gravity.TOP | Gravity.START;
        pp.x = left ? bp.x + bsz + Ui.dp(this, 6) : bp.x - Ui.dp(this, 6) - pw;
        panel.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST));
        int ph = panel.getMeasuredHeight();
        pp.y = Math.max(Ui.dp(this, 24), Math.min(bp.y, sh - ph - Ui.dp(this, 24)));

        panel.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && !editing) {
                outsideAt = SystemClock.uptimeMillis();
                closePanel();
            }
            return false;
        });

        panel.setAlpha(0f);
        panel.setTranslationX(left ? -pw / 2f : pw / 2f);
        wm.addView(panel, pp);
        panel.animate().alpha(1f).translationX(0f).setDuration(180).start();
        open = true;
        bubble.setActive(true);
    }

    private void closePanel() {
        if (!open) return;
        open = false;
        bubble.setActive(false);
        hideKeyboard();
        final View p = panel;
        panel = null;
        boolean left = pp.x > bp.x;
        p.animate().alpha(0f).translationX(left ? -pw / 3f : pw / 3f).setDuration(140)
                .withEndAction(() -> { try { wm.removeView(p); } catch (Throwable ignored) {} }).start();
    }

    private void setFocusable(boolean on) {
        if (pp == null || panel == null) return;
        if (on) {
            pp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            pp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        } else {
            pp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        wm.updateViewLayout(panel, pp);
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

        List<File> files = SoundStore.list(this);
        Ui.MaxHeightScroll sv = new Ui.MaxHeightScroll(this);
        sv.maxPx = (int) (sh * 0.5f);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        sv.addView(grid);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 8);
        panel.addView(sv, slp);

        if (files.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("কোনো ভয়েস নেই।\nঅ্যাপ খুলে ভয়েস যোগ করুন।");
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
        if (!files.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("চেপে ধরলে নাম বদলানো যায়");
            hint.setTextColor(Ui.MUTED);
            hint.setTextSize(9);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, Ui.dp(this, 6), 0, 0);
            panel.addView(hint);
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

    /** "নিজে শুনুন" - choose how loud YOU hear the sound on your own speaker. */
    private View monitorRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(this, 8);
        r.setLayoutParams(rlp);

        TextView l = new TextView(this);
        l.setText("🔊");
        l.setTextSize(13);
        r.addView(l, new LinearLayout.LayoutParams(-2, -2));

        int[] lv = SoundStore.MONITOR_LEVELS;
        for (int i = 0; i < lv.length; i++) {
            final int pct = lv[i];
            TextView p = Ui.pill(this, pct == 0 ? "বন্ধ" : bn(pct) + "%", 0, Ui.TEXT, 10);
            monPills[i] = p;
            p.setOnClickListener(v -> { Client.setMonitor(this, pct); styleMonitor(); });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, Ui.dp(this, 26), 1f);
            plp.leftMargin = Ui.dp(this, 5);
            r.addView(p, plp);
        }
        styleMonitor();
        return r;
    }

    private void styleMonitor() {
        int cur = Client.getMonitor();
        for (int i = 0; i < monPills.length; i++) {
            boolean on = SoundStore.MONITOR_LEVELS[i] == cur;
            monPills[i].setBackground(Ui.round(on ? Ui.ACCENT : 0xFF252B45, this, 100));
            monPills[i].setTextColor(on ? 0xFFFFFFFF : Ui.MUTED);
        }
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
        cell.addView(circle, new LinearLayout.LayoutParams(d, d));

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
        cell.setOnLongClickListener(v -> { showRename(f); return true; });
        return cell;
    }

    /** Inline rename: panel becomes focusable just long enough to type. */
    private void showRename(File f) {
        editing = true;
        panel.removeAllViews();
        setFocusable(true);

        TextView t = new TextView(this);
        t.setText("নাম বদলান");
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

        Runnable done = () -> {
            hideKeyboard();
            editing = false;
            setFocusable(false);
            if (panel != null) render();
        };
        cancel.setOnClickListener(v -> done.run());
        save.setOnClickListener(v -> { SoundStore.setLabel(this, f, et.getText().toString()); done.run(); });
        et.setOnEditorActionListener((v, id, ev) -> { save.performClick(); return true; });

        et.post(() -> {
            et.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    @Override public void onDestroy() {
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
