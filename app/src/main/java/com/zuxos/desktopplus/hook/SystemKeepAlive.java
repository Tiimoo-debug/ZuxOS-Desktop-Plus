package com.zuxos.desktopplus.hook;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: apps with a window on an external display are not killed to free memory or
 * tidy the background. Only the user closes them.
 *
 * <p>Two places decide that an app may die. Its importance - the "oom adj" - which the low-memory
 * killer ranks victims by: for these apps it is held at perceptible (200), where a playing music
 * app sits, so they go only under the most extreme pressure. And the framework's own clean-ups,
 * which kill empty and surplus cached processes by name: those few reasons are refused for these
 * apps. Everything the user does - force stop, closing the window, uninstalling - is untouched.
 *
 * <p>An app whose activity has gone but whose window (task) is still on the monitor stays
 * protected: alive, in the background, until the window is closed.
 *
 * <p>This runs in system_server, so every hook is wrapped, and repeated failure switches the whole
 * thing off for the boot rather than risk the system.
 */
final class SystemKeepAlive {

    /** Where this half says how it is doing, for the launcher's probe to read. */
    static final String PROPERTY = "sys.zuxos.keepalive";

    /** Perceptible: the low-memory killer's last resort among live apps. */
    private static final int HOLD_ADJ = 200;
    private static final long REFRESH_MS = 3000L;
    private static final long FIRST_MS = 20000L;
    private static final int MOST_ERRORS = 20;

    /** The framework's own clean-ups - never a user's request. */
    private static final String[] AUTOMATIC = {"empty #", "cached #", "trim empty",
            "excessive cpu", "too many cached", "isolated not needed", "swap low"};

    private static volatile Set<Integer> sUids = Collections.emptySet();
    private static volatile boolean sOff;
    private static int sErrors;
    private static int sHeld;
    private static int sSpared;
    private static boolean sInstalled;

    private SystemKeepAlive() {
    }

    static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            if (!Cfg.keepAlive()) {
                L.i("system keep-alive: off in settings");
                return;
            }
        } catch (Throwable ignored) {
            // Settings unreadable from here: on, as it defaults.
        }
        int adj = 0;
        int kills = 0;
        try {
            Class<?> state = Class.forName("com.android.server.am.ProcessStateRecord", false,
                    loader);
            for (Method m : state.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("setCurAdj") && p.length == 1 && p[0] == int.class) {
                    XposedBridge.hookMethod(m, CUR_ADJ);
                    adj++;
                }
            }
        } catch (Throwable t) {
            L.i("system keep-alive: no ProcessStateRecord.setCurAdj (" + t + ")");
        }
        try {
            Class<?> list = Class.forName("com.android.server.am.ProcessList", false, loader);
            for (Method m : list.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("setOomAdj") && p.length == 3 && p[0] == int.class
                        && p[1] == int.class && p[2] == int.class) {
                    XposedBridge.hookMethod(m, SET_OOM_ADJ);
                    adj++;
                }
            }
        } catch (Throwable t) {
            L.i("system keep-alive: no ProcessList.setOomAdj (" + t + ")");
        }
        try {
            Class<?> record = Class.forName("com.android.server.am.ProcessRecord", false, loader);
            for (Method m : record.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("killLocked") && p.length >= 1 && p[0] == String.class) {
                    XposedBridge.hookMethod(m, KILL);
                    kills++;
                }
            }
        } catch (Throwable t) {
            L.i("system keep-alive: no ProcessRecord.killLocked (" + t + ")");
        }
        L.i("system keep-alive: protecting apps on external displays (adj x" + adj
                + ", kills x" + kills + ")");
        if (adj + kills == 0) {
            return;
        }
        HandlerThread thread = new HandlerThread("zux-keepalive");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sOff) {
                    publish("off after errors");
                    return;
                }
                refresh();
                handler.postDelayed(this, REFRESH_MS);
            }
        }, FIRST_MS);
    }

    /** The importance the system is about to give a process. */
    private static final XC_MethodHook CUR_ADJ = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sOff || sUids.isEmpty()) {
                return;
            }
            try {
                int adj = (Integer) param.args[0];
                if (adj <= HOLD_ADJ) {
                    return;
                }
                Object app = fieldOf(param.thisObject, "mApp", APP);
                Object uid = app == null ? null : fieldOf(app, "uid", UID);
                if (uid instanceof Integer && sUids.contains(uid)) {
                    param.args[0] = HOLD_ADJ;
                    sHeld++;
                }
            } catch (Throwable t) {
                failed(t);
            }
        }
    };

    /** The importance going to the low-memory killer. */
    private static final XC_MethodHook SET_OOM_ADJ = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sOff || sUids.isEmpty()) {
                return;
            }
            try {
                int uid = (Integer) param.args[1];
                int amt = (Integer) param.args[2];
                if (amt > HOLD_ADJ && sUids.contains(uid)) {
                    param.args[2] = HOLD_ADJ;
                    sHeld++;
                }
            } catch (Throwable t) {
                failed(t);
            }
        }
    };

    /** A clean-up kill of a protected app is refused; anything a user asked for goes ahead. */
    private static final XC_MethodHook KILL = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sOff || sUids.isEmpty()) {
                return;
            }
            try {
                Object reason = param.args[0];
                if (!(reason instanceof String) || !automatic((String) reason)) {
                    return;
                }
                Object uid = fieldOf(param.thisObject, "uid", UID);
                if (uid instanceof Integer && sUids.contains(uid)) {
                    param.setResult(null);
                    if (sSpared++ < 20) {
                        L.i("system keep-alive: spared uid " + uid + " from '" + reason + "'");
                    }
                }
            } catch (Throwable t) {
                failed(t);
            }
        }
    };

    /** Fields read on the system's hot path: looked up once, not on every call. */
    private static final java.lang.reflect.Field[] APP = new java.lang.reflect.Field[1];
    private static final java.lang.reflect.Field[] UID = new java.lang.reflect.Field[1];

    private static Object fieldOf(Object target, String name, java.lang.reflect.Field[] cache)
            throws IllegalAccessException {
        java.lang.reflect.Field f = cache[0];
        if (f == null || !f.getDeclaringClass().isInstance(target)) {
            f = null;
            for (Class<?> c = target.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(name);
                    f.setAccessible(true);
                } catch (NoSuchFieldException ignored) {
                    // The superclass, then.
                }
            }
            if (f == null) {
                return null;
            }
            cache[0] = f;
        }
        return f.get(target);
    }

    private static boolean automatic(String reason) {
        String r = reason.toLowerCase();
        for (String a : AUTOMATIC) {
            if (r.startsWith(a) || r.contains(a)) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void failed(Throwable t) {
        if (++sErrors == 1) {
            L.e("system keep-alive: a hook failed", t);
        }
        if (sErrors >= MOST_ERRORS) {
            sOff = true;
            sUids = Collections.emptySet();
            L.w("system keep-alive: switched off after " + sErrors + " errors");
        }
    }

    /** Which apps have a window on an external display, from the system's own task list. */
    private static void refresh() {
        try {
            Context ctx = systemContext();
            if (ctx == null) {
                return;
            }
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            Set<Integer> uids = new HashSet<>();
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
                Object d = Reflect.field(task, "displayId");
                if (!(d instanceof Integer) || (Integer) d == 0) {
                    continue;
                }
                int uid = uidOf(ctx, task);
                if (uid >= 10000) {
                    // Apps only: the system and the launcher look after themselves.
                    uids.add(uid);
                }
            }
            if (!uids.equals(sUids)) {
                sUids = Collections.unmodifiableSet(uids);
                L.i("system keep-alive: protecting " + uids.size() + " app(s) on the monitor");
            }
            publish("on, " + uids.size() + " app(s), adj held " + sHeld + "x, kills refused "
                    + sSpared + "x");
        } catch (Throwable t) {
            failed(t);
        }
    }

    private static int uidOf(Context ctx, ActivityManager.RunningTaskInfo task) {
        Object effective = Reflect.field(task, "effectiveUid");
        if (effective instanceof Integer && (Integer) effective > 0) {
            return (Integer) effective;
        }
        String pkg = TaskbarApps.packageOf(task);
        if (pkg == null) {
            return -1;
        }
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Context systemContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object current = thread.getMethod("currentActivityThread").invoke(null);
            return current == null ? null
                    : (Context) thread.getMethod("getSystemContext").invoke(current);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void publish(String state) {
        try {
            Class.forName("android.os.SystemProperties")
                    .getMethod("set", String.class, String.class).invoke(null, PROPERTY, state);
        } catch (Throwable ignored) {
            // Only for the probe; the protection does not depend on it.
        }
    }
}
