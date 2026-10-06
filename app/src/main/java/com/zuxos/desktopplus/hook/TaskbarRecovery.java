package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Puts the launcher right when ZUI moves its taskbar from the tablet to the monitor.
 *
 * <p>The log of a bad boot (2026-10-06 14:20) showed it: ZUI built its taskbar on the tablet
 * (display 0), then moved that same bar to the monitor (display 2) three seconds later. A bar
 * built for the tablet keeps the tablet's settings - its start button is left out, apps it opens
 * go to the tablet, its recents key acts on the tablet, and its drawer can come up empty. Force
 * stopping the launcher fixed every one of those, because the launcher then built the bar on
 * the monitor from the start.
 *
 * <p>So this does what the force stop did, by itself: when a bar first seen on the tablet turns
 * up on another screen, the launcher process restarts and the system brings it back with the bar
 * built where it belongs. At most once every ten minutes, so it can never loop.
 */
final class TaskbarRecovery {

    private static final String PREFS = "zux_taskbar_recovery";
    private static final String KEY_AT = "restarted_at";
    private static final long MIN_GAP_MS = 10 * 60 * 1000L;

    /** Where each bar was first seen. */
    private static final Map<View, Integer> FIRST = new WeakHashMap<>();
    private static boolean sPending;

    private TaskbarRecovery() {
    }

    static void check(ViewGroup dragLayer) {
        if (dragLayer.getDisplay() == null) {
            return;
        }
        int now = dragLayer.getDisplay().getDisplayId();
        Integer first = FIRST.get(dragLayer);
        if (first == null) {
            FIRST.put(dragLayer, now);
            return;
        }
        if (first != 0 || now == 0 || sPending) {
            return;
        }
        sPending = true;
        Context ctx = dragLayer.getContext().getApplicationContext() != null
                ? dragLayer.getContext().getApplicationContext() : dragLayer.getContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long wall = System.currentTimeMillis();
        long last = prefs.getLong(KEY_AT, 0L);
        if (wall - last < MIN_GAP_MS) {
            L.w("taskbar recovery: the bar moved from the tablet to display " + now
                    + " again, but the launcher was already restarted for it "
                    + (wall - last) / 1000 + "s ago - leaving it");
            return;
        }
        L.w("taskbar recovery: ZUI built this taskbar on the tablet and moved it to display "
                + now + " (" + SystemClock.elapsedRealtime() / 1000 + "s after boot) - "
                + "restarting the launcher so it is built for the monitor");
        // Written synchronously: the process is about to go.
        prefs.edit().putLong(KEY_AT, wall).commit();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 1500L);
    }
}
