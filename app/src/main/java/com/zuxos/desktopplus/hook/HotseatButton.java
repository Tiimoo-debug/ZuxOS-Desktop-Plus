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
 * ZUX Home's own app-drawer button - the pill over its dock, and the arrow above it - taken
 * away while our start button
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
            installDrawSkip(hotseat);
            installArrowOff(loader);
        } catch (Throwable t) {
            L.i("zux home: no dock of ZUI's on this build (" + t + ")");
        }
    }

    // --- the swipe-up arrow: its image never loaded ------------------------------------------

    private static final String SCRIM = "com.android.launcher3.views.ScrimView";
    private static final String[] ARROW_NAMES = {
            "drag_handle_indicator_shadow", "drag_handle_indicator",
            "drag_handle_indicator_no_shadow"};

    /** The arrow's drawable resource id in the launcher; 0 until found or when there is none. */
    private static volatile int sArrowId;
    private static boolean sArrowLooked;

    /**
     * Launcher3's swipe-up arrow over the bottom of the home screen is an image the scrim loads
     * from the launcher's own resources - and loads again whenever ZUI decides to. While our
     * start button is on, that one image loads as nothing: the scrim has no arrow to show,
     * however often it reloads, and nothing of ours touches ZUI's views or their drawing.
     *
     * <p>The id is looked up once, when the first scrim is made - before it loads the arrow -
     * from the scrim's own resources; every drawable load then costs one int compare.
     */
    private static void installArrowOff(ClassLoader loader) {
        try {
            Class<?> scrim = Class.forName(SCRIM, false, loader);
            XposedBridge.hookAllConstructors(scrim, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sArrowLooked && param.args.length > 0
                            && param.args[0] instanceof android.content.Context) {
                        findArrow((android.content.Context) param.args[0]);
                    }
                }
            });
            XposedBridge.hookMethod(android.content.res.Resources.class.getDeclaredMethod(
                    "getDrawableForDensity", int.class, int.class,
                    android.content.res.Resources.Theme.class), new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int id = sArrowId;
                            if (id != 0 && (Integer) param.args[0] == id && Cfg.enabled()
                                    && Cfg.startButtonLeft()) {
                                param.setResult(new android.graphics.drawable.ColorDrawable(
                                        android.graphics.Color.TRANSPARENT));
                            }
                        }
                    });
        } catch (Throwable t) {
            L.i("zux home: the swipe-up arrow left as ZUI shows it (" + t + ")");
        }
    }

    private static synchronized void findArrow(android.content.Context ctx) {
        if (sArrowLooked) {
            return;
        }
        sArrowLooked = true;
        try {
            android.content.res.Resources res = ctx.getResources();
            String pkg = ctx.getPackageName();
            for (String name : ARROW_NAMES) {
                int id = res.getIdentifier(name, "drawable", pkg);
                if (id != 0) {
                    sArrowId = id;
                    L.i("zux home: the swipe-up arrow's image (" + name + ") is never loaded");
                    return;
                }
            }
            // Renamed by the firmware: the drawable entries named like it, by scanning the type
            // the launcher's own drawables are in.
            int any = res.getIdentifier("ic_info_no_shadow", "drawable", pkg);
            if (any == 0) {
                any = res.getIdentifier("ic_remove_no_shadow", "drawable", pkg);
            }
            if (any != 0) {
                int base = any & 0xFFFF0000;
                for (int i = 0; i < 0x4000; i++) {
                    String name;
                    try {
                        name = res.getResourceEntryName(base | i);
                    } catch (android.content.res.Resources.NotFoundException e) {
                        break;
                    }
                    if (name.contains("drag_handle_indicator")) {
                        sArrowId = base | i;
                        L.i("zux home: the swipe-up arrow's image (" + name + ") is never loaded");
                        return;
                    }
                }
            }
            L.i("zux home: no swipe-up arrow image found by name - left as ZUI shows it");
        } catch (Throwable t) {
            L.i("zux home: the swipe-up arrow's image not looked up (" + t + ")");
        }
    }

    private static Class<?> sDock;
    private static boolean sSkipSaid;

    /**
     * The dock's drawing, skipped while it has nothing of its own to show: the most specific
     * override in its chain, or View's and ViewGroup's, which then let every other view straight
     * through after one class compare - and run only when a drawing is re-recorded, not every
     * frame.
     */
    private static void installDrawSkip(Class<?> hotseat) {
        sDock = hotseat;
        // draw() when the dock paints anything of its own; dispatchDraw() alone when it does not,
        // as Android then goes straight to its children.
        for (String name : new String[]{"draw", "dispatchDraw"}) {
            Method m = null;
            for (Class<?> c = hotseat; c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod(name, android.graphics.Canvas.class);
                } catch (NoSuchMethodException ignored) {
                    // Not overridden here; the next class up.
                }
            }
            if (m == null) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, SKIP_DRAW);
                L.i("zux home: its dock's drawing held back while empty, at "
                        + m.getDeclaringClass().getSimpleName() + "." + name);
            } catch (Throwable t) {
                L.i("zux home: " + name + " of its dock could not be held back (" + t + ")");
            }
        }
    }

    private static final XC_MethodHook SKIP_DRAW = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object view = param.thisObject;
            if (view == null || view.getClass() != sDock) {
                return;
            }
            ViewGroup dock = (ViewGroup) view;
            if (Cfg.enabled() && Cfg.startButtonLeft() && showsNothing(dock)) {
                param.setResult(null);
                if (!sSkipSaid) {
                    sSkipSaid = true;
                    L.i("zux home: its empty dock not drawn - no pill, no arrow");
                }
            }
        }
    };

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
                // The button, and the pill under it with the arrow above: a plain view of ZUI's,
                // the only one in the dock with a size. ZUI keeps the dock up in its Recents too.
                boolean pill = child.getClass() == View.class
                        && (child.getWidth() > 0 || child.getTag(TAG_HIDDEN) != null);
                if (!(child instanceof ImageButton) && !pill) {
                    continue;
                }
                int want = ours ? View.GONE : View.VISIBLE;
                if (child.getVisibility() != want
                        && (ours || child.getTag(TAG_HIDDEN) != null)) {
                    child.setVisibility(want);
                    child.setTag(TAG_HIDDEN, ours ? Boolean.TRUE : null);
                    if (ours && !sSaid) {
                        sSaid = true;
                        L.i("zux home: drawer button and its pill hidden - the start button opens "
                                + "the drawer");
                    }
                }
            }
        } catch (Throwable ignored) {
            // The dock as ZUI left it.
        }
    }

    /**
     * In ZUI's desktop mode the dock holds no apps - those are in the taskbar - and what it still
     * draws is its own: the drawer pill and the arrow above it, painted by the dock itself around
     * a view that hiding took nothing away from. Then the dock simply does not draw, on home and
     * in Recents alike - its visibility and state stay ZUI's. With apps in it, it draws as ever.
     */
    private static boolean showsNothing(ViewGroup hotseat) {
        for (int i = 0; i < hotseat.getChildCount(); i++) {
            View child = hotseat.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getTag(TAG_HIDDEN) != null
                    || child.getWidth() == 0) {
                continue;
            }
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() == 0) {
                // The dock's icon container, empty.
                continue;
            }
            return false;
        }
        return true;
    }

    /** Marks a button we hid, so turning the setting off shows only what we took away. */
    private static final int TAG_HIDDEN = 0x7A000401;

}
