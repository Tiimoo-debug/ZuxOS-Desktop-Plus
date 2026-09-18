package com.zuxos.desktopplus.core;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Method;

/**
 * Real blur of what is behind a panel - and only what is behind that panel.
 *
 * <p>Every earlier attempt here used {@code FLAG_BLUR_BEHIND}, which blurs everything behind the
 * whole window; with a window the size of the display that is the entire screen, which is why it
 * kept being taken out again. But the launcher's own folders blur only the rectangle they occupy,
 * so the platform plainly has a way, and this is it.
 *
 * <p>Since Android 12 a window's view root can hand out a <em>background blur drawable</em>: a
 * drawable that registers a blur region with the compositor, which then blurs the content behind
 * exactly that drawable - its bounds, its corner radius - and nothing else. It is what
 * {@code Window.setBackgroundBlurRadius} is built on, and it works for any view in any window,
 * which is what makes it usable on the launcher's own taskbar as well as on our own panels.
 *
 * <p>All of it is hidden API, so all of it is reflection, and the first attempt logs exactly what
 * it found. If a build does not have it the callers fall back to plain translucency and nothing
 * else changes.
 */
public final class Blur {

    private static Boolean sAvailable;
    private static boolean sDescribed;

    private Blur() {
    }

    /**
     * A drawable that blurs whatever is behind it, or null where that cannot be had.
     *
     * @param host   a view already attached to the window this belongs to
     * @param radius blur radius in pixels; beyond about 100 the compositor stops going further
     * @param corner corner radius in pixels, so the blur follows the panel's shape
     * @param tint   colour laid over the blur - keep it faint, the blur is the effect
     */
    public static Drawable backdrop(View host, int radius, float corner, int tint) {
        if (host == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        if (!supported(host.getContext())) {
            return null;
        }
        try {
            Object viewRoot = View.class.getMethod("getViewRootImpl").invoke(host);
            if (viewRoot == null) {
                // Not attached yet. The callers ask again from onAttachedToWindow.
                return null;
            }
            Method factory = find(viewRoot.getClass(), "createBackgroundBlurDrawable");
            if (factory == null) {
                report("this build's view root has no createBackgroundBlurDrawable");
                return null;
            }
            Object drawable = factory.getParameterCount() == 1
                    ? factory.invoke(viewRoot, host.getContext())
                    : factory.invoke(viewRoot);
            if (!(drawable instanceof Drawable)) {
                report("createBackgroundBlurDrawable gave back "
                        + (drawable == null ? "null" : drawable.getClass().getName()));
                return null;
            }
            describe(drawable);
            set(drawable, "setBlurRadius", int.class, radius);
            set(drawable, "setCornerRadius", float.class, corner);
            set(drawable, "setColor", int.class, tint);
            sAvailable = Boolean.TRUE;
            return (Drawable) drawable;
        } catch (Throwable t) {
            report("not available (" + t + ")");
            return null;
        }
    }

    /** Whether anything blurs at all: the user, or the device, can switch it off system-wide. */
    public static boolean supported(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return false;
            }
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && !wm.isCrossWindowBlurEnabled()) {
                report("blur is switched off for this device - Developer options, or a battery "
                        + "saver, will be holding it down");
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True once a blur drawable has actually been made, so callers can say which look is live. */
    public static boolean live() {
        return Boolean.TRUE.equals(sAvailable);
    }

    private static Method find(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() <= 1) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static void set(Object target, String name, Class<?> type, Number value) {
        try {
            Method m = target.getClass().getMethod(name, type);
            m.setAccessible(true);
            m.invoke(target, type == int.class ? (Object) value.intValue()
                    : (Object) value.floatValue());
        } catch (Throwable t) {
            L.d("blur: " + name + " is not on this drawable (" + t + ")");
        }
    }

    /** The first one is described in full, so an unfamiliar build can be read from a log. */
    private static void describe(Object drawable) {
        if (sDescribed) {
            return;
        }
        sDescribed = true;
        StringBuilder sb = new StringBuilder();
        for (Method m : drawable.getClass().getDeclaredMethods()) {
            if (m.getName().startsWith("set")) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(m.getName()).append('(').append(m.getParameterCount()).append(')');
            }
        }
        L.i("blur: using " + drawable.getClass().getName() + " [" + sb + "]");
    }

    private static void report(String what) {
        if (!Boolean.FALSE.equals(sAvailable)) {
            sAvailable = Boolean.FALSE;
            L.i("blur: " + what);
        }
    }
}
