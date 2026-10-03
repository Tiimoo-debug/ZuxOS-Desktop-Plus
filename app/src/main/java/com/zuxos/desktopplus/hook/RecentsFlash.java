package com.zuxos.desktopplus.hook;

import android.os.SystemClock;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The flash of old Recents when an app opens.
 *
 * <p>Open anything from the desktop and ZUI's own overview - {@code RecentsViewDp}, which the probe
 * names - appears for a moment and goes again. It is the launcher's own launch transition showing a
 * view nobody asked for, and on an external desktop it reads as a glitch.
 *
 * <p>So it is vetoed, but only in the half second after a launch. A launch is something we already
 * see: {@link LaunchDisplay} sits on every activity start in this process and says when one
 * happened. Outside that window nothing is touched, which is what keeps the Recents button, the
 * gesture and the overview itself working exactly as they do now - the only thing suppressed is a
 * view appearing on its own while an app is coming up.
 *
 * <p>Hooked at {@code setVisibility} on the view's own class rather than anywhere in the launcher's
 * own logic: whatever decides to show it, this is where it would have to come through.
 */
final class RecentsFlash {

    /** How long after a launch an overview appearing is the launcher's flash rather than you. */
    private static final long WINDOW_MS = 700L;

    private static final String RECENTS = "com.zui.launcher.dpmode.secondarydisplaydp.RecentsViewDp";

    private static boolean sInstalled;
    private static volatile long sLaunchedAt;
    private static boolean sSaid;

    private RecentsFlash() {
    }

    /** Told by {@link LaunchDisplay} that an app is coming up. */
    static void launching() {
        sLaunchedAt = SystemClock.uptimeMillis();
    }

    static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        Class<?> cls = com.zuxos.desktopplus.core.Reflect.findClass(RECENTS, loader);
        if (cls == null) {
            // Another firmware, or another name for it. Nothing to suppress and nothing to say.
            return;
        }
        try {
            int hooked = XposedBridge.hookAllMethods(cls, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Cfg.hideRecentsFlash() || param.args.length == 0
                            || !(param.args[0] instanceof Integer)
                            || (Integer) param.args[0] != View.VISIBLE) {
                        return;
                    }
                    if (SystemClock.uptimeMillis() - sLaunchedAt > WINDOW_MS) {
                        // Not during a launch, so this is the overview somebody asked for.
                        return;
                    }
                    param.setResult(null);
                    if (!sSaid) {
                        sSaid = true;
                        L.i("recents flash: the overview is kept down while an app opens");
                    }
                }
            }).size();
            L.i("recents flash: watching the overview x" + hooked);
        } catch (Throwable t) {
            L.d("recents flash: could not watch the overview (" + t + ")");
        }
    }
}
