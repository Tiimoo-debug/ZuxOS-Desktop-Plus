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
    /** When recents last showed, either kind, and whether it was open when the button went. */
    private static volatile long sShownAt;
    private static long sReopenedAt;
    private static volatile boolean sWasOpen;
    /** Until when every SystemUiProxy call is written down: the trace after a press. */
    private static volatile long sTraceUntil;
    private static final Set<String> TRACED = new HashSet<>();
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
        sWasOpen = isOpenOn(display);
        sTraceUntil = sPressedAt + 3000L;
        synchronized (TRACED) {
            TRACED.clear();
        }
        note("pressed on display " + display + (sWasOpen ? " (recents was open)" : ""));
        if (!Cfg.recentsRoute()) {
            return;
        }
        // A copy left on the monitor would turn a tablet press into "close recents". The
        // tablet's own one is never touched from the monitor: the monitor has its own recents,
        // and closing the tablet's from there left quickstep thinking it was still up.
        Activity live = sLive.get();
        if (live != null && !live.isFinishing() && displayOf(live) != display
                && displayOf(live) != Display.DEFAULT_DISPLAY) {
            note("closed a copy left on display " + displayOf(live));
            live.finish();
        }
    }

    /** The screen recents is for: the last press, or the tablet for a gesture. */
    private static int target() {
        return SystemClock.uptimeMillis() - sPressedAt < PRESS_MS
                ? sTarget : Display.DEFAULT_DISPLAY;
    }

    /** A recents press on the tablet's own taskbar, just now. */
    private static boolean tabletPressed() {
        return fresh() && sTarget == Display.DEFAULT_DISPLAY;
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
        traceAll(proxy);
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
                        if (keepOffTheMonitor(param, m)) {
                            return;
                        }
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

    /**
     * Writes down every SystemUiProxy call in the seconds after a recents press - which is how the
     * next log says what ZUI's own button actually does, instead of anyone guessing.
     */
    private static void traceAll(Class<?> proxy) {
        for (Method m : proxy.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (SystemClock.uptimeMillis() > sTraceUntil) {
                            return;
                        }
                        String call = m.getName() + "(" + summary(param.args) + ")";
                        synchronized (TRACED) {
                            if (TRACED.size() > 16 || !TRACED.add(call)) {
                                return;
                            }
                        }
                        note("trace: SystemUiProxy." + call);
                    }
                });
            } catch (Throwable ignored) {
                // A method that cannot be hooked is simply not traced.
            }
        }
    }

    private static String summary(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (a instanceof Intent) {
                ComponentName c = ((Intent) a).getComponent();
                sb.append("Intent ").append(c != null ? c.getShortClassName() : "?");
            } else if (a instanceof Integer || a instanceof Boolean) {
                sb.append(a);
            } else {
                sb.append(a == null ? "null" : a.getClass().getSimpleName());
            }
        }
        return sb.toString();
    }

    /** Whether recents - either kind - is up on this display now. */
    private static boolean isOpenOn(int display) {
        Activity live = sLive.get();
        if (live != null && !live.isFinishing() && displayOf(live) == display
                && !Boolean.TRUE.equals(Reflect.field(live, "mStopped"))) {
            return true;
        }
        Object zui = com.zuxos.desktopplus.desktop.DesktopHost.activityOn(display);
        return Boolean.TRUE.equals(Reflect.call(zui, "isRecentsViewVisible"));
    }

    private static boolean recentsVisible(Object home) {
        return Boolean.TRUE.equals(Reflect.call(home, "isRecentsViewVisible"));
    }

    /** Quickstep's own recents, started here as a real task of its own on this display. */
    private static void startFallback(Context ctx, int display) {
        try {
            Intent intent = new Intent().setComponent(
                    new ComponentName(ctx.getPackageName(), RECENTS))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(display);
            Reflect.call(options, "setLaunchWindowingMode", FULLSCREEN);
            ctx.startActivity(intent, options.toBundle());
            note("started quickstep's recents ourselves on display " + display);
        } catch (Throwable t) {
            note("could not start quickstep's recents (" + t + ")");
        }
    }

    /**
     * Quickstep sometimes answers a recents request on the tablet by launching into ZUI's home on
     * the monitor ({@code startRecentsActivity(Intent SecondaryDisplayLauncher ...)}): the event
     * log showed the monitor's home raised while the tablet showed nothing. The monitor has its
     * own recents now, so such a launch is only ever the tablet's: it is refused - quickstep takes
     * a failed start as one and cleans up - and the tablet's recents is started on the tablet.
     */
    private static boolean keepOffTheMonitor(XC_MethodHook.MethodHookParam param, Method m) {
        if (!Cfg.recentsRoute()) {
            return false;
        }
        Intent intent = null;
        for (Object a : param.args) {
            if (a instanceof Intent) {
                intent = (Intent) a;
            }
        }
        ComponentName c = intent == null ? null : intent.getComponent();
        if (c == null || !c.getClassName().endsWith("SecondaryDisplayLauncher")) {
            return false;
        }
        // Only for a recents press on the tablet. Going home on the monitor goes through this
        // same launch - blocking that left the monitor's home unanswered and opened an empty
        // recents on the tablet, which is exactly what was seen.
        if (!tabletPressed()) {
            return false;
        }
        Class<?> type = m.getReturnType();
        param.setResult(type == boolean.class ? Boolean.FALSE : null);
        Context ctx = com.zuxos.desktopplus.core.AppCtx.get();
        if (ctx != null) {
            MAIN.post(() -> startFallback(ctx, Display.DEFAULT_DISPLAY));
        }
        note("kept quickstep off the monitor's home - recents opened on the tablet");
        return true;
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
        sShownAt = SystemClock.uptimeMillis();
        if (!Cfg.recentsRoute()) {
            return;
        }
        int display = displayOf(activity);
        int mode = windowingMode(activity);
        note("appeared on display " + display + ", mode " + mode);
        if (display != Display.DEFAULT_DISPLAY && display != -1) {
            // The monitor has the module's recents. A copy of this one there is the tablet's,
            // lost - and while it lives there the tablet's next press only closes it.
            note("closed the tablet's recents found on display " + display
                    + " - opening it on the tablet");
            activity.finish();
            long now = SystemClock.uptimeMillis();
            // Reopened on the tablet only if the tablet's recents was what was pressed; never
            // on its own, or the tablet gets a recents nobody asked for.
            if (tabletPressed() && now - sReopenedAt > 3000L) {
                // Once: if the system put it back on the monitor again, a loop helps nobody.
                sReopenedAt = now;
                Context ctx = activity.getApplicationContext();
                MAIN.postDelayed(() -> startFallback(ctx, Display.DEFAULT_DISPLAY), 300L);
            }
            return;
        }
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
        // No move to the front here: this firmware refuses the launcher REORDER_TASKS, and a
        // recents without focus is only the other screen having it.
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
                        // A call is not proof: only what is actually visible counts.
                        Activity home = (Activity) param.thisObject;
                        boolean visible = recentsVisible(home);
                        int display = displayOf(home);
                        if (visible) {
                            sShownAt = SystemClock.uptimeMillis();
                        }
                        com.zuxos.desktopplus.desktop.DesktopHost.setOverview(display, visible);
                        note("ZUI's recents on display " + display + ": " + name
                                + (visible ? " - visible" : " - not visible"));
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
