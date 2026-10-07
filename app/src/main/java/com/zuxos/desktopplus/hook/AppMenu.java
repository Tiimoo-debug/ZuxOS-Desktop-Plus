package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.os.UserHandle;

import com.zuxos.desktopplus.desktop.Menus;

import java.util.List;

/**
 * The taskbar's app actions, for the desktop's own menus.
 *
 * <p>Close and the app's shortcuts were only on a taskbar icon's menu, so the same app held on
 * the desktop or in the drawer offered less. This hands the desktop the same entries, built by
 * the same code, so all three menus agree. On the monitor that includes which of them an app
 * gets: New window, Minimize and Close only for an app that has a window there. The tablet's
 * desktop keeps the menu it always had.
 */
public final class AppMenu {

    private AppMenu() {
    }

    /**
     * Adds what the taskbar offers for an open app - New window, Minimize, Close (this window,
     * and all of them when there are several) - and the app's own shortcuts, each with its icon.
     * On the tablet's desktop: Close and the shortcuts, as before.
     */
    public static void addTo(List<Menus.Entry> entries, Context ctx, String pkg, UserHandle user,
            int displayId, Runnable after) {
        if (ctx == null || pkg == null) {
            return;
        }
        if (!com.zuxos.desktopplus.desktop.DesktopHost.isExternalOn(displayId)) {
            // The tablet's desktop: its menu as it always was - Close, then the shortcuts.
            entries.add(new Menus.Entry("Close", then(after, () -> TaskbarApps.close(ctx, pkg))));
        } else {
            addWindowEntries(entries, ctx, pkg, displayId, after);
        }
        addShortcuts(entries, ctx, pkg, user, displayId, after);
    }

    /** On the monitor: what the taskbar offers for an open app, and nothing for a closed one. */
    private static void addWindowEntries(List<Menus.Entry> entries, Context ctx, String pkg,
            int displayId, Runnable after) {
        List<Integer> windows = TaskbarApps.windowIds(ctx, pkg, displayId);
        if (!windows.isEmpty()) {
            // The window the app's taskbar icon stands for: its first when it has several.
            int window = windows.size() > 1 ? windows.get(0) : -1;
            entries.add(new Menus.Entry("New window", then(after,
                    () -> TaskbarApps.newWindow(ctx, pkg, displayId))));
            entries.add(new Menus.Entry("Minimize", then(after,
                    () -> TaskbarApps.minimize(ctx, pkg, displayId, window))));
            if (windows.size() > 1) {
                entries.add(new Menus.Entry("Close", then(after,
                        () -> TaskbarApps.closeWindow(ctx, pkg, displayId, window))));
                entries.add(new Menus.Entry("Close all windows", then(after,
                        () -> TaskbarApps.close(ctx, pkg))));
            } else {
                entries.add(new Menus.Entry("Close", then(after,
                        () -> TaskbarApps.close(ctx, pkg))));
            }
        }
    }

    private static void addShortcuts(List<Menus.Entry> entries, Context ctx, String pkg,
            UserHandle user, int displayId, Runnable after) {
        for (ShortcutInfo shortcut : TaskbarApps.shortcuts(ctx, pkg, user)) {
            CharSequence label = shortcut.getShortLabel() != null
                    ? shortcut.getShortLabel() : shortcut.getLongLabel();
            if (label == null) {
                continue;
            }
            entries.add(new Menus.Entry(label.toString(), () -> {
                TaskbarApps.startShortcut(ctx, shortcut, displayId);
                if (after != null) {
                    after.run();
                }
            }).withIcon(TaskbarApps.shortcutIcon(ctx, shortcut)));
        }
    }

    /**
     * Brings the app's window forward when it already has one on this screen, instead of
     * opening another; false when it has none, and the caller launches it as usual.
     */
    public static boolean bringIfOpen(Context ctx, String pkg, int displayId) {
        return TaskbarApps.bringIfOpen(ctx, pkg, displayId);
    }

    private static Runnable then(Runnable after, Runnable action) {
        return () -> {
            action.run();
            if (after != null) {
                after.run();
            }
        };
    }
}
