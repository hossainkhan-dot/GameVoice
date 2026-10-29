package com.gamevoice.app;

import android.content.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * App side of the "📱 অ্যাপ" folder: tells the Shizuku service which apps' sound should reach the other side
 * and how loud. Only apps that are switched ON are captured, so the game you play is never sent
 * unless you add it to the list and switch it on yourself.
 */
public final class AppAudio {
    private AppAudio() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gv-appaudio");
        t.setDaemon(true);
        return t;
    });

    /** Sends the current "on" apps (with their percentages) to the service. Safe to call any time. */
    public static void sync(Context c) {
        final IUserService s = Client.svc;
        if (s == null) return;
        List<SoundStore.AppItem> all = SoundStore.apps(c);
        int n = 0;
        int[] uids = new int[all.size()], vols = new int[all.size()];
        for (SoundStore.AppItem a : all) {
            if (!a.on) continue;
            int uid = SoundStore.appUid(c, a.pkg);
            if (uid < 0) continue;                       // uninstalled
            uids[n] = uid;
            vols[n] = a.vol;
            n++;
        }
        final int[] u = java.util.Arrays.copyOf(uids, n), v = java.util.Arrays.copyOf(vols, n);
        IO.execute(() -> { try { s.captureSet(u, v); } catch (Throwable ignored) {} });
    }

    /** Live volume change while the slider moves (no restart of the capture). */
    public static void setVolume(Context c, String pkg, int pct) {
        final IUserService s = Client.svc;
        final int uid = SoundStore.appUid(c, pkg);
        if (s == null || uid < 0) return;
        final int p = pct;
        IO.execute(() -> { try { s.captureVolume(uid, p); } catch (Throwable ignored) {} });
    }

    /**
     * Asks the service how each app is doing. Result: uid -> {"ok"|"err", peakPercent, message};
     * uid -1 = a general problem. Delivered on a background thread.
     */
    public static void status(Consumer<Map<Integer, String[]>> done) {
        final IUserService s = Client.svc;
        if (s == null) { done.accept(new HashMap<>()); return; }
        IO.execute(() -> {
            Map<Integer, String[]> m = new HashMap<>();
            try {
                String r = s.captureStatus();
                if (r != null) {
                    for (String line : r.split("\n")) {
                        String[] p = line.split("\\|", 4);
                        if (p.length < 4) continue;
                        try { m.put(Integer.parseInt(p[0]), new String[]{p[1], p[2], p[3]}); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            done.accept(m);
        });
    }
}
