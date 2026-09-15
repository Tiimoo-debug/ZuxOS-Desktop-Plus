package com.zuxos.desktopplus.core;

import android.content.Context;

/**
 * The hooked app's context, for code that runs outside an activity.
 *
 * <p>The taskbar and its app drawer are not activities - they are windows the launcher adds
 * directly - so hooks there have no activity to borrow a context from.
 */
public final class AppCtx {

    private static Context sCtx;

    private AppCtx() {
    }

    public static void set(Context ctx) {
        if (sCtx == null && ctx != null) {
            sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
    }

    public static Context get() {
        if (sCtx != null) {
            return sCtx;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sCtx = (Context) app;
            }
        } catch (Throwable t) {
            L.d("no application context available: " + t);
        }
        return sCtx;
    }
}
