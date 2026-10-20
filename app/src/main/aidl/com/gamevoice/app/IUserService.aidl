package com.gamevoice.app;
import android.os.ParcelFileDescriptor;
interface IUserService {
    void destroy() = 16777114;
    String play(in ParcelFileDescriptor pfd) = 1;
    void stop() = 2;
}
