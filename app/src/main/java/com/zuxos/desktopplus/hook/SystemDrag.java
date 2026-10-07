package com.zuxos.desktopplus.hook;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.desktop.DragPayload;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: a drag of ours is shown to the launcher's own windows and to nobody else.
 *
 * <p>Our drags go between the launcher's windows - the desktop, the drawer, the taskbar, folders
 * - so they have to be global. A global drag is also announced to every other app's windows, and
 * ZUI's sidebar answers each announcement by waiting on its AI service with no time limit: its
 * main thread stops, its gesture monitor stops taking touches, and the tablet's Home and Recents
 * stop responding until the "isn't responding" dialog. The drags carry Android's "same
 * application" flag, which Android 15 and later honour on their own where the platform flag is
 * on; this makes the system honour it everywhere: a window of another app is never a target.
 */
final class SystemDrag {

    private static boolean sInstalled;
    private static Field sFlags;
    private static Field sUid;
    private static Method sOwningUid;
    private static int sErrors;

    private SystemDrag() {
    }

    static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> state = Class.forName("com.android.server.wm.DragState", false, loader);
            sFlags = state.getDeclaredField("mFlags");
            sFlags.setAccessible(true);
            sUid = state.getDeclaredField("mUid");
            sUid.setAccessible(true);
            Class<?> window = Class.forName("com.android.server.wm.WindowState", false, loader);
            sOwningUid = window.getDeclaredMethod("getOwningUid");
            sOwningUid.setAccessible(true);
            int hooked = 0;
            for (Method m : state.getDeclaredMethods()) {
                if (m.getName().equals("isValidDropTarget") && m.getParameterCount() > 0
                        && m.getParameterTypes()[0] == window && m.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(m, TARGET);
                    hooked++;
                }
            }
            L.i("system drag: watching x" + hooked);
        } catch (Throwable t) {
            L.i("system drag: not available on this build (" + t + ")");
        }
    }

    private static final XC_MethodHook TARGET = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(param.getResult()) || sErrors > 20) {
                return;
            }
            try {
                int flags = sFlags.getInt(param.thisObject);
                if ((flags & DragPayload.SAME_APPLICATION) == 0) {
                    return;
                }
                int uid = (int) sOwningUid.invoke(param.args[0]);
                if (uid != sUid.getInt(param.thisObject)) {
                    param.setResult(Boolean.FALSE);
                }
            } catch (Throwable t) {
                sErrors++;
            }
        }
    };
}
