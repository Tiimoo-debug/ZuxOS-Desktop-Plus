package com.zuxos.desktopplus.hook.drawer;

import android.content.res.ColorStateList;
import android.view.View;

import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.theme.Theme;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUI's start menu in Retro: its app names black on the grey box, as Windows 98's were.
 *
 * <p>ZUI's drawer icons ({@code BubbleTextView} made for the drawer) take their colour from
 * their layout when they are made, and from {@code setTextColor} after that - the only two ways
 * in. So the colour is chosen there: for a drawer icon on a screen in Retro, black, whatever ZUI
 * asked for. Every other icon, and every icon on the tablet, gets exactly what ZUI asked for.
 */
public final class DrawerRetro {

    private static final String ICON = "com.android.launcher3.BubbleTextView";
    /** ZUI's {@code BubbleTextView.DISPLAY_ALL_APPS}: an icon in the drawer. */
    private static final int DISPLAY_ALL_APPS = 1;

    private static Field sDisplay;

    private DrawerRetro() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> icon = Reflect.findClass(ICON, loader);
            if (icon == null) {
                L.w("drawer retro: BubbleTextView not found, the drawer keeps ZUI's colours");
                return;
            }
            sDisplay = icon.getDeclaredField("mDisplay");
            sDisplay.setAccessible(true);
            int hooked = XposedBridge.hookAllConstructors(icon, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        View view = (View) param.thisObject;
                        if (retroDrawerIcon(view)) {
                            ((android.widget.TextView) view).setTextColor(Theme.RETRO.text());
                        }
                    } catch (Throwable t) {
                        L.d("drawer retro: icon left as ZUI made it (" + t + ")");
                    }
                }
            }).size();
            XC_MethodHook colour = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!retroDrawerIcon((View) param.thisObject)) {
                            return;
                        }
                        param.args[0] = param.args[0] instanceof ColorStateList
                                ? ColorStateList.valueOf(Theme.RETRO.text())
                                : (Object) Theme.RETRO.text();
                    } catch (Throwable t) {
                        L.d("drawer retro: colour left as ZUI chose it (" + t + ")");
                    }
                }
            };
            hooked += XposedBridge.hookMethod(
                    icon.getDeclaredMethod("setTextColor", int.class), colour) != null ? 1 : 0;
            hooked += XposedBridge.hookMethod(
                    icon.getDeclaredMethod("setTextColor", ColorStateList.class), colour) != null
                    ? 1 : 0;
            Health.hooked("drawer: retro app names", hooked);
        } catch (Throwable t) {
            L.w("drawer retro: not installed (" + t + "), the drawer keeps ZUI's colours");
        }
    }

    private static boolean retroDrawerIcon(View view) throws IllegalAccessException {
        return sDisplay != null && sDisplay.getInt(view) == DISPLAY_ALL_APPS
                && Theme.of(view).retro();
    }
}
