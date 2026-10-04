package com.zuxos.desktopplus.hook;

import android.app.ActivityOptions;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Apps that open on the screen you tapped them on.
 *
 * <p>Everything this module launches is already pinned to a display - {@code AppsRepo},
 * {@code QuickTiles}, {@code TaskbarMenu} and {@code Notifications} all set one. The launcher's own
 * icons go through none of those: ZUI starts them with no display at all, and the system then
 * decides, which is why an app tapped on the external desktop can open on the tablet.
 *
 * <p>So the launch is given the display it was asked for. Not a display we picked - the one the
 * tap happened on, recorded at the tap and used only if it is seconds old. That is what makes this
 * safe in a process that draws the home screen on <em>both</em> screens: a tap on the tablet's own
 * home injects the tablet's display, which is where that app was going anyway.
 *
 * <p>Nothing is overridden. A launch that already names a display keeps it.
 */
final class LaunchDisplay {

    /** How recent a tap has to be to have caused the launch we are looking at. */
    private static final long FRESH_MS = 3000L;

    /** Only used to find out what the platform calls the display key; never launched. */
    private static final int PROBE_DISPLAY = 0x5A58;

    private static boolean sInstalled;
    private static volatile int sTapDisplay = -1;
    private static volatile long sTapAt;
    private static String sKey;
    private static boolean sLookedForKey;
    private static boolean sSaid;

    private LaunchDisplay() {
    }

    static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            // Where a tap happened. Hooked on View itself, the way the drawer's clicks already
            // are, because the launcher's own click handling is minified beyond naming.
            XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        noteTap((View) param.thisObject);
                    }
                }
            });

            int hooked = 0;
            Class<?> apps = android.content.pm.LauncherApps.class;
            for (String name : new String[]{"startMainActivity", "startShortcut",
                    "startAppDetailsActivity"}) {
                hooked += XposedBridge.hookAllMethods(apps, name, OPTIONS).size();
            }
            Class<?> instrumentation = android.app.Instrumentation.class;
            hooked += XposedBridge.hookAllMethods(instrumentation, "execStartActivity",
                    OPTIONS).size();
            L.i("launch display: watching " + hooked + " launch path(s)");
            TaskbarRebind.install(loader);
        } catch (Throwable t) {
            L.e("launch display: could not watch the launch paths", t);
        }
    }

    private static void noteTap(View view) {
        try {
            if (view.getDisplay() == null) {
                return;
            }
            sTapDisplay = view.getDisplay().getDisplayId();
            sTapAt = SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
            // A view with no display attached yet; the next tap will have one.
        }
    }

    /** Puts the display of the last tap into whichever argument carries the launch options. */
    private static final XC_MethodHook OPTIONS = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!Cfg.launchOnTappedDisplay()) {
                return;
            }
            int display = freshTapDisplay();
            if (display < 0) {
                return;
            }
            // By declared type, never by "the argument that happens to be null": these methods
            // take tokens and activities that are routinely null, and putting a Bundle in one of
            // those slots would break every launch in the launcher.
            Class<?>[] types = parameterTypes(param);
            if (types == null) {
                return;
            }
            for (int i = 0; i < types.length && i < param.args.length; i++) {
                if (types[i] != Bundle.class) {
                    continue;
                }
                Bundle with = withDisplay((Bundle) param.args[i], display);
                if (with != null) {
                    param.args[i] = with;
                }
                return;
            }
        }
    };

    private static Class<?>[] parameterTypes(XC_MethodHook.MethodHookParam param) {
        java.lang.reflect.Member member = param.method;
        if (member instanceof java.lang.reflect.Method) {
            return ((java.lang.reflect.Method) member).getParameterTypes();
        }
        if (member instanceof java.lang.reflect.Constructor) {
            return ((java.lang.reflect.Constructor<?>) member).getParameterTypes();
        }
        return null;
    }

    private static int freshTapDisplay() {
        long at = sTapAt;
        int display = sTapDisplay;
        if (display < 0 || at <= 0 || SystemClock.uptimeMillis() - at > FRESH_MS) {
            // Nothing recent enough to blame for this launch. A launch from a notification or a
            // service is not a tap on a screen, and guessing a display for it would be worse than
            // leaving the system to decide.
            return -1;
        }
        return display;
    }

    /**
     * The options with a launch display in them, or null when there is nothing to change.
     *
     * <p>Returns null rather than the same bundle when the caller already named a display: a
     * launch that says where it wants to go is not ours to redirect.
     */
    private static Bundle withDisplay(Bundle options, int display) {
        String key = key();
        if (key == null) {
            return null;
        }
        if (options != null && options.containsKey(key)) {
            return null;
        }
        Bundle out = options == null ? new Bundle() : new Bundle(options);
        out.putInt(key, display);
        if (!sSaid) {
            sSaid = true;
            L.i("launch display: apps will open on the screen they were tapped on (key " + key
                    + ")");
        }
        return out;
    }

    /**
     * What this build of Android calls the launch display in an options bundle.
     *
     * <p>Asked rather than hardcoded: a real {@link ActivityOptions} is given a display nobody
     * would ever use, turned into a bundle, and whichever key came back holding that number is the
     * name. The constant itself is hidden, and a hidden constant guessed wrong would silently do
     * nothing at all.
     */
    private static synchronized String key() {
        if (sLookedForKey) {
            return sKey;
        }
        sLookedForKey = true;
        try {
            Bundle probe = ActivityOptions.makeBasic().setLaunchDisplayId(PROBE_DISPLAY).toBundle();
            for (String name : probe.keySet()) {
                Object value = probe.get(name);
                if (value instanceof Integer && (Integer) value == PROBE_DISPLAY) {
                    sKey = name;
                    return sKey;
                }
            }
            L.w("launch display: this build's options do not carry the display where we can find "
                    + "it, so launches are left alone");
        } catch (Throwable t) {
            L.w("launch display: could not ask what the display key is called (" + t + ")");
        }
        return sKey;
    }
}
