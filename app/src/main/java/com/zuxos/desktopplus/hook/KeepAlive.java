package com.zuxos.desktopplus.hook;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.system.SystemKeepAlive;
import com.zuxos.desktopplus.hook.taskbar.TaskbarApps;
import com.zuxos.desktopplus.hook.taskbar.TaskbarRebind;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Apps open on the monitor are the user's to close.
 *
 * <p>This is the launcher's half, through root, and it works without anything else enabled: every
 * app with a window on an external display is taken out of battery optimisation, allowed to run
 * in the background and put in the active standby bucket - the restrictions under which the
 * system and ZUI's power saving stop or kill apps that are not in front. What it cannot stop is a
 * kill for memory; that is {@link SystemKeepAlive}, which runs inside the system once the user
 * ticks System Framework for the module in LSPosed.
 *
 * <p>Only what this added is taken back: an app the user had already exempted keeps it when its
 * window closes.
 */
public final class KeepAlive {

    /** The safety-net read: often only when the system's task events cannot be heard. */
    private static final long EVERY_MS = 5000L;
    private static final long EVERY_HEARD_MS = 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean sStarted;
    private static Context sCtx;
    /** On the monitor right now. */
    private static final Set<String> PROTECTED = new LinkedHashSet<>();
    /** Exempted from battery optimisation by us, to be taken back. */
    private static final Set<String> ADDED = new LinkedHashSet<>();

    private KeepAlive() {
    }

    public static synchronized void start(Context ctx) {
        if (sStarted || ctx == null) {
            return;
        }
        sStarted = true;
        sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        MAIN.postDelayed(KeepAlive::tick, 2000L);
    }

    private static void tick() {
        try {
            if (Cfg.keepAlive()) {
                update();
            }
        } catch (Throwable t) {
            L.d("keep alive: " + t);
        }
        MAIN.postDelayed(KeepAlive::tick,
                TaskbarRebind.hearsTasks() ? EVERY_HEARD_MS : EVERY_MS);
    }

    /**
     * An app opened, closed or moved: the set to protect is read again in a moment, once the
     * move has settled - rather than on a five-second poll of every task.
     */
    public static void soon() {
        if (!sStarted) {
            return;
        }
        MAIN.removeCallbacks(SOON);
        MAIN.postDelayed(SOON, 700L);
    }

    private static final Runnable SOON = () -> {
        try {
            if (Cfg.keepAlive()) {
                update();
            }
        } catch (Throwable t) {
            L.d("keep alive: " + t);
        }
    };

    private static void update() {
        Context ctx = sCtx;
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> now = new LinkedHashSet<>();
        for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
            Object d = Reflect.field(task, "displayId");
            if (!(d instanceof Integer) || (Integer) d == 0) {
                continue;
            }
            String pkg = TaskbarApps.packageOf(task);
            if (pkg != null && !pkg.equals(ctx.getPackageName())) {
                now.add(pkg);
            }
        }
        if (now.equals(PROTECTED)) {
            return;
        }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        StringBuilder cmd = new StringBuilder();
        for (String pkg : now) {
            if (PROTECTED.contains(pkg)) {
                continue;
            }
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                cmd.append("cmd deviceidle whitelist +").append(pkg).append(" >/dev/null; ");
                ADDED.add(pkg);
            }
            cmd.append("cmd appops set ").append(pkg)
                    .append(" RUN_ANY_IN_BACKGROUND allow; ")
                    .append("am set-standby-bucket ").append(pkg).append(" active; ");
        }
        for (String pkg : PROTECTED) {
            if (now.contains(pkg)) {
                continue;
            }
            if (ADDED.remove(pkg)) {
                cmd.append("cmd deviceidle whitelist -").append(pkg).append(" >/dev/null; ");
            }
            // Its background allowance back to the system's own, and its standby bucket left to
            // the system again: off the monitor it is an app like any other, and an app the user
            // had restricted stays restricted.
            cmd.append("cmd appops set ").append(pkg)
                    .append(" RUN_ANY_IN_BACKGROUND default; ")
                    .append("am set-standby-bucket ").append(pkg).append(" working_set; ");
        }
        PROTECTED.clear();
        PROTECTED.addAll(now);
        if (cmd.length() > 0) {
            KeyShell.run(cmd.toString());
        }
        L.i("keep alive: protecting " + now.size() + " app(s) on the monitor (root)"
                + (now.isEmpty() ? "" : ": " + now));
    }

    static synchronized String describe() {
        String system = systemState();
        return "\nkeep alive\n  root: " + (sStarted ? "on" : "not started")
                + ", protecting " + PROTECTED + "\n  system: "
                + (system == null || system.isEmpty()
                ? "off (tick System Framework for the module in LSPosed, then reboot)"
                : system) + "\n";
    }

    /** What the system half last reported, through its property. */
    private static String systemState() {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, SystemKeepAlive.PROPERTY);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
