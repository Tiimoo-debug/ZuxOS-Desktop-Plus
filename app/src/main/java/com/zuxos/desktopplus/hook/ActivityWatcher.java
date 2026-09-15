package com.zuxos.desktopplus.hook;

import android.app.Activity;
import android.content.Intent;

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

    private static final Set<Class<?>> HOOKED_SUBCLASSES =
            Collections.newSetFromMap(new WeakHashMap<>());
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
            DesktopHost existing = DesktopHost.of(activity);
            if (existing != null) {
                existing.onResume();
                return;
            }
            if (!HostDetector.shouldAttach(activity)) {
                return;
            }
            hookSubclass(activity.getClass());
            DesktopHost.attach(activity, HostDetector.isExternal(activity));
        } catch (Throwable t) {
            L.e("attach failed for " + activity.getClass().getName(), t);
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
