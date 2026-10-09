package com.zuxos.desktopplus.hook.taskbar;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.Tasks;
import com.zuxos.desktopplus.hook.Windows;
import com.zuxos.desktopplus.hook.recents.TaskOverview;
import com.zuxos.desktopplus.logic.WindowMath;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maximize and Restore, for the taskbar's and the desktop's menus.
 *
 * <p>On the tablet's desktop mode a window goes full screen, app and all, as ZUI's own button
 * does there. On the monitor it stays a floating window and takes ZUI's own maximised bounds, read
 * from its window shell ({@code DesktopModeUtils} in SystemUI, see {@link WindowMath}): the screen
 * less its bars for an app that takes any size, and for one that does not, the largest window of
 * its own shape, centred - stretching it, as before, letterboxed it in a box of black. Once a
 * window is maximised by ZUI's own test, by this menu or by ZUI's button, the menu offers Restore,
 * which puts back the size it had.
 */
final class Maximize {

    static final String MAXIMIZE = "Maximize";
    static final String RESTORE = "Restore";

    private static final int WINDOWING_MODE_UNDEFINED = 0;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int RESIZE_MODE_SYSTEM = 0;
    private static final long CHECK_MS = 700L;
    /** ZUI's own size for a window it restores without having seen it before: 3/4 each way. */
    private static final float RESTORE_SCALE = 0.75f;

    /** Each window's size before this maximised it, by task id: what Restore puts back. */
    private static final Map<Integer, Rect> BEFORE = new HashMap<>();

    private Maximize() {
    }

    /**
     * The menu's word for {@link #toggle}: Restore for a floating window on the monitor that is
     * maximised already, Maximize for everything else. {@code tasks} are the display's, already
     * read for the menu, so this asks the system for nothing more.
     */
    static String label(Context ctx, List<ActivityManager.RunningTaskInfo> tasks, String pkg,
            int display, int taskId) {
        if (display == Display.DEFAULT_DISPLAY) {
            return MAXIMIZE;
        }
        ActivityManager.RunningTaskInfo task = find(tasks, pkg, taskId);
        if (task == null || windowingMode(task) != WINDOWING_MODE_FREEFORM) {
            return MAXIMIZE;
        }
        Rect area = area(ctx, display);
        Rect bounds = bounds(task);
        return area != null && bounds != null
                && WindowMath.isMaximized(array(bounds), array(area), resizable(task))
                ? RESTORE : MAXIMIZE;
    }

    /**
     * One window of the app - {@code taskId}, or its front one here when -1 - maximised, or
     * restored when it already is, and in front.
     */
    static void toggle(Context ctx, String pkg, int display, int taskId) {
        List<ActivityManager.RunningTaskInfo> tasks = TaskbarApps.tasksOn(ctx, display);
        ActivityManager.RunningTaskInfo task = find(tasks, pkg, taskId);
        if (task == null) {
            L.i("taskbar apps: " + pkg + " has no window on display " + display + " to maximise");
            return;
        }
        int id = task.taskId;
        int mode = windowingMode(task);
        if (mode != WINDOWING_MODE_FREEFORM) {
            // Full screen, a split, or unreadable: nothing to grow. Forcing an app into full
            // screen mode, as an earlier version did, letterboxed one that keeps its own shape.
            L.i("taskbar apps: " + pkg + " (task " + id + ", mode " + mode
                    + ") is not a floating window - brought to front only");
            TaskOverview.bringToFront(id, display);
            return;
        }
        if (display == Display.DEFAULT_DISPLAY && toFullScreen(task)) {
            // The tablet's desktop mode: full screen, window and app together - tried there on
            // 1.0.133 with Claude, Gallery, Lawnchair and Termux, each filling the screen as
            // ZUI's own maximise does.
            L.i("taskbar apps: maximising " + pkg + " (task " + id + ") to full screen");
        } else {
            // The monitor's desktop is another window system: switching a window there to full
            // screen from here went round ZUI's own handling of it - its window menu then
            // opened on the tablet, and apps that keep their shape sat in a box on black. Still
            // floating there, sized as ZUI's own button sizes it.
            resize(ctx, tasks, task, display);
        }
        TaskOverview.bringToFront(id, display);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            for (ActivityManager.RunningTaskInfo t : TaskbarApps.tasksOn(ctx, display)) {
                if (t.taskId == id) {
                    L.i("taskbar apps: " + pkg + " is now mode " + windowingMode(t) + " at "
                            + bounds(t));
                    return;
                }
            }
        }, CHECK_MS);
    }

    private static ActivityManager.RunningTaskInfo find(List<ActivityManager.RunningTaskInfo> tasks,
            String pkg, int taskId) {
        for (ActivityManager.RunningTaskInfo t : tasks) {
            if (taskId >= 0 ? t.taskId == taskId : pkg.equals(Tasks.packageOf(t))) {
                return t;
            }
        }
        return null;
    }

    /**
     * ZUI's toggle: a maximised window back to the size it had - or, one this never saw before,
     * to ZUI's own 3/4 of the screen - and any other to ZUI's maximised bounds.
     */
    private static void resize(Context ctx, List<ActivityManager.RunningTaskInfo> tasks,
            ActivityManager.RunningTaskInfo task, int display) {
        WindowMetrics metrics = metrics(ctx, display);
        Rect area = metrics == null ? null : area(metrics, display);
        Rect bounds = bounds(task);
        if (area == null || bounds == null) {
            L.i("taskbar apps: task " + task.taskId + " left as it is (screen " + area
                    + ", window " + bounds + ")");
            return;
        }
        int id = task.taskId;
        boolean resizable = resizable(task);
        Rect target;
        String what;
        synchronized (BEFORE) {
            // Windows that closed since: nothing to restore any more.
            Set<Integer> open = new HashSet<>();
            for (ActivityManager.RunningTaskInfo t : tasks) {
                open.add(t.taskId);
            }
            BEFORE.keySet().retainAll(open);
            if (WindowMath.isMaximized(array(bounds), array(area), resizable)) {
                Rect before = BEFORE.remove(id);
                Rect screen = metrics.getBounds();
                target = before != null ? before : rect(WindowMath.restoredDefault(
                        screen.width(), screen.height(), RESTORE_SCALE));
                what = before != null ? "restored" : "restored to ZUI's default size";
            } else {
                BEFORE.put(id, new Rect(bounds));
                target = rect(WindowMath.maximized(array(area), resizable, aspect(task),
                        portrait(task), caption(task)));
                what = resizable ? "maximised" : "maximised in its own shape";
            }
        }
        String how = resizeBySystem(id, target) ? "system resize"
                : resizeByTransaction(task, target) ? "window transaction" : "nothing";
        L.i("taskbar apps: task " + id + " " + what + " to " + target + " by " + how);
    }

    /**
     * The window and its app's activity full screen, with no size of their own, in front - one
     * transaction. The tablet's desktop mode only. False when refused.
     */
    private static boolean toFullScreen(ActivityManager.RunningTaskInfo task) {
        try {
            Object token = Reflect.field(task, "token");
            if (token == null) {
                return false;
            }
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("setWindowingMode", tokenClass, int.class)
                    .invoke(wct, token, WINDOWING_MODE_FULLSCREEN);
            try {
                wctClass.getMethod("setActivityWindowingMode", tokenClass, int.class)
                        .invoke(wct, token, WINDOWING_MODE_UNDEFINED);
            } catch (NoSuchMethodException ignored) {
                // An older build: the window alone, as before.
            }
            wctClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(wct, token, new Rect());
            wctClass.getMethod("reorder", tokenClass, boolean.class).invoke(wct, token, true);
            Class<?> organizer = Class.forName("android.window.WindowOrganizer");
            organizer.getMethod("applyTransaction", wctClass)
                    .invoke(organizer.getConstructor().newInstance(), wct);
            return true;
        } catch (Throwable t) {
            L.i("taskbar apps: full screen refused (" + cause(t) + ")");
            return false;
        }
    }

    /** {@code IActivityTaskManager.resizeTask}, in the system's own mode; false when refused. */
    private static boolean resizeBySystem(int taskId, Rect area) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            atm.getClass().getMethod("resizeTask", int.class, Rect.class, int.class)
                    .invoke(atm, taskId, area, RESIZE_MODE_SYSTEM);
            return true;
        } catch (Throwable t) {
            L.i("taskbar apps: system resize refused (" + cause(t) + ")");
            return false;
        }
    }

    /** The window organizer's bounds change, as before; false when refused. */
    private static boolean resizeByTransaction(ActivityManager.RunningTaskInfo task, Rect area) {
        try {
            Object token = Reflect.field(task, "token");
            if (token == null) {
                return false;
            }
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("setBounds", tokenClass, Rect.class).invoke(wct, token, area);
            wctClass.getMethod("reorder", tokenClass, boolean.class).invoke(wct, token, true);
            Class<?> organizer = Class.forName("android.window.WindowOrganizer");
            organizer.getMethod("applyTransaction", wctClass)
                    .invoke(organizer.getConstructor().newInstance(), wct);
            return true;
        } catch (Throwable t) {
            L.i("taskbar apps: window transaction refused (" + cause(t) + ")");
            return false;
        }
    }

    private static Throwable cause(Throwable t) {
        return t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                ? t.getCause() : t;
    }

    /** The task's windowing mode, from its configuration; -1 when it cannot be read. */
    private static int windowingMode(ActivityManager.RunningTaskInfo task) {
        Object window = windowConfiguration(task);
        Object mode = window == null ? null : Reflect.call(window, "getWindowingMode");
        return mode instanceof Integer ? (Integer) mode : -1;
    }

    /** The window's bounds on its display; null when they cannot be read. */
    static Rect bounds(ActivityManager.RunningTaskInfo task) {
        Object window = windowConfiguration(task);
        Object bounds = window == null ? null : Reflect.call(window, "getBounds");
        return bounds instanceof Rect ? (Rect) bounds : null;
    }

    /** Where the app itself is drawn: the window less its title bar; null when not known. */
    private static Rect appBounds(ActivityManager.RunningTaskInfo task) {
        Object window = windowConfiguration(task);
        Object bounds = window == null ? null : Reflect.call(window, "getAppBounds");
        return bounds instanceof Rect ? (Rect) bounds : null;
    }

    private static Object windowConfiguration(ActivityManager.RunningTaskInfo task) {
        Object config = Reflect.field(task, "configuration");
        return config == null ? null : Reflect.field(config, "windowConfiguration");
    }

    /** Whether the app takes any size; yes when unreadable, which is the old behaviour. */
    private static boolean resizable(ActivityManager.RunningTaskInfo task) {
        Object resizable = Reflect.field(task, "isResizeable");
        return !(resizable instanceof Boolean) || (Boolean) resizable;
    }

    /** ZUI's {@code calculateAspectRatio}: the app's own bounds, else its window's. */
    private static float aspect(ActivityManager.RunningTaskInfo task) {
        Object compat = Reflect.field(task, "appCompatTaskInfo");
        Object own = compat == null ? null : Reflect.field(compat, "topActivityAppBounds");
        Rect r = own instanceof Rect && !((Rect) own).isEmpty() ? (Rect) own : appBounds(task);
        if (r == null) {
            r = bounds(task);
        }
        return r == null ? 1f : WindowMath.aspect(r.width(), r.height());
    }

    /**
     * ZUI's test of an upright app: the orientation it asks for; else, when it is letterboxed,
     * whether its box is upright; else the shape it is drawn in.
     */
    private static boolean portrait(ActivityManager.RunningTaskInfo task) {
        ActivityInfo info = task.topActivityInfo;
        int requested = info != null ? info.screenOrientation
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (requested != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            return WindowMath.fixedPortrait(requested);
        }
        Object compat = Reflect.field(task, "appCompatTaskInfo");
        if (compat != null && Boolean.TRUE.equals(Reflect.call(compat,
                "isTopActivityLetterboxed"))) {
            return Boolean.TRUE.equals(Reflect.call(compat, "isTopActivityPillarboxShaped"));
        }
        Rect app = appBounds(task);
        if (app != null) {
            return app.height() > app.width();
        }
        Object config = Reflect.field(task, "configuration");
        Object orientation = config == null ? null : Reflect.field(config, "orientation");
        return orientation instanceof Integer && WindowMath.fixedPortrait((Integer) orientation);
    }

    /** How much of the window's height is its title bar, above the app. */
    private static int caption(ActivityManager.RunningTaskInfo task) {
        Rect app = appBounds(task);
        Rect bounds = bounds(task);
        return app == null || bounds == null ? 0 : Math.max(0, app.top - bounds.top);
    }

    private static WindowMetrics metrics(Context ctx, int display) {
        try {
            DisplayManager dm = ctx.getSystemService(DisplayManager.class);
            Display d = dm == null ? null : dm.getDisplay(display);
            return d == null ? null : ctx.createDisplayContext(d)
                    .getSystemService(WindowManager.class).getMaximumWindowMetrics();
        } catch (Throwable t) {
            L.d("taskbar apps: screen size unreadable (" + t + ")");
            return null;
        }
    }

    private static Rect area(Context ctx, int display) {
        WindowMetrics metrics = metrics(ctx, display);
        return metrics == null ? null : area(metrics, display);
    }

    /**
     * The most a maximised window covers - ZUI's stable bounds: below the status bar and the
     * camera cutout (the system moves a window that reaches higher down by as much, into the
     * taskbar), and above the bar.
     */
    private static Rect area(WindowMetrics metrics, int display) {
        try {
            Rect area = new Rect(metrics.getBounds());
            Insets top = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.displayCutout() | WindowInsets.Type.statusBars());
            Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars());
            int bottom = Math.max(bars.bottom, Windows.taskbarHeight(display));
            area.set(area.left + top.left, area.top + top.top, area.right - top.right,
                    area.bottom - bottom);
            return area.isEmpty() ? null : area;
        } catch (Throwable t) {
            L.d("taskbar apps: screen size unreadable (" + t + ")");
            return null;
        }
    }

    private static int[] array(Rect r) {
        return new int[]{r.left, r.top, r.right, r.bottom};
    }

    private static Rect rect(int[] r) {
        return new Rect(r[0], r[1], r[2], r[3]);
    }
}
