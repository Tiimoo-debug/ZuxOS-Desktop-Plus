package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import com.zuxos.desktopplus.core.L;

import java.util.HashSet;
import java.util.Set;

/**
 * Which of ZUI's three bars a taskbar is.
 *
 * <ul>
 *   <li>The tablet's, in its regular mode: home is ZUI's {@code DrawerLauncher}.</li>
 *   <li>The tablet's, in its desktop mode: home is ZUI's {@code CustomModeLauncher}; the bar is
 *       made by the same class as the regular one, and only ZUI's search box in its row
 *       ({@code ZuiTaskbarSearchContainer}) tells them apart.</li>
 *   <li>The monitor's: ZUI's {@code ...Dp} classes, on a screen of its own.</li>
 * </ul>
 *
 * <p>The tablet's bars come and go in ways the monitor's does not - switched off, stashed into a
 * gesture handle - so ours follow them there ({@link TaskbarFollow}). Each bar's mode is named
 * in the log once, so a log says which of the three it is about.
 */
final class TaskbarScope {

    private static final Set<String> SAID = new HashSet<>();

    private TaskbarScope() {
    }

    /** Whether {@code view} is part of a bar on the tablet's own screen, in either mode. */
    static boolean tablet(View view) {
        if (view == null || TaskbarTray.displayIdOf(view) != 0) {
            return false;
        }
        String mode = mode(view);
        if (SAID.add(mode)) {
            L.i("taskbar scope: a bar on the tablet's screen - " + mode + ", made by "
                    + className(view.getContext()));
        }
        return true;
    }

    /** A bar's name for the log: its mode, display and the class it is made from. */
    static String label(View view) {
        return mode(view) + " (display " + TaskbarTray.displayIdOf(view) + "/"
                + className(view.getContext()) + ")";
    }

    /**
     * The bar's mode: the monitor's by its screen; on the tablet, desktop mode when ZUI's search
     * box is in its row - the one thing that differs between the tablet's two bars.
     */
    static String mode(View view) {
        if (view == null) {
            return "unknown";
        }
        if (TaskbarTray.displayIdOf(view) != 0) {
            return "monitor desktop";
        }
        return hasSearch(view.getRootView(), 6) ? "tablet desktop mode" : "tablet";
    }

    private static boolean hasSearch(View v, int depth) {
        if (v.getClass().getSimpleName().equals("ZuiTaskbarSearchContainer")) {
            return true;
        }
        if (depth <= 0 || !(v instanceof android.view.ViewGroup)) {
            return false;
        }
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (hasSearch(g.getChildAt(i), depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /** The bar's context class, or one it wraps when that is one of ZUI's desktop-mode ones. */
    private static String className(Context ctx) {
        String first = ctx == null ? "no context" : ctx.getClass().getSimpleName();
        for (int i = 0; ctx != null && i < 6; i++) {
            String name = ctx.getClass().getSimpleName();
            if (name.endsWith("Dp")) {
                return name;
            }
            ctx = ctx instanceof ContextWrapper ? ((ContextWrapper) ctx).getBaseContext() : null;
        }
        return first;
    }
}
