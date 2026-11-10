package com.gamevoice.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * The round wheel (like the "quick messages" wheel of battle-royale games): a ring cut into N equal slots around an
 * empty hub. Slot i is centred at the angle -90 + i * 360/N degrees (first slot at the top, clockwise).
 * It only draws; touches are handled by whoever owns it (overlay service or settings screen).
 * The same view is used for the floating overlay and for the editor in the settings.
 */
public class WheelView extends View {
    public static final int GOLD = 0xFFFFC83D;

    private List<String> labels = new ArrayList<>();   // null entry = empty slot
    private int hover = -1, selected = -1;
    private boolean edit;                // editor: show "+" in empty slots, numbers
    private boolean dragging;            // editor: a sound is being dragged over the wheel
    private String hub = "", hubSub = "";
    private int hubColor = 0xFFFFFFFF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF outer = new RectF(), inner = new RectF();
    private StaticLayout[] lay = new StaticLayout[0];
    private float[] lx = new float[0], ly = new float[0];
    private float cx, cy, rOut, rIn, textPx;
    private final float dp;

    public WheelView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
    }

    // ---------------------------------------------------------------- data

    public void setSlots(List<String> l) {
        labels = l == null ? new ArrayList<>() : l;
        if (selected >= labels.size()) selected = -1;
        if (hover >= labels.size()) hover = -1;
        rebuild();
        invalidate();
    }

    public int slotCount() { return labels.size(); }
    public boolean filled(int i) { return i >= 0 && i < labels.size() && labels.get(i) != null; }
    public String label(int i) { return i >= 0 && i < labels.size() ? labels.get(i) : null; }

    public void setEditMode(boolean on) { edit = on; rebuild(); invalidate(); }
    public void setDragging(boolean on) { if (dragging != on) { dragging = on; invalidate(); } }

    /** Text in the middle hub (e.g. the name of the slot under the finger). */
    public void setHub(String main, String sub, int color) {
        main = main == null ? "" : main;
        sub = sub == null ? "" : sub;
        if (main.equals(hub) && sub.equals(hubSub) && color == hubColor) return;
        hub = main; hubSub = sub; hubColor = color;
        invalidate();
    }

    public void setHover(int i) {
        if (i == hover) return;
        hover = i;
        invalidate();
    }
    public int hover() { return hover; }

    public void setSelected(int i) {
        if (i == selected) return;
        selected = i;
        invalidate();
    }
    public int selected() { return selected; }

    // ------------------------------------------------------------ geometry

    @Override protected void onMeasure(int w, int h) {
        int ws = MeasureSpec.getSize(w), hs = MeasureSpec.getSize(h);
        int wm = MeasureSpec.getMode(w), hm = MeasureSpec.getMode(h);
        int s;
        if (wm == MeasureSpec.UNSPECIFIED && hm == MeasureSpec.UNSPECIFIED) s = (int) (300 * dp);
        else if (wm == MeasureSpec.UNSPECIFIED) s = hs;
        else if (hm == MeasureSpec.UNSPECIFIED) s = ws;
        else s = Math.min(ws, hs);
        setMeasuredDimension(s, s);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuild();
    }

    /** Hub radius / ring radius are fractions of the view size, so the same drawing works at any size. */
    private void rebuild() {
        float s = Math.min(getWidth(), getHeight());
        cx = getWidth() / 2f;
        cy = getHeight() / 2f;
        rOut = s / 2f - 3 * dp;
        rIn = rOut * 0.40f;
        int n = labels.size();
        textPx = Math.max(8 * dp, Math.min(13 * dp, rOut * 0.062f * (float) Math.sqrt(Math.min(1.0, 12.0 / Math.max(1, n)))));
        tp.setTextSize(textPx);
        lay = new StaticLayout[n];
        lx = new float[n];
        ly = new float[n];
        if (rOut <= 0 || n == 0) return;
        float step = 360f / n;
        float rMid = (rOut + rIn) / 2f;
        float thick = rOut - rIn;
        float chord = 2f * rMid * (float) Math.sin(Math.toRadians(step / 2));
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(-90 + i * step);
            float w = Math.max(chord * 0.92f, thick * 0.86f * (float) Math.abs(Math.cos(a)));
            w = Math.max(40 * dp, Math.min(thick * 0.95f, w));
            String t = labels.get(i);
            if (t == null) t = edit ? "+" : "";
            lay[i] = StaticLayout.Builder.obtain(t, 0, t.length(), tp, (int) w)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            lx[i] = cx + (float) Math.cos(a) * rMid;
            ly[i] = cy + (float) Math.sin(a) * rMid;
        }
    }

    /** Slot under (x, y) in view pixels; -1 for the hub, outside the ring, or when there are no slots. */
    public int indexAt(float x, float y) {
        int n = labels.size();
        if (n == 0) return -1;
        double d = Math.hypot(x - cx, y - cy);
        if (d < rIn || d > rOut * 1.15) return -1;
        double ang = Math.toDegrees(Math.atan2(y - cy, x - cx)) + 90 + 180.0 / n;
        ang = ((ang % 360) + 360) % 360;
        return Math.min(n - 1, (int) (ang / (360.0 / n)));
    }

    public boolean inHub(float x, float y) { return Math.hypot(x - cx, y - cy) < rIn; }

    // ---------------------------------------------------------------- draw

    @Override protected void onDraw(Canvas cv) {
        if (rOut <= 0) return;
        int n = labels.size();
        outer.set(cx - rOut, cy - rOut, cx + rOut, cy + rOut);
        inner.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn);

        // soft round backdrop so the wheel reads on top of any game
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x66000000);
        cv.drawCircle(cx, cy, rOut + 2 * dp, fill);

        float step = n == 0 ? 360f : 360f / n;
        for (int i = 0; i < n; i++) {
            boolean on = i == hover, has = labels.get(i) != null, sel = i == selected;
            float start = -90 + i * step - step / 2f;
            path.reset();
            path.arcTo(outer, start, step);
            path.arcTo(inner, start + step, -step);
            path.close();

            fill.setStyle(Paint.Style.FILL);
            if (on) fill.setColor(has || edit ? 0xF0FFC83D : 0xAA6A5F7A);
            else if (has) fill.setColor(0xCC241B38);
            else fill.setColor(0x99120C1E);
            cv.drawPath(path, fill);

            line.setStrokeWidth(1f * dp);
            line.setColor(0x40FFFFFF);
            cv.drawPath(path, line);
            if (sel && !on) {
                line.setStrokeWidth(2.5f * dp);
                line.setColor(GOLD);
                cv.drawPath(path, line);
            }

            if (lay[i] != null) {
                int color;
                if (on) color = 0xFF1B1226;
                else if (has) color = 0xFFFFFFFF;
                else color = 0x66FFFFFF;
                tp.setColor(color);
                cv.save();
                cv.translate(lx[i] - lay[i].getWidth() / 2f, ly[i] - lay[i].getHeight() / 2f);
                lay[i].draw(cv);
                cv.restore();
            }
        }

        // outer rim
        line.setStrokeWidth(1.5f * dp);
        line.setColor(dragging ? GOLD : 0x66FFFFFF);
        cv.drawCircle(cx, cy, rOut, line);

        // hub
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xEE120C1E);
        cv.drawCircle(cx, cy, rIn - 3 * dp, fill);
        line.setStrokeWidth(2f * dp);
        line.setColor(GOLD);
        cv.drawCircle(cx, cy, rIn - 3 * dp, line);

        if (!hub.isEmpty()) {
            float hw = (rIn - 3 * dp) * 1.6f;
            tp.setColor(hubColor);
            boolean big = hub.length() <= 2;           // an emoji / a symbol
            tp.setTextSize(big ? rIn * 0.8f : textPx * 1.05f);
            StaticLayout a = StaticLayout.Builder.obtain(hub, 0, hub.length(), tp, (int) hw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(big ? 1 : 3).setEllipsize(TextUtils.TruncateAt.END).build();
            StaticLayout b = null;
            if (!hubSub.isEmpty()) {
                tp.setTextSize(textPx * 0.85f);
                tp.setColor(0xFFB8BFDA);
                b = StaticLayout.Builder.obtain(hubSub, 0, hubSub.length(), tp, (int) hw)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build();
            }
            float total = a.getHeight() + (b == null ? 0 : b.getHeight());
            cv.save();
            cv.translate(cx - hw / 2f, cy - total / 2f);
            tp.setTextSize(big ? rIn * 0.8f : textPx * 1.05f);
            tp.setColor(hubColor);
            a.draw(cv);
            if (b != null) {
                cv.translate(0, a.getHeight());
                tp.setTextSize(textPx * 0.85f);
                tp.setColor(0xFFB8BFDA);
                b.draw(cv);
            }
            cv.restore();
            tp.setTextSize(textPx);
        }
    }
}
