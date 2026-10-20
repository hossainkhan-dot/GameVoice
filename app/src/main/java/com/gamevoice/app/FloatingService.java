package com.gamevoice.app;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.*;
import android.widget.*;
import java.io.File;

public class FloatingService extends Service {
    private WindowManager wm;
    private LinearLayout root;
    private LinearLayout panel;
    private WindowManager.LayoutParams lp;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel("gv", "GameVoice", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "gv")
                .setContentTitle("GameVoice চালু").setSmallIcon(android.R.drawable.ic_btn_speak_now).build();
        startForeground(1, n);

        wm = getSystemService(WindowManager.class);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView bubble = new TextView(this);
        bubble.setText("🎤");
        bubble.setTextSize(26);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackgroundColor(0xCC222222);
        bubble.setPadding(24, 24, 24, 24);
        root.addView(bubble);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        ScrollView sv = new ScrollView(this);
        sv.addView(panel);
        root.addView(sv, new LinearLayout.LayoutParams(420, 700));

        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 20; lp.y = 300;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - tx, dy = e.getRawY() - ty;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        lp.x = sx + (int) dx; lp.y = sy + (int) dy;
                        wm.updateViewLayout(root, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) toggle();
                        return true;
                }
                return false;
            }
        });
        wm.addView(root, lp);
    }

    private void toggle() {
        if (panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); return; }
        panel.removeAllViews();
        Button stop = new Button(this);
        stop.setText("■ থামাও");
        stop.setOnClickListener(v -> Client.stop());
        panel.addView(stop);
        for (File f : SoundStore.list(this)) {
            Button b = new Button(this);
            b.setText(f.getName());
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                String r = Client.play(f);
                if (!"ok".equals(r)) Toast.makeText(this, r, Toast.LENGTH_LONG).show();
            });
            panel.addView(b);
        }
        panel.setVisibility(View.VISIBLE);
    }

    @Override public void onDestroy() {
        if (root != null) wm.removeView(root);
        super.onDestroy();
    }
}
