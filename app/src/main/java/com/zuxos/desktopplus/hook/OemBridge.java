package com.zuxos.desktopplus.hook;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gets the stock desktop content out of the way.
 *
 * <p>The stock external-display home draws its own (non-rearrangeable) icon grid. Leaving it
 * visible under our surface would just show every app twice, so we hide the container it lives
 * in - remembering the original visibility so detaching puts everything back.
 */
public final class OemBridge {

    /** Ids seen on AOSP's secondary-display launcher and on Launcher3-derived OEM homes. */
    private static final String[] GRID_ID_HINTS = {
            "workspace", "workspace_grid", "pinned_apps", "pinned_apps_view", "app_grid",
            "icon_grid", "desktop_grid", "desktop_view", "hotseat", "apps_view", "app_drawer"};

    private static final String[] GRID_CLASS_HINTS = {
            "Workspace", "PinnedApps", "CellLayout", "IconGrid", "AppsGrid", "DesktopGrid"};

    private static final Map<Activity, Map<View, Integer>> HIDDEN = new WeakHashMap<>();

    private OemBridge() {
    }

    public static void applyTakeover(Activity activity, ViewGroup content, View ours, int mode) {
        if (mode == Const.TAKEOVER_NONE) {
            return;
        }
        if (content == null) {
            // The surface is in a window of its own; there is nothing of ours inside the
            // launcher's view tree to hide things relative to.
            L.d("takeover skipped: surface is not inside the launcher's view tree");
            return;
        }
        Map<View, Integer> hidden = new HashMap<>();
        try {
            List<View> targets = new ArrayList<>();
            List<View> quickstep = mode == Const.TAKEOVER_ALL && onTablet(activity)
                    ? homeContentBesideRecents(content, ours) : null;
            if (quickstep != null) {
                targets.addAll(quickstep);
            } else if (mode == Const.TAKEOVER_ALL) {
                for (int i = 0; i < content.getChildCount(); i++) {
                    View child = content.getChildAt(i);
                    if (child != ours) {
                        targets.add(child);
                    }
                }
            } else {
                for (int i = 0; i < content.getChildCount(); i++) {
                    View child = content.getChildAt(i);
                    if (child == ours) {
                        continue;
                    }
                    targets.addAll(Reflect.findByIdNames(child, GRID_ID_HINTS));
                    targets.addAll(Reflect.findByClassFragments(child, GRID_CLASS_HINTS));
                }
            }
            for (View v : targets) {
                if (v == ours || isAncestorOf(v, ours)) {
                    continue;
                }
                hidden.put(v, v.getVisibility());
                v.setVisibility(View.GONE);
            }
            L.i("takeover mode " + mode + " hid " + hidden.size() + " stock view(s)");
            if (hidden.isEmpty() && mode == Const.TAKEOVER_GRID) {
                L.w("no stock icon container recognised - if you see duplicate icons, switch "
                        + "takeover to 'Hide everything' in the module settings");
            }
        } catch (Throwable t) {
            L.e("takeover failed", t);
        }
        HIDDEN.put(activity, hidden);
    }

    public static void restore(Activity activity) {
        Map<View, Integer> hidden = HIDDEN.remove(activity);
        if (hidden == null) {
            return;
        }
        for (Map.Entry<View, Integer> e : hidden.entrySet()) {
            try {
                e.getKey().setVisibility(e.getValue());
            } catch (Throwable ignored) {
                // The view may already be gone with the activity.
            }
        }
    }

    /** Quickstep's own: Recents and what it draws with. Never hidden on the tablet. */
    private static final String[] RECENTS_IDS = {
            "overview_panel", "clear_all", "overview_actions_view", "list_mode_options",
            "scrim_view"};

    private static boolean onTablet(Activity activity) {
        try {
            android.view.Display d = activity.getDisplay();
            return d != null && d.getDisplayId() == android.view.Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * On the tablet, "hide everything" stops short of Recents. The launcher's root holds its
     * home screen and Quickstep's Recents side by side in one drag layer, and the home and
     * recents gestures animate inside it: hiding the root, as on the monitor, left Home and
     * Recents with nothing to draw into, buttons and gestures alike. So the root stays, and
     * only the home screen's parts inside it go. Null when the drag layer is not there, and
     * the takeover is then as before.
     */
    private static List<View> homeContentBesideRecents(ViewGroup content, View ours) {
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child == ours) {
                continue;
            }
            List<View> layers = Reflect.findByIdNames(child, "drag_layer");
            if (layers.isEmpty() || !(layers.get(0) instanceof ViewGroup)) {
                continue;
            }
            ViewGroup layer = (ViewGroup) layers.get(0);
            List<View> out = new ArrayList<>();
            for (int c = 0; c < layer.getChildCount(); c++) {
                View v = layer.getChildAt(c);
                if (!isRecents(v)) {
                    out.add(v);
                }
            }
            L.i("takeover: the tablet's Recents kept - " + out.size() + " home view(s) hidden "
                    + "inside the launcher's root instead of the root itself");
            return out;
        }
        L.w("takeover: no launcher drag layer on the tablet - hiding as on the monitor");
        return null;
    }

    private static boolean isRecents(View v) {
        String id = Reflect.idName(v);
        if (id != null) {
            for (String keep : RECENTS_IDS) {
                if (keep.equals(id)) {
                    return true;
                }
            }
        }
        String name = v.getClass().getSimpleName();
        return name.contains("RecentsView") || name.contains("Overview");
    }

    private static boolean isAncestorOf(View candidate, View child) {
        for (Object p = child.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == candidate) {
                return true;
            }
        }
        return false;
    }
}
