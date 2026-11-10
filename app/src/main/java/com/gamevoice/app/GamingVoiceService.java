package com.gamevoice.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.List;
import rikka.shizuku.Shizuku;

/**
 * Floating Gaming Voice button. The overlay shows ONLY the round wheel (set up in the settings screen).
 *
 *  - PRESS the button and slide: the wheel opens around the button. Slide onto a slot and let go: that sound plays.
 *                                Let go in the middle / outside the ring / without moving: nothing plays.
 *  - quick TAP                 : the wheel opens and stays; tap a slot to play it (it closes again),
 *                                tap the middle "■" to stop every sound, tap outside to close.
 *  - HOLD still for 2.5 seconds: the button can be moved anywhere on the screen (position is remembered).
 *
 * The slide-wheel takes no touches at all (the finger belongs to the button) and the tap-wheel lets every touch
 * outside it through, so the game's own controls keep working.
 */
public class GamingVoiceService extends Service {
    private static final long HOLD_MS = 2500;      // hold still this long to move the button
    private static final long SLIDE_MS = 220;      // pressing longer than a tap opens the wheel before the finger moves

    private static final int IDLE = 0, SLIDE = 1, MOVE = 2;

    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int sw, sh, bsz;
    private float fx, fy;

    // the wheel (slide mode: not touchable; tap mode: touchable)
    private WheelView wheel;
    private boolean sticky, armed;
    private float wx0, wy0;                      // screen position of the wheel window
    private int count;
    private String[] paths;
    private long outsideAt = -10000;

    private int state = IDLE;
    private final Runnable holdDone = this::enterMoveMode;
    private final Runnable slideRun = () -> { if (state == IDLE) showWheel(false); };
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
        readScreen();
        fx = WheelStore.bubbleX(this);
        fy = WheelStore.bubbleY(this);

        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextSize(22);
        bubble.setText("🎮");
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
        android.graphics.drawable.GradientDrawable d = Ui.oval(wheel != null || state != IDLE ? Ui.ACCENT : 0xE6161A2C);
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
                h.removeCallbacks(slideRun);
                if (wheel == null) h.postDelayed(slideRun, SLIDE_MS);
                h.postDelayed(holdDone, HOLD_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (state == MOVE) {
                    bp.x = clamp(moveSx + (int) (e.getRawX() - moveTx), 0, Math.max(0, sw - bsz));
                    bp.y = clamp(moveSy + (int) (e.getRawY() - moveTy), 0, Math.max(0, sh - bsz));
                    try { wm.updateViewLayout(bubble, bp); } catch (Throwable ignored) {}
                    return true;
                }
                if (state == IDLE && wheel == null
                        && (Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop)) {
                    showWheel(false);   // sliding out of the button opens the wheel at once
                }
                if (state == SLIDE) updateHover(e.getRawX(), e.getRawY());
                return true;
            }

            case MotionEvent.ACTION_UP:
                h.removeCallbacks(holdDone);
                h.removeCallbacks(slideRun);
                if (state == MOVE) {
                    savePos();
                } else if (state == SLIDE) {
                    updateHover(e.getRawX(), e.getRawY());
                    int idx = wheel == null ? -1 : wheel.hover();
                    File f = idx >= 0 ? WheelStore.fileAt(paths, idx) : null;
                    hideWheel();
                    if (f != null) {
                        bubble.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        playFile(f);
                    }
                } else { // quick tap
                    if (Math.abs(outsideAt - e.getDownTime()) < 350) { /* this tap just closed the wheel */ }
                    else if (wheel != null) hideWheel();
                    else showWheel(true);
                }
                state = IDLE;
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                updateBubbleFace();
                return true;

            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(holdDone);
                h.removeCallbacks(slideRun);
                if (state == MOVE) savePos();
                if (state == SLIDE) hideWheel();
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
        if (Math.hypot(lastRawX - downX, lastRawY - downY) > Ui.dp(this, 20)) return;
        hideWheel();
        state = MOVE;
        moveSx = bp.x;
        moveSy = bp.y;
        moveTx = lastRawX;
        moveTy = lastRawY;
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        bubble.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start();
        updateBubbleFace();
    }

    private void playFile(File f) {
        String r = WheelStore.play(this, f);
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
        WheelStore.setBubblePos(this, fx, fy);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        hideWheel();
        readScreen();
        applyBubblePos();
        try { wm.updateViewLayout(bubble, bp); } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------- the wheel

    /** sticky == false: slide mode (the finger of the button drives it). sticky == true: opened by a tap, touchable. */
    private void showWheel(boolean stickyMode) {
        if (wheel != null) return;
        readScreen();
        count = WheelStore.count(this);
        paths = WheelStore.paths(this);
        sticky = stickyMode;
        armed = false;
        state = stickyMode ? IDLE : SLIDE;

        int d = Math.max(Ui.dp(this, 150), Math.min(Ui.dp(this, 190), (Math.min(sw, sh) - Ui.dp(this, 12)) / 2));   // half the old size
        wheel = new WheelView(this);
        wheel.setSlots(WheelStore.labels(this, count, paths));
        if (stickyMode) wheel.setHub("■", "সব বন্ধ", Ui.RED);
        else wheel.setHub("", "", 0xFFFFFFFF);

        // centre of the wheel = centre of the button, pushed inwards when it would leave the screen
        float cxs = bp.x + bsz / 2f, cys = bp.y + bsz / 2f;
        wx0 = clampF(cxs - d / 2f, 0, sw - d);
        wy0 = clampF(cys - d / 2f, 0, sh - d);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (stickyMode) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        else flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(d, d,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(wx0);
        lp.y = Math.round(wy0);
        if (stickyMode) wheel.setOnTouchListener(this::onWheelTouch);
        wheel.setAlpha(0f);
        try { wm.addView(wheel, lp); } catch (Throwable t) { wheel = null; state = IDLE; return; }
        wheel.animate().alpha(1f).setDuration(110).start();
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        updateBubbleFace();
    }

    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }

    /** Slide mode: highlight the slot under the finger. Only after the finger really moved, so a wheel that was
     *  pushed inwards by a screen edge never plays a slot that merely lies under the button. */
    private void updateHover(float rawX, float rawY) {
        if (wheel == null) return;
        if (!armed && Math.hypot(rawX - downX, rawY - downY) > Ui.dp(this, 14)) armed = true;
        int idx = armed ? wheel.indexAt(rawX - wx0, rawY - wy0) : -1;
        if (idx >= 0 && WheelStore.fileAt(paths, idx) == null) idx = -1;   // empty slots are not selectable
        showHover(idx);
    }

    private void showHover(int idx) {
        if (wheel == null || idx == wheel.hover()) return;
        wheel.setHover(idx);
        if (idx >= 0) {
            bubble.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            wheel.setHub(wheel.label(idx), "", 0xFFFFFFFF);
        } else if (!sticky) {
            wheel.setHub("", "", 0xFFFFFFFF);
        }
    }

    /** Tap mode: tap a slot to play it, the hub to stop everything, outside to close. */
    private boolean onWheelTouch(View v, MotionEvent e) {
        if (wheel == null) return false;
        switch (e.getAction()) {
            case MotionEvent.ACTION_OUTSIDE:
                outsideAt = SystemClock.uptimeMillis();
                hideWheel();
                return true;
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                int idx = wheel.indexAt(e.getX(), e.getY());
                if (idx >= 0 && WheelStore.fileAt(paths, idx) == null) idx = -1;
                wheel.setHover(idx);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float x = e.getX(), y = e.getY();
                int idx = wheel.indexAt(x, y);
                boolean hub = wheel.inHub(x, y);
                File f = idx >= 0 ? WheelStore.fileAt(paths, idx) : null;
                if (f != null) {
                    hideWheel();
                    bubble.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    playFile(f);
                } else if (hub) {
                    Client.stop();
                    hideWheel();
                } else {
                    wheel.setHover(-1);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                wheel.setHover(-1);
                return true;
        }
        return true;
    }

    private void hideWheel() {
        if (wheel != null) {
            final View w = wheel;
            wheel = null;
            try { wm.removeView(w); } catch (Throwable ignored) {}
        }
        sticky = false;
        armed = false;
        if (bubble != null) updateBubbleFace();
    }

    @Override public void onDestroy() {
        h.removeCallbacks(holdDone);
        h.removeCallbacks(slideRun);
        hideWheel();
        try { if (bubble != null) wm.removeView(bubble); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
