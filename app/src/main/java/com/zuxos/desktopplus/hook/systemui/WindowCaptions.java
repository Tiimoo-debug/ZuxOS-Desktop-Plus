package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.view.WindowInsets;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The buttons on a maximised window's title bar, on a monitor without a status bar.
 *
 * <p>ZUI's window decorations show a full-screen window's title bar only while the status bar on
 * its screen is showing: {@code relayout} hands {@code mIsStatusBarVisible} to
 * {@code updateRelayoutParams}, which makes it the title bar's visibility, and ZUI's immersive
 * test ({@code getCurrentTaskInfoImmersived}) reads the same field. ZUI sets it from the status
 * bar's insets on that screen - and with the monitor's status bar not built
 * ({@link MonitorStatusBar}) there are none, so every maximised window there lost its buttons.
 *
 * <p>So on those screens the field gets, as each relayout reads it, what the status bar would have
 * said: showing, unless the app asks for the status bar to be hidden - ZUI's own test, the status
 * bar missing from the task's requested visible types. A video played full screen still loses
 * its buttons; everything else keeps them. Relayouts come with task and inset changes, so nothing
 * is watched.
 */
public final class WindowCaptions {

    private static final String BASE = "com.android.wm.shell.windowdecor.WindowDecoration";
    private static final String[] DECORATIONS = {
            "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration",
            "com.android.wm.shell.windowdecor.CaptionWindowDecoration",
    };

    private static Field sStatusBarVisible;
    private static boolean sSaid;

    private WindowCaptions() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> base = Reflect.findClass(BASE, loader);
            if (base == null) {
                L.w("window captions: ZUI's window decoration not found, left as it is");
                return;
            }
            sStatusBarVisible = base.getDeclaredField("mIsStatusBarVisible");
            sStatusBarVisible.setAccessible(true);
            XC_MethodHook before = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        answer(param);
                    } catch (Throwable t) {
                        L.d("window captions: left to ZUI (" + t + ")");
                    }
                }
            };
            int hooked = 0;
            for (String name : DECORATIONS) {
                Class<?> decoration = Reflect.findClass(name, loader);
                if (decoration == null) {
                    continue;
                }
                for (Method m : decoration.getDeclaredMethods()) {
                    // The relayout that reads the field: the one taking the transactions too.
                    Class<?>[] types = m.getParameterTypes();
                    if (m.getName().equals("relayout") && types.length == 7
                            && types[0] == ActivityManager.RunningTaskInfo.class) {
                        XposedBridge.hookMethod(m, before);
                        hooked++;
                    }
                }
            }
            if (hooked == 0) {
                L.w("window captions: ZUI's relayout not found, left as it is");
                return;
            }
            L.i("window captions: watching ZUI's relayout x" + hooked);
        } catch (Throwable t) {
            L.e("window captions: could not install", t);
        }
    }

    private static void answer(XC_MethodHook.MethodHookParam param) throws IllegalAccessException {
        if (!(param.args[0] instanceof ActivityManager.RunningTaskInfo)) {
            return;
        }
        ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) param.args[0];
        Object display = Reflect.field(task, "displayId");
        if (!(display instanceof Integer) || !MonitorStatusBar.hiddenOn((Integer) display)) {
            return;
        }
        boolean showing = !appHidesStatusBar(task);
        if (sStatusBarVisible.getBoolean(param.thisObject) == showing) {
            return;
        }
        sStatusBarVisible.setBoolean(param.thisObject, showing);
        if (showing && !sSaid) {
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
}
