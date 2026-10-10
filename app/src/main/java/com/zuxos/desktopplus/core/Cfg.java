package com.zuxos.desktopplus.core;

import com.zuxos.desktopplus.core.theme.Theme;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Read-only view of the module settings from inside a hooked process.
 *
 * <p>Values are re-read whenever the backing file changes, so toggling something in the
 * settings app takes effect the next time the desktop is (re)attached - no reboot needed.
 */
public final class Cfg {

    private static XSharedPreferences sPrefs;
    private static boolean sUnavailable;
    private static long sLastCheck;

    private Cfg() {
    }

    private static synchronized XSharedPreferences prefs() {
        if (sUnavailable) {
            return null;
        }
        try {
            if (sPrefs == null) {
                sPrefs = new XSharedPreferences(Const.MODULE_PKG, Const.PREFS);
                sPrefs.makeWorldReadable();
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sLastCheck > 500) {
                sLastCheck = now;
                if (sPrefs.hasFileChanged()) {
                    sPrefs.reload();
                    sSnapshotDue = true;
                }
                if (!sReadable) {
                    // Locked until the first unlock: read again until it opens, rather than
                    // trusting the file to say it changed.
                    sPrefs.reload();
                    sSnapshotDue = true;
                }
                if (sSnapshotDue) {
                    // Checked here, twice a second at most, not on every read.
                    sReadable = checkReadable(sPrefs);
                }
                if (sSnapshotDue && sReadable) {
                    sSnapshotDue = false;
                    snapshot(sPrefs);
                }
            }
            return sPrefs;
        } catch (Throwable t) {
            // No LSPosed prefs bridge - fall back to defaults rather than failing.
            sUnavailable = true;
            return null;
        }
    }

    public static void reload() {
        XSharedPreferences p = prefs();
        if (p != null) {
            try {
                p.reload();
            } catch (Throwable ignored) {
                // Keep the last known values.
            }
        }
        L.setDebug(getBool(Const.KEY_DEBUG, false));
    }

    public static boolean getBool(String key, boolean def) {
        XSharedPreferences p = prefs();
        if (!readable(p)) {
            String v = remembered(key);
            return v != null && v.startsWith("b:") ? Boolean.parseBoolean(v.substring(2)) : def;
        }
        try {
            return p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static int getInt(String key, int def) {
        XSharedPreferences p = prefs();
        if (!readable(p)) {
            String v = remembered(key);
            try {
                return v != null && v.startsWith("i:") ? Integer.parseInt(v.substring(2)) : def;
            } catch (NumberFormatException e) {
                return def;
            }
        }
        try {
            return p.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static String getString(String key, String def) {
        XSharedPreferences p = prefs();
        if (!readable(p)) {
            String v = remembered(key);
            return v != null && v.startsWith("s:") ? v.substring(2) : def;
        }
        try {
            return p.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    // --- the settings before the first unlock -------------------------------------------------

    /*
     * The settings file lives in the module's own storage, which stays locked from boot until the
     * first unlock - while the launcher, which starts before that, is already building its bars.
     * Every setting then read as its default, and "only open apps" is off by default: ZUI's own
     * recents and suggestions were let onto the bar for a moment after every boot. So the last
     * settings read are kept in the launcher's device-protected storage, readable from boot, and
     * stand in until the file can be read.
     */

    private static final String SNAPSHOT = "zux_desktop_plus_settings.properties";
    private static boolean sSnapshotDue = true;
    private static java.util.Properties sRemembered;
    private static String sLastSaved;

    private static volatile boolean sReadable;

    /** Whether the settings file can be read yet - as last checked. */
    private static boolean readable(XSharedPreferences p) {
        return p != null && sReadable;
    }

    private static boolean checkReadable(XSharedPreferences p) {
        try {
            return p.getFile().canRead() && !p.getAll().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Where the copy is kept: the hooked app's device-protected files - never in the system
     * process, which has no data directory of its own (asking for one throws, and an uncaught
     * throw there is a boot loop). Null wherever it cannot be had; nothing here may throw.
     */
    private static java.io.File snapshotFile() {
        try {
            if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID) {
                return null;
            }
            android.content.Context ctx = AppCtx.get();
            if (ctx == null || "android".equals(ctx.getPackageName())) {
                return null;
            }
            java.io.File dir = ctx.createDeviceProtectedStorageContext().getFilesDir();
            return dir == null ? null : new java.io.File(dir, SNAPSHOT);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A setting as last read from the file, typed by its prefix; null when never read. */
    private static synchronized String remembered(String key) {
        if (sRemembered == null) {
            java.io.File f = snapshotFile();
            if (f == null) {
                // Asked again next time: the app's context may not be there yet.
                return null;
            }
            java.util.Properties props = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                props.load(in);
            } catch (Throwable ignored) {
                // None yet: defaults, as before.
            }
            sRemembered = props;
        }
        return sRemembered.getProperty(key);
    }

    /** Keeps what the file says now, written only when it changed - a settings change. */
    private static void snapshot(XSharedPreferences p) {
        java.util.Properties props = new java.util.Properties();
        try {
            for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
                Object v = e.getValue();
                String typed = v instanceof Boolean ? "b:" + v : v instanceof Integer ? "i:" + v
                        : v instanceof String ? "s:" + v : null;
                if (typed != null) {
                    props.setProperty(e.getKey(), typed);
                }
            }
        } catch (Throwable t) {
            return;
        }
        String flat = new java.util.TreeMap<>(props).toString();
        if (flat.equals(sLastSaved)) {
            return;
        }
        sLastSaved = flat;
        synchronized (Cfg.class) {
            sRemembered = props;
        }
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID) {
            // The system process keeps no copy: it has nowhere to keep one.
            return;
        }
        // Off the caller's thread: a settings read must never wait on a disk write. Nothing in
        // it may escape - an uncaught throw on a thread takes the whole process down.
        new Thread(() -> {
            try {
                java.io.File f = snapshotFile();
                if (f == null) {
                    return;
                }
                java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    props.store(out, null);
                }
                if (!tmp.renameTo(f)) {
                    tmp.delete();
                }
            } catch (Throwable ignored) {
                // No copy this time; the settings themselves are unaffected.
            }
        }, "zux-settings-snapshot").start();
    }

    // --- Typed accessors used by the hooks -------------------------------

    public static boolean enabled() {
        return getBool(Const.KEY_ENABLED, true);
    }

    public static int displayMode() {
        return getInt(Const.KEY_DISPLAY_MODE, Const.DISPLAY_EXTERNAL);
    }

    public static int takeover() {
        return getInt(Const.KEY_TAKEOVER, Const.TAKEOVER_GRID);
    }

    public static boolean attachAnyActivity() {
        return getBool(Const.KEY_ATTACH_ANY, false);
    }

    public static int cellSizeDp() {
        return getInt(Const.KEY_CELL_SIZE, 104);
    }

    public static int iconSizeDp() {
        return getInt(Const.KEY_ICON_SIZE, 52);
    }

    public static boolean showLabels() {
        return getBool(Const.KEY_SHOW_LABELS, true);
    }

    public static boolean labelShadow() {
        return getBool(Const.KEY_LABEL_SHADOW, true);
    }

    public static boolean widgetsEnabled() {
        return getBool(Const.KEY_WIDGETS_ENABLED, true);
    }

    public static boolean foldersEnabled() {
        return getBool(Const.KEY_FOLDERS_ENABLED, true);
    }

    public static boolean drawerButton() {
        // Off by default: the stock taskbar sits on top of it.
        return getBool(Const.KEY_DRAWER_BUTTON, false);
    }

    public static boolean catchPinnedShortcuts() {
        return getBool(Const.KEY_CATCH_PINS, true);
    }

    public static boolean pages() {
        return getBool(Const.KEY_PAGES, true);
    }

    /**
     * Dots under the desktop for its pages.
     *
     * <p>Off by default: the arrows at the sides already say there is another page, and dots at
     * the bottom of the screen sit just above the taskbar, where they read as part of it.
     */
    public static boolean pageDots() {
        return getBool(Const.KEY_PAGE_DOTS, false);
    }

    public static boolean glass() {
        return getBool(Const.KEY_GLASS, true);
    }

    public static boolean animations() {
        return getBool(Const.KEY_ANIMATIONS, true);
    }

    /** Glass everywhere until asked: Retro is the monitor's, and only by choice. */
    public static int theme() {
        return getInt(Const.KEY_THEME, Theme.GLASS_ID);
    }

    /** The monitor's taskbar at the bottom, where ZUI puts it, until asked. */
    public static int taskbarEdge() {
        return getInt(Const.KEY_TASKBAR_EDGE, Const.EDGE_BOTTOM);
    }

    public static boolean nativeDrawer() {
        return getBool(Const.KEY_NATIVE_DRAWER, true);
    }

    public static int drawerSort() {
        return getInt(Const.KEY_DRAWER_SORT, Const.SORT_CUSTOM);
    }

    /**
     * Hold an app in the stock drawer to drag it out.
     *
     * <p>On by default, but a setting all the same: holding an icon is the launcher's own gesture
     * for its own popup, and taking it over is the sort of thing somebody will want to undo.
     */
    public static boolean drawerDrag() {
        return getBool(Const.KEY_DRAWER_DRAG, true);
    }

    /**
     * Put the launcher's drawer button at the left of the bar.
     *
     * <p>On by default: ZUI leaves it in the middle of the icon cluster, where open apps end up on
     * both sides of it, and no desktop has kept that button anywhere but a corner in thirty years.
     */
    public static boolean startButtonLeft() {
        return getBool(Const.KEY_START_LEFT, true);
    }

    /**
     * Keep ZUI's recent and recommended apps off the taskbar entirely.
     *
     * <p>On by default, and only does anything with "Only open apps in the taskbar" on, where our
     * own row shows what is open: ZUI's call that adds its recents is skipped, and any icon of its
     * that arrives another way is hidden as it is added. The key keeps its old name so an
     * existing choice carries over.
     */
    public static boolean hideRecommendedFlash() {
        return getBool(Const.KEY_HIDE_RECENTS_FLASH, true);
    }

    /**
     * The external taskbar's back, home and recents act on the external screen.
     *
     * <p>On by default: ZUI hands those keys to the system, which acts on whichever screen it
     * last thought was in front - so back on the monitor closed an app on the tablet. Fixing a
     * plain bug is not something to make anybody opt into.
     */
    public static boolean navKeysOwnScreen() {
        return getBool(Const.KEY_NAV_OWN_SCREEN, true);
    }

    /** Recents opens on the screen whose button was pressed, full screen, in front of apps. */
    public static boolean recentsRoute() {
        return getBool(Const.KEY_RECENTS_ROUTE, true);
    }

    /** Apps open on the monitor stay alive until the user closes them. */
    public static boolean keepAlive() {
        return getBool(Const.KEY_KEEP_ALIVE, true);
    }

    /** The Android robot as the drawer button's icon, in place of ZUI's own. */
    public static boolean startButtonRobot() {
        return getBool(Const.KEY_START_ROBOT, true);
    }

    /** A mark under the taskbar icons whose apps are open. */
    public static boolean runningMarks() {
        return getBool(Const.KEY_RUNNING_MARKS, true);
    }

    /**
     * Give the launcher's own launches the display they were tapped on.
     *
     * <p>Off by default, and deliberately so: it changes where every app in the launcher opens,
     * which is too much to turn on behind somebody's back. Turn it on if apps keep opening on the
     * wrong screen.
     */
    public static boolean launchOnTappedDisplay() {
        return getBool(Const.KEY_LAUNCH_DISPLAY, false);
    }

    public static boolean taskbarTray() {
        return getBool(Const.KEY_TASKBAR_TRAY, true);
    }

    public static boolean taskbarTemps() {
        return getBool(Const.KEY_TASKBAR_TEMPS, true);
    }

    public static boolean taskbarMenu() {
        return getBool(Const.KEY_TASKBAR_MENU, true);
    }

    /**
     * Black, white, or read from the background.
     *
     * <p>Carries the old switch forward: somebody who turned "Dark tray text" off had chosen
     * white, and replacing a setting is no reason to quietly undo their choice. Only when they
     * never touched it does this fall through to deciding for itself.
     */
    public static int taskbarTextMode() {
        int mode = getInt(Const.KEY_TASKBAR_TEXT_MODE, -1);
        if (mode >= 0) {
            return mode;
        }
        if (has(Const.KEY_TASKBAR_DARK_TEXT)) {
            return getBool(Const.KEY_TASKBAR_DARK_TEXT, true) ? Tone.MODE_DARK : Tone.MODE_LIGHT;
        }
        return Tone.MODE_AUTO;
    }

    /** Whether a setting has ever been written, as opposed to having a default. */
    private static boolean has(String key) {
        XSharedPreferences p = prefs();
        if (!readable(p)) {
            return remembered(key) != null;
        }
        try {
            return p.contains(key);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean taskbarGlass() {
        // Off by default: this is the one setting that changes how the launcher's own taskbar is
        // painted, and a fresh install should leave it exactly as the firmware drew it.
        return getBool(Const.KEY_TASKBAR_GLASS, false);
    }

    public static boolean taskbarRunningOnly() {
        // Off by default: this changes what the stock taskbar shows, and a fresh install should
        // leave the launcher's own idea of its bar alone until asked.
        return getBool(Const.KEY_TASKBAR_RUNNING_ONLY, false);
    }

    public static boolean taskbarAppMenu() {
        return getBool(Const.KEY_TASKBAR_APP_MENU, true);
    }

    public static boolean drawerGlass() {
        return getBool(Const.KEY_DRAWER_GLASS, true);
    }

    public static boolean notifications() {
        return getBool(Const.KEY_NOTIFICATIONS, true);
    }

    /** A notification that pops up on the tablet pops up on the monitor too, above the tray. */
    public static boolean notifyPopups() {
        return getBool(Const.KEY_NOTIFY_POPUPS, true);
    }

    public static boolean useRoot() {
        // On by default. Every caller tries the ordinary route first, so this only decides whether
        // the fallback is allowed to ask; turning it off costs the toggles and nothing else.
        return getBool(Const.KEY_USE_ROOT, true);
    }

    public static boolean unlockStock() {
        return getBool(Const.KEY_UNLOCK_STOCK, true);
    }

    public static boolean unlockAggressive() {
        return getBool(Const.KEY_UNLOCK_AGGRESSIVE, false);
    }

    public static boolean probe() {
        return getBool(Const.KEY_PROBE, false);
    }

    public static String extraTargets() {
        return getString(Const.KEY_EXTRA_TARGETS, "");
    }
}
