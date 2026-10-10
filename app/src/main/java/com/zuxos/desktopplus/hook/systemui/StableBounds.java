package com.zuxos.desktopplus.hook.systemui;

import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.WindowInsets;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Where windows may go on a screen, as the bars on it really are.
 *
 * <p>SystemUI's window shell keeps windows inside a screen's stable bounds when they are dragged,
 * snapped, maximised and placed ({@code DisplayLayout}). It works those out in
 * {@code recalcInsets} - again on every change of the screen's insets - from what it assumes:
 * the navigation bar at the bottom of a landscape screen, the configured status bar height at the
 * top, and on the desktop the screen less ZUI's taskbar height at the bottom. The monitor's
 * taskbar moved to the top, or its status bar not built, makes those wrong: windows went up under
 * the bar, and stopped short of a bottom bar that was not there.
 *
 * <p>So, once ZUI has worked them out, the parts the bars decide are read from the screen's real
 * insets - the ones the taskbar and the status bar themselves report: the top reaches down past a
 * bar at the top, the status bar's height counts only where there is a status bar, and the bottom
 * keeps clear only of what is really there. A screen with its status bar and its taskbar at the
 * bottom - the tablet, and the monitor as ZUI made it - keeps ZUI's answer untouched.
 */
public final class StableBounds {

    private static final String LAYOUT = "com.android.wm.shell.common.DisplayLayout";

    private static final Set<String> SAID = new HashSet<>();

    private StableBounds() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> layout = Reflect.findClass(LAYOUT, loader);
            Method recalc = null;
            if (layout != null) {
                try {
                    recalc = layout.getDeclaredMethod("recalcInsets", Resources.class);
                } catch (NoSuchMethodException ignored) {
                    // Reported below.
                }
            }
            if (recalc == null) {
                L.w("stable bounds: ZUI's DisplayLayout.recalcInsets not found, left as it is");
                return;
            }
            XposedBridge.hookMethod(recalc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        correct(param.thisObject);
                    } catch (Throwable t) {
                        L.d("stable bounds: left as ZUI made them (" + t + ")");
                    }
                }
            });
            L.i("stable bounds: watching how SystemUI keeps windows clear of the bars");
        } catch (Throwable t) {
            L.e("stable bounds: could not install", t);
        }
    }

    private static void correct(Object layout) {
        Object state = Reflect.field(layout, "mInsetsState");
        Object stableField = Reflect.field(layout, "mStableInsets");
        if (state == null || !(stableField instanceof Rect)) {
            return;
        }
        Rect stable = (Rect) stableField;
        Object frame = Reflect.call(state, "getDisplayFrame");
        Insets nav = frame instanceof Rect ? navigationInsets(state, (Rect) frame) : Insets.NONE;
        boolean statusBar = hasSource(state, WindowInsets.Type.statusBars());
        if (nav.top <= 0 && statusBar) {
            // The bars where ZUI expects them: its answer holds.
            return;
        }
        Object cutoutField = Reflect.field(layout, "mCutout");
        DisplayCutout cutout = cutoutField instanceof DisplayCutout ? (DisplayCutout) cutoutField
                : null;
        int cutTop = cutout != null ? cutout.getSafeInsetTop() : 0;
        int cutBottom = cutout != null ? cutout.getSafeInsetBottom() : 0;
        Rect before = new Rect(stable);
        stable.top = Math.max(statusBar ? stable.top : cutTop, nav.top);
        if (nav.top > 0) {
            // The bar is at the top: nothing is at the bottom but what the screen reports.
            stable.bottom = cutBottom + nav.bottom;
            // ZUI's desktop bounds stop short of its own bottom bar unless the two heights
            // agree; with the bar at the top there is none.
            Object navFrame = Reflect.field(layout, "mNavBarFrameHeight");
            if (navFrame instanceof Integer) {
                setInt(layout, "mTaskbarFrameHeight", (Integer) navFrame);
            }
        }
        String what = before + " -> " + stable;
        if (!before.equals(stable) && SAID.add(what)) {
            L.i("stable bounds: insets " + what + " (taskbar " + nav + ", status bar "
                    + (statusBar ? "shown" : "none") + ")");
        }
    }

    /**
     * The navigation bars' insets - the taskbar's - as ZUI itself reads them: by the overload
     * taking a frame, the types and whether to ignore visibility (a second overload takes the
     * requested types instead).
     */
    private static Insets navigationInsets(Object state, Rect frame) {
        try {
            Object insets = state.getClass().getMethod("calculateInsets", Rect.class, int.class,
                    boolean.class).invoke(state, frame, WindowInsets.Type.navigationBars(), false);
            return insets instanceof Insets ? (Insets) insets : Insets.NONE;
        } catch (Throwable t) {
            return Insets.NONE;
        }
    }

    /** Whether the screen's insets have a source of this type at all. */
    private static boolean hasSource(Object state, int type) {
        Object size = Reflect.call(state, "sourceSize");
        if (!(size instanceof Integer)) {
            // Cannot tell: as ZUI assumes.
            return true;
        }
        for (int i = 0; i < (Integer) size; i++) {
            Object source = Reflect.call(state, "sourceAt", i);
            if (Integer.valueOf(type).equals(Reflect.call(source, "getType"))) {
                return true;
            }
        }
        return false;
    }

    private static void setInt(Object target, String name, int value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // The superclass, then.
            } catch (Throwable t) {
                return;
            }
        }
    }
}
