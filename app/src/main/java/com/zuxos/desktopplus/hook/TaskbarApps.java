package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Process;
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
     * Turns the taskbar's predictions off and its running apps on.
     *
     * <p>Hooked after {@code init}, because these are set once when the controller is built and
     * the launcher sets them from its own flags - so the last word has to be after that.
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
                    apply(param.thisObject);
                }
            }).size();
            L.i("taskbar apps: running-apps switch installed x" + hooked);
            if (hooked == 0) {
                L.w("taskbar apps: nothing named init on the recent-apps controller - the "
                        + "\"only open apps\" setting will do nothing on this build");
            }
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

    private static void apply(Object controller) {
        if (controller != null) {
            // Kept whether or not the setting is on: it is the only thing that will say which
            // icons are running, and that question outlives this one switch.
            sRecentApps = new java.lang.ref.WeakReference<>(controller);
        }
        if (controller == null || !Cfg.taskbarRunningOnly()) {
            // Nothing to say when the setting is off. Setting the flags the other way round
            // would not be leaving the launcher alone - it would be overriding it in the
            // opposite direction, on a build that may well default to something else.
            return;
        }
        boolean running = invoke(controller, "setCanShowRunningApps", true);
        // The recommendations are the other half: leaving them on would mean running apps
        // alongside a row of guesses, which is not what "only what is open" means.
        boolean recents = invoke(controller, "setCanShowRecentApps", false);
        if (running && recents) {
            // Both setters took last time and the bar did not change, so the interesting part is
            // what the controller says afterwards: if it reads back true and still shows the
            // same pinned items, then this controller is not what fills this firmware's bar.
            L.i("taskbar apps: running apps on, predictions off"
                    + " - reads back " + read(controller, "getCanShowRunningApps")
                    + ", shown=" + size(controller, "getShownHotseatItems")
                    + ", tasks=" + size(controller, "getShownTasks")
                    + ", running=" + size(controller, "getRunningTaskIds"));
        } else {
            L.w("taskbar apps: this controller has no setCanShowRunningApps/RecentApps - the "
                    + "taskbar will keep showing whatever it chose");
        }
    }

    /** Calls a one-boolean setter, and says whether it was actually there to call. */
    private static boolean invoke(Object target, String name, boolean value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name, boolean.class);
                m.setAccessible(true);
                m.invoke(target, value);
                return true;
            } catch (NoSuchMethodException keepLooking) {
                continue;
            } catch (Throwable t) {
                L.d("taskbar apps: " + name + " refused (" + t + ")");
                return false;
            }
        }
        return false;
    }

    private static String read(Object target, String getter) {
        Object value = Reflect.call(target, getter);
        return value == null ? "?" : String.valueOf(value);
    }

    /** How many things a getter handed back, for a getter that hands back a collection. */
    private static String size(Object target, String getter) {
        Object value = Reflect.call(target, getter);
        if (value instanceof java.util.Collection) {
            return String.valueOf(((java.util.Collection<?>) value).size());
        }
        if (value != null && value.getClass().isArray()) {
            // Including int[], which getRunningTaskIds hands back and which is not an Object[].
            return String.valueOf(java.lang.reflect.Array.getLength(value));
        }
        return value == null ? "?" : String.valueOf(value);
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
        try {
            Object info = icon.getTag();
            String pkg = packageOf(info);
            if (pkg == null) {
                return false;
            }
            Context ctx = icon.getContext();
            int displayId = TaskbarTray.displayIdOf(icon);
            UserHandle user = userOf(info);
            List<TaskbarMenu.Entry> entries = new ArrayList<>();

            entries.add(new TaskbarMenu.Entry("Open",
                    () -> TaskbarMenu.launch(ctx, pkg, displayId)));
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

            int[] at = new int[2];
            icon.getLocationOnScreen(at);
            // Only claim the press if a menu really went up. Saying yes when it did not would
            // cancel the launcher's own popup and leave a long press doing nothing at all.
            return TaskbarMenu.showEntries(icon, displayId, at[0] + icon.getWidth() / 2f, entries);
        } catch (Throwable t) {
            L.e("taskbar apps: could not show the icon menu", t);
            return false;
        }
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

    /** The package behind an icon, from whichever field its item info keeps it in. */
    private static String packageOf(Object info) {
        if (info == null) {
            return null;
        }
        Object direct = Reflect.field(info, "packageName");
        if (direct instanceof String && !((String) direct).isEmpty()) {
            return (String) direct;
        }
        Object intent = Reflect.field(info, "intent");
        if (intent instanceof Intent) {
            Intent i = (Intent) intent;
            if (i.getComponent() != null) {
                return i.getComponent().getPackageName();
            }
            return i.getPackage();
        }
        return null;
    }

    private static UserHandle userOf(Object info) {
        Object user = Reflect.field(info, "user");
        return user instanceof UserHandle ? (UserHandle) user : Process.myUserHandle();
    }
}
