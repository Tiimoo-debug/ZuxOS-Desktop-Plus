package com.zuxos.desktopplus.hook;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.desktop.FolderStyle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The launcher's own folders - the ones on the tablet's home screen - open and close the way the
 * module's do: out of their icon and back into it, the iOS way.
 *
 * <p>Launcher3 builds each folder animation in a {@code FolderAnimationManager}, made with the
 * folder and whether it is opening, and asks it for an {@code AnimatorSet}. That set is swapped
 * for one holding the module's morph. The folder still adds its own listeners to whatever it is
 * handed and starts it, so everything it does around the animation - hiding its icon while open,
 * leaving the empty ring behind, taking itself off screen once closed - happens exactly as before.
 * Names are minified, so the methods are found by shape, not by name.
 */
final class NativeFolderMotion {

    private static final String MANAGER = "com.android.launcher3.folder.FolderAnimationManager";
    private static final String FOLDER = "com.android.launcher3.folder.Folder";
    private static final String FOLDER_ICON = "com.android.launcher3.folder.FolderIcon";

    /** What each manager was made for: the folder, and whether it opens. */
    private static final Map<Object, Object[]> MADE = Collections.synchronizedMap(
            new WeakHashMap<>());
    private static Field sIconField;
    private static boolean sSaid;

    private NativeFolderMotion() {
    }

    static void install(ClassLoader loader) {
        Class<?> manager = Reflect.findClass(MANAGER, loader);
        Class<?> folder = Reflect.findClass(FOLDER, loader);
        Class<?> icon = Reflect.findClass(FOLDER_ICON, loader);
        if (manager == null || folder == null || icon == null) {
            L.i("folder motion: this launcher has no Launcher3 folder animation to replace");
            return;
        }
        for (Class<?> c = folder; c != null && sIconField == null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == icon) {
                    f.setAccessible(true);
                    sIconField = f;
                    break;
                }
            }
        }
        int made = 0;
        int asked = 0;
        try {
            made = XposedBridge.hookAllConstructors(manager, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object panel = null;
                    Boolean opening = null;
                    for (Object arg : param.args) {
                        if (folder.isInstance(arg)) {
                            panel = arg;
                        } else if (arg instanceof Boolean) {
                            opening = (Boolean) arg;
                        }
                    }
                    if (panel != null && opening != null) {
                        MADE.put(param.thisObject, new Object[]{panel, opening});
                    }
                }
            }).size();
            for (Method m : manager.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0
                        || !AnimatorSet.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        replace(param);
                    }
                });
                asked++;
            }
        } catch (Throwable t) {
            L.d("folder motion: could not hook (" + t + ")");
            return;
        }
        L.i("folder motion: " + made + " constructor(s), " + asked + " animation method(s)"
                + (sIconField != null ? "" : ", no icon field found"));
    }

    private static void replace(XC_MethodHook.MethodHookParam param) {
        if (!Cfg.animations() || !(param.getResult() instanceof AnimatorSet)) {
            return;
        }
        Object[] made = MADE.get(param.thisObject);
        if (made == null || !(made[0] instanceof View)) {
            return;
        }
        try {
            View panel = (View) made[0];
            boolean opening = (Boolean) made[1];
            View icon = sIconField != null && sIconField.get(panel) instanceof View
                    ? (View) sIconField.get(panel) : null;
            ValueAnimator morph = FolderStyle.nativeMorph(panel, icon, opening);
            if (!opening) {
                morph.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // The launcher takes the folder away now; it is put back to rest just
                        // after, so whatever opens it next starts from a clean view.
                        panel.setAlpha(0f);
                        panel.post(() -> {
                            panel.setAlpha(1f);
                            panel.setScaleX(1f);
                            panel.setScaleY(1f);
                            panel.setTranslationX(0f);
                            panel.setTranslationY(0f);
                        });
                    }
                });
            }
            AnimatorSet set = new AnimatorSet();
            set.play(morph);
            param.setResult(set);
            if (!sSaid) {
                sSaid = true;
                L.i("folder motion: the launcher's folders now open out of their icon"
                        + (icon != null ? "" : " (icon not found, growing in place)"));
            }
        } catch (Throwable t) {
            L.d("folder motion: kept the launcher's own animation (" + t + ")");
        }
    }
}
