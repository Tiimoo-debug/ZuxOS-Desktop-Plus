package com.zuxos.desktopplus.hook;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Storage;

import java.io.File;

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
            String text = describe(activity, content);
            File out = Storage.exportCopy(activity, Const.FILE_PROBE, text);
            Storage.write(Storage.file(activity, Const.FILE_PROBE), text);
            L.i("probe dump" + (out != null ? " written to " + out.getAbsolutePath() : "") + ":\n" + text);
        } catch (Throwable t) {
            L.e("probe failed", t);
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
        sb.append("\nview tree\n");
        if (content != null) {
            appendTree(sb, content, 0);
        } else {
            sb.append("  (no content view)\n");
        }
        try {
            sb.append(com.zuxos.desktopplus.core.Thermals.describe());
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
        // Harmless when the taskbar is not up yet - it says so and costs a line.
        try {
            sb.append(describeTaskbarModel());
        } catch (Throwable t) {
            sb.append("\ntaskbar model\n  (unreadable: ").append(t).append(")\n");
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
            if (TaskbarTray.isTaskbar(root) && root instanceof ViewGroup) {
                found++;
                // Every one of them. With an external display the launcher runs a taskbar per
                // display, and describing the built-in one would say nothing about the one being
                // worked on.
                sb.append("\n  --- taskbar on display ").append(displayOf(root)).append('\n');
                try {
                    describeTaskbar(sb, (ViewGroup) root);
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
                .append(" [").append(v.getWidth()).append('x').append(v.getHeight()).append(']')
                .append(v.getVisibility() == View.VISIBLE ? "" : " (hidden)")
                .append('\n');
        if (v instanceof ViewGroup && depth < 12) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                appendTree(sb, g.getChildAt(i), depth + 1);
            }
        }
    }
}
