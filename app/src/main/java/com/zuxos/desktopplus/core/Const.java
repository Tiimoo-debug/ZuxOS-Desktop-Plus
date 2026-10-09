package com.zuxos.desktopplus.core;

/** Names and preference keys shared between the settings app and the hooked process. */
public final class Const {

    public static final String MODULE_PKG = "com.zuxos.desktopplus";
    public static final String PREFS = "zux_desktop_plus";
    public static final String TAG = "ZuxDesktopPlus";

    /** Directory created inside the hooked launcher's private data dir. */
    public static final String DATA_DIR = "zux_desktop_plus";

    // --- General ---------------------------------------------------------
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_DEBUG = "debug_log";
    public static final String KEY_PROBE = "probe_dump";
    public static final String KEY_EXTRA_TARGETS = "extra_targets";

    /** Which desktop mode the surface attaches to: see {@link #DISPLAY_EXTERNAL} etc. */
    public static final String KEY_DISPLAY_MODE = "display_mode";
    public static final int DISPLAY_EXTERNAL = 0;
    public static final int DISPLAY_INTERNAL = 1;
    public static final int DISPLAY_BOTH = 2;

    /** How much of the stock home screen is replaced: see {@link #TAKEOVER_NONE} etc. */
    public static final String KEY_TAKEOVER = "takeover";
    public static final int TAKEOVER_NONE = 0;
    public static final int TAKEOVER_GRID = 1;
    public static final int TAKEOVER_ALL = 2;

    /** Attach to every activity of the target package, not just detected home activities. */
    public static final String KEY_ATTACH_ANY = "attach_any_activity";

    // --- Desktop surface -------------------------------------------------
    public static final String KEY_CELL_SIZE = "cell_size_dp";
    public static final String KEY_ICON_SIZE = "icon_size_dp";
    public static final String KEY_SHOW_LABELS = "show_labels";
    public static final String KEY_WIDGETS_ENABLED = "widgets_enabled";
    public static final String KEY_FOLDERS_ENABLED = "folders_enabled";
    public static final String KEY_DRAWER_BUTTON = "drawer_button";
    public static final String KEY_CATCH_PINS = "catch_pinned_shortcuts";
    public static final String KEY_PAGES = "pages_enabled";
    public static final String KEY_PAGE_DOTS = "page_dots";
    public static final String KEY_GLASS = "glass_style";
    public static final String KEY_ANIMATIONS = "animations";
    public static final String KEY_LABEL_SHADOW = "label_shadow";

    // --- Drawer ----------------------------------------------------------
    public static final String KEY_DRAWER_SORT = "drawer_sort";
    /** Apply the module's folders and order to the launcher's own taskbar drawer too. */
    public static final String KEY_NATIVE_DRAWER = "native_drawer";
    public static final int SORT_ALPHA = 0;
    public static final int SORT_CUSTOM = 1;
    public static final int SORT_RECENT = 2;

    // --- Taskbar ---------------------------------------------------------
    /** Network, battery and a clock inside the launcher's own taskbar. */
    public static final String KEY_TASKBAR_TRAY = "taskbar_tray";
    /** CPU, GPU and battery temperatures alongside them. */
    public static final String KEY_TASKBAR_TEMPS = "taskbar_temps";
    /** Hold or right-click the taskbar for a menu. */
    public static final String KEY_TASKBAR_MENU = "taskbar_menu";
    /** Dark tray text, for a light taskbar. Superseded by {@link #KEY_TASKBAR_TEXT_MODE}. */
    public static final String KEY_TASKBAR_DARK_TEXT = "taskbar_dark_text";
    /** Black, white, or decided from the background. See {@code core/Tone.java}. */
    public static final String KEY_TASKBAR_TEXT_MODE = "taskbar_text_mode";
    /** Replace the taskbar's own opaque bar with a translucent, blurred one. */
    public static final String KEY_TASKBAR_GLASS = "taskbar_glass";
    /** Show only apps that are open in the stock taskbar, instead of its predictions. */
    public static final String KEY_TASKBAR_RUNNING_ONLY = "taskbar_running_only";
    /** Hold a taskbar icon for open, close, app info and the app's own shortcuts. */
    public static final String KEY_TASKBAR_APP_MENU = "taskbar_app_menu";
    /** Translucent glass behind the launcher's own app drawer. */
    public static final String KEY_DRAWER_GLASS = "native_drawer_glass";
    /** Hold an app in the stock drawer to drag it onto the desktop or the taskbar. */
    public static final String KEY_DRAWER_DRAG = "native_drawer_drag";
    /** A mark under every taskbar icon whose app is open. */
    public static final String KEY_RUNNING_MARKS = "taskbar_running_marks";
    /** Open an app on the screen its icon was tapped on, where the launcher names no display. */
    public static final String KEY_LAUNCH_DISPLAY = "launch_on_tapped_display";
    /** Move the launcher's drawer button to the left of the bar, beside the navigation keys. */
    public static final String KEY_START_LEFT = "start_button_left";
    public static final String KEY_START_ROBOT = "start_button_robot";
    /** Keep ZUI's overview down during the moment an app is opening. */
    public static final String KEY_HIDE_RECENTS_FLASH = "hide_recents_flash";
    public static final String KEY_NAV_OWN_SCREEN = "nav_keys_own_screen";
    /** Recents on the screen whose button was pressed, full screen and in front. */
    public static final String KEY_RECENTS_ROUTE = "recents_route";
    /** Apps open on the monitor are never killed by the system, only by the user. */
    public static final String KEY_KEEP_ALIVE = "keep_alive";
    /** Show notifications in the quick panel, through the module's own listener. */
    public static final String KEY_NOTIFICATIONS = "notifications";
    /** A new notification pops up on the monitor, above the tray. */
    public static final String KEY_NOTIFY_POPUPS = "notify_popups";
    /** Allow shell commands as root for the things a launcher may not do itself. */
    public static final String KEY_USE_ROOT = "use_root";

    // --- Stock-launcher unlocking hooks ----------------------------------
    public static final String KEY_UNLOCK_STOCK = "unlock_stock";
    public static final String KEY_UNLOCK_AGGRESSIVE = "unlock_aggressive";

    /** Files inside {@link #DATA_DIR}. */
    public static final String FILE_DESKTOP = "desktop.json";
    public static final String FILE_DRAWER = "drawer.json";
    /** Apps pinned to the taskbar by this module - never the launcher's own hotseat. */
    public static final String FILE_TASKBAR_PINS = "taskbar.json";
    public static final String FILE_PROBE = "probe.txt";

    /** AppWidgetHost id owned by this module. Deliberately far away from launcher host ids. */
    public static final int WIDGET_HOST_ID = 0x5A78;

    public static final int REQ_BIND_WIDGET = 0x5A70;
    public static final int REQ_CONFIGURE_WIDGET = 0x5A71;

    private Const() {
    }
}
