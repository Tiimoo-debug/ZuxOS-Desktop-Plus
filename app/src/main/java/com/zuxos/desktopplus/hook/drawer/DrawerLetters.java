package com.zuxos.desktopplus.hook.drawer;

import android.view.View;

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
 * first. ZUI has no setting for it: both of its scroller layouts use that class. So it is hidden
 * once, as ZUI hands it its list ({@code setRecyclerView}), and while hidden it takes no touches.
 * Installed with the drawer order itself, so turning that off brings the letters back.
 */
final class DrawerLetters {

    private static final String SCROLLER = "com.zui.launcher.views.RecyclerViewLettersScroller";

    private DrawerLetters() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> scroller = Reflect.findClass(SCROLLER, loader);
            if (scroller == null) {
                L.i("drawer letters: ZUI's letter bar not on this build");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(scroller, "setRecyclerView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                ((View) param.thisObject).setVisibility(View.GONE);
                            } catch (Throwable t) {
                                L.d("drawer letters: left showing (" + t + ")");
                            }
                        }
                    }).size();
            XC_MethodHook inert = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View
                            && ((View) param.thisObject).getVisibility() == View.GONE) {
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
