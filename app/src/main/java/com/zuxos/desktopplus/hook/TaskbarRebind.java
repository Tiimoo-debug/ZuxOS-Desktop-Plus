package com.zuxos.desktopplus.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Catches ZUI rebuilding its taskbar row, so the row can be tidied before it is ever drawn.
 *
 * <p>Every launch makes ZUI's {@code RecentUsedModel} call {@code bindRecentUsedApps}, which ends
 * in {@code TaskbarView.updateItems}: the whole row is rebuilt with the hotseat and the
 * recommended apps in it. Hooked there, on the method {@code TaskbarView} itself declares - the
 * last build hooked an inherited {@code setVisibility} on the wrong view, which Xposed reports as
 * {@code x0} and never calls.
 *
 * <p>ZUI's model is left alone. Stopping the bind would also stop the bar learning about anything
 * that changed, so the rebuild goes ahead and {@link TaskbarRunning#rebound} hides what is not
 * open immediately afterwards, in the same frame.
 */
final class TaskbarRebind {

    private static final String TASKBAR_VIEW = "com.android.launcher3.taskbar.TaskbarView";

    /** How long a launch counts as an app that is open, before the task list says so itself. */
    private static final long LAUNCH_MS = 5000L;

    private static boolean sInstalled;
    private static volatile String sLaunched;
    private static volatile long sLaunchedAt;

    private TaskbarRebind() {
    }

    /** Told by {@link LaunchDisplay} which app is coming up, from the launch's own arguments. */
    static void launching(Object[] args) {
        String pkg = packageIn(args);
        if (pkg != null) {
            sLaunched = pkg;
            sLaunchedAt = SystemClock.uptimeMillis();
        }
    }

    /** The app launched in the last few seconds, or null. */
    static String justLaunched() {
        String pkg = sLaunched;
        return pkg != null && SystemClock.uptimeMillis() - sLaunchedAt < LAUNCH_MS ? pkg : null;
    }

    private static String packageIn(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof ComponentName) {
                return ((ComponentName) arg).getPackageName();
            }
            if (arg instanceof Intent) {
                Intent intent = (Intent) arg;
                if (intent.getComponent() != null) {
                    return intent.getComponent().getPackageName();
                }
                if (intent.getPackage() != null) {
                    return intent.getPackage();
                }
            }
        }
        // LauncherApps.startShortcut names the package as its first argument.
        return args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
    }

    static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        Class<?> cls = Reflect.findClass(TASKBAR_VIEW, loader);
        if (cls == null) {
            L.d("taskbar rebind: no " + TASKBAR_VIEW + " on this build");
            return;
        }
        XC_MethodHook after = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof ViewGroup) {
                    TaskbarRunning.rebound((ViewGroup) param.thisObject);
                }
            }
        };
        int hooked = 0;
        // updateHotseatItems too: it is what updateItems calls on this build, and a firmware that
        // calls it directly would otherwise slip past.
        for (String name : new String[]{"updateItems", "updateHotseatItems"}) {
            try {
                hooked += XposedBridge.hookAllMethods(cls, name, after).size();
            } catch (Throwable t) {
                L.d("taskbar rebind: could not hook " + name + " (" + t + ")");
            }
        }
        L.i("taskbar rebind: watching the launcher rebuild its row x" + hooked);
    }
}
