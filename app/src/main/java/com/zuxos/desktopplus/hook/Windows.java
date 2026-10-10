package com.zuxos.desktopplus.hook;

import android.view.View;

import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every window this process owns.
 *
 * <p>The launcher is not one view tree. The desktop, the taskbar and the stock app drawer are
 * separate windows added to the same {@code WindowManagerGlobal}, so anything that has to reach
 * across them - dumping the taskbar's classes, closing the stock drawer - starts here.
 */
public final class Windows {

    private Windows() {
    }

    /** The root view of every window in this process, or an empty list if they are unreadable. */
    public static List<View> roots() {
        try {
            List<?> list = liveList();
            if (list == null) {
                return Collections.emptyList();
            }
            // Copied rather than returned live: the framework mutates this list from the UI
            // thread while windows are added and removed.
            List<View> out = new ArrayList<>(list.size());
            for (Object o : new ArrayList<>(list)) {
                if (o instanceof View) {
                    out.add((View) o);
                }
            }
            return out;
        } catch (Throwable t) {
            L.d("window list unreadable: " + t);
            return Collections.emptyList();
        }
    }

    /** Looked up once: this is asked several times a second while the tablet's bar is hidden. */
    private static Object sGlobal;
    private static Method sGetRoots;
    private static Field sViews;
    private static boolean sResolved;

    private static List<?> liveList() throws Exception {
        if (!sResolved) {
            sResolved = true;
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            sGlobal = global.getMethod("getInstance").invoke(null);
            try {
                // Present on some builds only; the backing field is the reliable route.
                sGetRoots = global.getMethod("getRootViews");
            } catch (Throwable ignored) {
                // Fall through to the field.
            }
            try {
                sViews = global.getDeclaredField("mViews");
                sViews.setAccessible(true);
            } catch (Throwable ignored) {
                // The method, then, or nothing.
            }
        }
        if (sGlobal == null) {
            return null;
        }
        if (sGetRoots != null) {
            try {
                Object roots = sGetRoots.invoke(sGlobal);
                if (roots instanceof List) {
                    return (List<?>) roots;
                }
            } catch (Throwable ignored) {
                // The field below.
            }
        }
        if (sViews != null) {
            Object value = sViews.get(sGlobal);
            if (value instanceof List) {
                return (List<?>) value;
            }
        }
        return null;
    }
}
