package com.zuxos.desktopplus.hook.drawer;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUI's A-Z bar down the side of its drawers, gone while the drawers follow our order.
 *
 * <p>ZUI's {@code RecyclerViewLettersScroller} jumps to an app by its first letter, which only
 * means something in an alphabetical list - and ours is the order the owner arranged, folders
 * first. ZUI has no setting for it: both of its scroller layouts use that class, and ZUI shows it
 * again whenever a search starts or ends ({@code ActivityAllAppsContainerView}), so hiding it was
 * undone (the 00:28 recording). Its visibility is left to ZUI; its letters are never drawn and it
 * takes no touches while the drawer follows our order, so turning that off brings them back.
 */
final class DrawerLetters {

    private static final String SCROLLER = "com.zui.launcher.views.RecyclerViewLettersScroller";

    private DrawerLetters() {
    }

    /** Whether the letters are off: while the drawer follows our order. */
    private static boolean off() {
        return Cfg.enabled() && Cfg.nativeDrawer();
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> scroller = Reflect.findClass(SCROLLER, loader);
            if (scroller == null) {
                L.i("drawer letters: ZUI's letter bar not on this build");
                return;
            }
            // Its letters are never drawn - and with them goes the strip it keeps back from the
            // back gesture, which is set while drawing.
            int hooked = XposedBridge.hookAllMethods(scroller, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (off()) {
                        param.setResult(null);
                    }
                }
            }).size();
            XC_MethodHook inert = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (off()) {
                        param.setResult(Boolean.FALSE);
                    }
                }
            };
            hooked += XposedBridge.hookAllMethods(scroller, "handleTouchEvent", inert).size();
            hooked += XposedBridge.hookAllMethods(scroller, "isHitInParent", inert).size();
            Health.hooked("drawer: A-Z bar hidden", hooked);
            L.i("drawer letters: ZUI's A-Z bar hidden while the drawer follows our order x"
                    + hooked);
        } catch (Throwable t) {
            L.w("drawer letters: not hidden (" + t + ")");
        }
    }
}
