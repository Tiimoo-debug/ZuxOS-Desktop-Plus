package com.zuxos.desktopplus.hook;

import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
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
 * <p>The rebuild goes ahead - stopping it would also stop the bar learning that anything changed -
 * but with "Only open apps" on, ZUI is handed no apps to build it with: our own row draws the
 * open ones, and nothing of ZUI's has to be hidden afterwards.
 */
final class TaskbarRebind {

    private static final String TASKBAR_VIEW = "com.android.launcher3.taskbar.TaskbarView";

    private static boolean sInstalled;

    private TaskbarRebind() {
    }

    private static int sSwallowed;

    /**
     * ZUI's own taskbar crash, caught before it takes the launcher down.
     *
     * <p>{@code RecentUsedModel} rebinds the row with an icon view that is still attached to it,
     * {@code addView} throws {@code IllegalStateException: The specified child already has a
     * parent}, and the launcher restarts - twice in the last log, with none of our switches
     * involved any more. The rebuild it interrupted is only half done, but the next bind redoes
     * it from scratch, so losing one is far cheaper than losing the launcher. Only that exact
     * failure is caught; anything else still throws.
     */
    private static void swallowDoubleAdd(XC_MethodHook.MethodHookParam param) {
        Throwable thrown = param.getThrowable();
        if (!(thrown instanceof IllegalStateException) || thrown.getMessage() == null
                || !thrown.getMessage().contains("already has a parent")) {
            return;
        }
        param.setResult(null);
        sSwallowed++;
        if (sSwallowed == 1 || sSwallowed % 20 == 0) {
            L.w("taskbar rebind: swallowed ZUI's own 'child already has a parent' in "
                    + param.method.getName() + " (" + sSwallowed + " so far) - the launcher "
                    + "would have restarted");
        }
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
        XC_MethodHook rebuild = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                emptyOfApps(param);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                swallowDoubleAdd(param);
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
                hooked += XposedBridge.hookAllMethods(cls, name, rebuild).size();
            } catch (Throwable t) {
                L.d("taskbar rebind: could not hook " + name + " (" + t + ")");
            }
        }
        sAppsAtSource = hooked > 0;
        L.i("taskbar rebind: ZUI's own apps kept off the bar where it builds them x" + hooked);
        blockRecents(loader);
        listenToTasks(loader);
    }

    /**
     * Whether ZUI is kept from building app icons at all. When it is, nothing of ZUI's has to
     * be hidden afterwards; only when these hooks could not go in does the bar fall back to
     * hiding ZUI's icons once they are there.
     */
    static volatile boolean sAppsAtSource;

    private static boolean sSaidEmpty;

    /**
     * ZUI's row, built without apps: with "Only open apps" on, every app on the bar is ours.
     *
     * <p>The row's items arrive as an array of item infos, and its recents as a list; both are
     * handed over empty, of the same type. ZUI then builds no icon to hide - the old way hid them
     * after they were added, through hooks on every view in the launcher, and still had them
     * flash. The bar's own drawer button is not an item and is built as always.
     */
    private static void emptyOfApps(XC_MethodHook.MethodHookParam param) {
        if (!Cfg.taskbarRunningOnly() || !Cfg.hideRecommendedFlash()) {
            return;
        }
        for (int i = 0; i < param.args.length; i++) {
            Object arg = param.args[i];
            if (arg instanceof Object[]) {
                param.args[i] = java.lang.reflect.Array.newInstance(
                        arg.getClass().getComponentType(), 0);
            } else if (arg instanceof java.util.List) {
                param.args[i] = new java.util.ArrayList<>();
            }
        }
        if (!sSaidEmpty) {
            sSaidEmpty = true;
            L.i("taskbar rebind: ZUI builds its row without apps - ours shows the open ones");
        }
    }

    private static final String TASK_LISTENERS =
            "com.android.systemui.shared.system.TaskStackChangeListeners";
    private static final String TASK_LISTENER =
            "com.android.systemui.shared.system.TaskStackChangeListener";

    /** Task events that mean the set of open apps may have changed. */
    private static final java.util.Set<String> TASK_EVENTS = new java.util.HashSet<>(
            java.util.Arrays.asList("onTaskStackChanged", "onTaskCreated", "onTaskRemoved",
                    "onTaskMovedToFront", "onTaskDisplayChanged", "onActivityRestartAttempt",
                    "onTaskAppeared", "onTaskVanished"));

    /** Held so the listener is not collected; the launcher keeps it only weakly on some builds. */
    private static Object sTaskListener;

    /** Whether the system's task events reach the bar, so it need not poll for them. */
    static boolean hearsTasks() {
        return sTaskListener != null;
    }

    /**
     * Hears straight from the system when a task opens, closes or comes to the front.
     *
     * <p>The launcher's own shared library already listens to the system's task stack for
     * Overview; our listener joins it, so an app opened on the desktop is on the bar a moment
     * later instead of at the next three-second read. The listener is an interface, answered by a
     * proxy: every method it declares has a default, and only the ones that matter do anything.
     */
    private static void listenToTasks(ClassLoader loader) {
        try {
            Class<?> listeners = Reflect.findClass(TASK_LISTENERS, loader);
            Class<?> listener = Reflect.findClass(TASK_LISTENER, loader);
            if (listeners == null || listener == null || !listener.isInterface()) {
                L.i("taskbar rebind: no task stack listener on this build - open apps are read "
                        + "every few seconds and whenever the launcher rebinds");
                return;
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader,
                    new Class<?>[]{listener}, (self, method, args) -> {
                        String name = method.getName();
                        if (TASK_EVENTS.contains(name)) {
                            TaskbarRunning.soon();
                        }
                        switch (name) {
                            case "equals":
                                return self == (args != null && args.length > 0 ? args[0] : null);
                            case "hashCode":
                                return System.identityHashCode(self);
                            case "toString":
                                return "ZuxDesktopPlus task listener";
                            default:
                                break;
                        }
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) {
                            return false;
                        }
                        if (type == int.class || type == long.class || type == short.class
                                || type == byte.class || type == float.class
                                || type == double.class || type == char.class) {
                            return 0;
                        }
                        return null;
                    });
            Object instance = listeners.getMethod("getInstance").invoke(null);
            listeners.getMethod("registerTaskStackListener", listener).invoke(instance, proxy);
            sTaskListener = proxy;
            L.i("taskbar rebind: listening to the system's task stack - open apps show at once");
        } catch (Throwable t) {
            L.i("taskbar rebind: could not listen to the task stack (" + t + ") - open apps are "
                    + "read every few seconds and whenever the launcher rebinds");
        }
    }

    private static final String MODEL_CALLBACKS =
            "com.android.launcher3.taskbar.TaskbarModelCallbacks";
    private static boolean sSaidBlocked;

    /**
     * ZUI's recent and recommended apps, refused at the door.
     *
     * <p>{@code TaskbarModelCallbacks.bindRecentUsedApps} is where ZUI's {@code RecentUsedModel}
     * hands the bar its recents on every launch and every close - the stack traces name it. With
     * "Only open apps" on, our row shows what is open, so this call is skipped outright: the
     * icons are never added, so there is nothing to flash. It is also the call ZUI's own
     * "child already has a parent" crash comes through.
     */
    private static void blockRecents(ClassLoader loader) {
        Class<?> callbacks = Reflect.findClass(MODEL_CALLBACKS, loader);
        if (callbacks == null) {
            L.d("taskbar rebind: no " + MODEL_CALLBACKS + " on this build");
            return;
        }
        try {
            int hooked = XposedBridge.hookAllMethods(callbacks, "bindRecentUsedApps",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!Cfg.taskbarRunningOnly() || !Cfg.hideRecommendedFlash()) {
                                return;
                            }
                            param.setResult(null);
                            // This call is how the bar used to hear that an app opened or
                            // closed; skipping it must not also skip finding out.
                            TaskbarRunning.soon();
                            if (!sSaidBlocked) {
                                sSaidBlocked = true;
                                L.i("taskbar rebind: the launcher's recent and recommended apps "
                                        + "are kept off the bar");
                            }
                        }
                    }).size();
            L.i("taskbar rebind: holding back the launcher's recent apps x" + hooked);
        } catch (Throwable t) {
            L.d("taskbar rebind: could not hook bindRecentUsedApps (" + t + ")");
        }
    }
}
