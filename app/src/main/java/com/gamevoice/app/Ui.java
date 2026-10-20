package com.gamevoice.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small UI helpers shared by the app screen and the overlay. */
public class Ui {
    public static final int BG = 0xFF0E1120;
    public static final int CARD = 0xFF181D33;
    public static final int PANEL = 0xF2141829;
    public static final int ACCENT = 0xFF7C6CFF;
    public static final int GREEN = 0xFF2ED47A;
    public static final int RED = 0xFFFF5C6C;
    public static final int TEXT = 0xFFF2F4FA;
    public static final int MUTED = 0xFF8D95B3;

    private static final int[] PALETTE = {
            0xFF7C6CFF, 0xFFFF6B8B, 0xFF2ED47A, 0xFFFFB02E,
            0xFF3FA9F5, 0xFFB86BFF, 0xFF1ECDC0, 0xFFFF7A45};

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int colorFor(String key) {
        return PALETTE[(key.hashCode() & 0x7fffffff) % PALETTE.length];
    }

    public static GradientDrawable round(int color, Context c, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** A round or pill-shaped text "button" (works inside non-focusable overlays). */
    public static TextView pill(Context c, String text, int bg, int fg, float sp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackground(round(bg, c, 100));
        return t;
    }

    public static String glyph(String label) {
        String s = label.trim();
        if (s.isEmpty()) return "♪";
        int cp = s.codePointAt(0);
        return new String(Character.toChars(cp)).toUpperCase();
    }

    /** ScrollView that never grows past maxPx. */
    public static class MaxHeightScroll extends ScrollView {
        public int maxPx = Integer.MAX_VALUE;
        public MaxHeightScroll(Context c) { super(c); setVerticalScrollBarEnabled(false); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST));
        }
    }

    /** Tiny microphone glyph drawn with canvas so it needs no font or image. */
    public static class MicView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public MicView(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            line.setColor(0xFFFFFFFF);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            setActive(false);
        }

        public void setActive(boolean on) {
            GradientDrawable d = oval(on ? ACCENT : 0xB3161A2C);
            d.setStroke(dp(getContext(), 1), on ? 0xFFFFFFFF : 0x55FFFFFF);
            setBackground(d);
            setAlpha(on ? 1f : 0.85f);
        }

        @Override protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, top = (getHeight() - s) / 2f;
            line.setStrokeWidth(s * 0.06f);
            r.set(cx - s * 0.10f, top + s * 0.20f, cx + s * 0.10f, top + s * 0.56f);
            cv.drawRoundRect(r, s * 0.10f, s * 0.10f, fill);
            r.set(cx - s * 0.19f, top + s * 0.34f, cx + s * 0.19f, top + s * 0.68f);
            cv.drawArc(r, 0, 180, false, line);
            cv.drawLine(cx, top + s * 0.68f, cx, top + s * 0.78f, line);
            cv.drawLine(cx - s * 0.09f, top + s * 0.78f, cx + s * 0.09f, top + s * 0.78f, line);
        }
    }
}
