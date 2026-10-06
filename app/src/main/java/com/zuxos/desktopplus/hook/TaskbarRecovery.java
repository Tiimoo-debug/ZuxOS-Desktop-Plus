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
 * <p>The log of a bad boot (2026-10-06 14:20): ZUI's bar was not on any screen when first
 * seen, then on the monitor with an empty row - its start button left out - and the probe
 * written at 14:21:08 shows that row. Apps opened from it went to the tablet and its drawer
 * could come up empty. Force stopping the launcher fixed all of it, because the launcher then
 * built the bar on the monitor properly.
 *
 * <p>So this does what the force stop did, by itself, when a bar on the monitor shows any of the
 * signs: built on the tablet and moved, made for a different screen than its window, or still
 * without its start button eight seconds in. The launcher process restarts and the system brings
 * it back. At most once every ten minutes, so it can never loop.
 */
final class TaskbarRecovery {

    private static final String PREFS = "zux_taskbar_recovery";
    private static final String KEY_AT = "restarted_at";
    private static final long MIN_GAP_MS = 10 * 60 * 1000L;

    /** Where each bar was first seen. */
    private static final Map<View, Integer> FIRST = new WeakHashMap<>();
    private static final Map<View, Long> SEEN_AT = new WeakHashMap<>();
    /** How long a bar on the monitor has to put its start button in before it counts as missing. */
    private static final long GRACE_MS = 8000L;
    private static boolean sPending;

    private static int contextDisplay(View view) {
        try {
            android.view.Display d = view.getContext().getDisplay();
            return d != null ? d.getDisplayId() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Whether ZUI's own icon row holds its all-apps (start) button. */
    private static boolean hasStartButton(ViewGroup dragLayer) {
        for (View row : com.zuxos.desktopplus.core.Reflect.findByIdNames(dragLayer,
                "taskbar_view")) {
            if (!(row instanceof ViewGroup)) {
                continue;
            }
            ViewGroup icons = (ViewGroup) row;
            for (int i = 0; i < icons.getChildCount(); i++) {
                if (icons.getChildAt(i).getClass().getName().contains("AllAppsButton")) {
                    return true;
                }
            }
            return false;
        }
        // No row found to judge by: not evidence of anything.
        return true;
    }

    private TaskbarRecovery() {
    }

    static void check(ViewGroup dragLayer) {
        if (dragLayer.getDisplay() == null || sPending) {
            return;
        }
        int now = dragLayer.getDisplay().getDisplayId();
        Integer first = FIRST.get(dragLayer);
        if (first == null) {
            FIRST.put(dragLayer, now);
            SEEN_AT.put(dragLayer, SystemClock.uptimeMillis());
            return;
        }
        if (now == 0) {
            return;
        }
        String why = null;
        if (first == 0) {
            why = "it was built on the tablet and moved to display " + now;
        } else {
            int context = contextDisplay(dragLayer);
            Long seen = SEEN_AT.get(dragLayer);
            if (context >= 0 && context != now) {
                why = "its window is on display " + now + " but it was made for display "
                        + context;
            } else if (seen != null && SystemClock.uptimeMillis() - seen > GRACE_MS
                    && !hasStartButton(dragLayer)) {
                // What the probe of the bad boot showed: ZUI's row on the monitor with nothing
                // in it, not even its all-apps button.
                why = "ZUI built it on display " + now + " without its start button";
            }
        }
        if (why == null) {
            return;
        }
        sPending = true;
        Context ctx = dragLayer.getContext().getApplicationContext() != null
                ? dragLayer.getContext().getApplicationContext() : dragLayer.getContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long wall = System.currentTimeMillis();
        long last = prefs.getLong(KEY_AT, 0L);
        if (wall - last < MIN_GAP_MS) {
            L.w("taskbar recovery: " + why + ", but the launcher was already restarted for it "
                    + (wall - last) / 1000 + "s ago - leaving it");
            return;
        }
        L.w("taskbar recovery: " + why + " (" + SystemClock.elapsedRealtime() / 1000
                + "s after boot) - restarting the launcher so it builds the bar for the monitor");
        // Written synchronously: the process is about to go.
        prefs.edit().putLong(KEY_AT, wall).commit();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 1500L);
    }
}
