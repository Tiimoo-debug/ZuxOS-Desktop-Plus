package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import com.zuxos.desktopplus.core.L;

import java.util.HashSet;
import java.util.Set;

/**
 * Whether a taskbar is on the tablet's own screen.
 *
 * <p>The tablet's bars come and go in ways the monitor's does not - switched off, stashed into a
 * gesture handle - so ours follow them there ({@link TaskbarFollow}). Which of ZUI's two tablet
 * bars it is - the regular one, or its desktop mode's, built from ZUI's {@code ...Dp} classes -
 * is named in the log once per bar; both are treated alike.
 */
final class TaskbarScope {

    private static final Set<String> SAID = new HashSet<>();

    private TaskbarScope() {
    }

    /** Whether {@code view} is part of a bar on the tablet's own screen. */
    static boolean tablet(View view) {
        if (view == null || TaskbarTray.displayIdOf(view) != 0) {
            return false;
        }
        String made = className(view.getContext());
        if (SAID.add(made)) {
            L.i("taskbar scope: a bar on the tablet's screen, made by " + made
                    + (made.endsWith("Dp") ? " (desktop mode)" : ""));
        }
        return true;
    }

    /** A bar's name for the log: its display and the class it is made from. */
    static String label(View view) {
        return "display " + TaskbarTray.displayIdOf(view) + "/" + className(view.getContext());
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
