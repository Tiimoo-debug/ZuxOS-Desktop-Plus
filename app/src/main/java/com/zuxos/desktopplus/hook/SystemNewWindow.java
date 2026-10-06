package com.zuxos.desktopplus.hook;

import android.content.Intent;

import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: "New window" opens a new window for every app.
 *
 * <p>An app whose main screen is single-task (Termux, most terminals and players) is always
 * brought back to its one task - Android looks for that task before anything else and ignores
 * the launch's request for another. So for a launch the taskbar marked as a new window, and only
 * for one, that search comes back empty and the system makes a new task as it would for any app.
 *
 * <p>Nothing else is touched: every other launch, from anywhere, finds its task as before.
 */
final class SystemNewWindow {

    /** The mark on the taskbar's "New window" launch. */
    static final String EXTRA = "com.zuxos.desktopplus.NEW_WINDOW";
    private static final String PROPERTY = "sys.zuxos.newwindow";

    private static boolean sInstalled;
    private static Field sIntent;
    private static int sErrors;

    private SystemNewWindow() {
    }

    static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> starter = Class.forName("com.android.server.wm.ActivityStarter", false,
                    loader);
            sIntent = starter.getDeclaredField("mIntent");
            sIntent.setAccessible(true);
            int hooked = 0;
            for (Method m : starter.getDeclaredMethods()) {
                if (m.getName().equals("getReusableTask")) {
                    XposedBridge.hookMethod(m, REUSE);
                    hooked++;
                }
            }
            L.i("system new window: watching task reuse x" + hooked);
            if (hooked > 0) {
                setProperty("on");
            }
        } catch (Throwable t) {
            L.i("system new window: not available on this build (" + t + ")");
        }
    }

    private static final XC_MethodHook REUSE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.getResult() == null || sErrors > 20) {
                return;
            }
            try {
                Object intent = sIntent.get(param.thisObject);
                if (intent instanceof Intent
                        && ((Intent) intent).getBooleanExtra(EXTRA, false)) {
                    param.setResult(null);
                    L.i("system new window: a new task for "
                            + ((Intent) intent).getComponent());
                }
            } catch (Throwable t) {
                // An intent whose extras will not unparcel here is not one of ours.
                sErrors++;
            }
        }
    };

    /** Whether the system's part is running - read from the launcher. */
    static boolean active() {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, PROPERTY);
            return "on".equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setProperty(String value) {
        try {
            Class.forName("android.os.SystemProperties")
                    .getMethod("set", String.class, String.class).invoke(null, PROPERTY, value);
        } catch (Throwable ignored) {
            // Only for the launcher's message.
        }
    }
}
