package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;

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
final class TaskbarDiag {

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
        L.i("taskbar diag: layout factory watched x" + hooked);
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
    static String snapshot(ViewGroup dragLayer) {
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
