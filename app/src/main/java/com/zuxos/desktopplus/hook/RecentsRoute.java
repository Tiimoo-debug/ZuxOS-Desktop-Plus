package com.zuxos.desktopplus.hook;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Recents opens on the screen whose button was pressed, full screen, in front of every app.
 *
 * <p>With another app as the tablet's home (Lawnchair, here), ZUI's launcher cannot draw recents
 * inside its home screen, so quickstep starts its fallback, {@code RecentsActivity} - in this
 * process. Nothing tells that activity which screen it is for. Desktop mode then starts it as a
 * small window behind the open apps, and a copy left hidden on the other screen makes the next
 * press "close" recents instead of opening it: it did not respond, or opened minimised and behind.
 *
 * <p>Three layers, each enough on its own if the others miss on some build:
 * <ol>
 *   <li>the taskbar's recents buttons say which screen was pressed ({@link #pressed});</li>
 *   <li>quickstep's launch of the fallback is given that display and full screen, wherever its
 *       launch options pass through {@code SystemUiProxy};</li>
 *   <li>when the fallback appears anyway on the wrong screen, in a window, or behind, it is
 *       corrected there and then.</li>
 * </ol>
 * Everything it decides is written to the log and to the probe's {@code recents} section.
 */
final class RecentsRoute {

    private static final String PROXY = "com.android.quickstep.SystemUiProxy";
    private static final String RECENTS = "com.android.quickstep.RecentsActivity";
    private static final String ZUI_HOME =
            "com.zui.launcher.secondarydisplay.SecondaryDisplayLauncher";

    private static final int FULLSCREEN = 1;
    /** How long a press stays the answer to "which screen". */
    private static final long PRESS_MS = 1500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile int sTarget = Display.DEFAULT_DISPLAY;
    private static volatile long sPressedAt;
    private static Runnable sAgain;
    private static boolean sRetried;
    private static WeakReference<Activity> sLive = new WeakReference<>(null);

    private static final Set<String> SAID = new HashSet<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static String sHooks = "not installed";

    private RecentsRoute() {
    }

    /**
     * A recents button was pressed on {@code display}. {@code again} presses it once more, for
     * when the first press only cleared away a stale copy.
     */
    static void pressed(int display, Runnable again) {
        sTarget = display;
        sPressedAt = SystemClock.uptimeMillis();
        sAgain = again;
        sRetried = false;
        note("pressed on display " + display);
        if (!Cfg.recentsRoute()) {
            return;
        }
        // A copy on another screen would turn this press into "close recents".
        Activity live = sLive.get();
        if (live != null && !live.isFinishing() && displayOf(live) != display) {
            note("closed a copy left on display " + displayOf(live));
            live.finish();
        }
    }

    /** The screen recents is for: the last press, or the tablet for a gesture. */
    private static int target() {
        return SystemClock.uptimeMillis() - sPressedAt < PRESS_MS
                ? sTarget : Display.DEFAULT_DISPLAY;
    }

    private static boolean fresh() {
        return SystemClock.uptimeMillis() - sPressedAt < PRESS_MS;
    }

    static void install(ClassLoader loader) {
        StringBuilder hooks = new StringBuilder();
        Class<?> recents = Reflect.findClass(RECENTS, loader);
        int options = hookLaunch(loader, recents);
        hooks.append("launch options x").append(options);
        if (recents != null) {
            try {
                XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (recents.isInstance(param.thisObject)) {
                            appeared((Activity) param.thisObject);
                        }
                    }
                });
                XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (sLive.get() == param.thisObject) {
                            sLive = new WeakReference<>(null);
                        }
                    }
                });
                hooks.append(", fallback recents watched");
            } catch (Throwable t) {
                hooks.append(", fallback recents not watched (").append(t).append(')');
            }
        } else {
            hooks.append(", no ").append(RECENTS);
        }
        watchZuiHome(loader, hooks);
        sHooks = hooks.toString();
        L.i("recents: " + sHooks);
    }

    /**
     * Steers quickstep's own launch of the fallback: every {@code SystemUiProxy} method that is
     * handed an intent together with launch options, found by shape since names are minified.
     */
    private static int hookLaunch(ClassLoader loader, Class<?> recents) {
        Class<?> proxy = Reflect.findClass(PROXY, loader);
        if (proxy == null) {
            return 0;
        }
        int hooked = 0;
        for (Method m : proxy.getDeclaredMethods()) {
            boolean intent = false;
            boolean opts = false;
            for (Class<?> p : m.getParameterTypes()) {
                intent |= p == Intent.class;
                opts |= p == ActivityOptions.class || p == Bundle.class;
            }
            if (!intent || !opts) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        steer(param.args, m.getName());
                    }
                });
                hooked++;
            } catch (Throwable t) {
                L.d("recents: could not hook " + m + " (" + t + ")");
            }
        }
        return hooked;
    }

    private static void steer(Object[] args, String method) {
        if (!Cfg.recentsRoute()) {
            return;
        }
        Intent intent = null;
        for (Object a : args) {
            if (a instanceof Intent) {
                intent = (Intent) a;
            }
        }
        ComponentName component = intent == null ? null : intent.getComponent();
        if (component == null || !component.getClassName().endsWith("RecentsActivity")) {
            return;
        }
        int display = target();
        for (Object a : args) {
            try {
                if (a instanceof ActivityOptions) {
                    ActivityOptions o = (ActivityOptions) a;
                    o.setLaunchDisplayId(display);
                    Reflect.call(o, "setLaunchWindowingMode", FULLSCREEN);
                } else if (a instanceof Bundle) {
                    Bundle b = (Bundle) a;
                    b.putInt("android.activity.launchDisplayId", display);
                    b.putInt("android.activity.windowingMode", FULLSCREEN);
                }
            } catch (Throwable t) {
                note("could not steer the launch (" + t + ")");
            }
        }
        note("launch via " + method + " sent to display " + display + ", full screen");
    }

    /** The fallback is on screen: make sure it is where it was asked for, full, and in front. */
    private static void appeared(Activity activity) {
        sLive = new WeakReference<>(activity);
        if (!Cfg.recentsRoute()) {
            return;
        }
        int display = displayOf(activity);
        int mode = windowingMode(activity);
        note("appeared on display " + display + ", mode " + mode);
        if (fresh() && display != sTarget) {
            note("was on display " + display + ", not " + sTarget + " - corrected");
            activity.finish();
            Runnable again = sAgain;
            if (!sRetried && again != null) {
                sRetried = true;
                MAIN.postDelayed(again, 250L);
            }
            return;
        }
        int task = activity.getTaskId();
        if (mode != FULLSCREEN && mode != 0) {
            if (fullScreen(task)) {
                note("was in a window (mode " + mode + ") - made full screen");
            }
        }
        // In front: a window started behind the open apps has no focus a moment later.
        MAIN.postDelayed(() -> {
            if (activity.isFinishing() || activity.hasWindowFocus()) {
                return;
            }
            try {
                ActivityManager am = (ActivityManager)
                        activity.getSystemService(Context.ACTIVITY_SERVICE);
                am.moveTaskToFront(task, 0);
                note("was behind the apps - brought to the front");
            } catch (Throwable t) {
                note("could not bring it to the front (" + t + ")");
            }
        }, 180L);
    }

    /** {@code setTaskWindowingMode}, which a recents provider may call. */
    private static boolean fullScreen(int task) {
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object service = atm.getMethod("getService").invoke(null);
            for (Method m : service.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("setTaskWindowingMode") && p.length == 3) {
                    m.invoke(service, task, FULLSCREEN, true);
                    return true;
                }
            }
            note("no setTaskWindowingMode on this build");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            note("full screen refused (" + cause + ")");
        }
        return false;
    }

    /**
     * ZUI's own recents on the monitor's home, which may be what a press there opens instead:
     * only written down for now, so the log says which of the two recents a press reached.
     */
    private static void watchZuiHome(ClassLoader loader, StringBuilder hooks) {
        Class<?> home = Reflect.findClass(ZUI_HOME, loader);
        if (home == null) {
            return;
        }
        int n = 0;
        for (String name : new String[]{"openRecentsView", "toggleRecentsViewVisible",
                "hideRecentsView"}) {
            try {
                n += XposedBridge.hookAllMethods(home, name, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        note("ZUI's monitor recents: " + name);
                    }
                }).size();
            } catch (Throwable ignored) {
                // Not on this build.
            }
        }
        hooks.append(", ZUI monitor recents watched x").append(n);
    }

    private static int displayOf(Activity activity) {
        try {
            Display d = activity.getDisplay();
            return d != null ? d.getDisplayId() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int windowingMode(Activity activity) {
        try {
            Object window = Reflect.field(activity.getResources().getConfiguration(),
                    "windowConfiguration");
            Object mode = Reflect.call(window, "getWindowingMode");
            return mode instanceof Integer ? (Integer) mode : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Logged once per kind of event, and the last few kept for the probe. */
    private static synchronized void note(String what) {
        RECENT.addLast(what);
        while (RECENT.size() > 6) {
            RECENT.removeFirst();
        }
        String kind = what.replaceAll("[0-9]+", "#");
        if (SAID.size() < 40 && SAID.add(kind)) {
            L.i("recents: " + what);
        }
    }

    /** For the probe. */
    static synchronized String describe() {
        StringBuilder sb = new StringBuilder("\nrecents\n");
        sb.append("  route: ").append(Cfg.recentsRoute() ? "on" : "off")
                .append(", hooks: ").append(sHooks).append('\n');
        sb.append("  last press: display ").append(sTarget).append(", ")
                .append(sPressedAt == 0 ? "never"
                        : (SystemClock.uptimeMillis() - sPressedAt) + "ms ago").append('\n');
        Activity live = sLive.get();
        sb.append("  fallback recents: ").append(live == null ? "none"
                : "display " + displayOf(live) + ", mode " + windowingMode(live)
                        + (live.isFinishing() ? ", finishing" : "")).append('\n');
        for (String s : RECENT) {
            sb.append("  - ").append(s).append('\n');
        }
        return sb.toString();
    }
}
