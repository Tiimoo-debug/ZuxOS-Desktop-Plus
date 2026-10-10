package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.view.Display;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.drawer.ZuiFolders;
import com.zuxos.desktopplus.model.Item;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Our pins on the tablet's bar, as ZUI's own items.
 *
 * <p>ZUI's bar builds its row from an array of item infos ({@code TaskbarView.updateHotseatItems}):
 * an app becomes ZUI's own icon, which opens, shows ZUI's popup with the app's shortcuts and dots,
 * and presses as ZUI's do; a folder becomes ZUI's {@code FolderIcon}, opened by ZUI's own bar
 * ({@code expandFolder}) with ZUI's bar motion. That array was handed over empty
 * ({@link TaskbarRebind}); on the tablet it now holds our pins, made by ZUI - apps from the bar's
 * own {@code AppInfo}s ({@code makeWorkspaceItem}), folders as ZUI's {@code FolderInfo}
 * ({@link ZuiFolders#barFolderInfo}) - in ZUI's hotseat, with ids of our own, so what ZUI would
 * save for them is kept out of its database ({@code ZuiFolderWrites}). Our own row keeps the open
 * apps that are not pinned. The monitor keeps our row for everything.
 */
final class ZuiBarPins {

    /** Ids of our pins on the bar: below the drawer folders' range, apart from it. */
    private static final int ID_BASE = ZuiFolders.ID_BASE - 500_000;
    /** ZUI's hotseat, which is what its bar shows. */
    private static final int HOTSEAT = -101;

    /** The items last made, for as long as the pins they were made from have not changed. */
    private static Object sMade;
    private static String sMadeFrom;

    /** The bar's last build, to build it again when our pins change. */
    private static WeakReference<View> sBar;
    private static Method sBuild;
    private static Object[] sArgs;
    private static boolean sSaid;

    private ZuiBarPins() {
    }

    /** Whether ZUI's bar holds our pins on this bar's screen, so our row leaves them out. */
    static boolean holdsPins(View anyOfTheBar) {
        return Cfg.taskbarRunningOnly() && Cfg.hideRecommendedFlash() && TaskbarRebind.sAppsAtSource
                && TaskbarTray.displayIdOf(anyOfTheBar) == Display.DEFAULT_DISPLAY;
    }

    /**
     * The items ZUI's bar is to build instead of its own: our pins, as ZUI's, or null where the
     * bar is not the tablet's.
     */
    static Object items(View bar, Class<?> type, Object handed) {
        if (TaskbarTray.displayIdOf(bar) != Display.DEFAULT_DISPLAY) {
            return null;
        }
        if (handed != null && handed == sMade) {
            // The same build passed on (updateItems hands it to updateHotseatItems).
            return handed;
        }
        try {
            Context ctx = bar.getContext();
            Object activity = Class.forName("com.android.launcher3.views.ActivityContext", false,
                    ctx.getClassLoader()).getMethod("lookupContext", Context.class)
                    .invoke(null, ctx);
            if (!(activity instanceof Context)) {
                return null;
            }
            List<Item> pins = TaskbarPins.pins(ctx);
            String from = keysOf(pins);
            if (sMade != null && from.equals(sMadeFrom)
                    && sMade.getClass().getComponentType() == type) {
                return sMade;
            }
            List<Object> made = new ArrayList<>();
            int index = 0;
            for (Item pin : pins) {
                int id = ID_BASE - index;
                Object info = pin.type == Item.TYPE_FOLDER
                        ? ZuiFolders.barFolderInfo((Context) activity, pin, id, HOTSEAT,
                        child -> child.pkg == null ? null
                                : TaskbarApps.zuiAppInfo(activity, child.pkg, child.cls))
                        : appItem((Context) activity, pin, id, index);
                if (info != null && type.isInstance(info)) {
                    made.add(info);
                }
                index++;
            }
            Object array = Array.newInstance(type, made.size());
            for (int i = 0; i < made.size(); i++) {
                Array.set(array, i, made.get(i));
            }
            sMade = array;
            sMadeFrom = from;
            if (!sSaid) {
                sSaid = true;
                L.i("zui bar pins: " + made.size() + " of " + pins.size()
                        + " pins built by ZUI's own bar");
            }
            return array;
        } catch (Throwable t) {
            L.w("zui bar pins: not built by ZUI's bar (" + t + ")");
            return null;
        }
    }

    /** A pinned app as ZUI's own hotseat item, made by ZUI from the bar's {@code AppInfo}. */
    private static Object appItem(Context activity, Item pin, int id, int index) throws Exception {
        if (pin.pkg == null) {
            return null;
        }
        Object app = TaskbarApps.zuiAppInfo(activity, pin.pkg, pin.cls);
        if (app == null) {
            return null;
        }
        Object made = app.getClass().getMethod("makeWorkspaceItem", Context.class)
                .invoke(app, activity);
        if (made == null) {
            return null;
        }
        setInt(made, "id", id);
        setInt(made, "container", HOTSEAT);
        setInt(made, "rank", index);
        setInt(made, "cellX", index);
        return made;
    }

    /** Whether a view's item is one of our pins on ZUI's bar. */
    static boolean isOurs(Object tag) {
        if (tag == null || tag instanceof String) {
            return false;
        }
        Object id = Reflect.field(tag, "id");
        return id instanceof Integer && (Integer) id <= ID_BASE;
    }

    /** ZUI's bar about to build its row: remembered, to build it again when our pins change. */
    static void building(View bar, Method build, Object[] args) {
        if (TaskbarTray.displayIdOf(bar) == Display.DEFAULT_DISPLAY) {
            sBar = new WeakReference<>(bar);
            sBuild = build;
            sArgs = args.clone();
        }
    }

    /** Our pins changed: ZUI's bar builds its row again, with them. */
    static void rebuild() {
        View bar = sBar != null ? sBar.get() : null;
        if (bar == null || sBuild == null || sArgs == null || !holdsPins(bar)) {
            return;
        }
        sMade = null;
        bar.post(() -> {
            try {
                sBuild.setAccessible(true);
                sBuild.invoke(bar, sArgs.clone());
            } catch (Throwable t) {
                L.w("zui bar pins: ZUI's bar not rebuilt (" + t + ")");
            }
        });
    }

    private static String keysOf(List<Item> pins) {
        StringBuilder sb = new StringBuilder();
        for (Item pin : pins) {
            sb.append(pin.key()).append('|').append(pin.label).append('|')
                    .append(pin.children.size()).append(';');
        }
        return sb.toString();
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException next) {
                // Up a level.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
