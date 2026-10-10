package com.zuxos.desktopplus.hook.systemui;

import android.view.Display;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * No status bar on the monitor's desktop.
 *
 * <p>ZUI's SystemUI puts a status bar on the external screen whenever its desktop is on
 * ({@code ZuiDpModeManager}: when the desktop mode setting turns on, and when the screen is
 * connected). It only shows icons - it cannot be pulled down - and the taskbar's tray, its quick
 * panel and the notification pop-ups do its job, while it takes a strip off every app. So ZUI is
 * asked not to build it: its own {@code createStatusBar} does nothing while the setting is on.
 * ZUI's tear-down, its colour updates and everything else that looks for the bar already allow
 * for there being none - the same as before the screen was ever connected.
 *
 * <p>Read when ZUI builds the bar, so the setting takes effect the next time the monitor's
 * desktop starts. Without the method, nothing is hooked and ZUI's bar stays.
 */
public final class MonitorStatusBar {

    private static final String MANAGER = "com.android.systemui.dpmode.ZuiDpModeManager";

    private static boolean sSaid;

    private MonitorStatusBar() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> manager = Reflect.findClass(MANAGER, loader);
            Method create = manager == null ? null : createStatusBar(manager);
            if (create == null) {
                L.w("monitor status bar: ZUI's createStatusBar not found, its bar stays");
                return;
            }
            XposedBridge.hookMethod(create, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Cfg.hideMonitorStatusBar()) {
                            return;
                        }
                        param.setResult(null);
                        if (!sSaid) {
                            sSaid = true;
                            L.i("monitor status bar: not built, the setting is on");
                        }
                    } catch (Throwable t) {
                        L.d("monitor status bar: left to ZUI (" + t + ")");
                    }
                }
            });
            L.i("monitor status bar: watching ZUI's " + create.getName());
        } catch (Throwable t) {
            L.e("monitor status bar: could not install, ZUI's bar stays", t);
        }
    }

    /**
     * ZUI's {@code createStatusBar(Display)}: compiled to a static accessor taking the manager
     * and the display, whose name keeps "createStatusBar". By that name first, then by that
     * shape when it is the only one.
     */
    private static Method createStatusBar(Class<?> manager) {
        Method shaped = null;
        int shapes = 0;
        for (Method m : manager.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            boolean takesDisplay = types.length > 0 && types[types.length - 1] == Display.class
                    && m.getReturnType() == void.class;
            if (!takesDisplay) {
                continue;
            }
            if (m.getName().contains("createStatusBar")) {
                return m;
            }
            if (Modifier.isStatic(m.getModifiers()) && types.length == 2
                    && types[0] == manager) {
                shaped = m;
                shapes++;
            }
        }
        return shapes == 1 ? shaped : null;
    }
}
