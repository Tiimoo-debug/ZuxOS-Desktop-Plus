package com.zuxos.desktopplus.hook;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Storage;
import com.zuxos.desktopplus.core.Thermals;
import com.zuxos.desktopplus.core.glass.LiquidGlass;
import com.zuxos.desktopplus.core.glass.ScreenBackdrop;
import com.zuxos.desktopplus.core.motion.FrameRate;
import com.zuxos.desktopplus.hook.home.HostDetector;
import com.zuxos.desktopplus.hook.home.SurfaceAttacher;
import com.zuxos.desktopplus.hook.recents.RecentsRoute;
import com.zuxos.desktopplus.hook.recents.TaskOverview;
import com.zuxos.desktopplus.hook.taskbar.TaskbarApps;
import com.zuxos.desktopplus.hook.taskbar.TaskbarDiag;
import com.zuxos.desktopplus.hook.taskbar.TaskbarMenu;
import com.zuxos.desktopplus.hook.taskbar.TaskbarNav;
import com.zuxos.desktopplus.hook.taskbar.TaskbarRunning;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;


/**
 * Diagnostics.
 *
 * <p>Nobody can see inside your firmware's home app from the outside, so the module can print
 * exactly what it found: the activity, the display, the view tree with class names and ids.
 * That dump is what you use to fill in {@code rules.json} or to report what did not work.
 */
public final class Probe {

    private Probe() {
    }

    public static void dump(Activity activity, ViewGroup content) {
        try {
            if (content == null && activity.getWindow() != null) {
                // No content view: dump whatever the window does have, which is the interesting
                // part when an attach attempt just failed.
                View decor = activity.getWindow().peekDecorView();
                if (decor instanceof ViewGroup) {
                    content = (ViewGroup) decor;
                }
            }
            // Every window too, as the exported probe has: the taskbars are windows of their own,
            // and a dump without them could not show a bar at all.
            String text = describe(activity, content) + describeAllWindows();
            String out = Storage.export(activity, Storage.stamped(Const.FILE_PROBE), text);
            Storage.write(Storage.file(activity, Const.FILE_PROBE), text);
            L.i("probe dump" + (out != null ? " written to " + out : "") + ":\n" + text);
        } catch (Throwable t) {
            L.e("probe failed", t);
        }
    }

    /**
     * The probe from the taskbar, with whatever app open: no activity of the launcher's is at
     * hand there, so it is the bars, the tasks, the windows and what the bar has logged - the
     * part of the probe that is about the taskbar - written to the same file.
     *
     * <p>With what the screens and the device are spending ({@link PowerProbe}) and what each
     * planned feature needs ({@link RoadmapProbe}): the file is written a couple of seconds after
     * the tap, once the CPU has been watched.
     */
    public static void dumpFromBar(android.content.Context ctx, int displayId) {
        try {
            StringBuilder sb = new StringBuilder("ZuxOS Desktop Plus probe (from the taskbar)\n");
            sb.append("display : ").append(displayId).append('\n');
            String front = TaskbarRunning.frontPackage(displayId);
            sb.append("front   : ").append(front).append('\n');
            try {
                sb.append(describeTaskbarModel());
            } catch (Throwable t) {
                sb.append("\ntaskbar model\n  (unreadable: ").append(t).append(")\n");
            }
            try {
                sb.append(describeTasks(ctx, "  asked from the bar on display " + displayId
                        + "\n"));
            } catch (Throwable t) {
                sb.append("\nrunning tasks\n  (unreadable: ").append(t).append(")\n");
            }
            sb.append(TaskbarDiag.describeTraces());
            try {
                sb.append(TaskbarNav.describe());
                sb.append(RecentsRoute.describe());
                sb.append(KeepAlive.describe());
            } catch (Throwable ignored) {
                // The rest still goes out.
            }
            sb.append(describeAllWindows());
            // Into the log as well as the file: it is what a hot device is asked about.
            String powerNow = PowerProbe.now(ctx) + RoadmapProbe.now(ctx);
            sb.append(powerNow);
            TaskbarMenu.toast(ctx, "Probe: measuring for a few seconds");
            PowerProbe.sample(ctx, RoadmapProbe.ROOT_READ, power -> {
                try {
                    String text = sb.append(power).toString();
                    String out = Storage.export(ctx, Storage.stamped(Const.FILE_PROBE), text);
                    Storage.write(Storage.file(ctx, Const.FILE_PROBE), text);
                    L.i("probe from the taskbar" + (out != null ? " written to " + out : "")
                            + "\n" + powerNow + power);
                    TaskbarMenu.toast(ctx, out != null ? "Probe saved to Download" : "Probe saved");
                } catch (Throwable t) {
                    L.e("probe from the taskbar failed", t);
                }
            });
        } catch (Throwable t) {
            L.e("probe from the taskbar failed", t);
        }
    }

    public static String describe(Activity activity, ViewGroup content) {
        StringBuilder sb = new StringBuilder();
        sb.append("ZuxOS Desktop Plus probe\n");
        sb.append("package : ").append(activity.getPackageName()).append('\n');
        sb.append("activity: ").append(activity.getClass().getName()).append('\n');
        try {
            android.view.Display d = activity.getDisplay();
            if (d != null) {
                sb.append("display : id=").append(d.getDisplayId())
                        .append(" name=").append(d.getName())
                        .append(" flags=0x").append(Integer.toHexString(d.getFlags()))
                        .append(" state=").append(d.getState()).append('\n');
            }
        } catch (Throwable ignored) {
            sb.append("display : unavailable\n");
        }
        sb.append("home act: ").append(HostDetector.isHomeActivity(activity)).append('\n');
        sb.append("attach  : ").append(SurfaceAttacher.diagnose(activity)).append('\n');
        try {
            sb.append("widgets : ")
                    .append(AppWidgetManager.getInstance(activity).getInstalledProviders().size())
                    .append(" providers visible\n");
        } catch (Throwable t) {
            sb.append("widgets : provider list unavailable (").append(t).append(")\n");
        }
        sb.append("glass   : shaders ")
                .append(LiquidGlass.isSupported() ? "on" : "off")
                .append(", live screen capture ")
                .append(ScreenBackdrop.refused()
                        ? "refused (" + ScreenBackdrop.reason() + ")"
                        : "allowed or not tried yet")
                .append('\n');
        sb.append("\nview tree\n");
        if (content != null) {
            appendTree(sb, content, 0);
        } else {
            sb.append("  (no content view)\n");
        }
        try {
            sb.append(Thermals.describe());
        } catch (Throwable t) {
            sb.append("\nthermal zones\n  (unreadable: ").append(t).append(")\n");
        }
        sb.append("\nactivity class hierarchy\n");
        for (Class<?> c = activity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            sb.append("  ").append(c.getName()).append('\n');
        }
        sb.append("\nboolean methods on the activity class (candidates for rules.json)\n");
        for (java.lang.reflect.Method m : activity.getClass().getDeclaredMethods()) {
            if (m.getReturnType() == boolean.class) {
                sb.append("  ").append(m.getName()).append('(')
                        .append(m.getParameterCount()).append(" args)\n");
            }
        }
        // Every method the desktop activity declares - recents, home and overview handling live
        // here on this firmware, under names R8 left alone or did not.
        sb.append("\nall methods declared on the activity class\n");
        for (java.lang.reflect.Method m : activity.getClass().getDeclaredMethods()) {
            sb.append("  ").append(m.getReturnType().getSimpleName()).append(' ')
                    .append(m.getName()).append('(');
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                sb.append(i == 0 ? "" : ", ").append(types[i].getSimpleName());
            }
            sb.append(")\n");
        }
        // Harmless when the taskbar is not up yet - it says so and costs a line.
        try {
            sb.append(describeTaskbarModel());
        } catch (Throwable t) {
            sb.append("\ntaskbar model\n  (unreadable: ").append(t).append(")\n");
        }
        try {
            sb.append(describeTasks(activity));
        } catch (Throwable t) {
            sb.append("\nrunning tasks\n  (unreadable: ").append(t).append(")\n");
        }
        try {
            sb.append(TaskbarNav.describe());
            sb.append(TaskOverview.describe());
            sb.append(RecentsRoute.describe());
            sb.append(KeepAlive.describe());
            sb.append(FrameRate.describe(activity));
        } catch (Throwable t) {
            sb.append("\nrecents\n  (unreadable: ").append(t).append(")\n");
        }
        try {
            sb.append(describeGates(activity));
        } catch (Throwable t) {
            sb.append("\ngate methods\n  (unreadable: ").append(t).append(")\n");
        }
        return sb.toString();
    }

    /**
     * Every task the launcher can see, and where it is.
     *
     * <p>Here because "Recents shows the wrong thing" and "the app opened on the other screen" are
     * the same question asked twice: which display is the task actually on. The display id, the
     * windowing mode and the bounds answer it outright, and all three are fields the framework
     * keeps but does not publish - so each is read by name and skipped where it cannot be read,
     * the same way {@code TaskbarRunning} reads them.
     */
    private static String describeTasks(Activity activity) {
        int thisDisplay = activity.getDisplay() != null ? activity.getDisplay().getDisplayId() : -1;
        return describeTasks(activity, "  this activity is on display " + thisDisplay + "\n");
    }

    private static String describeTasks(android.content.Context ctx, String where) {
        StringBuilder sb = new StringBuilder("\nrunning tasks\n");
        android.app.ActivityManager am = (android.app.ActivityManager)
                ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                am == null ? null : am.getRunningTasks(25);
        if (tasks == null || tasks.isEmpty()) {
            return sb.append("  (the activity manager will not list them)\n").toString();
        }
        sb.append(where);
        for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
            sb.append("  ")
                    .append(task.baseActivity == null ? "?" : task.baseActivity.flattenToShortString())
                    .append("\n    display=").append(Reflect.field(task, "displayId"))
                    .append(" visible=").append(Reflect.field(task, "isVisible"))
                    .append(" running=").append(Reflect.field(task, "isRunning"))
                    .append(" focused=").append(Reflect.field(task, "isFocused"))
                    .append(" activities=").append(Reflect.field(task, "numActivities"))
                    .append("\n    mode=").append(windowingMode(task))
                    .append(" bounds=").append(bounds(task))
                    .append("\n    fit: ").append(TaskbarApps.appState(task))
                    .append('\n');
        }
        return sb.toString();
    }

    /** Where the task's window actually is, which is half of "it opened on the wrong screen". */
    private static String bounds(Object task) {
        Object window = windowConfig(task);
        Object rect = window == null ? null : Reflect.call(window, "getBounds");
        return rect == null ? "?" : String.valueOf(rect);
    }

    /**
     * The task's window configuration.
     *
     * <p>Reflected rather than called: {@code TaskInfo.getConfiguration} and the
     * {@code windowConfiguration} inside it are kept by the framework and not published, so naming
     * them in code would not compile against the public SDK even though they are there at runtime.
     */
    private static Object windowConfig(Object task) {
        try {
            Object config = Reflect.call(task, "getConfiguration");
            if (config == null) {
                config = Reflect.field(task, "configuration");
            }
            return config == null ? null : Reflect.field(config, "windowConfiguration");
        } catch (Throwable t) {
            return null;
        }
    }

    /** The windowing mode a task is in - full screen, split, freeform - by name where we have one. */
    public static String windowingMode(Object task) {
        try {
            Object window = windowConfig(task);
            Object mode = window == null ? null : Reflect.call(window, "getWindowingMode");
            if (!(mode instanceof Integer)) {
                return "?";
            }
            switch ((Integer) mode) {
                case 1: return "fullscreen";
                case 2: return "pinned";
                case 3: return "split-primary";
                case 4: return "split-secondary";
                case 5: return "freeform";
                case 6: return "multi-window";
                default: return String.valueOf(mode);
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * The launcher's own switches for what the stock desktop will and will not allow.
     *
     * <p>{@code StockUnlockHooks} patches these, and on this firmware it found exactly one, from a
     * list of class names guessed out of AOSP. The names it could not guess are here: every
     * no-argument boolean method on the classes the launcher actually built, which is what a rule
     * in {@code rules.json} needs to name one.
     */
    private static String describeGates(Activity activity) {
        StringBuilder sb = new StringBuilder("\ngate methods (candidates for rules.json)\n");
        java.util.LinkedHashSet<Class<?>> classes = new java.util.LinkedHashSet<>();
        for (Class<?> c = activity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            classes.add(c);
        }
        // The views the desktop is actually made of, which is where the editing gates live.
        for (View view : Reflect.findByClassFragments(activity.getWindow().getDecorView(),
                "Workspace", "CellLayout", "DragLayer", "Launcher", "Desktop", "Hotseat")) {
            if (!view.getClass().getName().startsWith("com.zuxos")) {
                classes.add(view.getClass());
            }
        }
        for (Class<?> cls : classes) {
            StringBuilder names = new StringBuilder();
            try {
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getReturnType() != boolean.class || m.getParameterCount() != 0) {
                        continue;
                    }
                    names.append(names.length() == 0 ? "" : ", ").append(m.getName());
                }
            } catch (Throwable t) {
                names.append("<unreadable>");
            }
            if (names.length() > 0) {
                sb.append("  ").append(cls.getName()).append("\n    ").append(names).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Every root view in the process.
     *
     * <p>The taskbar and its app drawer are separate windows, so the activity's tree alone does
     * not show them - this is what identifies the classes behind the stock drawer.
     */
    public static String describeAllWindows() {
        StringBuilder sb = new StringBuilder("\nall windows in this process\n");
        java.util.List<View> roots = Windows.roots();
        if (roots.isEmpty()) {
            return sb.append("  (no window list on this build)\n").toString();
        }
        sb.append("  ").append(roots.size()).append(" window(s)\n");
        for (View root : roots) {
            sb.append("\n  --- window: ").append(root.getClass().getName());
            try {
                sb.append(" display=").append(root.getDisplay() != null
                        ? root.getDisplay().getDisplayId() : "?");
            } catch (Throwable ignored) {
                sb.append(" display=?");
            }
            sb.append(" visible=").append(root.getVisibility() == View.VISIBLE).append('\n');
            appendTree(sb, root, 2);
        }
        sb.append(describeDrawables(roots));
        return sb.toString();
    }

    /** The home screen's views that can paint things of their own beyond their children. */
    private static final String[] DRAWING_CLASSES = {
            "ScrimView", "PageIndicatorDots", "Workspace", "DragLayer", "ZuiHotseat",
            "StaticBlurView"};

    /**
     * What the home screen's own painters hold to paint with: each Drawable field's name, class,
     * size and bounds. What draws a mark that is no view of its own is in this list.
     */
    private static String describeDrawables(java.util.List<View> roots) {
        StringBuilder sb = new StringBuilder("\ndrawables on the home screen\n");
        java.util.List<View> queue = new java.util.ArrayList<>();
        for (View root : roots) {
            queue.add(root);
        }
        for (int i = 0; i < queue.size() && i < 4000; i++) {
            View v = queue.get(i);
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int c = 0; c < g.getChildCount(); c++) {
                    queue.add(g.getChildAt(c));
                }
            }
            String simple = v.getClass().getSimpleName();
            boolean wanted = false;
            for (String name : DRAWING_CLASSES) {
                wanted |= simple.equals(name);
            }
            if (!wanted) {
                continue;
            }
            sb.append("  ").append(v.getClass().getName()).append(" [")
                    .append(v.getWidth()).append('x').append(v.getHeight()).append("] bg=")
                    .append(v.getBackground() == null ? "none"
                            : v.getBackground().getClass().getSimpleName())
                    .append(" alpha=").append(v.getAlpha())
                    .append(v.isShown() ? " shown" : " not shown").append('\n');
            if (simple.equals("ZuiHotseat") && v instanceof android.view.ViewGroup) {
                // What each of the dock's own children paints with: the pill is one of them.
                android.view.ViewGroup dock = (android.view.ViewGroup) v;
                for (int c = 0; c < dock.getChildCount(); c++) {
                    View kid = dock.getChildAt(c);
                    sb.append("    child ").append(kid.getClass().getSimpleName()).append(" [")
                            .append(kid.getWidth()).append('x').append(kid.getHeight())
                            .append("] vis=").append(kid.getVisibility())
                            .append(" bg=").append(kid.getBackground() == null ? "none"
                                    : kid.getBackground().getClass().getSimpleName())
                            .append(" fg=").append(kid.getForeground() == null ? "none"
                                    : kid.getForeground().getClass().getSimpleName())
                            .append('\n');
                }
            }
            for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            || !android.graphics.drawable.Drawable.class.isAssignableFrom(
                                    f.getType())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object d = f.get(v);
                        sb.append("    ").append(c.getSimpleName()).append('.')
                                .append(f.getName()).append(" = ");
                        if (d == null) {
                            sb.append("null\n");
                            continue;
                        }
                        android.graphics.drawable.Drawable dr =
                                (android.graphics.drawable.Drawable) d;
                        sb.append(d.getClass().getSimpleName()).append(' ')
                                .append(dr.getIntrinsicWidth()).append('x')
                                .append(dr.getIntrinsicHeight()).append(" at ")
                                .append(dr.getBounds()).append(" alpha ").append(dr.getAlpha())
                                .append('\n');
                    } catch (Throwable t) {
                        sb.append("<unreadable>\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * The taskbar's model, rather than its views.
     *
     * <p>Four things asked for - show only running apps, a menu on long press, a shortcut opened
     * from the taskbar showing as running, and dragging onto it - all live below the views, in
     * whatever Launcher3 calls its taskbar controllers on this firmware. This is what names them,
     * so the next round hooks the right thing instead of guessing.
     *
     * <p>What each part is for: the icons' tags say how an icon got there (pinned, predicted, or
     * a running task); the context's controllers name the object that decides that; and the
     * shortcut-host answer says whether an app's own shortcuts can be listed for the menu.
     */
    public static String describeTaskbarModel() {
        StringBuilder sb = new StringBuilder("\ntaskbar model\n");
        int found = 0;
        for (View root : Windows.roots()) {
            ViewGroup bar = TaskbarTray.dragLayerOf(root);
            if (bar != null) {
                found++;
                // Every one of them. With an external display the launcher runs a taskbar per
                // display, and describing the built-in one would say nothing about the one being
                // worked on.
                sb.append("\n  --- taskbar on display ").append(displayOf(root)).append('\n');
                try {
                    sb.append("  built with: ").append(TaskbarDiag.snapshot(bar)).append('\n');
                    describeTaskbar(sb, bar);
                } catch (Throwable t) {
                    sb.append("  (unreadable: ").append(t).append(")\n");
                }
            }
        }
        if (found == 0) {
            sb.append("  (no taskbar window open)\n");
        }
        return sb.toString();
    }

    private static String displayOf(View root) {
        try {
            return root.getDisplay() != null
                    ? String.valueOf(root.getDisplay().getDisplayId()) : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void describeTaskbar(StringBuilder sb, ViewGroup dragLayer) {
        View row = TaskbarTray.rowReference(dragLayer);
        sb.append("  icon row: ").append(row == null ? "not found"
                : row.getClass().getName()).append('\n');
        if (row instanceof ViewGroup) {
            ViewGroup icons = (ViewGroup) row;
            for (int i = 0; i < icons.getChildCount(); i++) {
                View icon = icons.getChildAt(i);
                Object tag = icon.getTag();
                sb.append("    [").append(i).append("] ")
                        .append(icon.getClass().getSimpleName())
                        .append(" tag=").append(tag == null ? "null"
                                : tag.getClass().getName())
                        .append('\n');
                if (tag != null) {
                    appendFields(sb, tag, "      ");
                }
            }
        }

        Object ctx = dragLayer.getContext();
        sb.append("  taskbar context: ").append(ctx.getClass().getName()).append('\n');
        appendFieldTypes(sb, ctx, "    ");

        Object controllers = fieldWhoseTypeContains(ctx, "Controllers");
        if (controllers != null) {
            sb.append("  controllers: ").append(controllers.getClass().getName()).append('\n');
            appendFieldTypes(sb, controllers, "    ");
            for (String want : new String[]{"RecentApps", "Popup", "ViewController", "Model"}) {
                Object controller = fieldWhoseTypeContains(controllers, want);
                if (controller == null) {
                    continue;
                }
                sb.append("  ").append(want).append(" -> ")
                        .append(controller.getClass().getName()).append('\n');
                appendMethods(sb, controller, "    ");
                appendFields(sb, controller, "    ");
            }
        }

        try {
            android.content.pm.LauncherApps apps = (android.content.pm.LauncherApps)
                    dragLayer.getContext().getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
            sb.append("  shortcut host: ")
                    .append(apps != null && apps.hasShortcutHostPermission()).append('\n');
        } catch (Throwable t) {
            sb.append("  shortcut host: unreadable (").append(t).append(")\n");
        }
    }

    /** Field names and types only - for objects whose values are other objects. */
    private static void appendFieldTypes(StringBuilder sb, Object target, String indent) {
        int printed = 0;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            java.lang.reflect.Field[] fields = declaredFields(c);
            for (java.lang.reflect.Field f : fields) {
                if (printed++ > 60) {
                    return;
                }
                try {
                    sb.append(indent).append(f.getName()).append(" : ")
                            .append(f.getType().getSimpleName()).append('\n');
                } catch (Throwable ignored) {
                    // A field whose type will not load; its name is not worth the whole dump.
                }
            }
        }
    }

    /**
     * Declared fields, or none.
     *
     * <p>Reading a class's fields can itself fail - a field whose type belongs to a class this
     * build does not have throws while the list is being built. This is a diagnostic; it must
     * never be the reason a diagnostic could not be written.
     */
    private static java.lang.reflect.Field[] declaredFields(Class<?> c) {
        try {
            return c.getDeclaredFields();
        } catch (Throwable t) {
            return new java.lang.reflect.Field[0];
        }
    }

    /** Field names with their values, where a value is small enough to be worth printing. */
    private static void appendFields(StringBuilder sb, Object target, String indent) {
        int printed = 0;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : declaredFields(c)) {
                if (printed++ > 40) {
                    return;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(target);
                    String text = value == null ? "null" : String.valueOf(value);
                    if (text.length() > 120) {
                        text = text.substring(0, 120) + "...";
                    }
                    sb.append(indent).append(f.getName()).append(" (")
                            .append(f.getType().getSimpleName()).append(") = ")
                            .append(text).append('\n');
                } catch (Throwable ignored) {
                    // Unreadable field; the name and type above are still worth having.
                }
            }
        }
    }

    private static void appendMethods(StringBuilder sb, Object target, String indent) {
        StringBuilder names = new StringBuilder();
        java.lang.reflect.Method[] methods;
        try {
            methods = target.getClass().getDeclaredMethods();
        } catch (Throwable t) {
            sb.append(indent).append("methods: unreadable (").append(t).append(")\n");
            return;
        }
        for (java.lang.reflect.Method m : methods) {
            if (names.length() > 600) {
                break;
            }
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(m.getName()).append('(').append(m.getParameterCount()).append(')');
        }
        sb.append(indent).append("methods: ").append(names).append('\n');
    }

    private static Object fieldWhoseTypeContains(Object target, String fragment) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : declaredFields(c)) {
                try {
                    if (!f.getType().getSimpleName().contains(fragment)) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object value = f.get(target);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // Keep looking.
                }
            }
        }
        return null;
    }

    private static void appendTree(StringBuilder sb, View v, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        String id = Reflect.idName(v);
        sb.append(v.getClass().getName())
                .append(id != null ? " #" + id : "")
                .append(" [").append(v.getWidth()).append('x').append(v.getHeight())
                // Where it is, not just how big. A row with a hole in it, or two icons spread a
                // whole icon apart, is invisible in sizes alone and obvious in positions.
                .append(" @").append(v.getLeft()).append(',').append(v.getTop()).append(']')
                .append(v.getVisibility() == View.VISIBLE ? "" : " (hidden)")
                // How the launcher hides a bar without hiding its views: faded, slid, shrunk.
                .append(v.getAlpha() < 1f ? " alpha=" + v.getAlpha() : "")
                .append(v.getTranslationY() != 0f ? " ty=" + v.getTranslationY() : "")
                .append(v.getScaleY() != 1f ? " scale=" + v.getScaleY() : "")
                .append(v.hasOnClickListeners() ? " (click)" : "")
                .append('\n');
        if (v instanceof ViewGroup && depth < 12) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                appendTree(sb, g.getChildAt(i), depth + 1);
            }
        }
    }
}
