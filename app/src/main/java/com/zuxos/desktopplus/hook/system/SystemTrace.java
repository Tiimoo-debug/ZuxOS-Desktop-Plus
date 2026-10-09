package com.zuxos.desktopplus.hook.system;

import com.zuxos.desktopplus.core.L;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system, read-only: what a window change asked of the window organizer looked like,
 * and who asked.
 *
 * <p>ZUI's own maximise in a window's menu is not ours to see from the launcher, and copying it
 * by guesswork broke its window menu. Every window change - ZUI's and ours alike - goes through
 * the window organizer as one transaction, which holds the windowing mode and bounds it sets
 * per window. The first few that set a windowing mode are written to the log with the caller's
 * uid, so one maximise from a window's menu says exactly what it does.
 *
 * <p>On the monitor ZUI's maximise sets bounds only, so the first few of those are written too,
 * on a count of their own - every drag of a window is one, and must not use up the lines for the
 * rest. A transition's type is written with it: ZUI's toggle of a window's size has its own.
 * Only the calls that come in from outside are watched: inside, the system hands the same
 * transaction on under its own uid. Nothing is changed; nothing runs at load time but the hook
 * itself.
 */
public final class SystemTrace {

    private static final String CONTROLLER = "com.android.server.wm.WindowOrganizerController";
    private static final String[] NAMES = {
            "applyTransaction", "applySyncTransaction", "startTransition",
            "startLegacyTransition"};
    private static final String TRANSACTION = "android.window.WindowContainerTransaction";
    private static final int MAX_LINES = 40;
    private static final int MAX_SIZE_LINES = 20;
    /** {@code WindowConfiguration.WINDOW_CONFIG_BOUNDS}: the change sets the window's bounds. */
    private static final int WINDOW_CONFIG_BOUNDS = 1;

    private static boolean sInstalled;
    private static int sLines;
    private static int sSizeLines;
    /** The transaction last written, so one handed from call to call is written once. */
    private static WeakReference<Object> sLast = new WeakReference<>(null);

    private SystemTrace() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        int hooked = 0;
        try {
            Class<?> controller = Class.forName(CONTROLLER, false, loader);
            for (Method m : controller.getDeclaredMethods()) {
                if (internal(m)) {
                    continue;
                }
                for (String name : NAMES) {
                    if (name.equals(m.getName())) {
                        XposedBridge.hookMethod(m, TRACE);
                        hooked++;
                    }
                }
            }
        } catch (Throwable t) {
            L.i("system trace: not available on this build (" + t + ")");
            return;
        }
        L.i("system trace: window changes watched x" + hooked);
    }

    /** The system's own step inside a call, which carries the change on under its own uid. */
    private static boolean internal(Method m) {
        for (Class<?> type : m.getParameterTypes()) {
            if (type.getName().endsWith(".ActionChain")) {
                return true;
            }
        }
        return false;
    }

    /** One transaction, as written to the log. */
    private static final class Line {
        final String text;
        /** It sets a windowing mode; otherwise it only sets bounds. */
        final boolean mode;

        Line(String text, boolean mode) {
            this.text = text;
            this.mode = mode;
        }
    }

    /**
     * Each change that sets a windowing mode or bounds: the window's and its activity's mode
     * (-1 where not set), the bounds asked for, and the set masks; then the hierarchy operations
     * (moves, reorders) as the system writes them. Null when nothing sets either.
     */
    private static Line describe(Object wct) throws Exception {
        java.util.Map<?, ?> changes = (java.util.Map<?, ?>) wct.getClass()
                .getMethod("getChanges").invoke(wct);
        StringBuilder sb = new StringBuilder();
        boolean modes = false;
        boolean sizes = false;
        for (Object change : changes.values()) {
            Class<?> c = change.getClass();
            int mode = (int) c.getMethod("getWindowingMode").invoke(change);
            int activity = (int) c.getMethod("getActivityWindowingMode").invoke(change);
            int windowMask = (int) c.getMethod("getWindowSetMask").invoke(change);
            int configMask = (int) c.getMethod("getConfigSetMask").invoke(change);
            int changeMask = (int) c.getMethod("getChangeMask").invoke(change);
            Object config = c.getMethod("getConfiguration").invoke(change);
            Object window = config == null ? null
                    : config.getClass().getField("windowConfiguration").get(config);
            Object bounds = window == null ? null
                    : window.getClass().getMethod("getBounds").invoke(window);
            modes |= mode >= 0 || activity >= 0;
            sizes |= (windowMask & WINDOW_CONFIG_BOUNDS) != 0;
            sb.append("{mode=").append(mode).append(" activityMode=").append(activity)
                    .append(" bounds=").append(bounds)
                    .append(" windowMask=0x").append(Integer.toHexString(windowMask))
                    .append(" configMask=0x").append(Integer.toHexString(configMask))
                    .append(" changeMask=0x").append(Integer.toHexString(changeMask))
                    .append("} ");
        }
        if (!modes && !sizes) {
            return null;
        }
        Object hops = wct.getClass().getMethod("getHierarchyOps").invoke(wct);
        String ops = String.valueOf(hops);
        if (ops.length() > 400) {
            ops = ops.substring(0, 400) + "...";
        }
        return new Line(sb.append("ops=").append(ops).toString(), modes);
    }

    private static final XC_MethodHook TRACE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sLines >= MAX_LINES && sSizeLines >= MAX_SIZE_LINES) {
                return;
            }
            try {
                Integer type = null;
                for (Object arg : param.args) {
                    if (arg instanceof Integer && type == null) {
                        // A transition's type, ahead of its transaction.
                        type = (Integer) arg;
                    }
                    if (arg == null || !TRANSACTION.equals(arg.getClass().getName())) {
                        continue;
                    }
                    if (sLast.get() == arg) {
                        return;
                    }
                    Line line = describe(arg);
                    if (line == null) {
                        // Neither a windowing mode nor a size: reorders and the like say nothing
                        // about a maximise.
                        return;
                    }
                    if (line.mode ? sLines >= MAX_LINES : sSizeLines >= MAX_SIZE_LINES) {
                        return;
                    }
                    if (line.mode) {
                        sLines++;
                    } else {
                        sSizeLines++;
                    }
                    sLast = new WeakReference<>(arg);
                    L.i("system trace: " + param.method.getName()
                            + (type != null ? " type " + type : "") + " from uid "
                            + android.os.Binder.getCallingUid()
                            + (line.mode ? "" : ", size only") + ": " + line.text);
                    return;
                }
            } catch (Throwable ignored) {
                // A line less in the log; the change itself goes on untouched.
            }
        }
    };
}
