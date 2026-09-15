package com.zuxos.desktopplus.hook;

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
        if (Cfg.attachAnyActivity()) {
            return true;
        }
        return isHomeActivity(activity) || nameLooksLikeHome(activity);
    }

    /** True when the system itself considers this activity a home screen. */
    public static boolean isHomeActivity(Activity activity) {
        try {
            PackageManager pm = activity.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> infos = pm.queryIntentActivities(home, PackageManager.MATCH_ALL);
            String name = activity.getClass().getName();
            for (ResolveInfo ri : infos) {
                if (ri.activityInfo != null && name.equals(ri.activityInfo.name)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            L.d("home lookup failed: " + t);
        }
        return false;
    }

    private static boolean nameLooksLikeHome(Activity activity) {
        String name = activity.getClass().getName().toLowerCase();
        for (String hint : CLASS_HINTS) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
