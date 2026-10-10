package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.view.WindowInsets;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The buttons on a maximised window's title bar, on a monitor without a status bar.
 *
 * <p>ZUI hides a full-screen window's title bar while it counts the window as immersive
 * ({@code WindowDecoration.getCurrentTaskInfoImmersived}): when its swipe-from-top flag is set
 * for the task, or when the status bar on the window's screen is not showing. It reads the
 * second from the status bar's insets - and with the monitor's status bar not built
 * ({@link MonitorStatusBar}) there are none, so every maximised window there counted as immersive
 * and lost its buttons.
 *
 * <p>So on those screens the answer is what the status bar would have said: immersive when the
 * app itself asks for the status bar to be hidden - ZUI's own test, the status bar missing from
 * the task's requested visible types - or when ZUI's swipe flag is set. A video played full screen
 * still loses its buttons; everything else keeps them. ZUI asks again whenever the task changes,
 * so nothing is watched.
 */
public final class WindowCaptions {

    private static final String DECORATION = "com.android.wm.shell.windowdecor.WindowDecoration";
    private static final String PC_MODE = "com.android.wm.shell.ov.utils.OvcPcModeUtils";

    private static Class<?> sPcMode;
    private static boolean sSaid;

    private WindowCaptions() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> decoration = Reflect.findClass(DECORATION, loader);
            Method immersive = null;
            if (decoration != null) {
                for (Method m : decoration.getDeclaredMethods()) {
                    if (m.getName().equals("getCurrentTaskInfoImmersived")
                            && m.getParameterCount() == 1) {
                        immersive = m;
                    }
                }
            }
            if (immersive == null) {
                L.w("window captions: ZUI's immersive test not found, left as it is");
                return;
            }
            sPcMode = Reflect.findClass(PC_MODE, loader);
            XposedBridge.hookMethod(immersive, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        answer(param);
                    } catch (Throwable t) {
                        L.d("window captions: left to ZUI (" + t + ")");
                    }
                }
            });
            L.i("window captions: watching ZUI's immersive test");
        } catch (Throwable t) {
            L.e("window captions: could not install", t);
        }
    }

    private static void answer(XC_MethodHook.MethodHookParam param) {
        if (!Boolean.TRUE.equals(param.getResult())
                || !(param.args[0] instanceof ActivityManager.RunningTaskInfo)) {
            return;
        }
        ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) param.args[0];
        Object display = Reflect.field(task, "displayId");
        if (!(display instanceof Integer) || !MonitorStatusBar.hiddenOn((Integer) display)) {
            return;
        }
        boolean immersive = swipedFromTop(task.taskId) || appHidesStatusBar(task);
        param.setResult(immersive);
        if (!immersive && !sSaid) {
            sSaid = true;
            L.i("window captions: buttons kept on display " + display
                    + " - no status bar there, and the app did not ask to hide it");
        }
    }

    /** The app's own request: the status bar left out of the types it wants visible. */
    private static boolean appHidesStatusBar(ActivityManager.RunningTaskInfo task) {
        Object types = Reflect.field(task, "requestedVisibleTypes");
        return types instanceof Integer
                && ((Integer) types & WindowInsets.Type.statusBars()) == 0;
    }

    /** ZUI's swipe-from-top flag for the task, as its own test reads it. */
    private static boolean swipedFromTop(int taskId) {
        if (sPcMode == null) {
            return false;
        }
        Object utils = null;
        try {
            utils = sPcMode.getMethod("getInstance").invoke(null);
        } catch (Throwable ignored) {
            // No flag to read: treated as not set.
        }
        Object flags = Reflect.field(utils, "mImmersivedSwipFromUpTaskInfo");
        return flags instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) flags).get(taskId));
    }
}
