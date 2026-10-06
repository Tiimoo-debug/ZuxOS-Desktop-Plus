package com.zuxos.desktopplus.hook;

import android.content.Intent;
import android.graphics.Rect;

import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: a launch the module marks for full screen gets full screen.
 *
 * <p>ZUI's desktop opens every new task on the monitor as a floating window, and Android
 * remembers each app's last window and hands it back - after, and over, anything the launch
 * itself asked for. ZUI's photo editor will not run in a window: it closed itself the moment it
 * opened, "not support split screen" (17:53 log: "getlauncherparams=...FilterShowActivity 5").
 *
 * <p>So for a launch carrying the mark, and only for one, the window the system worked out is
 * replaced by full screen, and nothing turns the task back into a window afterwards. Every other
 * launch, from anywhere, is left exactly as it was.
 */
final class SystemFullscreen {

    /** The mark on a launch that must be full screen. */
    static final String EXTRA = "com.zuxos.desktopplus.FULLSCREEN";

    private static final int FULLSCREEN = 1;

    private static boolean sInstalled;
    private static Class<?> sActivityRecord;
    private static Class<?> sTask;
    private static Field sRecordIntent;
    private static Field sTaskIntent;
    private static Field sMode;
    private static Field sBounds;
    private static int sErrors;

    private SystemFullscreen() {
    }

    static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        int hooked = 0;
        try {
            sActivityRecord = Class.forName("com.android.server.wm.ActivityRecord", false,
                    loader);
            sRecordIntent = field(sActivityRecord, "intent");
            Class<?> params = Class.forName(
                    "com.android.server.wm.LaunchParamsController$LaunchParams", false, loader);
            sMode = field(params, "mWindowingMode");
            sBounds = field(params, "mBounds");
            Class<?> controller = Class.forName(
                    "com.android.server.wm.LaunchParamsController", false, loader);
            for (Method m : controller.getDeclaredMethods()) {
                if (m.getName().equals("calculate")) {
                    XposedBridge.hookMethod(m, CALCULATE);
                    hooked++;
                }
            }
        } catch (Throwable t) {
            L.i("system fullscreen: launch params not reachable (" + t + ")");
        }
        try {
            sTask = Class.forName("com.android.server.wm.Task", false, loader);
            sTaskIntent = field(sTask, "intent");
            // Declared on the task or on one of the classes it extends, depending on the build.
            for (Class<?> c = sTask; c != null && c != Object.class; c = c.getSuperclass()) {
                Method m = setter(c);
                if (m != null) {
                    XposedBridge.hookMethod(m, SET_MODE);
                    hooked++;
                    break;
                }
            }
        } catch (Throwable t) {
            L.i("system fullscreen: task mode not reachable (" + t + ")");
        }
        L.i("system fullscreen: watching marked launches x" + hooked);
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // Further up.
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    private static Method setter(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("setWindowingMode") && p.length == 1 && p[0] == int.class) {
                return m;
            }
        }
        return null;
    }

    /** The launch's own window, as worked out, swapped for full screen when it is marked. */
    private static final XC_MethodHook CALCULATE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (sErrors > 20) {
                return;
            }
            try {
                Object record = null;
                Object result = null;
                for (Object a : param.args) {
                    if (record == null && sActivityRecord.isInstance(a)) {
                        // The first record is the one being launched; the second, its source.
                        record = a;
                    } else if (a != null && a.getClass() == sMode.getDeclaringClass()) {
                        result = a;
                    }
                }
                if (record == null || result == null
                        || !marked(sRecordIntent.get(record))) {
                    return;
                }
                if (sMode.getInt(result) != FULLSCREEN) {
                    L.i("system fullscreen: full screen instead of mode " + sMode.getInt(result)
                            + " for " + ((Intent) sRecordIntent.get(record)).getComponent());
                }
                sMode.setInt(result, FULLSCREEN);
                Object bounds = sBounds.get(result);
                if (bounds instanceof Rect) {
                    ((Rect) bounds).setEmpty();
                }
            } catch (Throwable t) {
                sErrors++;
            }
        }
    };

    /** And no turning a marked task back into a window as it settles on the monitor. */
    private static final XC_MethodHook SET_MODE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sErrors > 20 || !sTask.isInstance(param.thisObject)) {
                return;
            }
            Object mode = param.args[0];
            // Undefined is no escape either: it would take the monitor's own mode, a window.
            if (!(mode instanceof Integer) || (Integer) mode == FULLSCREEN) {
                return;
            }
            try {
                if (marked(sTaskIntent.get(param.thisObject))) {
                    param.args[0] = FULLSCREEN;
                }
            } catch (Throwable t) {
                sErrors++;
            }
        }
    };

    private static boolean marked(Object intent) {
        try {
            return intent instanceof Intent && ((Intent) intent).hasExtra(EXTRA);
        } catch (Throwable t) {
            // Extras that will not unparcel here are not ours.
            return false;
        }
    }
}
