package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.L;

/**
 * Contexts that are allowed to add an overlay window.
 *
 * <p>A view inside the taskbar has a <em>window context</em> bound to the taskbar's own window
 * type. Asking it to add a window of any other type is refused outright:
 *
 * <pre>Window type mismatch. Window Context's window type is 2024, while LayoutParams' type is
 * set to 2038.</pre>
 *
 * <p>That is what stopped the tray's panel opening. The sanctioned way out is to ask for a
 * context bound to the type we actually want, on the display we actually want it on.
 */
public final class Overlays {

    private Overlays() {
    }

    /**
     * A context that may add a {@code TYPE_APPLICATION_OVERLAY} window on {@code ctx}'s display.
     *
     * <p>Falls back to the application context, which is bound to no window type at all and so
     * accepts any - at the cost of landing on the default display, which is why it is the
     * fallback and not the first choice.
     */
    public static Context windowContext(Context ctx) {
        if (ctx == null) {
            return AppCtx.get();
        }
        try {
            // Both the display lookup and the window context arrived in R, so the check has to
            // come before either of them rather than between them.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Display display = ctx.getDisplay();
                if (display != null) {
                    return ctx.createWindowContext(display,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                }
            }
        } catch (Throwable t) {
            L.d("overlay: no window context for this display (" + t + ")");
        }
        Context app = AppCtx.get();
        return app != null ? app : ctx;
    }

    /** The window manager belonging to {@code ctx}; windows must be removed through the same one. */
    public static WindowManager windowManager(Context ctx) {
        return (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }
}
