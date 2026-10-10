package com.zuxos.desktopplus.hook.drawer;

import android.graphics.Rect;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.hook.taskbar.BarEdge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUI's start menu on the monitor, dropping down from a bar at the top.
 *
 * <p>ZUI's drawer there is a sheet that slides up from the bottom of the screen
 * ({@code AbstractSlideInView.setTranslationShift}: the content moves down by the shift), and in
 * the monitor's mode ZUI keeps a bar's height clear at its foot
 * ({@code TaskbarAllAppsContainerView.setInsets}). Both are where ZUI decides for a bar at the
 * bottom, so both are decided here for a bar at the top: the sheet rests under the bar, slides
 * from above, and its foot keeps nothing clear.
 *
 * <p>Only with the monitor's bar at the top ({@link Cfg#taskbarEdge()}), and only for ZUI's
 * monitor drawer ({@code isInDpMode}); the tablet's drawer and every other sheet are ZUI's.
 */
public final class DrawerFromTop {

    private static final String SLIDE_IN = "com.android.launcher3.views.AbstractSlideInView";
    private static final String TASKBAR_SHEET =
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView";
    private static final String APPS_VIEW =
            "com.android.launcher3.allapps.ActivityAllAppsContainerView";
    private static final String TASKBAR_APPS_VIEW =
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsContainerView";
    private static final String OVERLAY_CONTEXT =
            "com.android.launcher3.taskbar.overlay.TaskbarOverlayContext";

    private static Field sShift;
    private static Field sContent;
    private static Method sRange;
    private static Field sBackground;
    private static Method sDpMode;
    private static boolean sSaid;

    private DrawerFromTop() {
    }

    public static void install(ClassLoader loader) {
        if (Cfg.taskbarEdge() != Const.EDGE_TOP) {
            return;
        }
        try {
            Class<?> slideIn = Reflect.findClass(SLIDE_IN, loader);
            Class<?> sheet = Reflect.findClass(TASKBAR_SHEET, loader);
            Class<?> appsView = Reflect.findClass(APPS_VIEW, loader);
            Class<?> taskbarApps = Reflect.findClass(TASKBAR_APPS_VIEW, loader);
            Class<?> overlay = Reflect.findClass(OVERLAY_CONTEXT, loader);
            if (slideIn == null || sheet == null || appsView == null || taskbarApps == null
                    || overlay == null) {
                L.w("drawer from top: ZUI's drawer classes not found, it slides up as ZUI made "
                        + "it");
                return;
            }
            sShift = slideIn.getDeclaredField("mTranslationShift");
            sShift.setAccessible(true);
            sContent = slideIn.getDeclaredField("mContent");
            sContent.setAccessible(true);
            sRange = slideIn.getDeclaredMethod("getShiftRange");
            sRange.setAccessible(true);
            sBackground = appsView.getDeclaredField("mBottomSheetBackground");
            sBackground.setAccessible(true);
            sDpMode = overlay.getMethod("isInDpMode");
            int hooked = 0;
            // The slide itself: from above instead of from below. The subclass's override calls
            // this one and then goes on with its own work, which is left as it is.
            hooked += XposedBridge.hookMethod(
                    slideIn.getDeclaredMethod("setTranslationShift", float.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                View view = (View) param.thisObject;
                                if (!sheet.isInstance(view) || !monitor(view)) {
                                    return;
                                }
                                float shift = (Float) param.args[0];
                                View content = (View) sContent.get(view);
                                if (content == null) {
                                    return;
                                }
                                sShift.setFloat(view, shift);
                                content.setTranslationY(-raise(view, content)
                                        - shift * ((Number) sRange.invoke(view)).floatValue());
                                view.invalidate();
                                param.setResult(null);
                                if (!sSaid) {
                                    sSaid = true;
                                    L.i("drawer from top: ZUI's drawer drops down from the "
                                            + "monitor's bar at the top");
                                }
                            } catch (Throwable t) {
                                L.d("drawer from top: slide left to ZUI (" + t + ")");
                            }
                        }
                    }) != null ? 1 : 0;
            // The foot: ZUI sets it to a bar's height and then hands the insets on to here.
            hooked += XposedBridge.hookMethod(
                    appsView.getDeclaredMethod("setInsets", Rect.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                View view = (View) param.thisObject;
                                if (taskbarApps.isInstance(view) && monitor(view)
                                        && param.args[0] instanceof Rect) {
                                    ((Rect) param.args[0]).bottom = 0;
                                }
                            } catch (Throwable t) {
                                L.d("drawer from top: insets left to ZUI (" + t + ")");
                            }
                        }
                    }) != null ? 1 : 0;
            Health.hooked("drawer: from a bar at the top", hooked);
        } catch (Throwable t) {
            L.w("drawer from top: not installed (" + t + "), it slides up as ZUI made it");
        }
    }

    /**
     * How far up the sheet goes to rest under the bar instead of on the screen's foot: from where
     * ZUI lays its background out ({@code mBottomSheetBackground}) to the bar's lower edge. 0 until
     * both are measured - the slide-in view's next layout asks again.
     */
    private static float raise(View sheetView, View content) throws IllegalAccessException {
        Object found = sBackground.get(content);
        if (!(found instanceof View) || ((View) found).getHeight() <= 0) {
            return 0f;
        }
        int bar = BarEdge.reserved(Ui.displayOf(sheetView)).top;
        if (bar <= 0) {
            return 0f;
        }
        int top = 0;
        for (View v = (View) found; v != null && v != sheetView; ) {
            top += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return Math.max(0, top - bar);
    }

    /** Whether this view is in ZUI's drawer for the monitor, by ZUI's own answer. */
    private static boolean monitor(View view) throws ReflectiveOperationException {
        Object ctx = view.getContext();
        return sDpMode.getDeclaringClass().isInstance(ctx)
                && Boolean.TRUE.equals(sDpMode.invoke(ctx));
    }
}
