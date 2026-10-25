package com.gamevoice.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;

/** The music player controls: title, seek bar with times, previous / play-pause / next, repeat. */
public class MusicUi {

    public static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Play / pause / skip glyphs drawn with canvas (no font or image needed). */
    static class Icon extends View {
        static final int PLAY = 0, PAUSE = 1, PREV = 2, NEXT = 3;
        private int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
        }

        void setKind(int k) {
            if (k != kind) { kind = k; invalidate(); }
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h) * 0.30f, cx = w / 2f, cy = h / 2f;
            path.reset();
            switch (kind) {
                case PLAY:
                    path.moveTo(cx - s * 0.7f, cy - s);
                    path.lineTo(cx + s * 0.95f, cy);
                    path.lineTo(cx - s * 0.7f, cy + s);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                case PAUSE:
                    cv.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.2f, cy + s, p);
                    cv.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.8f, cy + s, p);
                    break;
                case PREV:
                    cv.drawRect(cx - s, cy - s, cx - s * 0.65f, cy + s, p);
                    path.moveTo(cx + s, cy - s);
                    path.lineTo(cx - s * 0.55f, cy);
                    path.lineTo(cx + s, cy + s);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                default: // NEXT
                    cv.drawRect(cx + s * 0.65f, cy - s, cx + s, cy + s, p);
                    path.moveTo(cx - s, cy - s);
                    path.lineTo(cx + s * 0.55f, cy);
                    path.lineTo(cx - s, cy + s);
                    path.close();
                    cv.drawPath(path, p);
                    break;
            }
        }
    }

    /**
     * The player. Keeps itself up to date while it is attached to a window.
     * compact = smaller version for the floating panel.
     */
    public static class Player extends LinearLayout {
        private TextView title, tCur, tDur, rep;
        private SeekBar bar;
        private Icon playIcon;
        private final Runnable listener = this::update;

        public Player(Context c, boolean compact) {
            super(c);
            setOrientation(VERTICAL);

            title = new TextView(c);
            title.setTextColor(Ui.TEXT);
            title.setTextSize(compact ? 12 : 15);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            addView(title, new LayoutParams(-1, -2));

            bar = new SeekBar(c);
            bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
            bar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A4165));
            bar.setMax(1);
            LayoutParams blp = new LayoutParams(-1, -2);
            blp.topMargin = Ui.dp(c, compact ? 2 : 6);
            addView(bar, blp);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user) tCur.setText(fmt(p));
                }
                @Override public void onStartTrackingTouch(SeekBar s) { MusicPlayer.dragging = true; }
                @Override public void onStopTrackingTouch(SeekBar s) {
                    MusicPlayer.dragging = false;
                    MusicPlayer.seekTo(s.getProgress());
                }
            });

            LinearLayout times = new LinearLayout(c);
            times.setOrientation(HORIZONTAL);
            tCur = new TextView(c);
            tDur = new TextView(c);
            for (TextView t : new TextView[]{tCur, tDur}) {
                t.setTextColor(Ui.MUTED);
                t.setTextSize(compact ? 10 : 12);
            }
            tCur.setText("0:00");
            tDur.setText("0:00");
            tDur.setGravity(Gravity.END);
            times.addView(tCur, new LayoutParams(0, -2, 1f));
            times.addView(tDur, new LayoutParams(0, -2, 1f));
            times.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
            addView(times, new LayoutParams(-1, -2));

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LayoutParams rlp = new LayoutParams(-1, -2);
            rlp.topMargin = Ui.dp(c, compact ? 4 : 8);
            addView(row, rlp);

            int small = Ui.dp(c, compact ? 34 : 46), big = Ui.dp(c, compact ? 42 : 58);
            int gap = Ui.dp(c, compact ? 10 : 18);

            // repeat: off -> repeat all -> repeat one
            rep = new TextView(c);
            rep.setText("🔁");
            rep.setTextSize(compact ? 14 : 18);
            rep.setGravity(Gravity.CENTER);
            rep.setIncludeFontPadding(false);
            rep.setOnClickListener(v -> MusicPlayer.cycleRepeat());
            row.addView(rep, new LayoutParams(small, small));

            Icon prev = new Icon(c, Icon.PREV, 0xFFFFFFFF);
            prev.setBackground(Ui.oval(0xFF252B45));
            prev.setOnClickListener(v -> MusicPlayer.prev(getContext()));
            LayoutParams plp = new LayoutParams(small, small);
            plp.leftMargin = gap;
            row.addView(prev, plp);

            playIcon = new Icon(c, Icon.PLAY, 0xFFFFFFFF);
            playIcon.setBackground(Ui.oval(Ui.ACCENT));
            playIcon.setOnClickListener(v -> MusicPlayer.toggle(getContext()));
            LayoutParams playlp = new LayoutParams(big, big);
            playlp.leftMargin = gap;
            row.addView(playIcon, playlp);

            Icon next = new Icon(c, Icon.NEXT, 0xFFFFFFFF);
            next.setBackground(Ui.oval(0xFF252B45));
            next.setOnClickListener(v -> MusicPlayer.next(getContext()));
            LayoutParams nlp = new LayoutParams(small, small);
            nlp.leftMargin = gap;
            row.addView(next, nlp);

            // invisible spacer so the play button stays centred (mirrors the repeat button)
            View spacer = new View(c);
            LayoutParams slp = new LayoutParams(small, small);
            slp.leftMargin = gap;
            row.addView(spacer, slp);

            update();
        }

        public void update() {
            Context c = getContext();
            File f = MusicPlayer.cur;
            int st = MusicPlayer.state;
            String name = f == null ? "কোনো মিউজিক বাছা হয়নি" : SoundStore.musicLabel(c, f);
            title.setText(st == MusicPlayer.LOADING ? name + "  …" : name);

            boolean live = st == MusicPlayer.PLAYING || st == MusicPlayer.PAUSED;
            bar.setEnabled(live);
            long dur = MusicPlayer.durMs;
            int max = (int) Math.max(1, Math.min(Integer.MAX_VALUE, dur));
            if (bar.getMax() != max) bar.setMax(max);
            if (!MusicPlayer.dragging) {
                long pos = live ? MusicPlayer.posMs : 0;
                bar.setProgress((int) Math.max(0, Math.min(pos, max)));
                tCur.setText(fmt(pos));
            }
            tDur.setText(fmt(dur));

            playIcon.setKind(st == MusicPlayer.PLAYING || st == MusicPlayer.LOADING ? Icon.PAUSE : Icon.PLAY);
            rep.setText(MusicPlayer.repeat == MusicPlayer.REPEAT_ONE ? "🔂" : "🔁");
            rep.setAlpha(MusicPlayer.repeat == MusicPlayer.REPEAT_OFF ? 0.35f : 1f);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            MusicPlayer.addListener(listener);
            update();
        }

        @Override protected void onDetachedFromWindow() {
            MusicPlayer.removeListener(listener);
            super.onDetachedFromWindow();
        }
    }
}
