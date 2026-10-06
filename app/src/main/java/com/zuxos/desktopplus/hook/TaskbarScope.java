package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import com.zuxos.desktopplus.core.L;

import java.util.HashSet;
import java.util.Set;

/**
 * Which taskbar this is, and so how much of it is ours.
 *
 * <p>The tablet's screen has two bars of ZUI's own: the regular tablet taskbar, and the one its
 * desktop mode puts up. The desktop-mode bar already shows exactly the open apps, and was right
 * before this module ever touched it - so it is left entirely to ZUI. The monitor's bar and the
 * tablet's regular bar are ours.
 *
 * <p>ZUI builds its desktop-mode bars from its own {@code ...Dp} classes - the monitor's bar runs
 * in a {@code TaskbarActivityContextDp} (probe) - so a bar on the tablet's screen made from one
 * of those is the tablet's desktop mode. Each bar is named in the log once, so a firmware that
 * names them differently shows up at once.
 */
final class TaskbarScope {

    enum Kind {
        /** The external monitor's bar. */
        MONITOR,
        /** The tablet's regular taskbar: ours, following ZUI's own hiding and stashing. */
        TABLET,
        /** The tablet's desktop-mode taskbar: ZUI's, untouched. */
        TABLET_DESKTOP
    }

    private static final Set<String> SAID = new HashSet<>();

    private TaskbarScope() {
    }

    /** What kind of bar {@code view} is part of; any view of the bar, attached or not. */
    static Kind kind(View view) {
        if (view == null) {
            return Kind.MONITOR;
        }
        int display = TaskbarTray.displayIdOf(view);
        if (display != 0) {
            return Kind.MONITOR;
        }
        String made = desktopClass(view.getContext());
        Kind kind = made != null ? Kind.TABLET_DESKTOP : Kind.TABLET;
        say(kind, made != null ? made : contextName(view.getContext()));
        return kind;
    }

    /**
     * The same, for something that only holds the bar's context or one of its views - ZUI's model
     * callbacks, which say nothing about which bar they feed. True (ours) when no field tells.
     */
    static boolean oursFromFields(Object owner) {
        if (owner == null) {
            return true;
        }
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> type = f.getType();
                if (!View.class.isAssignableFrom(type) && !Context.class.isAssignableFrom(type)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(owner);
                    if (value instanceof View) {
                        return ours((View) value);
                    }
                    if (value instanceof Context) {
                        return ours((Context) value);
                    }
                } catch (Throwable ignored) {
                    // Not readable; the next one may be.
                }
            }
        }
        return true;
    }

    private static boolean ours(Context ctx) {
        int display = 0;
        try {
            android.view.Display d = ctx.getDisplay();
            display = d != null ? d.getDisplayId() : 0;
        } catch (Throwable ignored) {
            // Not tied to a display: taken as the tablet's.
        }
        if (display != 0) {
            return true;
        }
        String made = desktopClass(ctx);
        say(made != null ? Kind.TABLET_DESKTOP : Kind.TABLET,
                made != null ? made : contextName(ctx));
        return made == null;
    }

    /** Whether the module's taskbar features belong on this bar at all. */
    static boolean ours(View view) {
        return kind(view) != Kind.TABLET_DESKTOP;
    }

    /** Whether this is a bar on the tablet's own screen that is ours. */
    static boolean tablet(View view) {
        return kind(view) == Kind.TABLET;
    }

    /** The context's own class, or one it wraps, when it is one of ZUI's desktop-mode ones. */
    private static String desktopClass(Context ctx) {
        for (int i = 0; ctx != null && i < 6; i++) {
            String name = ctx.getClass().getSimpleName();
            if (name.endsWith("Dp")) {
                return name;
            }
            ctx = ctx instanceof ContextWrapper ? ((ContextWrapper) ctx).getBaseContext() : null;
        }
        return null;
    }

    private static String contextName(Context ctx) {
        return ctx == null ? "no context" : ctx.getClass().getSimpleName();
    }

    private static void say(Kind kind, String why) {
        String line = kind + " " + why;
        if (SAID.add(line)) {
            L.i("taskbar scope: display 0 = " + (kind == Kind.TABLET_DESKTOP
                    ? "the tablet's desktop mode (" + why + ") - left to ZUI"
                    : "the tablet's regular taskbar (" + why + ") - ours"));
        }
    }
}
