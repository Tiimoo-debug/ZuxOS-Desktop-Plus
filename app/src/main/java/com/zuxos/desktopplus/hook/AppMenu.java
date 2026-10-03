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
 * the desktop or in the drawer offered less. This hands the desktop the same two, built by the
 * same code, so all three menus agree.
 */
public final class AppMenu {

    private AppMenu() {
    }

    /** Adds "Close" and the app's own shortcuts, each with its icon. */
    public static void addTo(List<Menus.Entry> entries, Context ctx, String pkg, UserHandle user,
            int displayId, Runnable after) {
        if (ctx == null || pkg == null) {
            return;
        }
        entries.add(new Menus.Entry("Close", () -> {
            TaskbarApps.close(ctx, pkg);
            if (after != null) {
                after.run();
            }
        }));
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
}
