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
            installArrowSwap(loader);
        } catch (Throwable t) {
            L.i("zux home: no dock of ZUI's on this build (" + t + ")");
        }
    }

    // --- the swipe-up arrow, at its source ---------------------------------------------------

    private static final String SCRIM = "com.android.launcher3.views.ScrimView";

    /**
     * Launcher3's swipe-up caret over the bottom of the home screen: a small Drawable the scrim
     * keeps and draws, fading it as the drawer comes up. Swapped once, when a scrim is made, for
     * one that draws nothing: ZUI goes on sizing and fading it as ever and nothing is fought.
     */
    private static void installArrowSwap(ClassLoader loader) {
        try {
            Class<?> scrim = Class.forName(SCRIM, false, loader);
            XposedBridge.hookAllConstructors(scrim, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        View v = (View) param.thisObject;
                        // Its drawables are loaded in the constructor or right after; once it
                        // is laid out they are all there.
                        v.post(() -> swapArrow(v));
                    }
                }
            });
            // ZUI loads a fresh arrow into the scrim later on - the probe found one back after
            // the first was taken - so it is also taken just before the scrim draws: one field
            // read per redraw of the scrim, and a fresh arrow never reaches the screen.
            int drawHooks = 0;
            for (Method m : scrim.getDeclaredMethods()) {
                if ("onDraw".equals(m.getName()) && m.getParameterCount() == 1) {
                    drawHooks++;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                keepArrowOff((View) param.thisObject);
                            }
                        }
                    });
                }
            }
            L.i("zux home: the scrim's swipe-up arrow kept off at its drawing x" + drawHooks);
        } catch (Throwable t) {
            L.i("zux home: no scrim of the launcher's to take the arrow from (" + t + ")");
        }
    }

    private static java.lang.reflect.Field sHandleField;
    private static boolean sHandleLooked;
    private static int sRetaken;

    /** Before a scrim draws: its arrow, if ZUI put a fresh one in, swapped out again. */
    private static void keepArrowOff(View scrim) {
        if (!Cfg.enabled() || !Cfg.startButtonLeft()) {
            return;
        }
        if (!sHandleLooked) {
            sHandleLooked = true;
            for (Class<?> c = scrim.getClass(); c != null && c != View.class;
                    c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("mDragHandle");
                    f.setAccessible(true);
                    sHandleField = f;
                    break;
                } catch (Throwable ignored) {
                    // Not here; the class above.
                }
            }
        }
        java.lang.reflect.Field f = sHandleField;
        if (f == null) {
            swapArrow(scrim);
            return;
        }
        try {
            Object d = f.get(scrim);
            if (!(d instanceof android.graphics.drawable.Drawable) || d instanceof EmptyDrawable) {
                return;
            }
            android.graphics.drawable.Drawable arrow = (android.graphics.drawable.Drawable) d;
            android.graphics.drawable.Drawable none = new EmptyDrawable(
                    arrow.getIntrinsicWidth(), arrow.getIntrinsicHeight());
            none.setBounds(arrow.getBounds());
            f.set(scrim, none);
            synchronized (SWAPPED) {
                if (!SWAPPED.containsKey(scrim)) {
                    SWAPPED.put(scrim, new Object[]{f, arrow});
                }
            }
            if (sRetaken < 5) {
                sRetaken++;
                L.i("zux home: a fresh swipe-up arrow on the scrim taken before it drew");
            }
        } catch (Throwable ignored) {
            // The scrim draws as ZUI made it.
        }
    }

    /** Per scrim: the arrow taken away, kept so turning the setting off brings it back. */
    private static final java.util.Map<Object, Object[]> SWAPPED = new java.util.WeakHashMap<>();

    private static void swapArrow(View scrim) {
        if (!Cfg.enabled() || !Cfg.startButtonLeft() || SWAPPED.containsKey(scrim)) {
            return;
        }
        int limit = Math.round(300 * scrim.getResources().getDisplayMetrics().density);
        StringBuilder seen = new StringBuilder();
        for (Class<?> c = scrim.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        || !android.graphics.drawable.Drawable.class.isAssignableFrom(
                                f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object d = f.get(scrim);
                    if (!(d instanceof android.graphics.drawable.Drawable)
                            || d instanceof android.graphics.drawable.ColorDrawable) {
                        continue;
                    }
                    android.graphics.drawable.Drawable arrow =
                            (android.graphics.drawable.Drawable) d;
                    int w = arrow.getIntrinsicWidth();
                    int h = arrow.getIntrinsicHeight();
                    seen.append(' ').append(f.getName()).append('=')
                            .append(d.getClass().getSimpleName()).append(' ').append(w)
                            .append('x').append(h);
                    if (w <= 0 || h <= 0 || w > limit || h > limit) {
                        continue;
                    }
                    android.graphics.drawable.Drawable none = new EmptyDrawable(w, h);
                    none.setBounds(arrow.getBounds());
                    f.set(scrim, none);
                    SWAPPED.put(scrim, new Object[]{f, arrow});
                    L.i("zux home: the swipe-up arrow taken from the scrim (" + c.getSimpleName()
                            + "." + f.getName() + ", " + d.getClass().getSimpleName() + " " + w
                            + "x" + h + ")");
                    scrim.invalidate();
                    return;
                } catch (Throwable ignored) {
                    // That field stays as it is.
                }
            }
        }
        L.i("zux home: no arrow on the scrim - drawables:" + (seen.length() == 0 ? " none"
                : seen));
    }

    /** The arrows taken, back where they were: the setting was turned off. */
    private static void restoreArrows() {
        if (SWAPPED.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Object, Object[]> e : SWAPPED.entrySet()) {
            try {
                ((java.lang.reflect.Field) e.getValue()[0]).set(e.getKey(), e.getValue()[1]);
                ((View) e.getKey()).invalidate();
            } catch (Throwable ignored) {
                // Gone with its scrim.
            }
        }
        SWAPPED.clear();
    }

    /** Draws nothing, at the size of what it stands in for. */
    private static final class EmptyDrawable extends android.graphics.drawable.Drawable {
        private final int mWidth;
        private final int mHeight;

        EmptyDrawable(int width, int height) {
            mWidth = width;
            mHeight = height;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSPARENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return mWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return mHeight;
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
            if (!ours) {
                restoreArrows();
            }
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
