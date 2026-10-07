package com.zuxos.desktopplus.hook;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUX Home's own app-drawer button - the pill over its dock - taken away while our start button
 * is on: the start button opens the same drawer, from the bar, on every screen.
 *
 * <p>Done where ZUI builds and updates its dock ({@code ZuiHotseat}), after each of its own
 * methods, so nothing of ours runs unless ZUI touches its dock itself.
 */
final class HotseatButton {

    private static final String HOTSEAT = "com.zui.launcher.uiextend.ZuiHotseat";

    private static boolean sInstalled;
    private static boolean sSaid;

    private HotseatButton() {
    }

    static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> hotseat = Class.forName(HOTSEAT, false, loader);
            int hooked = 0;
            for (Method m : hotseat.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, AFTER);
                    hooked++;
                } catch (Throwable ignored) {
                    // One method less; the layout pass alone is enough.
                }
            }
            L.i("zux home: watching its dock x" + hooked);
        } catch (Throwable t) {
            L.i("zux home: no dock of ZUI's on this build (" + t + ")");
        }
    }

    private static final XC_MethodHook AFTER = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.thisObject instanceof ViewGroup) {
                apply((ViewGroup) param.thisObject);
            }
        }
    };

    /** The dock's drawer button gone while our start button is on, back when it is off. */
    private static void apply(ViewGroup hotseat) {
        try {
            boolean ours = Cfg.enabled() && Cfg.startButtonLeft();
            for (int i = 0; i < hotseat.getChildCount(); i++) {
                View child = hotseat.getChildAt(i);
                if (!(child instanceof ImageButton)) {
                    continue;
                }
                int want = ours ? View.GONE : View.VISIBLE;
                if (child.getVisibility() != want
                        && (ours || child.getTag(TAG_HIDDEN) != null)) {
                    child.setVisibility(want);
                    child.setTag(TAG_HIDDEN, ours ? Boolean.TRUE : null);
                    if (ours && !sSaid) {
                        sSaid = true;
                        L.i("zux home: drawer button hidden - the start button opens the drawer");
                    }
                }
            }
        } catch (Throwable ignored) {
            // The dock as ZUI left it.
        }
    }

    /** Marks a button we hid, so turning the setting off shows only what we took away. */
    private static final int TAG_HIDDEN = 0x7A000401;
}
