package com.zuxos.desktopplus.hook;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUX Home's own app-drawer button - the pill over its dock, and the arrow above it - taken
 * away while our start button
 * is on: the start button opens the same drawer, from the bar, on every screen.
 *
 * <p>Done where ZUI builds and updates its dock ({@code ZuiHotseat}), after each of its own
 * methods, so nothing of ours runs unless ZUI touches its dock itself.
 */
final class HotseatButton {

    private static final String HOTSEAT = "com.zui.launcher.uiextend.ZuiHotseat";

    private static boolean sInstalled;
    private static boolean sSaid;

    private HotseatButton() {
    }

    static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> hotseat = Class.forName(HOTSEAT, false, loader);
            int hooked = 0;
            for (Method m : hotseat.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, AFTER);
                    hooked++;
                } catch (Throwable ignored) {
                    // One method less; the layout pass alone is enough.
                }
            }
            L.i("zux home: watching its dock x" + hooked);
        } catch (Throwable t) {
            L.i("zux home: no dock of ZUI's on this build (" + t + ")");
        }
    }

    private static final XC_MethodHook AFTER = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.thisObject instanceof ViewGroup) {
                apply((ViewGroup) param.thisObject);
            }
        }
    };

    /** The dock's drawer button gone while our start button is on, back when it is off. */
    private static void apply(ViewGroup hotseat) {
        try {
            boolean ours = Cfg.enabled() && Cfg.startButtonLeft();
            for (int i = 0; i < hotseat.getChildCount(); i++) {
                View child = hotseat.getChildAt(i);
                // The button, and the pill under it with the arrow above: a plain view of ZUI's,
                // the only one in the dock with a size. ZUI keeps the dock up in its Recents too.
                boolean pill = child.getClass() == View.class
                        && (child.getWidth() > 0 || child.getTag(TAG_HIDDEN) != null);
                if (!(child instanceof ImageButton) && !pill) {
                    continue;
                }
                int want = ours ? View.GONE : View.VISIBLE;
                if (child.getVisibility() != want
                        && (ours || child.getTag(TAG_HIDDEN) != null)) {
                    child.setVisibility(want);
                    child.setTag(TAG_HIDDEN, ours ? Boolean.TRUE : null);
                    if (ours && !sSaid) {
                        sSaid = true;
                        L.i("zux home: drawer button and its pill hidden - the start button opens "
                                + "the drawer");
                    }
                }
            }
            holdDock(hotseat, ours && showsNothing(hotseat));
        } catch (Throwable ignored) {
            // The dock as ZUI left it.
        }
    }

    /**
     * In ZUI's desktop mode the dock holds no apps - those are in the taskbar - and what it still
     * draws is its own: the drawer pill and the arrow above it, painted by the dock itself around
     * a view that hiding took nothing away from. Then the whole dock goes, on home and in Recents
     * alike; with apps in it, as outside desktop mode, it stays.
     */
    private static boolean showsNothing(ViewGroup hotseat) {
        for (int i = 0; i < hotseat.getChildCount(); i++) {
            View child = hotseat.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getTag(TAG_HIDDEN) != null
                    || child.getWidth() == 0) {
                continue;
            }
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() == 0) {
                // The dock's icon container, empty.
                continue;
            }
            return false;
        }
        return true;
    }

    private static void holdDock(ViewGroup hotseat, boolean hide) {
        boolean hidden = hotseat.getTag(TAG_HIDDEN) != null;
        if (hide) {
            if (hotseat.getVisibility() == View.VISIBLE) {
                hotseat.setVisibility(View.INVISIBLE);
            }
            if (!hidden) {
                hotseat.setTag(TAG_HIDDEN, Boolean.TRUE);
                L.i("zux home: its empty dock hidden - the pill and arrow it draws go with it");
            }
            if (hotseat.getTag(TAG_WATCHED) == null) {
                hotseat.setTag(TAG_WATCHED, Boolean.TRUE);
                // ZUI shows its dock again in state changes of its own, not only through the
                // dock's methods: checked before each frame of the launcher's window, a field
                // read and a compare.
                hotseat.getViewTreeObserver().addOnPreDrawListener(() -> {
                    if (hotseat.getTag(TAG_HIDDEN) != null
                            && hotseat.getVisibility() == View.VISIBLE) {
                        hotseat.setVisibility(View.INVISIBLE);
                        return false;
                    }
                    return true;
                });
            }
        } else if (hidden) {
            hotseat.setTag(TAG_HIDDEN, null);
            hotseat.setVisibility(View.VISIBLE);
        }
    }

    /** Marks a button we hid, so turning the setting off shows only what we took away. */
    private static final int TAG_HIDDEN = 0x7A000401;
    /** Marks a dock whose frames are already checked, so the check is added once. */
    private static final int TAG_WATCHED = 0x7A000402;
}
