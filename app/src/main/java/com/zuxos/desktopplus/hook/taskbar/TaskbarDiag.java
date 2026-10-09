package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.Windows;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * What decides the tablet's taskbar layout on the home screen, read off the device.
 *
 * <p>In portrait ZUI rebuilds the tablet's bar for its home screen in a phone-style mode - keys
 * across the middle, no taskbar - and for an app as a taskbar. The switch is in the launcher's
 * obfuscated taskbar code, so before anything is changed there this says, for each bar, which
 * flags it was built with and which layout its keys got: a home bar and an app bar side by side
 * name the flag that flips. Reading only; nothing here changes what ZUI does.
 */
public final class TaskbarDiag {

    private static final String FACTORY =
            "com.android.launcher3.taskbar.navbutton.NavButtonLayoutFactory";

    private static boolean sInstalled;
    private static String sLastFactory;
    private static String sLastSnapshot;

    private TaskbarDiag() {
    }

    static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        int hooked = 0;
        for (String name : new String[]{FACTORY, FACTORY + "$Companion"}) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                for (Method m : cls.getDeclaredMethods()) {
                    if (Modifier.isAbstract(m.getModifiers()) || m.getParameterCount() < 4) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, FACTORY_CALL);
                    hooked++;
                }
            } catch (Throwable ignored) {
                // Not on this build.
            }
        }
        Health.hooked("diagnostics: taskbar layout factory", hooked);
        L.i("taskbar diag: layout factory watched x" + hooked);
        installTrace(loader);
    }

    // --- what ZUI calls when home and an app trade places -------------------------------------

    private static final String NAV_CONTROLLER =
            "com.android.launcher3.taskbar.NavbarButtonsViewController";
    /** The last calls into ZUI's nav button controller: names and times, oldest overwritten. */
    private static final int RING = 160;
    private static final String[] RING_NAMES = new String[RING];
    private static final long[] RING_TIMES = new long[RING];
    private static int sRingNext;
    private static int sTraced;
    private static final java.util.ArrayDeque<String> TRACES = new java.util.ArrayDeque<>();
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static void installTrace(ClassLoader loader) {
        try {
            Class<?> cls = Class.forName(NAV_CONTROLLER, false, loader);
            int hooked = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, RECORD);
                    hooked++;
                } catch (Throwable ignored) {
                    // One method less.
                }
            }
            Health.hooked("diagnostics: nav controller", hooked);
            L.i("taskbar diag: nav controller traced x" + hooked);
        } catch (Throwable t) {
            L.i("taskbar diag: no nav controller to trace (" + t + ")");
        }
    }

    /** Two array writes per call: cheap enough to leave on. */
    private static final XC_MethodHook RECORD = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (sTraced >= MAX_TRACES) {
                return;
            }
            synchronized (RING_NAMES) {
                RING_NAMES[sRingNext] = param.method.getName();
                RING_TIMES[sRingNext] = android.os.SystemClock.uptimeMillis();
                sRingNext = (sRingNext + 1) % RING;
            }
        }
    };

    private static final int MAX_TRACES = 6;

    /**
     * The task in front of the tablet changed - home to an app, or back. The bar's keys as they
     * are now, and a second later the calls ZUI made around the change and the keys as they
     * ended up: the method that lays the keys out for home is in that list.
     */
    static void onFrontChanged(String from, String to) {
        if (sTraced >= MAX_TRACES) {
            return;
        }
        MAIN.post(() -> {
            ViewGroup bar = tabletBar();
            String before = bar != null ? geometry(bar) : "no bar";
            long since = android.os.SystemClock.uptimeMillis() - 1500L;
            MAIN.postDelayed(() -> {
                if (sTraced >= MAX_TRACES) {
                    return;
                }
                sTraced++;
                StringBuilder calls = new StringBuilder();
                synchronized (RING_NAMES) {
                    for (int i = 0; i < RING; i++) {
                        int at = (sRingNext + i) % RING;
                        if (RING_NAMES[at] != null && RING_TIMES[at] >= since) {
                            calls.append(calls.length() == 0 ? "" : " ").append(RING_NAMES[at]);
                        }
                    }
                }
                ViewGroup now = tabletBar();
                String line = from + " -> " + to + ": keys before " + before + "; calls ["
                        + calls + "]; keys after " + (now != null ? geometry(now) : "no bar");
                L.i("taskbar diag: front " + line);
                synchronized (TRACES) {
                    TRACES.addLast(line);
                    while (TRACES.size() > MAX_TRACES) {
                        TRACES.removeFirst();
                    }
                }
            }, 1000L);
        });
    }

    /** The traces so far, for the probe. */
    public static String describeTraces() {
        StringBuilder sb = new StringBuilder("\nhome and app switches\n");
        synchronized (TRACES) {
            if (TRACES.isEmpty()) {
                sb.append("  (none yet - go home and back to an app)\n");
            }
            for (String t : TRACES) {
                sb.append("  ").append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private static ViewGroup tabletBar() {
        for (android.view.View root : Windows.roots()) {
            ViewGroup bar = TaskbarTray.dragLayerOf(root);
            if (bar != null && TaskbarTray.displayIdOf(bar) == 0) {
                return bar;
            }
        }
        return null;
    }

    /** Where the keys and ZUI's icon row are, and how they are laid out. */
    private static String geometry(ViewGroup bar) {
        StringBuilder sb = new StringBuilder();
        java.util.List<android.view.View> keys =
                Reflect.findByIdNames(bar, "end_nav_buttons");
        if (keys.isEmpty()) {
            sb.append("[none]");
        } else {
            android.view.View k = keys.get(0);
            sb.append("[x ").append(TaskbarStart.drawnLeftIn(bar, k)).append(" w ")
                    .append(k.getWidth());
            if (k.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) k.getLayoutParams();
                sb.append(" g 0x").append(Integer.toHexString(lp.gravity)).append(" lw ")
                        .append(lp.width).append(" ms ").append(lp.getMarginStart())
                        .append(" me ").append(lp.getMarginEnd());
            }
            if (k instanceof ViewGroup) {
                sb.append(" kids");
                ViewGroup g = (ViewGroup) k;
                for (int i = 0; i < g.getChildCount(); i++) {
                    sb.append(' ').append(g.getChildAt(i).getWidth());
                }
            }
            sb.append(']');
        }
        java.util.List<android.view.View> row =
                Reflect.findByIdNames(bar, "taskbar_view");
        if (!row.isEmpty()) {
            android.view.View r = row.get(0);
            sb.append(" row[vis ").append(r.getVisibility()).append(" a ").append(r.getAlpha())
                    .append(" s ").append(r.getScaleX()).append(" ty ")
                    .append(r.getTranslationY()).append(']');
        }
        return sb.toString();
    }

    /** One line per distinct call: every argument that is a flag or a number, and the result. */
    private static final XC_MethodHook FACTORY_CALL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < param.args.length; i++) {
                    Object a = param.args[i];
                    sb.append(i == 0 ? "" : ", ");
                    if (a instanceof Boolean || a instanceof Number) {
                        sb.append(a);
                    } else {
                        sb.append(a == null ? "null" : a.getClass().getSimpleName());
                    }
                }
                Object result = param.getResult();
                String line = param.method.getName() + "(" + sb + ") -> "
                        + (result == null ? "null" : result.getClass().getSimpleName());
                if (!line.equals(sLastFactory)) {
                    sLastFactory = line;
                    L.i("taskbar diag: layout factory " + line);
                }
            } catch (Throwable ignored) {
                // Diagnostics only.
            }
        }
    };

    /** Logs a bar of the tablet's as it is put up - once per distinct snapshot. */
    static void onBar(ViewGroup dragLayer) {
        try {
            if (TaskbarTray.displayIdOf(dragLayer) != 0) {
                return;
            }
            String line = snapshot(dragLayer);
            if (!line.equals(sLastSnapshot)) {
                sLastSnapshot = line;
                L.i("taskbar diag: tablet bar " + line);
            }
        } catch (Throwable t) {
            L.d("taskbar diag: no snapshot (" + t + ")");
        }
    }

    /**
     * The bar's keys layout, and every flag of its context and of the device profiles it holds.
     */
    public static String snapshot(ViewGroup dragLayer) {
        StringBuilder sb = new StringBuilder("keys by ").append(NavKeysHold.sLastLayoutter);
        Object context = taskbarContext(dragLayer.getContext());
        if (context == null) {
            return sb.append("; no taskbar context").toString();
        }
        sb.append("; context ").append(flags(context));
        for (Class<?> c = context.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        || !f.getType().getSimpleName().equals("DeviceProfile")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object profile = f.get(context);
                    sb.append("; ").append(f.getName()).append(' ')
                            .append(profile == null ? "null" : flags(profile));
                } catch (Throwable ignored) {
                    // One profile less.
                }
            }
        }
        return sb.toString();
    }

    private static Object taskbarContext(Context ctx) {
        for (Context c = ctx; c != null; ) {
            if (c.getClass().getSimpleName().equals("TaskbarActivityContext")) {
                return c;
            }
            c = c instanceof ContextWrapper ? ((ContextWrapper) c).getBaseContext() : null;
        }
        return null;
    }

    /** Every boolean field the object declares, as name=value. */
    private static String flags(Object o) {
        StringBuilder sb = new StringBuilder("[");
        for (Field f : o.getClass().getDeclaredFields()) {
            if (f.getType() != boolean.class || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.setAccessible(true);
                sb.append(sb.length() == 1 ? "" : " ").append(f.getName()).append('=')
                        .append(f.getBoolean(o) ? 1 : 0);
            } catch (Throwable ignored) {
                // One flag less.
            }
        }
        return sb.append(']').toString();
    }
}
