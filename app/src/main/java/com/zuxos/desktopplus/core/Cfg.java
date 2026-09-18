package com.zuxos.desktopplus.core;

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
        if (p == null) {
            return def;
        }
        try {
            return p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static int getInt(String key, int def) {
        XSharedPreferences p = prefs();
        if (p == null) {
            return def;
        }
        try {
            return p.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static String getString(String key, String def) {
        XSharedPreferences p = prefs();
        if (p == null) {
            return def;
        }
        try {
            return p.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
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

    public static boolean glass() {
        return getBool(Const.KEY_GLASS, true);
    }

    public static boolean animations() {
        return getBool(Const.KEY_ANIMATIONS, true);
    }

    public static boolean nativeDrawer() {
        return getBool(Const.KEY_NATIVE_DRAWER, true);
    }

    public static int drawerSort() {
        return getInt(Const.KEY_DRAWER_SORT, Const.SORT_CUSTOM);
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

    public static boolean taskbarDarkText() {
        // The stock taskbar is light, so dark text is the readable default.
        return getBool(Const.KEY_TASKBAR_DARK_TEXT, true);
    }

    public static boolean taskbarGlass() {
        // Off by default: this is the one setting that changes how the launcher's own taskbar is
        // painted, and a fresh install should leave it exactly as the firmware drew it.
        return getBool(Const.KEY_TASKBAR_GLASS, false);
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
