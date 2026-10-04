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
 * <p>ZUI's model is left alone. Stopping the bind would also stop the bar learning about anything
 * that changed, so the rebuild goes ahead and {@link TaskbarRunning#rebound} hides its app icons
 * immediately afterwards, in the same frame - our own row draws the open apps.
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
        XC_MethodHook after = new XC_MethodHook() {
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
                hooked += XposedBridge.hookAllMethods(cls, name, after).size();
            } catch (Throwable t) {
                L.d("taskbar rebind: could not hook " + name + " (" + t + ")");
            }
        }
        L.i("taskbar rebind: watching the launcher rebuild its row x" + hooked);
        try {
            int laid = XposedBridge.hookAllMethods(cls, "onLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewGroup) {
                        // Hidden here too, in the pass that would draw them: anything of the
                        // launcher's that came back since it was added is gone before the frame.
                        TaskbarRunning.hideOnSight((ViewGroup) param.thisObject);
                        TaskbarStart.relayout((ViewGroup) param.thisObject);
                    }
                }
            }).size();
            TaskbarStart.sLayoutHooked = laid > 0;
            L.i("taskbar rebind: placing the drawer button after the row's layout x" + laid);
        } catch (Throwable t) {
            L.d("taskbar rebind: could not hook onLayout (" + t + ")");
        }
        blockRecents(loader);
        listenToTasks(loader);
        hideOnAdd();
        sTaskbarView = cls;
        TaskbarStart.guardIcon(loader);
        keepStartShowing();
        keepStartOpaque();
    }

    /** The launcher's row class, compared by identity: the hooks below run on every view. */
    private static Class<?> sTaskbarView;

    /** Whether the start button is to stay up while the launcher hides its row. */
    private static boolean keepsStart(Object view) {
        return view != null && view.getClass() == sTaskbarView
                && Cfg.taskbarRunningOnly() && Cfg.startButtonLeft();
    }

    private static boolean sSaidOpaque;

    /** The alpha each row was last asked for - the fade's direction is the drawer's state. */
    private static final java.util.Map<android.view.View, Float> LAST_ALPHA =
            new java.util.WeakHashMap<>();

    /**
     * The other half of hiding the row: ZUI fades it out before it marks it invisible.
     *
     * <p>The recording shows the start button dimming over a quarter of a second and staying
     * gone while the drawer is open, although the row was never invisible - its alpha was 0.
     * The fade is held at fully opaque for the row, under the same two settings as above.
     */
    private static void keepStartOpaque() {
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "setAlpha",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object view = param.thisObject;
                            if (view == null || view.getClass() != sTaskbarView
                                    || param.args.length != 1
                                    || !(param.args[0] instanceof Float)) {
                                return;
                            }
                            float asked = (Float) param.args[0];
                            // Which way the fade is going says which way the drawer is: out as
                            // it opens, back in as it closes. Read before it is held at 1.
                            Float last = LAST_ALPHA.put((android.view.View) view, asked);
                            float before = last != null ? last : 1f;
                            if (asked < before - 0.001f) {
                                TaskbarStart.drawerShowing((android.view.View) view, true);
                            } else if (asked > before + 0.001f) {
                                TaskbarStart.drawerShowing((android.view.View) view, false);
                            }
                            if (asked >= 1f || !keepsStart(view)) {
                                return;
                            }
                            param.args[0] = 1f;
                            if (!sSaidOpaque) {
                                sSaidOpaque = true;
                                L.i("taskbar start: kept the start button opaque while the "
                                        + "launcher faded its row");
                            }
                        }
                    });
        } catch (Throwable t) {
            L.d("taskbar rebind: could not watch the row's alpha (" + t + ")");
        }
    }

    private static boolean sSaidKept;

    /**
     * Keeps the start button on the bar while ZUI's app drawer is open.
     *
     * <p>ZUI hides its whole icon row when the drawer opens. With "Only open apps" on, that row
     * holds nothing but the start button - every app is in our row - so hiding it only took the
     * start button away, which a desktop never does. The request to hide it is turned into a
     * request to show it, for that one view and only with both settings on.
     */
    private static void keepStartShowing() {
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "setVisibility",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0
                                    || !(param.args[0] instanceof Integer)
                                    || (Integer) param.args[0] == android.view.View.VISIBLE
                                    || !keepsStart(param.thisObject)) {
                                return;
                            }
                            param.args[0] = android.view.View.VISIBLE;
                            if (!sSaidKept) {
                                sSaidKept = true;
                                L.i("taskbar start: kept the start button visible while the "
                                        + "launcher hid its row");
                            }
                        }
                    });
        } catch (Throwable t) {
            L.d("taskbar rebind: could not watch the row's visibility (" + t + ")");
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

    /**
     * Anything ZUI still adds to its row by another route is hidden as it is added.
     *
     * <p>{@code onViewAdded} runs inside {@code addView}, before the view has been laid out or
     * drawn, so an icon hidden here never reaches the screen. Hooked on {@code ViewGroup},
     * where the method is declared, and narrowed to the launcher's taskbar row at once.
     */
    private static void hideOnAdd() {
        try {
            int hooked = XposedBridge.hookAllMethods(ViewGroup.class, "onViewAdded",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object row = param.thisObject;
                            if (row == null || !TASKBAR_VIEW.equals(row.getClass().getName())
                                    || param.args.length == 0
                                    || !(param.args[0] instanceof android.view.View)) {
                                return;
                            }
                            TaskbarRunning.hideIfApp((android.view.View) param.args[0]);
                        }
                    }).size();
            L.i("taskbar rebind: hiding the launcher's icons as they are added x" + hooked);
        } catch (Throwable t) {
            L.d("taskbar rebind: could not hook onViewAdded (" + t + ")");
        }
    }
}
