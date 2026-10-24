package com.gamevoice.app;
import android.os.ParcelFileDescriptor;
interface IUserService {
    void destroy() = 16777114;
    String play(in ParcelFileDescriptor pfd, int volumePct) = 1;
    void stop() = 2;
    String status() = 3;
    String diagnose() = 4;
}
