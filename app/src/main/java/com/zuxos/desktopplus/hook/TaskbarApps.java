package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Su;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * What a taskbar icon does when you hold it, and which icons are there at all.
 *
 * <p>Both of these are the launcher's own business, and the probe showed it already has the
 * machinery: {@code TaskbarRecentAppsController} carries {@code setCanShowRunningApps} and
 * {@code setCanShowRecentApps}, and {@code TaskbarPopupController.showForIcon} is the long press.
 * So neither is fought with - the first is a switch the launcher already has and was never
 * offered, and the second is a menu it already opens, filled with what was asked for instead.
 */
final class TaskbarApps {

    private static final String RECENT_APPS =
            "com.android.launcher3.taskbar.TaskbarRecentAppsController";
    private static final String POPUP =
            "com.android.launcher3.taskbar.TaskbarPopupController";

    private static boolean sInstalled;

    private TaskbarApps() {
    }

    static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        installRunningOnly(loader);
        installMenu(loader);
    }

    // --- only the apps that are open ---------------------------------------

    /**
     * Keeps hold of the launcher's recent-apps controller, and sets nothing on it.
     *
     * <p>It used to set {@code setCanShowRunningApps(true)} and {@code setCanShowRecentApps(false)}.
     * Both took, both read back true, and the bar never changed - the probe said so every round:
     * {@code shown=0, tasks=0}. ZUI fills its own bar and that controller is not what does it.
     *
     * <p>Worse than useless, as it turned out. The log caught the launcher crashing nine times in
     * one session, six of them here:
     *
     * <pre>
     * IllegalStateException: The specified child already has a parent
     *   at TaskbarView.updateHotseatItems
     *   at TaskbarModelCallbacks.bindRecentUsedApps
     *   at com.zui.launcher.uiextend.RecentUsedModel.J
     * </pre>
     *
     * <p>ZUI's own model, re-adding an icon that still has a parent - its bug, but one those two
     * switches walk it into, and a crash in the middle of {@code updateHotseatItems} is what left
     * the row looking half-built. So nothing is set. The controller itself is still worth keeping:
     * {@code getRunningAppState} answers which icons are running, which is a question we do use.
     */
    private static void installRunningOnly(ClassLoader loader) {
        Class<?> cls = Reflect.findClass(RECENT_APPS, loader);
        if (cls == null) {
            L.w("taskbar apps: no TaskbarRecentAppsController on this build");
            return;
        }
        try {
            int hooked = XposedBridge.hookAllMethods(cls, "init", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    remember(param.thisObject);
                }
            }).size();
            L.i("taskbar apps: holding the recent-apps controller x" + hooked
                    + " (its switches are left alone - they crash this firmware)");
        } catch (Throwable t) {
            L.e("taskbar apps: could not reach the recent-apps controller", t);
        }
    }

    /** The launcher's recent-apps controller, for whoever needs to ask it something. */
    static Object recentAppsController() {
        java.lang.ref.WeakReference<Object> ref = sRecentApps;
        return ref == null ? null : ref.get();
    }

    private static java.lang.ref.WeakReference<Object> sRecentApps;

    private static void remember(Object controller) {
        if (controller != null) {
            sRecentApps = new java.lang.ref.WeakReference<>(controller);
        }
    }

    // --- the menu on a long press ------------------------------------------

    /**
     * Who actually handles a long press on a taskbar icon.
     *
     * <p>The menu hooked last time turned out to be the app drawer's: the same popup controller
     * serves both, and only the drawer goes through it. Rather than guess again, each icon is
     * asked directly what its own long-click listener is - the answer is the thing to hook.
     */
    static void describeLongPress(ViewGroup dragLayer) {
        if (sDescribedLongPress) {
            return;
        }
        View row = TaskbarTray.rowReference(dragLayer);
        if (!(row instanceof ViewGroup) || ((ViewGroup) row).getChildCount() == 0) {
            return;
        }
        sDescribedLongPress = true;
        StringBuilder sb = new StringBuilder();
        ViewGroup icons = (ViewGroup) row;
        for (int i = 0; i < icons.getChildCount() && i < 4; i++) {
            View icon = icons.getChildAt(i);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(icon.getClass().getSimpleName()).append(" -> ")
                    .append(longClickListener(icon));
        }
        L.i("taskbar apps: long press is handled by " + sb);
    }

    private static boolean sDescribedLongPress;

    /**
     * An icon's long-click listener, out of the private box View keeps its listeners in.
     *
     * <p>{@code View.mListenerInfo} is where they all live and there is no getter for it; the
     * class name of what is in there is the whole answer, so this reads it and nothing else.
     */
    private static String longClickListener(View icon) {
        try {
            java.lang.reflect.Field infoField = View.class.getDeclaredField("mListenerInfo");
            infoField.setAccessible(true);
            Object info = infoField.get(icon);
            if (info == null) {
                return "none";
            }
            java.lang.reflect.Field listenerField =
                    info.getClass().getDeclaredField("mOnLongClickListener");
            listenerField.setAccessible(true);
            Object listener = listenerField.get(info);
            return listener == null ? "none" : listener.getClass().getName();
        } catch (Throwable t) {
            return "unreadable (" + t + ")";
        }
    }


    /**
     * Replaces the launcher's own icon popup with ours.
     *
     * <p>Hooked at {@code showForIcon} rather than at the touch: the launcher already decides
     * when a press is a long one, and taking that over would have meant competing with its own
     * drag gesture for the same press.
     */
    private static void installMenu(ClassLoader loader) {
        Class<?> cls = Reflect.findClass(POPUP, loader);
        if (cls == null) {
            L.w("taskbar apps: no TaskbarPopupController on this build");
            return;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Cfg.taskbarAppMenu()) {
                    return;
                }
                if (param.args.length == 0 || !(param.args[0] instanceof View)) {
                    return;
                }
                View icon = (View) param.args[0];
                if (show(icon)) {
                    // Ours is up; the launcher's would land on top of it.
                    param.setResult(null);
                }
            }
        };
        int hooked = 0;
        for (String name : new String[]{"showForIcon", "showForIconDp"}) {
            try {
                hooked += XposedBridge.hookAllMethods(cls, name, hook).size();
            } catch (Throwable ignored) {
                // Not on this build; the other name may be.
            }
        }
        L.i("taskbar apps: icon menu installed x" + hooked);
        if (hooked == 0) {
            L.w("taskbar apps: nothing to hook for the icon menu - holding an icon will do "
                    + "whatever the launcher does");
        }
    }

    private static boolean show(View icon) {
        Object info = icon.getTag();
        String pkg = IconInfo.packageOf(info);
        if (pkg == null) {
            // Not one app: the all-apps button, or one of our own drawer folders, which the
            // native-drawer hooks answer for themselves.
            return false;
        }
        return showMenu(icon, pkg, IconInfo.userOf(info), TaskbarTray.displayIdOf(icon));
    }

    /**
     * Gives the launcher's own icons a hold menu, because on this bar nothing else does.
     *
     * <p>The popup controller we hook serves the tablet's taskbar, and holding an icon there opens
     * a menu. The desktop's bar never calls it: the probe says its icons carry no long-click
     * listener at all - {@code long press is handled by DoubleShadowBubbleTextView -> none} - so a
     * hold there does nothing whatsoever. One is set here, on each icon as the row is walked.
     *
     * <p>The icons we touched are remembered and cleared by {@link #forgetMenus} when the setting
     * goes off. Whatever ZUI had set is not restored, but it is named in the log the first time.
     */
    static void installRowMenu(ViewGroup icons) {
        if (!Cfg.taskbarAppMenu()) {
            forgetMenus(icons);
            return;
        }
        for (int i = 0; i < icons.getChildCount(); i++) {
            View icon = icons.getChildAt(i);
            if (IconInfo.packageOf(icon.getTag()) == null) {
                continue;
            }
            // Every walk, not once per view. ZUI rebinds the icon of the app open in front and
            // hands it a listener of its own in the process; remembering that we had already set
            // ours is exactly how that one icon was left with no menu.
            Object current = longClickListenerOf(icon);
            if (current == ROW_MENU) {
                continue;
            }
            if (current != null && SAID_FOREIGN.add(current.getClass().getName())) {
                L.i("taskbar apps: replacing the launcher's own hold listener "
                        + current.getClass().getName() + " on " + IconInfo.packageOf(icon.getTag()));
            }
            MENUS.put(icon, Boolean.TRUE);
            icon.setOnLongClickListener(ROW_MENU);
        }
    }

    private static final View.OnLongClickListener ROW_MENU = v -> {
        Object info = v.getTag();
        String pkg = IconInfo.packageOf(info);
        return pkg != null && showMenu(v, pkg, IconInfo.userOf(info), TaskbarTray.displayIdOf(v));
    };

    private static final java.util.Set<String> SAID_FOREIGN = new java.util.HashSet<>();

    /** The view's long-click listener, or null when it has none or it cannot be read. */
    private static Object longClickListenerOf(View view) {
        Object info = Reflect.field(view, "mListenerInfo");
        return info == null ? null : Reflect.field(info, "mOnLongClickListener");
    }

    /** Takes our listener back off the launcher's icons. */
    static void forgetMenus(ViewGroup icons) {
        for (int i = 0; i < icons.getChildCount(); i++) {
            View icon = icons.getChildAt(i);
            if (MENUS.remove(icon) != null) {
                icon.setOnLongClickListener(null);
                icon.setLongClickable(false);
            }
        }
    }

    /** The launcher's icons we have given a menu to, so they can be given it back. */
    private static final java.util.Map<View, Boolean> MENUS = new java.util.WeakHashMap<>();

    /**
     * The hold menu for an app, wherever its icon lives.
     *
     * <p>Shared with the running-apps row, so an app that the launcher has no icon for gets the
     * same menu as one it does rather than a second menu that looks nearly like it.
     *
     * @return true when a menu really went up; saying yes when it did not would cancel the
     *         launcher's own popup and leave a long press doing nothing at all
     */
    static boolean showMenu(View icon, String pkg, UserHandle user, int displayId) {
        try {
            int[] at = new int[2];
            icon.getLocationOnScreen(at);
            return TaskbarMenu.showEntries(icon, displayId, at[0] + icon.getWidth() / 2f,
                    entriesFor(icon.getContext(), pkg, user, displayId));
        } catch (Throwable t) {
            L.e("taskbar apps: could not show the icon menu", t);
            return false;
        }
    }

    /**
     * What a taskbar icon offers: open it, close it, its settings, and its own shortcuts.
     *
     * <p>Handed out rather than shown, so that an icon with something extra to offer - a pin, which
     * can also be unpinned - adds to this list instead of growing a second menu beside it.
     */
    static List<TaskbarMenu.Entry> entriesFor(Context ctx, String pkg, UserHandle user,
            int displayId) {
        List<TaskbarMenu.Entry> entries = new ArrayList<>();
        entries.add(new TaskbarMenu.Entry("Open", () -> TaskbarMenu.launch(ctx, pkg, displayId)));
        entries.add(new TaskbarMenu.Entry("Close", () -> close(ctx, pkg)));
        entries.add(new TaskbarMenu.Entry("App info", () -> appInfo(ctx, pkg, displayId)));
        for (ShortcutInfo shortcut : shortcuts(ctx, pkg, user)) {
            CharSequence label = shortcut.getShortLabel() != null
                    ? shortcut.getShortLabel() : shortcut.getLongLabel();
            if (label == null) {
                continue;
            }
            entries.add(new TaskbarMenu.Entry(label.toString(),
                    () -> startShortcut(ctx, shortcut, displayId)));
        }
        return entries;
    }

    /**
     * The app's own shortcuts.
     *
     * <p>Only the launcher may ask for these, and the probe said this one may - which is what
     * makes the menu worth having rather than three buttons.
     */
    private static List<ShortcutInfo> shortcuts(Context ctx, String pkg, UserHandle user) {
        List<ShortcutInfo> out = new ArrayList<>();
        try {
            LauncherApps apps = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (apps == null || !apps.hasShortcutHostPermission()) {
                return out;
            }
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
            query.setPackage(pkg);
            query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> found = apps.getShortcuts(query, user);
            if (found != null) {
                for (ShortcutInfo shortcut : found) {
                    if (shortcut.isEnabled()) {
                        out.add(shortcut);
                    }
                    if (out.size() >= 5) {
                        // A taskbar menu, not a launcher drawer.
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            L.d("taskbar apps: no shortcuts for " + pkg + " (" + t + ")");
        }
        return out;
    }

    private static void startShortcut(Context ctx, ShortcutInfo shortcut, int displayId) {
        try {
            LauncherApps apps = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (apps == null) {
                return;
            }
            apps.startShortcut(shortcut, null, TaskbarMenu.launchOptions(displayId));
        } catch (Throwable t) {
            L.e("taskbar apps: could not start that shortcut", t);
            TaskbarMenu.toast(ctx, "That shortcut would not start");
        }
    }

    /**
     * Stops the app.
     *
     * <p>{@code killBackgroundProcesses} only reaches an app that is already in the background,
     * which is precisely not the one you are looking at in the taskbar. Force-stopping is a
     * privileged thing to do, so it goes the same way as the other privileged things here.
     */
    private static void close(Context ctx, String pkg) {
        Su.run(outcome -> {
            if (outcome.ok()) {
                L.i("taskbar apps: stopped " + pkg);
                return;
            }
            if (!outcome.shouldFallBack()) {
                return;
            }
            L.i("taskbar apps: closing " + pkg + " needs root");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    TaskbarMenu.toast(ctx, "Closing an app needs root"));
        }, "am force-stop " + pkg);
    }

    private static void appInfo(Context ctx, String pkg, int displayId) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", pkg, null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, TaskbarMenu.launchOptions(displayId));
        } catch (Throwable t) {
            L.e("taskbar apps: could not open app info for " + pkg, t);
        }
    }

}
