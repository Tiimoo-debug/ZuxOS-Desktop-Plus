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
        installHomeHold(loader);
    }

    // --- ZUI's home layout of the keys, undone where it is made -------------------------------

    private static final String NAV_CONTROLLER =
            "com.android.launcher3.taskbar.NavbarButtonsViewController";
    /** ZUI's own name, kept by its R8 pass: it centres the keys for home and widens them. */
    private static final String SET_GRAVITY = "setNavButtonContainerGravity";

    /** Per keys container: the layout it had in an app, the one kept on home too. */
    private static final Map<View, Layout> IN_APP = new WeakHashMap<>();
    private static final Map<View, Boolean> HELD = new WeakHashMap<>();
    private static final Map<Class<?>, List<Field>> CONTROLLER_VIEWS = new WeakHashMap<>();

    /**
     * ZUI lays the tablet's keys out twice: at one side of the bar, narrow, in an app; and in the
     * middle, three times as wide, on its home screen ({@code setNavButtonContainerGravity}, with
     * the widths set just before it). Every trip home moved them. Here the in-app layout is
     * remembered and put back right after ZUI's call, in the same frame, so nothing moves; a
     * pre-draw check on the container catches any other path to the home layout.
     */
    private static void installHomeHold(ClassLoader loader) {
        int n = 0;
        try {
            Class<?> cls = Class.forName(NAV_CONTROLLER, false, loader);
            for (Method m : cls.getDeclaredMethods()) {
                if (SET_GRAVITY.equals(m.getName()) && !Modifier.isAbstract(m.getModifiers())) {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, AFTER_GRAVITY);
                    n++;
                }
            }
        } catch (Throwable t) {
            L.d("nav keys hold: no nav controller (" + t + ")");
        }
        L.i("nav keys hold: ZUI's home layout of the keys " + (n > 0 ? "held at its call x" + n
                : "has no call of its own on this build; held before each frame instead"));
    }

    private static final de.robv.android.xposed.XC_MethodHook AFTER_GRAVITY =
            new de.robv.android.xposed.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == null) {
                        return;
                    }
                    try {
                        View keys = keysOf(param.thisObject);
                        if (keys != null) {
                            holdHome(keys);
                            followFrames(keys);
                        }
                    } catch (Throwable t) {
                        L.d("nav keys hold: " + t);
                    }
                }
            };

    /** The controller's keys container: a view field that is it, or holds it. */
    private static View keysOf(Object controller) throws IllegalAccessException {
        List<Field> fields = CONTROLLER_VIEWS.get(controller.getClass());
        if (fields == null) {
            fields = viewFields(controller.getClass());
            CONTROLLER_VIEWS.put(controller.getClass(), fields);
        }
        for (Field f : fields) {
            Object v = f.get(controller);
            if (v instanceof View && "end_nav_buttons".equals(Reflect.idName((View) v))) {
                return (View) v;
            }
        }
        for (Field f : fields) {
            Object v = f.get(controller);
            if (v instanceof ViewGroup) {
                List<View> found = Reflect.findByIdNames((View) v, "end_nav_buttons");
                if (!found.isEmpty()) {
                    return found.get(0);
                }
            }
        }
        return null;
    }

    /** A pre-draw check on the container, once per container. */
    private static void followFrames(View keys) {
        if (HELD.containsKey(keys) || displayOf(keys) != Display.DEFAULT_DISPLAY) {
            return;
        }
        HELD.put(keys, Boolean.TRUE);
        remember(keys);
        // Remembered once laid out - not every frame - and held before every frame.
        keys.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> remember(v));
        int[] skipped = new int[1];
        keys.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (!holdHome(keys)) {
                skipped[0] = 0;
                return true;
            }
            // Should ZUI centre them again in every layout, the frame is drawn anyway rather
            // than skipped for ever.
            return ++skipped[0] > MAX_SKIPPED_FRAMES;
        });
    }

    private static final int MAX_SKIPPED_FRAMES = 2;

    private static boolean centred(View keys) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) keys.getLayoutParams();
        return (Gravity.getAbsoluteGravity(lp.gravity, keys.getLayoutDirection())
                & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL;
    }

    private static boolean ours(View keys) {
        return keys instanceof ViewGroup && displayOf(keys) == Display.DEFAULT_DISPLAY
                && keys.getLayoutParams() instanceof FrameLayout.LayoutParams;
    }

    /** The in-app layout, whenever the keys are laid out at a side. */
    private static void remember(View keys) {
        if (ours(keys) && !centred(keys)
                && ((FrameLayout.LayoutParams) keys.getLayoutParams()).gravity
                        != Gravity.NO_GRAVITY) {
            IN_APP.put(keys, Layout.of((ViewGroup) keys));
        }
    }

    /**
     * Puts the in-app layout back over the home one. True when it changed something, so the
     * frame about to be drawn is skipped and the right one drawn instead.
     */
    private static boolean holdHome(View keys) {
        if (!ours(keys)) {
            return false;
        }
        if (!centred(keys)) {
            return false;
        }
        Layout app = IN_APP.get(keys);
        if (app == null) {
            if (sHomeSaid == 0) {
                sHomeSaid++;
                L.i("nav keys hold: home before any app - the keys' app layout is not known yet");
            }
            return false;
        }
        boolean changed = app.applyTo((ViewGroup) keys);
        if (changed && sHomeSaid < 4) {
            sHomeSaid++;
            L.i("nav keys hold: ZUI's home layout of the keys undone - kept as in apps");
        }
        return changed;
    }

    private static int sHomeSaid;

    /** The container's place and its keys' sizes. */
    private static final class Layout {
        int gravity;
        int width;
        int marginStart;
        int marginEnd;
        int[] kidWidths;
        int[] kidStarts;
        int[] kidEnds;

        static Layout of(ViewGroup keys) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) keys.getLayoutParams();
            Layout l = new Layout();
            l.gravity = lp.gravity;
            l.width = lp.width;
            l.marginStart = lp.getMarginStart();
            l.marginEnd = lp.getMarginEnd();
            int n = keys.getChildCount();
            l.kidWidths = new int[n];
            l.kidStarts = new int[n];
            l.kidEnds = new int[n];
            for (int i = 0; i < n; i++) {
                ViewGroup.LayoutParams k = keys.getChildAt(i).getLayoutParams();
                l.kidWidths[i] = k != null ? k.width : ViewGroup.LayoutParams.WRAP_CONTENT;
                if (k instanceof ViewGroup.MarginLayoutParams) {
                    l.kidStarts[i] = ((ViewGroup.MarginLayoutParams) k).getMarginStart();
                    l.kidEnds[i] = ((ViewGroup.MarginLayoutParams) k).getMarginEnd();
                }
            }
            return l;
        }

        boolean applyTo(ViewGroup keys) {
            boolean changed = false;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) keys.getLayoutParams();
            if (lp.gravity != gravity || lp.width != width || lp.getMarginStart() != marginStart
                    || lp.getMarginEnd() != marginEnd) {
                lp.gravity = gravity;
                lp.width = width;
                lp.setMarginStart(marginStart);
                lp.setMarginEnd(marginEnd);
                keys.setLayoutParams(lp);
                changed = true;
            }
            // The kids only when they are the same ones: a different set is a different layout.
            if (keys.getChildCount() != kidWidths.length) {
                return changed;
            }
            for (int i = 0; i < kidWidths.length; i++) {
                View kid = keys.getChildAt(i);
                ViewGroup.LayoutParams k = kid.getLayoutParams();
                if (k == null) {
                    continue;
                }
                boolean kidChanged = k.width != kidWidths[i];
                k.width = kidWidths[i];
                if (k instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) k;
                    kidChanged |= m.getMarginStart() != kidStarts[i]
                            || m.getMarginEnd() != kidEnds[i];
                    m.setMarginStart(kidStarts[i]);
                    m.setMarginEnd(kidEnds[i]);
                }
                if (kidChanged) {
                    kid.setLayoutParams(k);
                    changed = true;
                }
            }
            return changed;
        }
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
        for (View keys : Reflect.findByIdNames(dragLayer, "end_nav_buttons")) {
            followFrames(keys);
        }
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
