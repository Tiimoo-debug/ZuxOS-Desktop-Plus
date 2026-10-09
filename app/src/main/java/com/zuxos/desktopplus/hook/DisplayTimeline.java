package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Display;

import com.zuxos.desktopplus.core.L;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * What the screens did and when, and when our pieces followed, for the probe.
 *
 * <p>Unplugging and replugging the monitor (roadmap #1) and a start animation as it connects (#8)
 * both depend on the order of events: the display appearing, changing state and mode, the
 * launcher's bars coming up, the desktop attaching. The system's own display events are heard,
 * not polled, and only a change in what is recorded is kept - a few dozen lines at most.
 */
public final class DisplayTimeline {

    private static final int MAX_LINES = 40;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    /** What each display looked like when last written down, so repeats are not. */
    private static final SparseArray<String> LAST = new SparseArray<>();
    private static boolean sStarted;
    private static DisplayManager sDisplays;

    private DisplayTimeline() {
    }

    /** Starts listening, once per process. Main thread. */
    public static void start(Context ctx) {
        if (sStarted || ctx == null) {
            return;
        }
        sStarted = true;
        try {
            sDisplays = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            for (Display d : sDisplays.getDisplays()) {
                record(d.getDisplayId(), "present");
            }
            sDisplays.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    record(displayId, "added");
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    LAST.remove(displayId);
                    add("display " + displayId + " removed");
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    record(displayId, "changed");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            L.d("display timeline: not listening (" + t + ")");
        }
    }

    /** Something of ours that followed a display event: a bar came up, the desktop attached. */
    public static void note(String what) {
        add(what);
    }

    private static void record(int displayId, String event) {
        Display d = sDisplays != null ? sDisplays.getDisplay(displayId) : null;
        if (d == null) {
            add("display " + displayId + " " + event + " (gone already)");
            return;
        }
        Display.Mode mode = d.getMode();
        String state = String.format(Locale.ROOT, "%s %dx%d@%.0f, state %d", d.getName(),
                mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate(),
                d.getState());
        if (state.equals(LAST.get(displayId))) {
            // Changed in something not written here.
            return;
        }
        LAST.put(displayId, state);
        add("display " + displayId + " " + event + ": " + state);
    }

    private static void add(String line) {
        synchronized (LINES) {
            LINES.addLast(String.format(Locale.ROOT, "%9.1f s  %s",
                    SystemClock.uptimeMillis() / 1000f, line));
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder("\ndisplay timeline (#1 replug, #8 start animation;"
                + " seconds since boot)\n");
        synchronized (LINES) {
            if (LINES.isEmpty()) {
                sb.append("  (nothing yet)\n");
            }
            for (String line : LINES) {
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
