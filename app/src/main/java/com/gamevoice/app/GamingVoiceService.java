package com.gamevoice.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import rikka.shizuku.Shizuku;

/**
 * Floating Gaming Voice button (separate from the older mic bubble, which is unchanged).
 *
 * Gestures on the button:
 *  - TAP                      : opens the panel (pack list on top, the active pack's voice commands below).
 *  - DRAG from the button     : opens the pack list next to it; the pack you release your finger on becomes ACTIVE.
 *  - HOLD 2 seconds, then drag: moves the button anywhere on the screen (position is remembered).
 *
 * The overlay windows are only as big as the button / panel, and let every touch outside them through to the
 * game, so the game's own controls keep working.
 */
public class GamingVoiceService extends Service {
    /** How long the button must be held before it can be moved. */
    private static final long HOLD_MS = 2000;
    private static final int COLS = 4;

    private static final int IDLE = 0, PICK = 1, MOVE = 2;

    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bp, pp;
    private final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout panel;
    private Ui.MaxHeightScroll scroller;
    private HorizontalScrollView packHs;
    private int packScrollX;
    private boolean open, panelAttached;
    private long outsideAt = -10000;
    private int sw, sh, bsz, pw;
    private float fx, fy;
    private int slide;

    // drag-to-pick list
    private LinearLayout picker;
    private final List<TextView> pickCells = new ArrayList<>();
    private final List<String> pickIds = new ArrayList<>();
    private int hover = -1;

    private int state = IDLE;
    private final Runnable holdDone = this::enterMoveMode;
    private float lastRawX, lastRawY;
    private int moveSx, moveSy;
    private float moveTx, moveTy;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel("gv", "GameVoice", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "gv")
                .setContentTitle("গেমিং ভয়েস চালু").setSmallIcon(android.R.drawable.ic_btn_speak_now).build();
        startForeground(2, n);

        // The Shizuku link normally exists already; if this process was restarted, try to restore it.
        try {
            if (Client.svc == null && Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Client.bind(getApplicationContext());
            }
        } catch (Throwable ignored) {}
        Client.loadMonitor(this);

        wm = getSystemService(WindowManager.class);
        bsz = Ui.dp(this, 46);
        pw = Ui.dp(this, 288);
        readScreen();
        fx = PackStore.bubbleX(this);
        fy = PackStore.bubbleY(this);

        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextSize(22);
        bubble.setIncludeFontPadding(false);
        bubble.setElevation(Ui.dp(this, 4));
        updateBubbleFace();
        bp = new WindowManager.LayoutParams(bsz, bsz,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        applyBubblePos();
        bubble.setOnTouchListener(this::onBubbleTouch);
        wm.addView(bubble, bp);
    }

    // ------------------------------------------------------------- bubble

    private void updateBubbleFace() {
        PackStore.Pack a = PackStore.active(this);
        bubble.setText(a == null ? PackStore.DEFAULT_EMOJI : a.emoji);
        android.graphics.drawable.GradientDrawable d = Ui.oval(open || state != IDLE ? Ui.ACCENT : 0xE6161A2C);
        d.setStroke(Ui.dp(this, 1.5f), state == MOVE ? Ui.GREEN : 0x88FFFFFF);
        bubble.setBackground(d);
    }

    private boolean onBubbleTouch(View v, MotionEvent e) {
        lastRawX = e.getRawX();
        lastRawY = e.getRawY();
        int slop = Ui.dp(this, 10);
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                state = IDLE;
                moveTx = e.getRawX();
                moveTy = e.getRawY();
                h.removeCallbacks(holdDone);
                h.postDelayed(holdDone, HOLD_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (state == MOVE) {
                    // Free movement: any point of the screen.
                    bp.x = clamp(moveSx + (int) (e.getRawX() - moveTx), 0, Math.max(0, sw - bsz));
                    bp.y = clamp(moveSy + (int) (e.getRawY() - moveTy), 0, Math.max(0, sh - bsz));
                    try { wm.updateViewLayout(bubble, bp); } catch (Throwable ignored) {}
                } else if (state == IDLE) {
                    float dx = e.getRawX() - moveTx, dy = e.getRawY() - moveTy;
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        // finger left the button before the hold finished: this is a "pick a pack" drag
                        h.removeCallbacks(holdDone);
                        state = PICK;
                        showPicker();
                        updateBubbleFace();
                    }
                }
                if (state == PICK) updateHover(e.getRawX(), e.getRawY());
                return true;
            }

            case MotionEvent.ACTION_UP:
                h.removeCallbacks(holdDone);
                if (state == MOVE) {
                    savePos();
                } else if (state == PICK) {
                    String id = hover >= 0 && hover < pickIds.size() ? pickIds.get(hover) : null;
                    hidePicker();
                    if (id != null && !id.isEmpty()) {
                        PackStore.setActive(this, id);
                        openPanel();
                    }
                } else { // plain tap
                    if (Math.abs(outsideAt - e.getDownTime()) < 350) { /* this tap just closed the panel */ }
                    else if (open) closePanel();
                    else openPanel();
                }
                state = IDLE;
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                updateBubbleFace();
                return true;

            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(holdDone);
                if (state == MOVE) savePos();
                hidePicker();
                state = IDLE;
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                updateBubbleFace();
                return true;
        }
        return false;
    }

    /** Held long enough: from now on the finger moves the button. */
    private void enterMoveMode() {
        if (state != IDLE) return;
        state = MOVE;
        if (open) closePanel();
        moveSx = bp.x;
        moveSy = bp.y;
        moveTx = lastRawX;
        moveTy = lastRawY;
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        bubble.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start();
        updateBubbleFace();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }

    private void readScreen() {
        Rect r = wm.getCurrentWindowMetrics().getBounds();
        sw = r.width();
        sh = r.height();
    }

    private void applyBubblePos() {
        bp.x = clamp(Math.round(fx * Math.max(0, sw - bsz)), 0, Math.max(0, sw - bsz));
        bp.y = clamp(Math.round(fy * Math.max(0, sh - bsz)), 0, Math.max(0, sh - bsz));
    }

    private void savePos() {
        fx = bp.x / (float) Math.max(1, sw - bsz);
        fy = bp.y / (float) Math.max(1, sh - bsz);
        PackStore.setBubblePos(this, fx, fy);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // Screen rotated (games are often landscape): keep the button at the same relative spot.
        hidePicker();
        if (open) closePanel();
        readScreen();
        applyBubblePos();
        try { wm.updateViewLayout(bubble, bp); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------ drag-to-pick list

    private void showPicker() {
        if (open) closePanel();
        hidePicker();
        readScreen();
        List<PackStore.Pack> packs = PackStore.list(this);
        String activeId = PackStore.activeId(this);

        int rowH = Ui.dp(this, 38), margin = Ui.dp(this, 16);
        int maxRows = Math.max(3, (sh - 2 * margin - Ui.dp(this, 16)) / rowH);
        int n = Math.max(1, packs.size());
        int cols = (int) Math.ceil(n / (double) maxRows);
        int colW = Math.min(Ui.dp(this, 150), Math.max(Ui.dp(this, 90), (sw - 2 * margin) / Math.max(1, cols)));
        cols = Math.max(1, Math.min(cols, (sw - 2 * margin) / colW));
        int rows = Math.min(maxRows, (int) Math.ceil(n / (double) cols));

        picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.HORIZONTAL);
        picker.setBackground(Ui.round(Ui.PANEL, this, 16));
        picker.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        picker.setElevation(Ui.dp(this, 8));
        pickCells.clear();
        pickIds.clear();
        hover = -1;

        if (packs.isEmpty()) {
            TextView t = pickCell("কোনো প্যাক নেই", false);
            picker.addView(t, new LinearLayout.LayoutParams(colW, rowH));
        } else {
            int idx = 0;
            for (int c = 0; c < cols; c++) {
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                for (int r = 0; r < rows && idx < packs.size(); r++, idx++) {
                    PackStore.Pack p = packs.get(idx);
                    boolean act = p.id.equals(activeId);
                    TextView t = pickCell(p.emoji + " " + p.name + (act ? "  ●" : ""), act);
                    col.addView(t, new LinearLayout.LayoutParams(colW, rowH));
                    pickCells.add(t);
                    pickIds.add(p.id);
                }
                picker.addView(col, new LinearLayout.LayoutParams(-2, -2));
            }
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        picker.measure(View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST));
        int w = picker.getMeasuredWidth(), ph = picker.getMeasuredHeight(), gap = Ui.dp(this, 8);
        boolean right = bp.x + bsz + gap + w <= sw;
        lp.x = right ? bp.x + bsz + gap : Math.max(0, bp.x - gap - w);
        lp.y = clamp(bp.y + bsz / 2 - ph / 2, 0, Math.max(0, sh - ph));
        try { wm.addView(picker, lp); } catch (Throwable e) { picker = null; }
    }

    private TextView pickCell(String text, boolean active) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setTextColor(Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        return t;
    }

    /** Highlights the pack under the finger. */
    private void updateHover(float rx, float ry) {
        int found = -1;
        int[] loc = new int[2];
        for (int i = 0; i < pickCells.size(); i++) {
            TextView t = pickCells.get(i);
            t.getLocationOnScreen(loc);
            if (rx >= loc[0] && rx < loc[0] + t.getWidth() && ry >= loc[1] && ry < loc[1] + t.getHeight()) {
                found = i;
                break;
            }
        }
        if (found == hover) return;
        if (hover >= 0 && hover < pickCells.size()) pickCells.get(hover).setBackground(null);
        hover = found;
        if (hover >= 0) {
            pickCells.get(hover).setBackground(Ui.round(Ui.ACCENT, this, 12));
            bubble.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void hidePicker() {
        if (picker != null) {
            try { wm.removeView(picker); } catch (Throwable ignored) {}
            picker = null;
        }
        pickCells.clear();
        pickIds.clear();
        hover = -1;
    }

    // ---------------------------------------------------------------- panel

    private void openPanel() {
        if (open) closePanel();
        readScreen();
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
            // Outside touches (the game) pass through untouched; they only close the panel when it is not pinned.
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && !PackStore.pinned(this)) {
                outsideAt = SystemClock.uptimeMillis();
                closePanel();
            }
            return false;
        });

        panel.setAlpha(0f);
        panel.setTranslationX(slide * pw / 2f);
        try { wm.addView(panel, pp); } catch (Throwable t) { panel = null; return; }
        panelAttached = true;
        panel.animate().alpha(1f).translationX(0f).setDuration(160).start();
        open = true;
        updateBubbleFace();
    }

    private void closePanel() {
        if (!open) return;
        open = false;
        panelAttached = false;
        final View p = panel;
        panel = null;
        scroller = null;
        packHs = null;
        updateBubbleFace();
        if (p == null) return;
        p.animate().alpha(0f).translationX(slide * pw / 3f).setDuration(120)
                .withEndAction(() -> { try { wm.removeView(p); } catch (Throwable ignored) {} }).start();
    }

    /** Panel next to the button: right of it, else left of it, else below/above; always inside the screen. */
    private void layoutPanel() {
        if (panel == null) return;
        readScreen();
        int margin = Ui.dp(this, 16), gap = Ui.dp(this, 6);
        int wSpec = View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST);
        panel.measure(wSpec, hSpec);
        int ph = panel.getMeasuredHeight();
        int avail = sh - 2 * margin;
        if (ph > avail && scroller != null) {
            scroller.maxPx = Math.max(Ui.dp(this, 60), scroller.getMeasuredHeight() - (ph - avail));
            panel.measure(wSpec, hSpec);
            ph = panel.getMeasuredHeight();
        }
        boolean preferRight = bp.x + bsz / 2 < sw / 2;
        int rightX = bp.x + bsz + gap, leftX = bp.x - gap - pw;
        boolean rightOk = rightX + pw <= sw, leftOk = leftX >= 0;
        int mode;
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

    private void refreshPanel() {
        if (panel == null) return;
        render();
        layoutPanel();
        if (panelAttached) {
            try { wm.updateViewLayout(panel, pp); } catch (Throwable ignored) {}
        }
    }

    private void render() {
        panel.removeAllViews();
        panel.addView(header());

        List<PackStore.Pack> packs = PackStore.list(this);
        if (packs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("কোনো ভয়েস প্যাক নেই।\n⚙ চেপে অ্যাপ খুলুন এবং প্যাক বানান।");
            t.setTextColor(Ui.MUTED);
            t.setTextSize(12);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 12));
            panel.addView(t);
            scroller = null;
            return;
        }
        PackStore.Pack act = PackStore.active(this);
        panel.addView(packBar(packs, act.id));

        scroller = new Ui.MaxHeightScroll(this);
        scroller.maxPx = (int) (sh * 0.5f);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(grid);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 8);
        panel.addView(scroller, slp);

        List<PackStore.Slot> ready = new ArrayList<>();
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < act.slots.size(); i++) {
            if (PackStore.fileOf(this, act, act.slots.get(i)) != null) { ready.add(act.slots.get(i)); index.add(i); }
        }
        if (ready.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("এই প্যাকে এখনো কোনো ভয়েস নেই।\n⚙ চেপে অ্যাপ থেকে ভয়েস যোগ করুন।");
            t.setTextColor(Ui.MUTED);
            t.setTextSize(12);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            grid.addView(t);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < ready.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(commandCell(act, ready.get(i), index.get(i)), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        // pad the last row so cells keep the same width
        if (row != null) for (int k = ready.size() % COLS; k != 0 && k < COLS; k++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private View header() {
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("🎮 গেমিং ভয়েস");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        hd.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        int s = Ui.dp(this, 28);
        final TextView pin = Ui.pill(this, "📌", 0xFF2A3050, Ui.TEXT, 12);
        stylePin(pin);
        pin.setOnClickListener(v -> { PackStore.setPinned(this, !PackStore.pinned(this)); stylePin(pin); });
        hd.addView(pin, new LinearLayout.LayoutParams(s, s));

        TextView stop = Ui.pill(this, "■", Ui.RED, 0xFFFFFFFF, 12);
        stop.setOnClickListener(v -> Client.stop());
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(s, s);
        stp.leftMargin = Ui.dp(this, 6);
        hd.addView(stop, stp);

        TextView gear = Ui.pill(this, "⚙", 0xFF2A3050, Ui.TEXT, 14);
        gear.setOnClickListener(v -> {
            Intent i = new Intent(this, PackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            closePanel();
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(s, s);
        glp.leftMargin = Ui.dp(this, 6);
        hd.addView(gear, glp);

        TextView close = Ui.pill(this, "✕", 0xFF2A3050, Ui.RED, 13);
        close.setOnClickListener(v -> closePanel());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(s, s);
        clp.leftMargin = Ui.dp(this, 6);
        hd.addView(close, clp);
        return hd;
    }

    private void stylePin(TextView pin) {
        boolean on = PackStore.pinned(this);
        pin.setBackground(Ui.round(on ? Ui.ACCENT : 0xFF2A3050, this, 100));
        pin.setAlpha(on ? 1f : 0.6f);
    }

    /** Pack tabs: tap one to make it the ACTIVE pack (the previous one is switched off). */
    private View packBar(List<PackStore.Pack> packs, String activeId) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        packHs = hs;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(row);
        for (final PackStore.Pack p : packs) {
            TextView t = Ui.folderChip(this, p.emoji, p.name, p.id.equals(activeId), 11, 10, 6);
            t.setOnClickListener(v -> {
                if (packHs != null) packScrollX = packHs.getScrollX();
                PackStore.setActive(this, p.id);
                updateBubbleFace();
                refreshPanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 28));
            lp.rightMargin = Ui.dp(this, 5);
            row.addView(t, lp);
        }
        final int keep = packScrollX;
        hs.post(() -> hs.scrollTo(keep, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 8);
        hs.setLayoutParams(lp);
        return hs;
    }

    /** One voice command: round button + name. One tap plays it. */
    private View commandCell(final PackStore.Pack pack, final PackStore.Slot slot, int index) {
        final String label = PackStore.slotLabel(slot, index);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));

        int d = Ui.dp(this, 44);
        final TextView circle = new TextView(this);
        circle.setText(Ui.glyph(label));
        circle.setTextColor(0xFFFFFFFF);
        circle.setTextSize(17);
        circle.setTypeface(Typeface.DEFAULT_BOLD);
        circle.setGravity(Gravity.CENTER);
        circle.setIncludeFontPadding(false);
        circle.setBackground(Ui.oval(Ui.colorFor(slot.id)));
        circle.setElevation(Ui.dp(this, 2));
        FrameLayout badge = new FrameLayout(this);
        badge.addView(circle, new FrameLayout.LayoutParams(-1, -1));
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
            File f = PackStore.fileOf(this, pack, slot);
            if (f == null) { Toast.makeText(this, "ফাইলটি পাওয়া যায়নি", Toast.LENGTH_SHORT).show(); return; }
            String r = Client.play(this, f, pack.vol);
            if (!"ok".equals(r)) Toast.makeText(this, r, Toast.LENGTH_LONG).show();
        });
        return cell;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(holdDone);
        hidePicker();
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
