package com.zuxos.desktopplus.hook;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;

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

    /**
     * The root view of the stock drawer's window, or null when it is not open.
     *
     * <p>Windows cannot see into each other, so a panel floating over the stock drawer has
     * nothing behind it to refract unless it is handed the drawer's own view tree.
     */
    public static View stockDrawerRoot() {
        for (View root : Windows.roots()) {
            if (isAllAppsWindow(root) && root.getWidth() > 0 && root.getHeight() > 0) {
                return root;
            }
        }
        return null;
    }

    /**
     * The drawer's window, by name.
     *
     * <p>Launcher3's taskbar all-apps classes are all named {@code Taskbar*AllApps*}, and the
     * firmware's R8 pass keeps launcher class names (the probe dump shows them in full), so the
     * name is the reliable signal. The tree is searched too, in case a build wraps the drag layer
     * in something more generic.
     */
    private static boolean isAllAppsWindow(View root) {
        if (containsAllAppsName(root.getClass())) {
            return true;
        }
        return !findAllApps(root).isEmpty();
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
