package com.zuxos.desktopplus.hook;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.zuxos.desktopplus.core.L;

/**
 * Finds somewhere to put the desktop surface.
 *
 * <p>The obvious answer - {@code android.R.id.content} - is not always available: ZuxOS's
 * {@code SecondaryDisplayLauncher} does not have a content view when it resumes (it installs its
 * own layout later, once the launcher model has loaded), so an attach attempt at that moment
 * finds nothing. Hence a chain of fallbacks, ending in a window of our own on the same display.
 */
public final class SurfaceAttacher {

    /** Where the surface went, and how to undo it. */
    public static final class Target {
        public final ViewGroup container;
        private final boolean mWindowMode;
        private final String mHow;

        private Target(ViewGroup container, boolean windowMode, String how) {
            this.container = container;
            this.mWindowMode = windowMode;
            this.mHow = how;
        }

        public boolean isWindowMode() {
            return mWindowMode;
        }

        public String describe() {
            return mHow;
        }

        public boolean attach(Activity activity, View root) {
            try {
                if (!mWindowMode) {
                    container.addView(root, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    return true;
                }
                activity.getWindowManager().addView(root, windowParams(activity));
                return true;
            } catch (Throwable t) {
                L.e("could not attach the surface (" + mHow + ")", t);
                return false;
            }
        }

        public void detach(Activity activity, View root) {
            try {
                if (!mWindowMode) {
                    if (root.getParent() instanceof ViewGroup) {
                        ((ViewGroup) root.getParent()).removeView(root);
                    }
                    return;
                }
                activity.getWindowManager().removeViewImmediate(root);
            } catch (Throwable t) {
                L.d("detach failed (" + mHow + "): " + t);
            }
        }
    }

    private SurfaceAttacher() {
    }

    /** Best available attach point, or null when the activity has no usable window yet. */
    public static Target resolve(Activity activity) {
        View content = null;
        try {
            content = activity.findViewById(android.R.id.content);
        } catch (Throwable t) {
            L.d("findViewById(content) threw: " + t);
        }
        if (content instanceof ViewGroup) {
            return new Target((ViewGroup) content, false, "content view");
        }

        Window window = activity.getWindow();
        if (window != null) {
            View decor = null;
            try {
                decor = window.peekDecorView();
                if (decor == null) {
                    // Forces the decor to be installed, which is exactly what we want here.
                    decor = window.getDecorView();
                }
            } catch (Throwable t) {
                L.d("getDecorView threw: " + t);
            }
            if (decor instanceof ViewGroup) {
                View inner = decor.findViewById(android.R.id.content);
                if (inner instanceof ViewGroup) {
                    return new Target((ViewGroup) inner, false, "decor content child");
                }
                return new Target((ViewGroup) decor, false, "decor view");
            }
        }

        if (canUseOwnWindow(activity)) {
            return new Target(null, true, "own window");
        }
        return null;
    }

    private static boolean canUseOwnWindow(Activity activity) {
        try {
            return Settings.canDrawOverlays(activity);
        } catch (Throwable t) {
            return false;
        }
    }

    private static WindowManager.LayoutParams windowParams(Activity activity) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("ZuxOS Desktop Plus");
        return lp;
    }

    /** Why an attach attempt failed - printed verbatim into the log. */
    public static String diagnose(Activity activity) {
        StringBuilder sb = new StringBuilder();
        sb.append(activity.getClass().getName());
        try {
            sb.append(" display=").append(activity.getDisplay() != null
                    ? activity.getDisplay().getDisplayId() : "?");
        } catch (Throwable ignored) {
            sb.append(" display=?");
        }
        sb.append(" finishing=").append(activity.isFinishing());
        sb.append(" destroyed=").append(activity.isDestroyed());
        Window window = activity.getWindow();
        sb.append(" window=").append(window == null ? "null" : window.getClass().getSimpleName());
        if (window != null) {
            View peek = null;
            try {
                peek = window.peekDecorView();
            } catch (Throwable ignored) {
                sb.append(" peekDecor=threw");
            }
            sb.append(" decor=").append(peek == null ? "null" : peek.getClass().getSimpleName());
            if (peek instanceof ViewGroup) {
                sb.append(" decorChildren=").append(((ViewGroup) peek).getChildCount());
            }
        }
        sb.append(" canDrawOverlays=").append(canUseOwnWindow(activity));
        return sb.toString();
    }
}
