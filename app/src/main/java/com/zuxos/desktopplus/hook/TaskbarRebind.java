package com.zuxos.desktopplus.hook;

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
                        TaskbarStart.relayout((ViewGroup) param.thisObject);
                    }
                }
            }).size();
            TaskbarStart.sLayoutHooked = laid > 0;
            L.i("taskbar rebind: placing the drawer button after the row's layout x" + laid);
        } catch (Throwable t) {
            L.d("taskbar rebind: could not hook onLayout (" + t + ")");
        }
    }
}
