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
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            Object roots = global.getMethod("getRootViews").invoke(instance);
            if (!(roots instanceof java.util.List)) {
                return sb.append("  (unavailable)\n").toString();
            }
            java.util.List<?> list = (java.util.List<?>) roots;
            sb.append("  ").append(list.size()).append(" window(s)\n");
            for (Object o : list) {
                if (!(o instanceof View)) {
                    continue;
                }
                View root = (View) o;
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
        } catch (Throwable t) {
            sb.append("  (not readable: ").append(t).append(")\n");
        }
        return sb.toString();
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
