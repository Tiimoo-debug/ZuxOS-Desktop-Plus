package com.zuxos.desktopplus.hook;

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
    private static boolean sSaidTouch;

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
        /** ZUI's icon row, found once rather than searched for on every frame. */
        java.lang.ref.WeakReference<View> row;
    }

    /** On a bar of ours; again whenever the bar is refreshed, which re-installs after a re-attach. */
    static void install(ViewGroup dragLayer) {
        ViewTreeObserver observer = dragLayer.getViewTreeObserver();
        State state = STATES.get(dragLayer);
        if (state != null && state.observer == observer && observer.isAlive()) {
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
        if (row == null || row.getRootView() != dragLayer) {
            row = iconRow(dragLayer);
            if (row == null) {
                return;
            }
            state.row = new java.lang.ref.WeakReference<>(row);
        }
        float alpha = row.isShown() ? row.getAlpha() : 0f;
        float shift = row.getTranslationY();
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
            if (added && !sSaidTouch) {
                sSaidTouch = true;
                L.i("taskbar follow: ZUI limited the bar's touch to its own icons; ours added");
            }
        } catch (Throwable t) {
            L.d("taskbar follow: could not widen the touch region (" + t + ")");
        }
    }
}
