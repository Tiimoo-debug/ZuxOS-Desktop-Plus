package com.zuxos.desktopplus.core;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny, failure-tolerant reflection helpers.
 *
 * <p>Everything here returns null / false instead of throwing: we are poking at an OEM
 * launcher whose internals we cannot see from the outside, so "not found" is a normal
 * outcome that the caller is expected to handle.
 */
public final class Reflect {

    private Reflect() {
    }

    public static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object call(Object target, String method, Object... args) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(method) || m.getParameterCount() != args.length) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    public static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                // Try the superclass.
            }
        }
        return null;
    }

    /** Resource entry name of a view id, e.g. {@code workspace}, or null. */
    public static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return null;
        }
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Depth-first search for views whose id entry name equals one of {@code names}. */
    public static List<View> findByIdNames(View root, String... names) {
        List<View> out = new ArrayList<>();
        collect(root, out, names);
        return out;
    }

    private static void collect(View v, List<View> out, String[] names) {
        String id = idName(v);
        if (id != null) {
            for (String n : names) {
                if (id.equalsIgnoreCase(n)) {
                    out.add(v);
                    break;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out, names);
            }
        }
    }

    /** Depth-first search for views whose class name contains one of {@code fragments}. */
    public static List<View> findByClassFragments(View root, String... fragments) {
        List<View> out = new ArrayList<>();
        collectByClass(root, out, fragments);
        return out;
    }

    private static void collectByClass(View v, List<View> out, String[] fragments) {
        String cls = v.getClass().getName();
        for (String f : fragments) {
            if (cls.toLowerCase().contains(f.toLowerCase())) {
                out.add(v);
                break;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectByClass(g.getChildAt(i), out, fragments);
            }
        }
    }
}
