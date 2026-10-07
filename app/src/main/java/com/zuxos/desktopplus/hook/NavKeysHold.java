package com.zuxos.desktopplus.hook;

import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the tablet bar's navigation keys at the end of the bar.
 *
 * <p>With desktop windows showing, Launcher3 lays the keys out at the start of the tablet's bar
 * instead of the end - its desktop-windowing layout - and back again when they go. Our start
 * button and row then chased them, a step behind, and overlapped them until the next refresh.
 * Rather than follow the move, it is undone where it is made: the layoutter that puts the keys'
 * container at the start is, in the same call, told the end again. Nothing moves, nothing has to
 * follow, and the hook runs only when ZUI lays its keys out.
 *
 * <p>Only the tablet's own screen; the monitor's bar is left as ZUI made it. A centred,
 * phone-style layout is left alone too.
 */
final class NavKeysHold {

    private static final String PACKAGE = "com.android.launcher3.taskbar.navbutton.";
    private static final String[] LAYOUTTERS = {
            "AbstractNavButtonLayoutter", "TaskbarNavLayoutter", "SetupNavLayoutter",
            "KidsNavLayoutter", "PhoneLandscapeNavLayoutter", "PhonePortraitNavLayoutter",
            "PhoneSeascapeNavLayoutter", "PhoneGestureLayoutter",
    };

    private static boolean sInstalled;
    /** The layoutter that last laid the keys out, for the taskbar's diagnostics. */
    static volatile String sLastLayoutter = "none yet";
    private static int sHooked;

    /** Per keys container: its end margin, as last laid out at the end. */
    private static final Map<View, Integer> END_MARGIN = new WeakHashMap<>();
    /** Per layoutter class: its fields that can hold a view. */
    private static final Map<Class<?>, List<Field>> VIEW_FIELDS = new WeakHashMap<>();
    private static final Map<View, Boolean> WATCHED = new WeakHashMap<>();

    private NavKeysHold() {
    }

    static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        StringBuilder found = new StringBuilder();
        for (String name : LAYOUTTERS) {
            Class<?> cls;
            try {
                cls = Class.forName(PACKAGE + name, false, loader);
            } catch (Throwable t) {
                continue;
            }
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                try {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, AFTER);
                    n++;
                } catch (Throwable ignored) {
                    // One method less; the others still catch the layout.
                }
            }
            if (n > 0) {
                sHooked += n;
                found.append(' ').append(name).append(" x").append(n);
            }
        }
        L.i(sHooked > 0 ? "nav keys hold: watching" + found
                : "nav keys hold: no layoutter on this build; following the keys' layout instead");
    }

    private static final de.robv.android.xposed.XC_MethodHook AFTER =
            new de.robv.android.xposed.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == null) {
                        return;
                    }
                    sLastLayoutter = param.thisObject.getClass().getSimpleName();
                    try {
                        for (Field f : viewFields(param.thisObject.getClass())) {
                            Object v = f.get(param.thisObject);
                            if (v instanceof View && "end_nav_buttons".equals(
                                    Reflect.idName((View) v))) {
                                hold((View) v);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                        // Not a layout call with the keys at hand.
                    }
                }
            };

    private static List<Field> viewFields(Class<?> cls) {
        List<Field> fields = VIEW_FIELDS.get(cls);
        if (fields != null) {
            return fields;
        }
        fields = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && View.class.isAssignableFrom(
                        f.getType())) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
        }
        VIEW_FIELDS.put(cls, fields);
        return fields;
    }

    /**
     * For a build where the layoutters cannot be found: the keys' own layout is watched on this
     * bar and the same correction made, one layout later.
     */
    static void watch(ViewGroup dragLayer) {
        if (sHooked > 0) {
            return;
        }
        View keys = TaskbarStart.navKeys(dragLayer);
        if (keys == null || WATCHED.containsKey(keys)) {
            return;
        }
        WATCHED.put(keys, Boolean.TRUE);
        keys.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(() -> hold(v)));
        hold(keys);
    }

    /** Puts the keys' container back at the end when it was laid out at the start. */
    private static void hold(View keys) {
        if (displayOf(keys) != Display.DEFAULT_DISPLAY
                || !(keys.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) keys.getLayoutParams();
        int horizontal = Gravity.getAbsoluteGravity(lp.gravity, keys.getLayoutDirection())
                & Gravity.HORIZONTAL_GRAVITY_MASK;
        boolean rtl = keys.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int end = rtl ? Gravity.LEFT : Gravity.RIGHT;
        if (horizontal == end) {
            END_MARGIN.put(keys, lp.getMarginEnd());
            return;
        }
        if (lp.gravity == Gravity.NO_GRAVITY
                || horizontal == Gravity.CENTER_HORIZONTAL) {
            // Centred, or never placed by gravity: not the desktop-windowing layout.
            return;
        }
        Integer margin = END_MARGIN.get(keys);
        int marginEnd = margin != null ? margin : lp.getMarginStart();
        lp.gravity = (lp.gravity & Gravity.VERTICAL_GRAVITY_MASK) | Gravity.END;
        lp.setMarginStart(0);
        lp.setMarginEnd(marginEnd);
        keys.setLayoutParams(lp);
        if (sSaid < 3) {
            sSaid++;
            L.i("nav keys hold: the tablet's keys kept at the end (margin " + marginEnd + ")");
        }
    }

    private static int sSaid;

    private static int displayOf(View view) {
        Display d = view.getDisplay();
        if (d == null) {
            try {
                d = view.getContext().getDisplay();
            } catch (Throwable ignored) {
                // A context with no display of its own: not a bar we know.
            }
        }
        return d != null ? d.getDisplayId() : -1;
    }
}
