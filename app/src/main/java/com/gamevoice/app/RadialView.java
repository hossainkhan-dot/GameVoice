package com.gamevoice.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * The "flower": a centre circle with the voice commands as petals around it. It is only drawn (the finger that
 * selects a petal belongs to the floating button), so it never has to handle touches itself.
 * Petal i sits at angle -90 + i * 360/n degrees (first one at the top, clockwise).
 */
public class RadialView extends View {
    public static final int VOICE = 0, PAGE = 1, PACK = 2;

    public static final class Item {
        public final int kind;
        public final String glyph, label;
        public final int color;
        public final Object tag;
        public Item(int kind, String glyph, String label, int color, Object tag) {
            this.kind = kind; this.glyph = glyph; this.label = label; this.color = color; this.tag = tag;
        }
    }

    // sizes in dp at scale 1
    private static final float RING = 84, PETAL_LEN = 60, PETAL_WID = 50, INNER = 30, CENTER_R = 24, BAND = 26;
    private static final float EXT = RING + PETAL_LEN / 2f + 8;

    private final float u0;        // px per dp
    private float u;               // px per dp after fitting the screen (u0 * scale)
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private List<Item> items = new ArrayList<>();
    private String centerGlyph = "🎮", caption = "";
    private int hover = -1;

    public RadialView(Context c) {
        super(c);
        u0 = c.getResources().getDisplayMetrics().density;
        u = u0;
        stroke.setStyle(Paint.Style.STROKE);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    /** Shrinks the flower if the screen is too small to show it whole (short landscape screens). */
    public void fit(int sw, int sh) {
        float sc = Math.min(1f, Math.min(sw / (2 * EXT * u0), sh / ((2 * EXT + BAND) * u0)));
        u = u0 * Math.max(0.5f, sc);
    }

    public int viewW() { return (int) Math.ceil(2 * EXT * u); }
    public int viewH() { return (int) Math.ceil((2 * EXT + BAND) * u); }
    public float centerX() { return EXT * u; }
    public float centerY() { return (BAND + EXT) * u; }

    public void setData(String centerGlyph, String caption, List<Item> items) {
        this.centerGlyph = centerGlyph;
        this.caption = caption;
        this.items = items;
        invalidate();
    }

    public Item item(int i) { return i >= 0 && i < items.size() ? items.get(i) : null; }

    public void setHover(int i) {
        if (i == hover) return;
        hover = i;
        invalidate();
    }

    /** Which petal is under (x, y) (view coordinates, px)? -1 = centre / empty / too far away (= cancel). */
    public int indexAt(float x, float y) {
        int n = items.size();
        if (n == 0) return -1;
        float dx = x - centerX(), dy = y - centerY();
        double dist = Math.hypot(dx, dy);
        if (dist < INNER * u || dist > EXT * 1.9f * u) return -1;
        double step = 360.0 / n;
        double ang = Math.toDegrees(Math.atan2(dy, dx)) + 90;   // 0 = top
        ang = ((ang % 360) + 360) % 360;
        int i = (int) Math.round(ang / step) % n;
        return items.get(i) == null ? -1 : i;
    }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(viewW(), viewH()); }

    @Override protected void onDraw(Canvas cv) {
        float cx = centerX(), cy = centerY();

        // soft backdrop so the petals read on any game
        fill.setColor(0x66000000);
        cv.drawCircle(cx, cy, EXT * u, fill);

        // pack name on top
        if (!caption.isEmpty()) {
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextSize(11 * u);
            tp.setColor(0xFFFFFFFF);
            String s = TextUtils.ellipsize(caption, tp, 2 * EXT * u - 8 * u, TextUtils.TruncateAt.END).toString();
            float w = tp.measureText(s) + 16 * u;
            rect.set(cx - w / 2, 2 * u, cx + w / 2, (BAND - 4) * u);
            fill.setColor(0xCC161A2C);
            cv.drawRoundRect(rect, 10 * u, 10 * u, fill);
            cv.drawText(s, cx, rect.centerY() + 4 * u, tp);
        }

        int n = items.size();
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            if (it == null) continue;
            double a = Math.toRadians(-90 + i * 360.0 / n);
            float px = cx + (float) Math.cos(a) * RING * u, py = cy + (float) Math.sin(a) * RING * u;
            boolean on = i == hover;
            float k = on ? 1.18f : 1f;

            cv.save();
            cv.rotate((float) Math.toDegrees(a) + 90, px, py);
            rect.set(px - PETAL_WID * u * k / 2, py - PETAL_LEN * u * k / 2, px + PETAL_WID * u * k / 2, py + PETAL_LEN * u * k / 2);
            fill.setColor(on ? 0xFFFFFFFF : it.color);
            fill.setAlpha(on ? 255 : 235);
            cv.drawOval(rect, fill);
            stroke.setStrokeWidth((on ? 3 : 1.5f) * u);
            stroke.setColor(on ? Ui.ACCENT : 0xAAFFFFFF);
            cv.drawOval(rect, stroke);
            cv.restore();

            int fg = on ? 0xFF14172A : 0xFFFFFFFF;
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setColor(fg);
            tp.setTextSize(18 * u * k);
            cv.drawText(it.glyph, px, py + 2 * u, tp);
            tp.setTypeface(Typeface.DEFAULT);
            tp.setTextSize(9 * u);
            String lab = TextUtils.ellipsize(it.label, tp, PETAL_WID * u - 4 * u, TextUtils.TruncateAt.END).toString();
            cv.drawText(lab, px, py + 15 * u * k, tp);
        }

        // centre: where the finger started (the button itself)
        fill.setColor(0xFF161A2C);
        cv.drawCircle(cx, cy, CENTER_R * u, fill);
        stroke.setStrokeWidth(2 * u);
        stroke.setColor(Ui.ACCENT);
        cv.drawCircle(cx, cy, CENTER_R * u, stroke);
        tp.setTypeface(Typeface.DEFAULT);
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(20 * u);
        cv.drawText(centerGlyph, cx, cy + 7 * u, tp);
    }
}
