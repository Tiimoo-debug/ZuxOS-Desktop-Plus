package com.zuxos.desktopplus.hook;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Xml;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.hook.system.SystemKeepAlive;
import com.zuxos.desktopplus.hook.taskbar.TaskbarRebind;

import org.xmlpull.v1.XmlPullParser;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * <p>ZUI's own two kill lists are used as well, the ones its system keeps for apps it must never
 * kill: lmkd's, for the kernel's low-memory kills, and its memory cleaner's. The system takes
 * these from any app, without System Framework ticked. ZUI keeps both in files across reboots, so
 * what this added is recorded here too, and taken back when the app leaves the monitor, the
 * switch goes off, or the launcher starts and finds it gone.
 *
 * <p>Only what this added is taken back: an app the user had already exempted, or ZUI had already
 * listed, keeps it when its window closes.
 */
public final class KeepAlive {

    /** The safety-net read: often only when the system's task events cannot be heard. */
    private static final long EVERY_MS = 5000L;
    private static final long EVERY_HEARD_MS = 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** ZUI's lists' binder calls, which write their files in the system: one at a time, here. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zux-desktop-plus-keepalive");
        t.setDaemon(true);
        return t;
    });

    /** ZUI's kill lists, by the name its system gives them: lmkd's, and its memory cleaner's. */
    private static final String[] ZUI_LISTS = {"1", "2"};
    private static final String[] ZUI_LIST_NAMES = {"lmkd", "memory cleaner"};
    private static final int CLEANER = 1;
    /** The firmware's switch for each: with it off, the system refuses the list. */
    private static final String[] ZUI_LIST_FEATURES = {"ZuiLmkWhiteList",
            "ZuiMemoryAcceleration"};
    /**
     * The memory cleaner's own never-kill apps, built into the firmware. The system's list holds
     * them with the ones added, and shows only the added: these are never ours to take off.
     */
    private static final String CLEANER_CONFIG = "/system/etc/ZuiMemCleanerConfig.xml";
    /** The launcher's own file: what this put in ZUI's lists, one set per list. */
    private static final String RECORD = "zux_keep_alive";
    private static final String KEY_RECORD = "zui_list_";

    private static boolean sStarted;
    private static Context sCtx;
    /** On the monitor right now. */
    private static final Set<String> PROTECTED = new LinkedHashSet<>();
    /** Exempted from battery optimisation by us, to be taken back. */
    private static final Set<String> ADDED = new LinkedHashSet<>();
    /** ZUI's lists were put in line with the record since the launcher started. */
    private static boolean sListsSynced;
    /** This build has no such lists: tried once, and left. */
    private static volatile boolean sListsMissing;
    /** {@link #CLEANER_CONFIG}'s apps, read once on the keep-alive thread. */
    private static Set<String> sCleanerOwn;

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
            update(Cfg.keepAlive());
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
            update(Cfg.keepAlive());
        } catch (Throwable t) {
            L.d("keep alive: " + t);
        }
    };

    /**
     * The apps on the monitor protected, and those that left it let go. With the switch off,
     * everything this protected is let go once, and then nothing is read until it is on again.
     */
    private static void update(boolean on) {
        Context ctx = sCtx;
        if (!on && PROTECTED.isEmpty() && sListsSynced) {
            return;
        }
        Set<String> now = new LinkedHashSet<>();
        if (on) {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
                int d = Tasks.displayOf(task);
                if (d == Tasks.UNKNOWN || d == 0) {
                    continue;
                }
                String pkg = Tasks.packageOf(task);
                if (pkg != null && !pkg.equals(ctx.getPackageName())) {
                    now.add(pkg);
                }
            }
        }
        boolean changed = !now.equals(PROTECTED);
        if (changed || !sListsSynced) {
            syncZuiLists(now);
        }
        if (!changed) {
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

    /**
     * ZUI's lists brought in line with {@code want}, off the main thread: what is on the monitor
     * and not yet listed is added, what this added and is no longer there is removed. An app
     * already on a list - put there by ZUI or the user - is left as it is, and never removed.
     */
    private static void syncZuiLists(Set<String> want) {
        sListsSynced = true;
        if (sListsMissing) {
            return;
        }
        Set<String> wanted = new LinkedHashSet<>(want);
        IO.execute(() -> {
            try {
                Object am = activityManager();
                Method get = am.getClass().getMethod("getZmcLmkWhiteList", String.class);
                Method add = am.getClass().getMethod("addZmcLmkWhiteList", List.class,
                        String.class);
                Method remove = am.getClass().getMethod("removeZmcLmkWhiteList", List.class,
                        String.class);
                SharedPreferences record = sCtx.getSharedPreferences(RECORD,
                        Context.MODE_PRIVATE);
                int answered = 0;
                for (int i = 0; i < ZUI_LISTS.length; i++) {
                    if (Boolean.FALSE.equals(zuiFeature(ZUI_LIST_FEATURES[i]))) {
                        continue;
                    }
                    answered++;
                    Set<Object> held = new HashSet<>();
                    List<?> listed = (List<?>) get.invoke(am, ZUI_LISTS[i]);
                    if (listed != null) {
                        // Null for lmkd's until its first app: the list has no file before.
                        held.addAll(listed);
                    }
                    if (i == CLEANER) {
                        held.addAll(cleanerOwn());
                    }
                    String key = KEY_RECORD + ZUI_LISTS[i];
                    Set<String> ours = new LinkedHashSet<>(
                            record.getStringSet(key, Collections.emptySet()));
                    List<String> gain = new ArrayList<>();
                    for (String pkg : wanted) {
                        if (!ours.contains(pkg) && !held.contains(pkg)) {
                            gain.add(pkg);
                        }
                    }
                    List<String> lose = new ArrayList<>();
                    for (String pkg : ours) {
                        if (!wanted.contains(pkg)) {
                            lose.add(pkg);
                        }
                    }
                    if (gain.isEmpty() && lose.isEmpty()) {
                        continue;
                    }
                    if (!gain.isEmpty()) {
                        // Recorded first: a launcher gone between the two leaves at most a name
                        // to take back, never one forgotten on ZUI's list.
                        ours.addAll(gain);
                        record.edit().putStringSet(key, new LinkedHashSet<>(ours)).commit();
                        // A copy: the system drops from the list it is given what it already has.
                        add.invoke(am, new ArrayList<>(gain), ZUI_LISTS[i]);
                    }
                    if (!lose.isEmpty()) {
                        remove.invoke(am, new ArrayList<>(lose), ZUI_LISTS[i]);
                        ours.removeAll(lose);
                        record.edit().putStringSet(key, new LinkedHashSet<>(ours)).commit();
                    }
                    L.i("keep alive: ZUI's " + ZUI_LIST_NAMES[i] + " list"
                            + (gain.isEmpty() ? "" : ", added " + gain)
                            + (lose.isEmpty() ? "" : ", removed " + lose));
                }
                Health.hooked("keep-alive: ZUI's kill lists", answered);
            } catch (NoSuchMethodException e) {
                sListsMissing = true;
                Health.hooked("keep-alive: ZUI's kill lists", 0);
                L.i("keep alive: ZUI's kill lists are not in this build (" + e.getMessage()
                        + "), root and the system half only");
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null ? t.getCause() : t;
                L.w("keep alive: ZUI's kill lists refused (" + cause + ")");
            }
        });
    }

    private static Object activityManager() throws Exception {
        return Class.forName("android.app.ActivityManager").getMethod("getService")
                .invoke(null);
    }

    private static Set<String> cleanerOwn() {
        if (sCleanerOwn != null) {
            return sCleanerOwn;
        }
        Set<String> own = new HashSet<>();
        try (FileInputStream in = new FileInputStream(CLEANER_CONFIG)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                    event = parser.next()) {
                if (event == XmlPullParser.START_TAG
                        && ("PermanentPackageName".equals(parser.getName())
                        || "WhiteListProcessName".equals(parser.getName()))) {
                    own.add(parser.nextText().trim());
                }
            }
        } catch (Throwable t) {
            L.d("keep alive: " + CLEANER_CONFIG + " unreadable (" + t + ")");
        }
        sCleanerOwn = own;
        return own;
    }

    /** One of ZUI's firmware switches; null when they cannot be read. */
    private static Boolean zuiFeature(String name) {
        try {
            Object on = Class.forName("com.lgsi.config.LgsiFeatures")
                    .getMethod("enabled", String.class).invoke(null, name);
            return on instanceof Boolean ? (Boolean) on : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static synchronized String describe() {
        String system = systemState();
        return "\nkeep alive\n  root: " + (sStarted ? "on" : "not started")
                + ", protecting " + PROTECTED + "\n  system: "
                + (system == null || system.isEmpty()
                ? "off (tick System Framework for the module in LSPosed, then reboot)"
                : system) + "\n" + describeZuiLists();
    }

    /** What ZUI's two lists hold now, and which of those this put there. */
    private static String describeZuiLists() {
        StringBuilder sb = new StringBuilder();
        try {
            Object am = activityManager();
            Method get = am.getClass().getMethod("getZmcLmkWhiteList", String.class);
            SharedPreferences record = sCtx == null ? null
                    : sCtx.getSharedPreferences(RECORD, Context.MODE_PRIVATE);
            for (int i = 0; i < ZUI_LISTS.length; i++) {
                Object held = get.invoke(am, ZUI_LISTS[i]);
                Set<String> ours = record == null ? Collections.emptySet()
                        : record.getStringSet(KEY_RECORD + ZUI_LISTS[i],
                        Collections.emptySet());
                String list = Boolean.FALSE.equals(zuiFeature(ZUI_LIST_FEATURES[i]))
                        ? "off in this firmware" : held == null ? "empty" : String.valueOf(held);
                if (list.length() > 1500) {
                    list = list.substring(0, 1500) + "...";
                }
                sb.append("  ZUI's ").append(ZUI_LIST_NAMES[i]).append(" list: ").append(list)
                        .append("\n    added by us: ").append(ours).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  ZUI's lists: unreadable (").append(t).append(")\n");
        }
        return sb.toString();
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
