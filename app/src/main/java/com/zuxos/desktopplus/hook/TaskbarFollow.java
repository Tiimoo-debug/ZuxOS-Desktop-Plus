package com.zuxos.desktopplus.hook;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Our pieces of a taskbar going along with ZUI's own, instead of ZUI being held open for them.
 *
 * <p><b>Hiding.</b> On the tablet's regular bar ZUI hides its icon row whenever the bar is
 * switched off or stashed - gesture navigation stashes it into a handle. Our row, start button,
 * tray, marks and glass are the drag layer's own children, which that never touches, so they
 * stayed where the bar had been. Just before each frame of the bar, they take the row's fade and
 * its slide; while ZUI's drawer is open they stay put, so the start button is there to close it.
 *
 * <p><b>Touch.</b> When ZUI limits the bar's window to a touchable region - its own icons, in the
 * states where the rest of the bar is meant to let touches through - our icons fell outside it,
 * and taps on them went to the app behind: the bar "stopped responding". The region is widened by
 * whichever of ours are visible, right after ZUI computes it, and by nothing while they are not.
 */
final class TaskbarFollow {

    /** Per bar: what was installed where, and what the pieces were last set to. */
    private static final Map<View, State> STATES = new WeakHashMap<>();

    private static Class<?> sInsetsListener;
    private static Method sAddInsets;
    private static Method sRemoveInsets;
    private static Field sTouchableInsets;
    private static Field sTouchableRegion;
    private static boolean sInsetsUnavailable;
    private static boolean sSaidFollow;
    private static final Map<View, Boolean> TOUCH_SAID = new WeakHashMap<>();

    /** {@code InternalInsetsInfo.TOUCHABLE_INSETS_REGION}. */
    private static final int TOUCHABLE_REGION = 3;

    private TaskbarFollow() {
    }

    private static final class State {
        ViewTreeObserver observer;
        ViewTreeObserver.OnPreDrawListener preDraw;
        Object insets;
        float alpha = 1f;
        float shift = 0f;
        String lastDescribed;
        int described;
        /** ZUI's icon row, found once rather than searched for on every frame. */
        java.lang.ref.WeakReference<View> row;
    }

    /** On a bar of ours; again whenever the bar is refreshed, which re-installs after a re-attach. */
    static void install(ViewGroup dragLayer) {
        ViewTreeObserver observer = dragLayer.getViewTreeObserver();
        State state = STATES.get(dragLayer);
        if (state != null && state.observer == observer && observer.isAlive()) {
            // Back to the end of the line: a bar that registers its own touch region after ours
            // - one built on a window context may, each time it shows - would otherwise
            // overwrite what ours adds, and our icons would take no taps.
            if (state.insets != null) {
                try {
                    sRemoveInsets.invoke(observer, state.insets);
                    sAddInsets.invoke(observer, state.insets);
                } catch (Throwable ignored) {
                    // Left where it was.
                }
            }
            return;
        }
        if (state != null) {
            detach(state);
        }
        state = new State();
        state.observer = observer;
        if (TaskbarScope.tablet(dragLayer)) {
            State held = state;
            state.preDraw = () -> {
                follow(dragLayer, held);
                return true;
            };
            observer.addOnPreDrawListener(state.preDraw);
            if (!sSaidFollow) {
                sSaidFollow = true;
                L.i("taskbar follow: ours hide, stash and come back with the tablet's own bar");
            }
        }
        state.insets = insetsListener(dragLayer);
        if (state.insets != null) {
            try {
                sAddInsets.invoke(observer, state.insets);
            } catch (Throwable t) {
                state.insets = null;
                L.d("taskbar follow: no touch region (" + t + ")");
            }
        }
        STATES.put(dragLayer, state);
    }

    private static void detach(State state) {
        ViewTreeObserver observer = state.observer;
        if (observer == null || !observer.isAlive()) {
            return;
        }
        if (state.preDraw != null) {
            observer.removeOnPreDrawListener(state.preDraw);
        }
        if (state.insets != null) {
            try {
                sRemoveInsets.invoke(observer, state.insets);
            } catch (Throwable ignored) {
                // Gone with the observer.
            }
        }
    }

    // --- hiding ------------------------------------------------------------------------------

    /** Just before a frame: our pieces take the row's fade and slide, unless the drawer is up. */
    private static void follow(ViewGroup dragLayer, State state) {
        View row = state.row != null ? state.row.get() : null;
        if (row == null || !isUnder(row, dragLayer)) {
            row = iconRow(dragLayer);
            if (row == null) {
                return;
            }
            state.row = new java.lang.ref.WeakReference<>(row);
        }
        // ZUI hides a bar by fading or sliding its icon row, or by fading and sliding each icon
        // in it; its drawer button - invisible, ours stands in for it - still gets the latter.
        float alpha = row.isShown() ? row.getAlpha() : 0f;
        float shift = row.getTranslationY();
        View zui = row instanceof ViewGroup ? TaskbarStart.allAppsButton((ViewGroup) row) : null;
        if (zui != null) {
            alpha *= zui.getAlpha();
            shift += zui.getTranslationY();
        }
        describe(dragLayer, state, row, zui);
        if ((alpha < 0.999f || shift != 0f)
                && TaskbarStart.drawerOpen(TaskbarTray.displayIdOf(dragLayer))) {
            // ZUI hides its row for its drawer; ours stays, so the start button can close it.
            alpha = 1f;
            shift = 0f;
        }
        if (alpha == state.alpha && shift == state.shift) {
            return;
        }
        state.alpha = alpha;
        state.shift = shift;
        apply(dragLayer, alpha, shift);
    }

    /**
     * One line per change in how ZUI shows a tablet bar, a few dozen at most per bar: what each
     * of its hiding channels reads. Enough to see from a log alone how it hides for the keyboard.
     */
    private static void describe(ViewGroup dragLayer, State state, View row, View zui) {
        if (state.described >= 20) {
            return;
        }
        String line = "row " + row.getVisibility() + "/" + row.getAlpha() + "/"
                + row.getTranslationY()
                + (zui != null ? " button " + zui.getAlpha() + "/" + zui.getTranslationY() : "")
                + " layer " + dragLayer.getAlpha() + "/" + dragLayer.getTranslationY()
                + " window " + dragLayer.getWindowVisibility();
        if (!line.equals(state.lastDescribed)) {
            state.lastDescribed = line;
            state.described++;
            L.i("taskbar follow: " + TaskbarScope.label(dragLayer) + " " + line);
        }
    }

    private static void apply(ViewGroup dragLayer, float alpha, float shift) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            View child = dragLayer.getChildAt(i);
            if (!isOurs(child)) {
                continue;
            }
            child.setAlpha(alpha);
            child.setTranslationY(shift);
        }
    }

    private static boolean isOurs(View child) {
        return child.getClass().getName().startsWith("com.zuxos.desktopplus.");
    }

    /** Whether {@code view} is still attached somewhere inside {@code group}. */
    private static boolean isUnder(View view, ViewGroup group) {
        if (!view.isAttachedToWindow()) {
            return false;
        }
        for (Object p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == group) {
                return true;
            }
        }
        return false;
    }

    private static View iconRow(ViewGroup dragLayer) {
        List<View> found = Reflect.findByIdNames(dragLayer, "taskbar_view");
        return found.isEmpty() ? null : found.get(0);
    }

    // --- touch -------------------------------------------------------------------------------

    /** A listener of the hidden insets interface, made at run time, or null when it cannot be. */
    private static Object insetsListener(ViewGroup dragLayer) {
        if (!bindInsets()) {
            return null;
        }
        return Proxy.newProxyInstance(TaskbarFollow.class.getClassLoader(),
                new Class<?>[]{sInsetsListener}, (self, method, args) -> {
                    switch (method.getName()) {
                        case "onComputeInternalInsets":
                            if (args != null && args.length == 1) {
                                widen(dragLayer, args[0]);
                            }
                            return null;
                        case "equals":
                            return self == (args != null && args.length > 0 ? args[0] : null);
                        case "hashCode":
                            return System.identityHashCode(self);
                        case "toString":
                            return "ZuxDesktopPlus taskbar touch";
                        default:
                            return null;
                    }
                });
    }

    private static synchronized boolean bindInsets() {
        if (sInsetsListener != null) {
            return true;
        }
        if (sInsetsUnavailable) {
            return false;
        }
        try {
            sInsetsListener = Class.forName(
                    "android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> info = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            sAddInsets = ViewTreeObserver.class.getMethod("addOnComputeInternalInsetsListener",
                    sInsetsListener);
            sRemoveInsets = ViewTreeObserver.class.getMethod(
                    "removeOnComputeInternalInsetsListener", sInsetsListener);
            sTouchableInsets = info.getDeclaredField("mTouchableInsets");
            sTouchableInsets.setAccessible(true);
            sTouchableRegion = info.getField("touchableRegion");
            return true;
        } catch (Throwable t) {
            sInsetsListener = null;
            sInsetsUnavailable = true;
            L.i("taskbar follow: the bar's touch region cannot be reached (" + t + ")");
            return false;
        }
    }

    /** Right after ZUI set the bar's touchable region: ours are added where they are showing. */
    private static void widen(ViewGroup dragLayer, Object info) {
        try {
            if (sTouchableInsets.getInt(info) != TOUCHABLE_REGION) {
                // The whole window takes touches already, or none of it is meant to.
                return;
            }
            Region region = (Region) sTouchableRegion.get(info);
            if (region == null) {
                return;
            }
            Rect zuis = region.getBounds();
            int[] at = new int[2];
            boolean added = false;
            for (View piece : new View[]{TaskbarRunning.scrollerOf(dragLayer),
                    TaskbarStart.buttonIn(dragLayer), TaskbarTray.trayOf(dragLayer)}) {
                if (piece == null || !piece.isShown() || piece.getAlpha() < 0.05f
                        || piece.getWidth() <= 0) {
                    continue;
                }
                piece.getLocationInWindow(at);
                region.op(at[0], at[1], at[0] + piece.getWidth(), at[1] + piece.getHeight(),
                        Region.Op.UNION);
                added = true;
            }
            if (added && TOUCH_SAID.put(dragLayer, Boolean.TRUE) == null) {
                View start = TaskbarStart.buttonIn(dragLayer);
                String startIn = "no start button";
                if (start != null && start.getWidth() > 0) {
                    start.getLocationInWindow(at);
                    startIn = "start button " + (region.contains(at[0] + start.getWidth() / 2,
                            at[1] + start.getHeight() / 2) ? "inside" : "OUTSIDE");
                }
                L.i("taskbar follow: " + TaskbarScope.label(dragLayer) + " - ZUI limited its"
                        + " touch to " + zuis + "; with ours " + startIn);
            }
        } catch (Throwable t) {
            L.d("taskbar follow: could not widen the touch region (" + t + ")");
        }
    }
}
