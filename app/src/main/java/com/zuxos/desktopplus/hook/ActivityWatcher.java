package com.zuxos.desktopplus.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.desktop.DesktopHost;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Watches the launcher's activity lifecycle and attaches the desktop surface.
 *
 * <p>{@code Activity} is hooked rather than a specific launcher class: which class hosts the
 * external desktop differs between ZuxOS builds, and hooking the base class means we do not
 * have to guess. Everything is wrapped defensively - a module must never crash the launcher.
 */
public final class ActivityWatcher {

    /** Delays for re-attach attempts after a resume that found no window yet. */
    private static final long[] RETRY_DELAYS_MS = {150, 600, 1500, 3000, 6000};

    private static final Set<Class<?>> HOOKED_SUBCLASSES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Activity> RETRYING =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean sInstalled;

    private ActivityWatcher() {
    }

    public static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onActivityResumed((Activity) param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    DesktopHost host = DesktopHost.of((Activity) param.thisObject);
                    if (host != null) {
                        host.onPause();
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    DesktopHost.detach((Activity) param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onNewIntent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // Pressing home while already home: close whatever we have open.
                    DesktopHost host = DesktopHost.of((Activity) param.thisObject);
                    if (host != null) {
                        host.closeOverlays();
                    }
                }
            });
            hookBack(Activity.class);
            hookActivityResult(Activity.class);
            hookKeys(Activity.class);
            hookContentView(Activity.class);
            L.i("activity hooks installed");
        } catch (Throwable t) {
            L.e("could not install activity hooks", t);
        }
    }

    private static void onActivityResumed(Activity activity) {
        try {
            Cfg.reload();
            if (!Cfg.enabled()) {
                return;
            }
            // The taskbar is its own window and may have been created before the module loaded,
            // or after a display reconnect that we never saw - this is the cheap re-check.
            TaskbarTray.refresh();
            DesktopHost existing = DesktopHost.of(activity);
            if (existing != null) {
                existing.onResume();
                return;
            }
            if (!HostDetector.shouldAttach(activity)) {
                return;
            }
            hookSubclass(activity.getClass());
            if (DesktopHost.attach(activity, HostDetector.isExternal(activity)) == null) {
                scheduleRetries(activity);
            }
        } catch (Throwable t) {
            L.e("attach failed for " + activity.getClass().getName(), t);
        }
    }

    /**
     * Retries the attach a few times.
     *
     * <p>ZuxOS's secondary-display launcher has no window to attach to when it resumes - it
     * installs its layout once the launcher model has loaded - so the first attempt legitimately
     * finds nothing and we come back for it.
     */
    private static void scheduleRetries(Activity activity) {
        if (!RETRYING.add(activity)) {
            return;
        }
        for (long delay : RETRY_DELAYS_MS) {
            MAIN.postDelayed(() -> {
                try {
                    if (activity.isDestroyed() || activity.isFinishing()
                            || DesktopHost.of(activity) != null) {
                        RETRYING.remove(activity);
                        return;
                    }
                    if (DesktopHost.attach(activity, HostDetector.isExternal(activity)) != null) {
                        RETRYING.remove(activity);
                    }
                } catch (Throwable t) {
                    L.e("retry attach failed", t);
                }
            }, delay);
        }
    }

    /** Attaches as soon as the launcher installs its own layout. */
    private static void hookContentView(Class<?> clazz) {
        try {
            XposedBridge.hookAllMethods(clazz, "setContentView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Activity activity = (Activity) param.thisObject;
                    if (DesktopHost.of(activity) != null || !Cfg.enabled()) {
                        return;
                    }
                    // Post so the launcher's own layout is fully in place first.
                    MAIN.post(() -> {
                        try {
                            if (HostDetector.shouldAttach(activity)) {
                                DesktopHost.attach(activity, HostDetector.isExternal(activity));
                            }
                        } catch (Throwable t) {
                            L.e("attach after setContentView failed", t);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            L.d("no setContentView on " + clazz.getName());
        }
    }

    /**
     * Launcher activities usually override {@code onBackPressed} / {@code onActivityResult}
     * without calling {@code super}, so the concrete class needs hooking too.
     */
    private static void hookSubclass(Class<?> clazz) {
        if (clazz == null || clazz == Activity.class || !HOOKED_SUBCLASSES.add(clazz)) {
            return;
        }
        hookBack(clazz);
        hookActivityResult(clazz);
        hookKeys(clazz);
        hookContentView(clazz);
    }

    private static void hookBack(Class<?> clazz) {
        try {
            XposedBridge.hookAllMethods(clazz, "onBackPressed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    DesktopHost host = DesktopHost.of((Activity) param.thisObject);
                    if (host != null && host.onBackPressed()) {
                        // Consumed by us: do not let the launcher act on it.
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            L.d("no onBackPressed on " + clazz.getName());
        }
    }

    private static void hookKeys(Class<?> clazz) {
        try {
            XposedBridge.hookAllMethods(clazz, "onKeyDown", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    DesktopHost host = DesktopHost.of((Activity) param.thisObject);
                    if (host != null && host.onKeyDown((Integer) param.args[0])) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
        } catch (Throwable t) {
            L.d("no onKeyDown on " + clazz.getName());
        }
    }

    private static void hookActivityResult(Class<?> clazz) {
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 3) {
                        return;
                    }
                    DesktopHost host = DesktopHost.of((Activity) param.thisObject);
                    if (host == null) {
                        return;
                    }
                    int requestCode = (Integer) param.args[0];
                    int resultCode = (Integer) param.args[1];
                    Intent data = param.args[2] instanceof Intent ? (Intent) param.args[2] : null;
                    if (host.onActivityResult(requestCode, resultCode, data)) {
                        // Our widget bind/configure round trip - the launcher must not see it.
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            L.d("no onActivityResult on " + clazz.getName());
        }
    }
}
