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

    private static String digits(int n, boolean bengali) {
        String s = String.valueOf(n);
        if (!bengali) return s;
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) sb.append((char) ('\u09E6' + (ch - '0')));
        return sb.toString();
    }

    /**
     * "[label 40%] ----o------" : a draggable 1..100 % volume slider (0 = off when allowOff).
     * onChange gets the new percent while the finger moves.
     */
    public static android.widget.LinearLayout volumeSlider(final Context c, final String prefix, int percent,
            final boolean compact, final boolean allowOff, final boolean bengali,
            final java.util.function.IntConsumer onChange) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView lab = new TextView(c);
        lab.setTextColor(TEXT);
        lab.setTextSize(compact ? 11 : 14);
        lab.setTypeface(Typeface.DEFAULT_BOLD);
        lab.setMinWidth(dp(c, compact ? 70 : 118));
        final android.widget.SeekBar sb = new android.widget.SeekBar(c);
        final int min = allowOff ? 0 : 1;
        sb.setMax(100);
        sb.setMin(min);
        sb.setProgress(Math.max(min, Math.min(100, percent)));
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        sb.setSplitTrack(false);
        final Runnable upd = () -> {
            int p = sb.getProgress();
            lab.setText(prefix + (p == 0 ? "বন্ধ" : digits(p, bengali) + "%"));
        };
        upd.run();
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                upd.run();
                if (user && onChange != null) onChange.accept(p);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {
                if (s.getParent() != null) s.getParent().requestDisallowInterceptTouchEvent(true);
            }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        row.addView(lab, new android.widget.LinearLayout.LayoutParams(-2, -2));
        row.addView(sb, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

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

    public static final int GOLD = 0xFFFFC83D;

    /** Tiny gold star badge for favourite voices. */
    public static TextView star(Context c) {
        TextView t = new TextView(c);
        t.setText("★");
        t.setTextSize(10);
        t.setTextColor(GOLD);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(dp(c, 2), 0, 0, 0xFF000000);
        return t;
    }

    /** First visible character (a whole emoji, even multi-part ones like ❤️ or 👨‍👩‍👧). */
    public static String firstGrapheme(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return "";
        try {
            android.icu.text.BreakIterator it = android.icu.text.BreakIterator.getCharacterInstance();
            it.setText(s);
            int end = it.next();
            if (end > 0) return s.substring(0, end);
        } catch (Throwable ignored) {}
        return new String(Character.toChars(s.codePointAt(0)));
    }

    public static String glyph(String label) {
        String g = firstGrapheme(label);
        return g.isEmpty() ? "♪" : g.toUpperCase();
    }

    /** Emoji choices for folder icons. */
    public static final String[] EMOJIS = {
            "📁", "😂", "🤣", "😎", "🤡", "👻", "💀", "😈",
            "🔥", "🎮", "🎤", "🎵", "🔊", "🐔", "🐶", "🐱",
            "🐸", "🐵", "🦆", "🐷", "🤖", "👽", "💩", "🥶",
            "😱", "🤪", "😡", "🥵", "🚀", "💥", "🎉", "❤️"};

    /** Folder tab: "emoji name" in a pill. Highlighted when selected. */
    public static TextView folderChip(Context c, String emoji, String name, boolean on,
                                      float sp, float padHDp, float padVDp) {
        TextView t = new TextView(c);
        t.setText(emoji + " " + name);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(c, 130));
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(c, padHDp), dp(c, padVDp), dp(c, padHDp), dp(c, padVDp));
        styleChip(t, on);
        return t;
    }

    public static void styleChip(TextView t, boolean on) {
        t.setBackground(round(on ? ACCENT : 0xFF252B45, t.getContext(), 100));
        t.setTextColor(on ? 0xFFFFFFFF : 0xFFB8BFDA);
    }

    /** Grid of emoji; tap one to select it. */
    public static class EmojiPicker extends android.widget.LinearLayout {
        public String value;
        public Runnable onPick;
        private final java.util.List<TextView> cells = new java.util.ArrayList<>();

        public EmojiPicker(Context c, String initial, int cols, float cellDp) {
            super(c);
            setOrientation(VERTICAL);
            value = initial;
            android.widget.LinearLayout row = null;
            for (int i = 0; i < EMOJIS.length; i++) {
                if (i % cols == 0) {
                    row = new android.widget.LinearLayout(c);
                    row.setOrientation(HORIZONTAL);
                    addView(row, new LayoutParams(-2, -2));
                }
                final String e = EMOJIS[i];
                TextView t = new TextView(c);
                t.setText(e);
                t.setTextSize(20);
                t.setGravity(Gravity.CENTER);
                t.setIncludeFontPadding(false);
                t.setOnClickListener(v -> { value = e; restyle(); if (onPick != null) onPick.run(); });
                cells.add(t);
                row.addView(t, new LayoutParams(dp(c, cellDp), dp(c, cellDp)));
            }
            restyle();
        }

        public void restyle() {
            for (int i = 0; i < cells.size(); i++) {
                boolean on = EMOJIS[i].equals(value);
                cells.get(i).setBackground(on ? round(ACCENT, getContext(), 10) : null);
            }
        }
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
