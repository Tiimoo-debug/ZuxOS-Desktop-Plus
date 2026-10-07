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
            // The hover - lift, jiggle, window preview - on the launcher's icons as on ours.
            TaskbarPreview.attach(icon, IconInfo.packageOf(icon.getTag()),
                    TaskbarTray.displayIdOf(icon));
            // And a click on the app already in front minimises it, as on our own icons.
            Object click = clickListenerOf(icon);
            if (click instanceof View.OnClickListener && !(click instanceof FrontToggle)) {
                icon.setOnClickListener(new FrontToggle((View.OnClickListener) click));
            }
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

    private static Object clickListenerOf(View view) {
        Object info = Reflect.field(view, "mListenerInfo");
        return info == null ? null : Reflect.field(info, "mOnClickListener");
    }

    /** The launcher's own click, unless the app is in front - then it is minimised. */
    private static final class FrontToggle implements View.OnClickListener {
        private final View.OnClickListener mOriginal;

        FrontToggle(View.OnClickListener original) {
            mOriginal = original;
        }

        @Override
        public void onClick(View v) {
            String pkg = IconInfo.packageOf(v.getTag());
            if (pkg != null && minimizeIfFront(v.getContext(), pkg, -1,
                    TaskbarTray.displayIdOf(v))) {
                return;
            }
            mOriginal.onClick(v);
        }
    }

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
                icon.setOnHoverListener(null);
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
        return showMenu(icon, pkg, user, displayId, -1);
    }

    /** The same, for the icon of one window: {@code taskId} is its task, -1 for the app's. */
    static boolean showMenu(View icon, String pkg, UserHandle user, int displayId, int taskId) {
        try {
            int[] at = new int[2];
            icon.getLocationOnScreen(at);
            return TaskbarMenu.showEntries(icon, displayId, at[0] + icon.getWidth() / 2f,
                    entriesFor(icon.getContext(), pkg, user, displayId, taskId));
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
        return entriesFor(ctx, pkg, user, displayId, -1);
    }

    /**
     * The same, for one window of the app: {@code taskId} is that window's task, or -1 for the
     * app's own icon - its first window on this display. With more than one window open here,
     * Close closes this one only, and "Close all windows" closes the app. An app with no window
     * here has neither: there is nothing of it to close, minimise or open again.
     */
    static List<TaskbarMenu.Entry> entriesFor(Context ctx, String pkg, UserHandle user,
            int displayId, int taskId) {
        List<TaskbarMenu.Entry> entries = new ArrayList<>();
        List<Integer> windows = windowIds(ctx, pkg, displayId);
        int window = taskId >= 0 ? taskId : firstOf(windows);
        entries.add(new TaskbarMenu.Entry("Open", () -> {
            if (!bringIfOpen(ctx, pkg, displayId)) {
                TaskbarMenu.launch(ctx, pkg, displayId);
            }
        }));
        if (!windows.isEmpty()) {
            // For every app: one that keeps a single window gets its second through the
            // system's part of the module (System Framework in LSPosed).
            entries.add(new TaskbarMenu.Entry("New window",
                    () -> newWindow(ctx, pkg, displayId)));
            entries.add(new TaskbarMenu.Entry("Minimize",
                    () -> minimize(ctx, pkg, displayId, window)));
            entries.add(new TaskbarMenu.Entry("Maximize",
                    () -> maximize(ctx, pkg, displayId, window)));
        }
        if (windows.size() > 1) {
            entries.add(new TaskbarMenu.Entry("Close",
                    () -> closeWindow(ctx, pkg, displayId, window)));
            entries.add(new TaskbarMenu.Entry("Close all windows", () -> close(ctx, pkg)));
        } else if (!windows.isEmpty()) {
            entries.add(new TaskbarMenu.Entry("Close", () -> close(ctx, pkg)));
        }
        entries.add(new TaskbarMenu.Entry("App info", () -> appInfo(ctx, pkg, displayId)));
        for (ShortcutInfo shortcut : shortcuts(ctx, pkg, user)) {
            CharSequence label = shortcut.getShortLabel() != null
                    ? shortcut.getShortLabel() : shortcut.getLongLabel();
            if (label == null) {
                continue;
            }
            entries.add(new TaskbarMenu.Entry(label.toString(), shortcutIcon(ctx, shortcut),
                    () -> startShortcut(ctx, shortcut, displayId)));
        }
        return entries;
    }

    /**
     * The shortcut's own icon, the one the app drew for it.
     *
     * <p>Falls back to a plain shortcut glyph rather than to one guessed from the title: a
     * shortcut called "New chat" is not an "add" button, whatever the word suggests.
     */
    static android.graphics.drawable.Drawable shortcutIcon(Context ctx,
            ShortcutInfo shortcut) {
        try {
            LauncherApps apps = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            android.graphics.drawable.Drawable icon = apps == null ? null
                    : apps.getShortcutIconDrawable(shortcut,
                            ctx.getResources().getDisplayMetrics().densityDpi);
            if (icon != null) {
                return icon;
            }
        } catch (Throwable t) {
            L.d("taskbar apps: no icon for shortcut " + shortcut.getId() + " (" + t + ")");
        }
        return com.zuxos.desktopplus.core.Glyphs.of(com.zuxos.desktopplus.core.Glyphs.SHORTCUT);
    }

    /**
     * The app's own shortcuts.
     *
     * <p>Only the launcher may ask for these, and the probe said this one may - which is what
     * makes the menu worth having rather than three buttons.
     */
    static List<ShortcutInfo> shortcuts(Context ctx, String pkg, UserHandle user) {
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

    static void startShortcut(Context ctx, ShortcutInfo shortcut, int displayId) {
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
    /** The app's tasks on a display, front first, from the running list. */
    static List<android.app.ActivityManager.RunningTaskInfo> tasksOn(Context ctx,
            int display) {
        List<android.app.ActivityManager.RunningTaskInfo> out = new ArrayList<>();
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningTaskInfo task : am.getRunningTasks(40)) {
                Object d = Reflect.field(task, "displayId");
                if (d instanceof Integer && (Integer) d == display) {
                    out.add(task);
                }
            }
        } catch (Throwable t) {
            L.d("taskbar apps: could not list tasks (" + t + ")");
        }
        return out;
    }

    static String packageOf(android.app.ActivityManager.RunningTaskInfo task) {
        android.content.ComponentName c = task.baseIntent != null
                && task.baseIntent.getComponent() != null ? task.baseIntent.getComponent()
                : task.topActivity != null ? task.topActivity : task.baseActivity;
        return c == null ? null : c.getPackageName();
    }

    private static android.app.ActivityManager.RunningTaskInfo taskOf(Context ctx, String pkg,
            int display) {
        for (android.app.ActivityManager.RunningTaskInfo task : tasksOn(ctx, display)) {
            if (pkg.equals(packageOf(task))) {
                return task;
            }
        }
        return null;
    }

    /**
     * Sends the app to the back of its screen, still running: whatever was under it comes
     * forward - the previous app, or the desktop. Nothing is stopped; an app on the monitor is
     * only ever closed by the user.
     */
    static void minimize(Context ctx, String pkg, int display) {
        minimize(ctx, pkg, display, -1);
    }

    /** The same, for one window: {@code taskId}, or the app's front one here when -1. */
    static void minimize(Context ctx, String pkg, int display, int taskId) {
        android.app.ActivityManager.RunningTaskInfo task = null;
        if (taskId >= 0) {
            for (android.app.ActivityManager.RunningTaskInfo t : tasksOn(ctx, display)) {
                if (t.taskId == taskId) {
                    task = t;
                    break;
                }
            }
        }
        if (task == null) {
            task = taskOf(ctx, pkg, display);
        }
        if (task == null) {
            L.i("taskbar apps: " + pkg + " has no window on display " + display);
            return;
        }
        minimizeTask(ctx, task, display);
    }

    /**
     * One window of the app - {@code taskId}, or its front one here when -1 - as big as it can
     * be, and in front, as ZUI's own maximise does: a floating window goes full screen, app and
     * all; a full-screen one already is as big as it gets.
     */
    static void maximize(Context ctx, String pkg, int display, int taskId) {
        android.app.ActivityManager.RunningTaskInfo task = null;
        for (android.app.ActivityManager.RunningTaskInfo t : tasksOn(ctx, display)) {
            if (taskId >= 0 ? t.taskId == taskId : pkg.equals(packageOf(t))) {
                task = t;
                break;
            }
        }
        if (task == null) {
            L.i("taskbar apps: " + pkg + " has no window on display " + display + " to maximise");
            return;
        }
        int id = task.taskId;
        int mode = windowingMode(task);
        if (mode != WINDOWING_MODE_FREEFORM) {
            // Full screen, a split, or unreadable: nothing to grow. Forcing an app into full
            // screen mode, as the last version did, letterboxed one that keeps its own shape.
            L.i("taskbar apps: " + pkg + " (task " + id + ", mode " + mode
                    + ") is not a floating window - brought to front only");
            TaskOverview.bringToFront(id, display);
            return;
        }
        // What ZUI's own maximise in a window's menu leaves - the probe after it says so: the
        // window full screen, the whole screen. The app's activity is put back to following its
        // window too: switching the window alone left the activity floating inside it at its old
        // size, an app the size of a phone on black.
        boolean asked = toFullScreen(task);
        TaskOverview.bringToFront(id, display);
        L.i("taskbar apps: maximising " + pkg + " (task " + id + ") to full screen"
                + (asked ? "" : " - refused, sizing it to the screen instead"));
        if (!asked) {
            sizeToScreen(ctx, task, display);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            for (android.app.ActivityManager.RunningTaskInfo t : tasksOn(ctx, display)) {
                if (t.taskId == id) {
                    int now = windowingMode(t);
                    L.i("taskbar apps: maximised " + pkg + " is now mode " + now + " at "
                            + boundsOf(t));
                    if (now == WINDOWING_MODE_FREEFORM) {
                        // Kept floating after all: as big as a floating window may be.
                        sizeToScreen(ctx, t, display);
                    } else if (now == WINDOWING_MODE_FULLSCREEN) {
                        fillIfLetterboxed(pkg, t);
                    }
                    return;
                }
            }
        }, MAXIMIZE_CHECK_MS);
    }

    /**
     * The window and its app's activity full screen, with no size of their own, in front - one
     * transaction, so it lands as one step. False when refused.
     */
    private static boolean toFullScreen(android.app.ActivityManager.RunningTaskInfo task) {
        try {
            Object token = Reflect.field(task, "token");
            if (token == null) {
                return false;
            }
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("setWindowingMode", tokenClass, int.class)
                    .invoke(wct, token, WINDOWING_MODE_FULLSCREEN);
            try {
                wctClass.getMethod("setActivityWindowingMode", tokenClass, int.class)
                        .invoke(wct, token, WINDOWING_MODE_UNDEFINED);
            } catch (NoSuchMethodException ignored) {
                // An older build: the window alone, as before.
            }
            wctClass.getMethod("setBounds", tokenClass, android.graphics.Rect.class)
                    .invoke(wct, token, new android.graphics.Rect());
            wctClass.getMethod("reorder", tokenClass, boolean.class).invoke(wct, token, true);
            Class<?> organizer = Class.forName("android.window.WindowOrganizer");
            organizer.getMethod("applyTransaction", wctClass)
                    .invoke(organizer.getConstructor().newInstance(), wct);
            return true;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            L.i("taskbar apps: full screen refused (" + cause + ")");
            return false;
        }
    }

    /**
     * An app that keeps the size it was opened at - a phone-sized box on black once its window is
     * full screen - reopened to fill it, as the system's own restart button for such an app does
     * and as ZUI's maximise leaves it. Only when the system says it is boxed in.
     */
    private static void fillIfLetterboxed(String pkg,
            android.app.ActivityManager.RunningTaskInfo task) {
        Object compat = Reflect.field(task, "appCompatTaskInfo");
        Object boxed = compat == null ? null : Reflect.call(compat, "isTopActivityLetterboxed");
        Object boxWidth = compat == null ? null : Reflect.field(compat, "topActivityLetterboxWidth");
        Object bounds = boundsOf(task);
        if (!Boolean.TRUE.equals(boxed) && boxWidth instanceof Integer
                && bounds instanceof android.graphics.Rect && (Integer) boxWidth > 0
                && (Integer) boxWidth < ((android.graphics.Rect) bounds).width()) {
            // Narrower than its window: boxed in, whatever the flag says yet.
            boxed = Boolean.TRUE;
        }
        if (!Boolean.TRUE.equals(boxed)) {
            L.i("taskbar apps: " + pkg + " fills its window (" + (boxed == null
                    ? "letterboxing unreadable" : "not letterboxed") + ")");
            return;
        }
        try {
            Object token = Reflect.field(task, "token");
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> organizer = Class.forName("android.window.TaskOrganizer");
            organizer.getMethod("restartTaskTopActivityProcessIfVisible", tokenClass)
                    .invoke(organizer.getConstructor().newInstance(), token);
            L.i("taskbar apps: " + pkg + " was letterboxed - relaunched to fill the screen");
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            L.i("taskbar apps: " + pkg + " letterboxed, relaunch refused (" + cause + ")");
        }
    }

    private static final int WINDOWING_MODE_UNDEFINED = 0;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    /**
     * The fallback: still floating, as big as the system lets one be - below the status bar,
     * where it moves any window that reaches higher, and above the taskbar.
     */
    private static void sizeToScreen(Context ctx, android.app.ActivityManager.RunningTaskInfo task,
            int display) {
        android.graphics.Rect area = maximizedArea(ctx, display);
        if (area == null) {
            return;
        }
        String how = resizeBySystem(task.taskId, area) ? "system resize"
                : resizeByTransaction(task, area) ? "window transaction" : "nothing";
        L.i("taskbar apps: task " + task.taskId + " sized to " + area + " by " + how);
    }

    private static Object boundsOf(android.app.ActivityManager.RunningTaskInfo task) {
        Object config = Reflect.field(task, "configuration");
        Object window = config == null ? null : Reflect.field(config, "windowConfiguration");
        return window == null ? null : Reflect.call(window, "getBounds");
    }

    private static final long MAXIMIZE_CHECK_MS = 700L;

    /** {@code IActivityTaskManager.resizeTask}, in the system's own mode; false when refused. */
    private static boolean resizeBySystem(int taskId, android.graphics.Rect area) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            atm.getClass().getMethod("resizeTask", int.class, android.graphics.Rect.class,
                    int.class).invoke(atm, taskId, area, RESIZE_MODE_SYSTEM);
            return true;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            L.i("taskbar apps: system resize refused (" + cause + ")");
            return false;
        }
    }

    private static final int RESIZE_MODE_SYSTEM = 0;

    /** The window organizer's bounds change, as before; false when refused. */
    private static boolean resizeByTransaction(android.app.ActivityManager.RunningTaskInfo task,
            android.graphics.Rect area) {
        try {
            Object token = Reflect.field(task, "token");
            if (token == null) {
                return false;
            }
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("setBounds", tokenClass, android.graphics.Rect.class)
                    .invoke(wct, token, area);
            wctClass.getMethod("reorder", tokenClass, boolean.class).invoke(wct, token, true);
            Class<?> organizer = Class.forName("android.window.WindowOrganizer");
            organizer.getMethod("applyTransaction", wctClass)
                    .invoke(organizer.getConstructor().newInstance(), wct);
            return true;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            L.i("taskbar apps: window transaction refused (" + cause + ")");
            return false;
        }
    }

    private static final int WINDOWING_MODE_FREEFORM = 5;

    /** The task's windowing mode, from its configuration; -1 when it cannot be read. */
    private static int windowingMode(android.app.ActivityManager.RunningTaskInfo task) {
        Object config = Reflect.field(task, "configuration");
        Object window = config == null ? null : Reflect.field(config, "windowConfiguration");
        Object mode = window == null ? null : Reflect.call(window, "getWindowingMode");
        return mode instanceof Integer ? (Integer) mode : -1;
    }

    /**
     * The most a floating window may cover: below the status bar - the system moves a window
     * that reaches higher down by as much, into the taskbar - and above the taskbar.
     */
    private static android.graphics.Rect maximizedArea(Context ctx, int display) {
        try {
            android.hardware.display.DisplayManager dm =
                    ctx.getSystemService(android.hardware.display.DisplayManager.class);
            android.view.Display d = dm == null ? null : dm.getDisplay(display);
            if (d == null) {
                return null;
            }
            android.view.WindowManager wm = ctx.createDisplayContext(d)
                    .getSystemService(android.view.WindowManager.class);
            android.view.WindowMetrics metrics = wm.getMaximumWindowMetrics();
            android.graphics.Rect area = new android.graphics.Rect(metrics.getBounds());
            android.graphics.Insets cutout = metrics.getWindowInsets()
                    .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout()
                            | android.view.WindowInsets.Type.statusBars());
            android.graphics.Insets bars = metrics.getWindowInsets()
                    .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars());
            int bottom = Math.max(bars.bottom, Windows.taskbarHeight(display));
            area.set(area.left + cutout.left, area.top + cutout.top, area.right - cutout.right,
                    area.bottom - bottom);
            return area.isEmpty() ? null : area;
        } catch (Throwable t) {
            L.d("taskbar apps: screen size unreadable (" + t + ")");
            return null;
        }
    }

    /** The app's windows on this display, as task ids in the order they were opened. */
    static List<Integer> windowIds(Context ctx, String pkg, int display) {
        List<Integer> ids = new ArrayList<>();
        for (android.app.ActivityManager.RunningTaskInfo t : tasksOn(ctx, display)) {
            if (pkg.equals(packageOf(t)) && TaskbarRunning.isOpen(t)) {
                ids.add(t.taskId);
            }
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    /**
     * The window the app's own icon stands for: its first, for good, when it has several - so
     * a tap, Close or Minimize on it never lands on whichever happened to be in front. -1 for
     * one window or none, where "the app" says it already.
     */
    private static int firstOf(List<Integer> ids) {
        return ids.size() > 1 ? ids.get(0) : -1;
    }

    /**
     * A tap on an app that is already open here brings its window forward - its first one, the
     * same the taskbar's icon stands for - instead of opening another. True when it did; false
     * for an app with no window on this display, which is then launched as usual.
     */
    public static boolean bringIfOpen(Context ctx, String pkg, int display) {
        if (ctx == null || pkg == null) {
            return false;
        }
        List<Integer> ids = windowIds(ctx, pkg, display);
        if (ids.isEmpty()) {
            return false;
        }
        if (TaskOverview.bringToFront(ids.get(0), display)) {
            L.i("taskbar apps: " + pkg + " is open - brought window " + ids.get(0) + " forward");
            return true;
        }
        return false;
    }

    /**
     * The window in front of everything on this screen, or null: the focused one, or failing
     * that the first one listed if it is showing.
     */
    static android.app.ActivityManager.RunningTaskInfo frontTask(Context ctx, int display) {
        List<android.app.ActivityManager.RunningTaskInfo> tasks = tasksOn(ctx, display);
        // Focused and showing. A window sent to the back keeps the focus when nothing else on
        // its screen takes it, and counting it as in front made every tap on its icon minimise
        // it again - the window could never be brought back from the bar.
        for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
            if (Boolean.TRUE.equals(Reflect.field(t, "isFocused"))
                    && !Boolean.FALSE.equals(Reflect.field(t, "isVisible"))) {
                return t;
            }
        }
        if (!tasks.isEmpty() && Boolean.TRUE.equals(Reflect.field(tasks.get(0), "isVisible"))) {
            return tasks.get(0);
        }
        return null;
    }

    /**
     * A click on an open app's icon, as a desktop taskbar does it: the app already in front is
     * minimised, rather than opened again over itself. True when it was - the click is done.
     *
     * @param taskId one window of the app, or -1 for whichever of its windows is in front
     */
    static boolean minimizeIfFront(Context ctx, String pkg, int taskId, int display) {
        try {
            android.app.ActivityManager.RunningTaskInfo front = frontTask(ctx, display);
            if (front == null || !pkg.equals(packageOf(front))
                    || (taskId >= 0 && front.taskId != taskId)) {
                return false;
            }
            L.i("taskbar apps: " + pkg + " is in front - minimizing it, not reopening");
            minimizeTask(ctx, front, display);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void minimizeTask(Context ctx, android.app.ActivityManager.RunningTaskInfo task,
            int display) {
        String pkg = packageOf(task);
        // The window itself goes to the back - under the desktop - and stays running. The
        // monitor's windows are free-floating (the probe lists them as freeform), so bringing
        // another app forward, as this used to, left the "minimised" one in sight.
        String refused = sendToBack(task);
        if (refused == null) {
            L.i("taskbar apps: minimized " + pkg + " (task " + task.taskId + ")");
            return;
        }
        // Not ours to reorder: the system half does it, if System Framework is ticked.
        int taskId = task.taskId;
        try {
            Intent intent = new Intent(SystemBridge.ACTION_MINIMIZE).setPackage("android")
                    .putExtra(SystemBridge.EXTRA_TASK, taskId);
            android.app.BroadcastOptions options = android.app.BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            ctx.sendBroadcast(intent, null, options.toBundle());
        } catch (Throwable t) {
            L.d("taskbar apps: could not ask the system to minimise (" + t + ")");
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            android.app.ActivityManager.RunningTaskInfo still = null;
            for (android.app.ActivityManager.RunningTaskInfo t : tasksOn(ctx, display)) {
                if (t.taskId == taskId) {
                    still = t;
                }
            }
            if (still == null || !Boolean.TRUE.equals(Reflect.field(still, "isVisible"))) {
                L.i("taskbar apps: minimized " + pkg + " through the system");
                return;
            }
            // Neither could: the desktop in front of everything, by home on that screen.
            String route = TaskbarNav.key(ctx, android.view.KeyEvent.KEYCODE_HOME, display, null);
            L.i("taskbar apps: could not send " + pkg + " back (" + refused
                    + "); showed the desktop instead (" + route + ")");
        }, 400L);
    }

    /**
     * Moves a task to the bottom of its screen, the way a minimise button does. Null when done,
     * else why not.
     */
    private static String sendToBack(android.app.ActivityManager.RunningTaskInfo task) {
        try {
            Object token = Reflect.field(task, "token");
            if (token == null) {
                return "no window token";
            }
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("reorder", Class.forName("android.window.WindowContainerToken"),
                    boolean.class).invoke(wct, token, false);
            Class<?> organizer = Class.forName("android.window.WindowOrganizer");
            organizer.getMethod("applyTransaction", wctClass)
                    .invoke(organizer.getConstructor().newInstance(), wct);
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /**
     * Whether the app can have a second window at all. An app whose main screen is single-task
     * or single-instance is always brought back to its one window, whatever the
     * launch asks for; offering it a "new window" only reopened it.
     */
    static boolean allowsWindows(Context ctx, String pkg) {
        try {
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            android.content.pm.ResolveInfo ri = intent == null ? null
                    : ctx.getPackageManager().resolveActivity(intent, 0);
            if (ri == null || ri.activityInfo == null) {
                return false;
            }
            android.content.pm.ActivityInfo ai = ri.activityInfo;
            if (ai.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
                    || ai.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                return false;
            }
            return ai.documentLaunchMode
                    != android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_NEVER;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Another window of the app, beside the one already open. Apps that allow it open a second
     * one; an app that only ever has one just comes forward.
     */
    /**
     * Whether the new window came, checked for a few seconds: a heavy app can take more than a
     * second to make its task, and checking once at 1.5 s said "only one window" for Claude, whose
     * second window then opened anyway.
     */
    private static void watchNewWindow(Context ctx, String pkg, int display, int before,
            int round) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            int after = windowsOf(ctx, pkg, display);
            if (after > before) {
                L.i("taskbar apps: new window of " + pkg + " on display " + display + " ("
                        + after + " now, " + (round + 1) * 400 + "ms)");
                return;
            }
            if (round < 11) {
                watchNewWindow(ctx, pkg, display, before, round + 1);
                return;
            }
            boolean system = SystemNewWindow.active();
            L.i("taskbar apps: " + pkg + " reopened its one window instead of a new one"
                    + " (single-task=" + !allowsWindows(ctx, pkg) + ", system part="
                    + system + ")");
            TaskbarMenu.toast(ctx, system || allowsWindows(ctx, pkg)
                    ? "This app only allows one window"
                    : "For a second window of this app, tick System Framework for ZuxOS "
                            + "Desktop Plus in LSPosed and reboot");
        }, 400L);
    }

    private static int windowsOf(Context ctx, String pkg, int display) {
        int n = 0;
        for (android.app.ActivityManager.RunningTaskInfo t : tasksOn(ctx, display)) {
            if (pkg.equals(packageOf(t))) {
                n++;
            }
        }
        return n;
    }

    static void newWindow(Context ctx, String pkg, int display) {
        try {
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            // Read by the system's part of the module: a new task even for an app whose main
            // screen is single-task (Termux), which Android would otherwise reuse.
            intent.putExtra(SystemNewWindow.EXTRA, true);
            int before = windowsOf(ctx, pkg, display);
            ctx.startActivity(intent, TaskbarMenu.launchOptions(display));
            watchNewWindow(ctx, pkg, display, before, 0);
        } catch (Throwable t) {
            L.e("taskbar apps: could not open a new window of " + pkg, t);
        }
    }

    static void close(Context ctx, String pkg) {
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

    /**
     * Closes one window of the app - {@code taskId}, or its front one here when that is -1 -
     * and leaves its other windows running: removed by the launcher when it may, otherwise by
     * the system's part of the module. Never stops the app, which would take every window.
     */
    static void closeWindow(Context ctx, String pkg, int display, int taskId) {
        int task = taskId;
        if (task < 0) {
            android.app.ActivityManager.RunningTaskInfo front = taskOf(ctx, pkg, display);
            if (front == null) {
                return;
            }
            task = front.taskId;
        }
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            Object removed = atm.getClass().getMethod("removeTask", int.class).invoke(atm, task);
            if (!Boolean.FALSE.equals(removed)) {
                L.i("taskbar apps: closed window " + task + " of " + pkg);
                return;
            }
        } catch (Throwable t) {
            L.d("taskbar apps: the launcher may not close a window ("
                    + (t.getCause() != null ? t.getCause() : t) + "); asking the system");
        }
        try {
            Intent intent = new Intent(SystemBridge.ACTION_CLOSE_TASK).setPackage("android")
                    .putExtra(SystemBridge.EXTRA_TASK, task);
            android.app.BroadcastOptions options = android.app.BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            ctx.sendBroadcast(intent, null, options.toBundle());
            L.i("taskbar apps: asked the system to close window " + task + " of " + pkg);
        } catch (Throwable t) {
            L.e("taskbar apps: could not close window " + task + " of " + pkg, t);
        }
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
