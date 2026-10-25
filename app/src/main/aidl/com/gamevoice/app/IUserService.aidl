package com.gamevoice.app;
import android.os.ParcelFileDescriptor;
interface IUserService {
    void destroy() = 16777114;
    String play(in ParcelFileDescriptor pfd, int volumePct) = 1;
    void stop() = 2;
    String status() = 3;
    String diagnose() = 4;
    boolean ready() = 5;
    // ---- music player (long track: pause / seek / volume) ----
    String musicPlay(in ParcelFileDescriptor pfd, int volumePct, long startMs) = 6;
    void musicPause() = 7;
    void musicResume() = 8;
    void musicSeek(long ms) = 9;
    void musicStop() = 10;
    void musicVolume(int volumePct) = 11;
    /** {state, positionMs, durationMs}; state: 0 idle, 1 loading, 2 playing, 3 paused, 4 ended */
    long[] musicInfo() = 12;
}
