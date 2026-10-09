package com.zuxos.desktopplus.hook.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.Display;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;

import java.util.List;

/** Decides whether a given activity is the desktop-mode home we should take over. */
public final class HostDetector {

    private static final String[] CLASS_HINTS = {
            "launcher", "home", "desktop", "secondary", "workspace", "pcmode"};

    private static final String ZUI_LAUNCHER = "com.zui.launcher";

    private HostDetector() {
    }

    public static boolean isExternal(Activity activity) {
        try {
            Display display = activity.getDisplay();
            return display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldAttach(Activity activity) {
        boolean external = isExternal(activity);
        int mode = Cfg.displayMode();
        if (mode == Const.DISPLAY_EXTERNAL && !external) {
            return false;
        }
        if (mode == Const.DISPLAY_INTERNAL && external) {
            return false;
        }
        if (!external && ZUI_LAUNCHER.equals(activity.getPackageName())) {
            // ZUI's own home on the tablet stays ZUI's: covering it put a second desktop, second
            // folders and our Apps pill over it, and took Recents' buttons and gestures with it.
            // The tablet gets our taskbar only.
            return false;
        }
        if (Cfg.attachAnyActivity()) {
            return true;
        }
        if (ZUI_LAUNCHER.equals(activity.getPackageName())) {
            // ZUI's own screens are known: only one it declares a home is the desktop. Its
            // settings, "ZuiLauncherSettings", has "launcher" in its name too, and the desktop
            // covered it when it opened on the monitor.
            return isHomeActivity(activity);
        }
        return isHomeActivity(activity) || nameLooksLikeHome(activity);
    }

    /**
     * True when the system itself considers this activity a home screen: the tablet's home or a
     * second screen's ({@code SECONDARY_HOME}, which is what ZUI's monitor desktop declares).
     */
    public static boolean isHomeActivity(Activity activity) {
        try {
            PackageManager pm = activity.getPackageManager();
            String name = activity.getClass().getName();
            for (String category : new String[]{Intent.CATEGORY_HOME,
                    Intent.CATEGORY_SECONDARY_HOME}) {
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(category);
                List<ResolveInfo> infos = pm.queryIntentActivities(home,
                        PackageManager.MATCH_ALL);
                for (ResolveInfo ri : infos) {
                    if (ri.activityInfo != null && name.equals(ri.activityInfo.name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            L.d("home lookup failed: " + t);
        }
        return false;
    }

    /**
     * By the activity's own name, not its package's: every screen of ZUI's launcher lives in
     * {@code com.zui.launcher}, which matched "launcher" - so its search permission screen and
     * its recents were taken for home screens, and the desktop attached itself to them.
     */
    private static boolean nameLooksLikeHome(Activity activity) {
        String name = activity.getClass().getSimpleName().toLowerCase();
        for (String hint : CLASS_HINTS) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
