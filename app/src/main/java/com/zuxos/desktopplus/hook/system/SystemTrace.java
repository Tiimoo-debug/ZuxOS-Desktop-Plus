package com.zuxos.desktopplus.hook.system;

import com.zuxos.desktopplus.core.L;

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
 * uid, so one maximise from a window's menu says exactly what it does. Nothing is changed;
 * nothing runs at load time but the hook itself.
 */
public final class SystemTrace {

    private static final String CONTROLLER = "com.android.server.wm.WindowOrganizerController";
    private static final String[] NAMES = {
            "applyTransaction", "applySyncTransaction", "startTransition",
            "startLegacyTransition"};
    private static final int MAX_LINES = 40;

    private static boolean sInstalled;
    private static int sLines;

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

    /**
     * Each change that sets a windowing mode: the window's and its activity's mode (-1 where
     * not set), the bounds asked for, and the set masks; then the hierarchy operations (moves,
     * reorders) as the system writes them. Null when nothing sets a windowing mode.
     */
    private static String describe(Object wct) throws Exception {
        java.util.Map<?, ?> changes = (java.util.Map<?, ?>) wct.getClass()
                .getMethod("getChanges").invoke(wct);
        StringBuilder sb = new StringBuilder();
        boolean modes = false;
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
            sb.append("{mode=").append(mode).append(" activityMode=").append(activity)
                    .append(" bounds=").append(bounds)
                    .append(" windowMask=0x").append(Integer.toHexString(windowMask))
                    .append(" configMask=0x").append(Integer.toHexString(configMask))
                    .append(" changeMask=0x").append(Integer.toHexString(changeMask))
                    .append("} ");
        }
        if (!modes) {
            return null;
        }
        Object hops = wct.getClass().getMethod("getHierarchyOps").invoke(wct);
        String ops = String.valueOf(hops);
        if (ops.length() > 400) {
            ops = ops.substring(0, 400) + "...";
        }
        return sb.append("ops=").append(ops).toString();
    }

    private static final XC_MethodHook TRACE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sLines >= MAX_LINES) {
                return;
            }
            try {
                for (Object arg : param.args) {
                    if (arg == null || !"android.window.WindowContainerTransaction".equals(
                            arg.getClass().getName())) {
                        continue;
                    }
                    String text = describe(arg);
                    if (text == null) {
                        // Nothing that sets a windowing mode: drags, reorders and resizes say
                        // nothing about a maximise.
                        return;
                    }
                    sLines++;
                    L.i("system trace: " + param.method.getName() + " from uid "
                            + android.os.Binder.getCallingUid() + ": " + text);
                    return;
                }
            } catch (Throwable ignored) {
                // A line less in the log; the change itself goes on untouched.
            }
        }
    };
}
