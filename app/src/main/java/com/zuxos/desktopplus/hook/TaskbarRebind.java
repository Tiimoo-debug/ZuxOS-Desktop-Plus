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
        hideOnAdd();
        keepStartShowing();
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
                            Object view = param.thisObject;
                            if (view == null || param.args.length == 0
                                    || !(param.args[0] instanceof Integer)
                                    || (Integer) param.args[0] == android.view.View.VISIBLE
                                    || !TASKBAR_VIEW.equals(view.getClass().getName())
                                    || !Cfg.taskbarRunningOnly() || !Cfg.startButtonLeft()) {
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
