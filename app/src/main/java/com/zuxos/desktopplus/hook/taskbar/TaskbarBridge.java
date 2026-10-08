package com.zuxos.desktopplus.hook.taskbar;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.Windows;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reaching into the stock taskbar from outside its view tree.
 *
 * <p>The taskbar's app drawer is its own window. Launching an app from a folder opened over that
 * drawer used to leave the drawer sitting on top of whatever had just started, because nothing
 * told it to close - the launcher only closes it for launches it performed itself.
 */
public final class TaskbarBridge {

    private TaskbarBridge() {
    }

    /**
     * Closes the stock app drawer if it is open.
     *
     * <p>Must be called on the launcher's UI thread. Returns true when a drawer was found and
     * asked to close; false is not an error, it usually just means the drawer was not open.
     */
    public static boolean closeStockDrawer() {
        boolean closed = false;
        for (View root : Windows.roots()) {
            if (!isAllAppsWindow(root)) {
                continue;
            }
            if (closeWindow(root)) {
                closed = true;
            }
        }
        return closed;
    }

    /** Whether the stock drawer is open on this display. */
    public static boolean isStockDrawerOpen(int displayId) {
        return drawerOn(displayId) != null;
    }

    /** Closes the stock drawer on this display only; true when one was open and asked to close. */
    public static boolean closeStockDrawer(int displayId) {
        View root = drawerOn(displayId);
        return root != null && closeWindow(root);
    }

    /**
     * Gets the stock drawer out of the way of a drag that started in it, without closing it: its
     * window fades out and stops taking touches, so the desktop or the taskbar under it gets the
     * drop. Closing it instead took away the window the drag belonged to, and Android cancels a
     * drag whose window is gone - only a drop quick enough to beat the closing animation landed.
     *
     * @param inDrawer any view in the drawer's window
     * @return what to run once the drag is over - closes the drawer and puts its window back as
     *         it was - or null when the window could not be reached
     */
    public static Runnable stepAsideStockDrawer(View inDrawer) {
        View root = inDrawer.getRootView();
        if (!(root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams)) {
            return null;
        }
        android.view.WindowManager.LayoutParams lp =
                (android.view.WindowManager.LayoutParams) root.getLayoutParams();
        android.view.WindowManager wm = (android.view.WindowManager) root.getContext()
                .getSystemService(android.content.Context.WINDOW_SERVICE);
        int flags = lp.flags;
        float dim = lp.dimAmount;
        int blur = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                ? lp.getBlurBehindRadius() : 0;
        try {
            root.animate().alpha(0f).setDuration(Motion.SHORT)
                    .setInterpolator(Motion.EXIT).start();
            lp.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.flags &= ~(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    | android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                lp.setBlurBehindRadius(0);
            }
            wm.updateViewLayout(root, lp);
        } catch (Throwable t) {
            L.d("taskbar: the drawer could not step aside (" + t + ")");
            return null;
        }
        Runnable restore = () -> {
            root.animate().cancel();
            root.setAlpha(1f);
            lp.flags = flags;
            lp.dimAmount = dim;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                lp.setBlurBehindRadius(blur);
            }
            if (root.isAttachedToWindow()) {
                try {
                    wm.updateViewLayout(root, lp);
                } catch (Throwable ignored) {
                    // Gone in the meantime: the next one starts from these values anyway.
                }
            }
        };
        return () -> {
            closeWindow(root);
            // Put back once the drawer has gone - its window removed, or, if the launcher keeps
            // the window, once the close has run - so the next opening is the usual one.
            boolean[] done = new boolean[1];
            Runnable once = () -> {
                if (!done[0]) {
                    done[0] = true;
                    restore.run();
                }
            };
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                    once.run();
                }
            });
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(once,
                    STEP_ASIDE_RESTORE_MS);
        };
    }

    private static final long STEP_ASIDE_RESTORE_MS = 600L;

    private static View drawerOn(int displayId) {
        for (View root : Windows.roots()) {
            if (root.isAttachedToWindow() && root.getVisibility() == View.VISIBLE
                    && root.getWindowVisibility() == View.VISIBLE
                    && root.getWidth() > 0 && root.getHeight() > 0
                    && TaskbarTray.displayIdOf(root) == displayId && isAllAppsWindow(root)) {
                return root;
            }
        }
        return null;
    }

    /**
     * The drawer's window: the taskbar's own overlay window, holding its all-apps sheet.
     *
     * <p>Launcher3's taskbar all-apps classes are all named {@code Taskbar*AllApps*}, and the
     * firmware's R8 pass keeps launcher class names, so the name is the reliable signal. It is
     * looked for only near the top of a window that is not an activity's: ZUX Home's own window
     * always holds its hidden built-in drawer ({@code LauncherAllAppsContainerView}), and taking
     * that for the taskbar's drawer made every drawer icon look like it was elsewhere, the start
     * button "close" a drawer that was never open, and Home tap the middle of the home screen.
     */
    public static boolean isAllAppsWindow(View root) {
        if (root == null) {
            return false;
        }
        if (root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams) {
            int type = ((android.view.WindowManager.LayoutParams) root.getLayoutParams()).type;
            if (type >= android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                    && type <= android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) {
                return false;
            }
        }
        return taskbarAllAppsWithin(root, ALL_APPS_DEPTH);
    }

    /** The sheet sits two levels under the overlay's drag layer; three leaves room for a wrapper. */
    private static final int ALL_APPS_DEPTH = 3;

    private static boolean taskbarAllAppsWithin(View v, int depth) {
        if (isTaskbarAllApps(v.getClass())) {
            return true;
        }
        if (depth <= 0 || !(v instanceof ViewGroup)) {
            return false;
        }
        ViewGroup g = (ViewGroup) v;
        for (int c = 0; c < g.getChildCount(); c++) {
            if (taskbarAllAppsWithin(g.getChildAt(c), depth - 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTaskbarAllApps(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name.startsWith("Taskbar") && name.contains("AllApps")
                    && !name.contains("Button")) {
                return true;
            }
        }
        return false;
    }

    /** The stock drawer's window root on this view's own display, or null when not open there. */
    public static View stockDrawerRootOn(int displayId) {
        return drawerOn(displayId);
    }

    private static boolean containsAllAppsName(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name.contains("AllApps") && !name.contains("Button")) {
                return true;
            }
        }
        return false;
    }

    /** Every all-apps-named view in the tree, shallowest first. */
    private static List<View> findAllApps(View root) {
        List<View> found = new ArrayList<>();
        List<View> queue = new ArrayList<>();
        queue.add(root);
        for (int i = 0; i < queue.size() && i < 512; i++) {
            View v = queue.get(i);
            if (containsAllAppsName(v.getClass())) {
                found.add(v);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int c = 0; c < g.getChildCount(); c++) {
                    queue.add(g.getChildAt(c));
                }
            }
        }
        return found;
    }

    /** Three ways to dismiss, weakest assumptions last. */
    private static boolean closeWindow(View root) {
        List<View> candidates = findAllApps(root);
        // The sheet is the one that knows how to close itself; its container and its recycler
        // carry all-apps names too, so every candidate is offered the call.
        for (View candidate : candidates) {
            if (invokeClose(candidate)) {
                return true;
            }
        }
        View sheet = null;
        for (View candidate : candidates) {
            if (candidate != root) {
                sheet = candidate;
                break;
            }
        }
        if (touchOutside(root, sheet)) {
            return true;
        }
        return pressBack(root);
    }

    /**
     * {@code AbstractFloatingView.close(boolean)}. Only a method actually named {@code close} is
     * accepted: guessing at a minified {@code void x(boolean)} could hit anything.
     */
    private static boolean invokeClose(View view) {
        for (Class<?> c = view.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"close".equals(m.getName()) || m.getParameterCount() != 1
                        || m.getParameterTypes()[0] != boolean.class) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    m.invoke(view, true);
                    return true;
                } catch (Throwable t) {
                    L.d("taskbar: close() refused: " + t);
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * A tap on the scrim above the sheet, which is how a slide-in view is normally dismissed.
     *
     * <p>The point is taken above the sheet's top edge, so it lands on the scrim and never on a
     * row of apps.
     */
    private static boolean touchOutside(View root, View sheet) {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        float y = 4f;
        if (sheet != null && sheet != root) {
            int[] sheetLoc = new int[2];
            int[] rootLoc = new int[2];
            sheet.getLocationOnScreen(sheetLoc);
            root.getLocationOnScreen(rootLoc);
            float top = sheetLoc[1] - rootLoc[1];
            if (top < 8f) {
                // The sheet reaches the top of its window: there is no scrim to tap.
                return false;
            }
            y = top / 2f;
        }
        float x = width / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x, y, 0);
        try {
            boolean handled = root.dispatchTouchEvent(down);
            handled |= root.dispatchTouchEvent(up);
            return handled;
        } catch (Throwable t) {
            L.d("taskbar: outside tap failed: " + t);
            return false;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static boolean pressBack(View root) {
        long now = SystemClock.uptimeMillis();
        try {
            boolean handled = root.dispatchKeyEvent(
                    new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
            handled |= root.dispatchKeyEvent(
                    new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
            return handled;
        } catch (Throwable t) {
            L.d("taskbar: back key failed: " + t);
            return false;
        }
    }
}
