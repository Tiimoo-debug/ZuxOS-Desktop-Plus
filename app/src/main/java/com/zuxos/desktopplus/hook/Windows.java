package com.zuxos.desktopplus.hook;

import android.view.View;

import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Field;
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
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            List<?> list = null;
            try {
                // Present on some builds only; the backing field is the reliable route.
                Object roots = global.getMethod("getRootViews").invoke(instance);
                if (roots instanceof List) {
                    list = (List<?>) roots;
                }
            } catch (Throwable ignored) {
                // Fall through to the field.
            }
            if (list == null) {
                Field views = global.getDeclaredField("mViews");
                views.setAccessible(true);
                Object value = views.get(instance);
                if (value instanceof List) {
                    list = (List<?>) value;
                }
            }
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
}
