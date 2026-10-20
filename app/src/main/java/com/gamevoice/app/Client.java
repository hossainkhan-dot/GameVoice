package com.gamevoice.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import rikka.shizuku.Shizuku;

public class Client {
    public static volatile IUserService svc;
    public static Runnable onChange;

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = IUserService.Stub.asInterface(b);
            if (onChange != null) onChange.run();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            svc = null;
            if (onChange != null) onChange.run();
        }
    };

    public static void bind(Context ctx) {
        if (svc != null) return;
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(ctx.getPackageName(), InjectService.class.getName()))
                .daemon(false).processNameSuffix("inject").version(1);
        Shizuku.bindUserService(args, conn);
    }

    public static String play(File f) {
        IUserService s = svc;
        if (s == null) return "Shizuku সংযুক্ত নয়";
        try {
            return s.play(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY));
        } catch (Throwable e) { return "ERR: " + e; }
    }

    public static void stop() {
        try { if (svc != null) svc.stop(); } catch (Throwable ignored) {}
    }
}
