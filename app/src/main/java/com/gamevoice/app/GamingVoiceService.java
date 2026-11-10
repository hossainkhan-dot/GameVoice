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
 * Gestures on the button (the button is the centre of the "flower"):
 *  - PRESS and slide            : the voice commands of the active pack open as petals around the button.
 *                                 Slide onto a petal and let go: that voice plays. Let go in the middle: nothing.
 *  - quick TAP                  : opens the panel (pack list + all commands, pin, settings).
 *  - HOLD still for 2.5 seconds : the button can be moved anywhere on the screen (position is remembered).
 *  - petals "⋯" / "🔁"          : keep the finger on them for a moment to flip the page / switch the active pack.
 *
 * The overlay windows are only as big as the button / flower / panel, the flower does not take touches at all and
 * the panel lets every touch outside it through, so the game's own controls keep working.
 */
public class GamingVoiceService extends Service {
    /** Holding the button still this long (without sliding to a petal) lets you move it. */
    private static final long HOLD_MS = 2500;
    /** Pressing longer than this (a tap is shorter) opens the flower even before the finger slides. */
    private static final long RADIAL_MS = 220;
    /** Keeping the finger on a "⋯" / "🔁" petal this long flips the page / switches the pack. */
    private static final long DWELL_MS = 700;
    private static final int VOICES_PER_PAGE = 8;
    private static final int COLS = 4;

    private static final int IDLE = 0, RADIAL = 1, MOVE = 2;

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

    // the flower
    private RadialView radial;
    private float rx0, ry0;                       // screen position of the flower window
    private PackStore.Pack radialPack;
    private int page, hover = -1;

    private int state = IDLE;
    private final Runnable holdDone = this::enterMoveMode;
    private final Runnable radialRun = () -> { if (state == IDLE) showRadial(); };
    private final Runnable dwellRun = this::dwellAdvance;
    private float lastRawX, lastRawY, downX, downY;
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
                downX = moveTx = e.getRawX();
                downY = moveTy = e.getRawY();
                h.removeCallbacks(holdDone);
                h.removeCallbacks(radialRun);
                h.postDelayed(radialRun, RADIAL_MS);
                h.postDelayed(holdDone, HOLD_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (state == MOVE) {
                    // Free movement: any point of the screen.
                    bp.x = clamp(moveSx + (int) (e.getRawX() - moveTx), 0, Math.max(0, sw - bsz));
                    bp.y = clamp(moveSy + (int) (e.getRawY() - moveTy), 0, Math.max(0, sh - bsz));
                    try { wm.updateViewLayout(bubble, bp); } catch (Throwable ignored) {}
                    return true;
                }
                if (state == IDLE && (Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop)) {
                    showRadial();   // sliding out of the button opens the flower at once
                }
                if (state == RADIAL) updateHover(e.getRawX(), e.getRawY());
                return true;
            }

            case MotionEvent.ACTION_UP:
                h.removeCallbacks(holdDone);
                h.removeCallbacks(radialRun);
                if (state == MOVE) {
                    savePos();
                } else if (state == RADIAL) {
                    updateHover(e.getRawX(), e.getRawY());
                    RadialView.Item it = radial == null ? null : radial.item(hover);
                    PackStore.Pack pk = radialPack;
                    hideRadial();
                    if (it != null && it.kind == RadialView.VOICE && pk != null) {
                        bubble.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        playSlot(pk, (PackStore.Slot) it.tag);
                    }
                } else { // quick tap
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
                h.removeCallbacks(radialRun);
                if (state == MOVE) savePos();
                hideRadial();
                state = IDLE;
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                updateBubbleFace();
                return true;
        }
        return false;
    }

    /** Held still long enough: from now on the finger moves the button. */
    private void enterMoveMode() {
        if (state == MOVE) return;
        // only when the finger really stayed on the button (not while it is choosing a petal)
        if (Math.hypot(lastRawX - downX, lastRawY - downY) > Ui.dp(this, 20)) return;
        hideRadial();
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

    private void playSlot(PackStore.Pack pk, PackStore.Slot slot) {
        File f = PackStore.fileOf(this, pk, slot);
        if (f == null) { Toast.makeText(this, "ফাইলটি পাওয়া যায়নি", Toast.LENGTH_SHORT).show(); return; }
        String r = Client.play(this, f, pk.vol);
        if (!"ok".equals(r)) Toast.makeText(this, r, Toast.LENGTH_LONG).show();
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
        hideRadial();
        if (open) closePanel();
        readScreen();
        applyBubblePos();
        try { wm.updateViewLayout(bubble, bp); } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------- the flower

    private void showRadial() {
        if (state != IDLE) return;
        state = RADIAL;
        if (open) closePanel();
        readScreen();
        page = 0;
        hover = -1;
        radial = new RadialView(this);
        radial.fit(sw, sh);
        buildRadial();

        // centre of the flower = centre of the button, pushed inwards when it would leave the screen
        float cw = radial.centerX(), ch = radial.centerY();
        float cxs = clampF(bp.x + bsz / 2f, cw, sw - (radial.viewW() - cw));
        float cys = clampF(bp.y + bsz / 2f, ch, sh - (radial.viewH() - ch));
        rx0 = cxs - cw;
        ry0 = cys - ch;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(radial.viewW(), radial.viewH(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(rx0);
        lp.y = Math.round(ry0);
        try { wm.addView(radial, lp); } catch (Throwable t) { radial = null; state = IDLE; return; }
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        updateBubbleFace();
    }

    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }

    /** (Re)builds the petals: the voices of the active pack, plus "⋯" (more pages) and "🔁" (next pack). */
    private void buildRadial() {
        PackStore.Pack act = PackStore.active(this);
        radialPack = act;
        List<PackStore.Slot> voices = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        if (act != null) {
            for (int i = 0; i < act.slots.size(); i++) {
                if (PackStore.fileOf(this, act, act.slots.get(i)) != null) { voices.add(act.slots.get(i)); idx.add(i); }
            }
        }
        int total = voices.size();
        int per = Math.min(VOICES_PER_PAGE, total);
        int pages = per == 0 ? 1 : (int) Math.ceil(total / (double) per);
        if (page >= pages) page = 0;
        boolean pageCtl = pages > 1, packCtl = PackStore.list(this).size() > 1;

        List<RadialView.Item> slots = new ArrayList<>();
        for (int i = 0; i < per; i++) {
            int k = page * per + i;
            if (k < total) {
                PackStore.Slot sl = voices.get(k);
                String label = PackStore.slotLabel(sl, idx.get(k));
                slots.add(new RadialView.Item(RadialView.VOICE, Ui.glyph(label), label, Ui.colorFor(sl.id), sl));
            } else slots.add(null);   // blank petal on the last page keeps "⋯" / "🔁" where they were
        }
        if (pageCtl) slots.add(new RadialView.Item(RadialView.PAGE, "⋯", "পাতা " + (page + 1) + "/" + pages, 0xFF3A4170, null));
        if (packCtl) slots.add(new RadialView.Item(RadialView.PACK, "🔁", "প্যাক", 0xFF3A4170, null));

        String cap = act == null ? "কোনো প্যাক নেই (চাপ দিয়ে ⚙)"
                : act.emoji + " " + act.name + (total == 0 ? " — খালি" : "");
        radial.setData(act == null ? PackStore.DEFAULT_EMOJI : act.emoji, cap, slots);
    }

    /** Highlights the petal under the finger; "⋯" / "🔁" react when the finger rests on them. */
    private void updateHover(float rawX, float rawY) {
        if (radial == null) return;
        int idx = radial.indexAt(rawX - rx0, rawY - ry0);
        if (idx == hover) return;
        hover = idx;
        radial.setHover(hover);
        h.removeCallbacks(dwellRun);
        if (hover >= 0) {
            bubble.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            RadialView.Item it = radial.item(hover);
            if (it != null && it.kind != RadialView.VOICE) h.postDelayed(dwellRun, DWELL_MS);
        }
    }

    private void dwellAdvance() {
        if (radial == null || state != RADIAL) return;
        RadialView.Item it = radial.item(hover);
        if (it == null) return;
        if (it.kind == RadialView.PAGE) {
            page++;
        } else if (it.kind == RadialView.PACK) {
            List<PackStore.Pack> all = PackStore.list(this);
            if (all.isEmpty()) return;
            String cur = PackStore.active(this) == null ? "" : PackStore.active(this).id;
            int at = 0;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(cur)) at = i;
            PackStore.setActive(this, all.get((at + 1) % all.size()).id);
            page = 0;
            updateBubbleFace();
        } else return;
        bubble.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        buildRadial();
        h.postDelayed(dwellRun, DWELL_MS * 2);   // keep flipping while the finger stays, a bit slower
    }

    private void hideRadial() {
        h.removeCallbacks(dwellRun);
        if (radial != null) {
            try { wm.removeView(radial); } catch (Throwable ignored) {}
            radial = null;
        }
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
            playSlot(pack, slot);
        });
        return cell;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(holdDone);
        h.removeCallbacks(radialRun);
        hideRadial();
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
