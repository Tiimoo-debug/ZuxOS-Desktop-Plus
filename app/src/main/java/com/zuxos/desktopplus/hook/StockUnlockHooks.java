package com.zuxos.desktopplus.hook;

import android.content.Context;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/**
 * Optional second front: re-enable the stock launcher's own editing gates.
 *
 * <p>Our desktop surface does not need any of this - it is here because some ZuxOS builds only
 * *disable* features in desktop mode that the launcher already implements, and flipping those
 * booleans back is the lightest possible fix when it works.
 *
 * <p>Only classes we can actually name are touched. Everything else comes from a user-written
 * {@code rules.json} in the launcher's data dir, filled in from a {@link Probe} dump:
 *
 * <pre>
 * {"rules": [
 *   {"class": "com.zui.home.desktop.DesktopController", "method": "isEditDisabled", "returns": false},
 *   {"class": "com.zui.home.desktop.DesktopView", "methodContains": "canDrag", "returns": true}
 * ]}
 * </pre>
 */
public final class StockUnlockHooks {

    /** Known Launcher3 / AOSP secondary-display classes, present on Launcher3-derived homes. */
    private static final List<String> KNOWN_CLASSES = Arrays.asList(
            "com.android.launcher3.Launcher",
            "com.android.launcher3.Workspace",
            "com.android.launcher3.CellLayout",
            "com.android.launcher3.DeviceProfile",
            "com.android.launcher3.InvariantDeviceProfile",
            "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher",
            "com.android.launcher3.secondarydisplay.SecondaryDragLayer",
            // ZuxOS keeps Launcher3's package names but moved the secondary-display launcher.
            "com.zui.launcher.secondarydisplay.SecondaryDisplayLauncher",
            "com.zui.launcher.secondarydisplay.SecondaryDragLayer",
            "com.zui.launcher.Launcher",
            "com.zui.launcher.Workspace",
            "com.zui.home.Launcher");

    /** Methods that say "editing is blocked" - forced to false. */
    private static final Set<String> FORCE_FALSE = new HashSet<>(Arrays.asList(
            "isworkspacelocked", "isworkspacedisabled", "isdragdisabled", "isdragforbidden",
            "isdraglocked", "iseditdisabled", "islayoutlocked", "isicontlocked", "isiconlocked",
            "islocked", "isreadonly", "isfolderdisabled", "iswidgetdisabled", "isaddwidgetdisabled",
            "isdesktoplocked", "isforbiddrag", "notallowdrag", "disabledrag"));

    /** Methods that say "editing is allowed" - forced to true. */
    private static final Set<String> FORCE_TRUE = new HashSet<>(Arrays.asList(
            "isdragenabled", "candrag", "allowdrag", "supportdrag", "candraganddrop",
            "iseditenabled", "iseditable", "issupportedit", "supportedit", "canedit",
            "canreorder", "supportreorder", "issortable", "canmoveitem", "ismoveenabled",
            "cancreatefolder", "allowcreatefolder", "supportfolder", "isfolderenabled",
            "canaddwidget", "iswidgetsupported", "supportwidget", "iswidgetenabled",
            "canaddshortcut", "supportshortcut"));

    private static ClassLoader sLoader;
    private static boolean sUserRulesLoaded;

    private StockUnlockHooks() {
    }

    public static void install(ClassLoader loader) {
        sLoader = loader;
        int hooked = 0;
        for (String className : KNOWN_CLASSES) {
            Class<?> clazz = com.zuxos.desktopplus.core.Reflect.findClass(className, loader);
            if (clazz == null) {
                continue;
            }
            hooked += hookBooleans(clazz);
        }
        if (hooked > 0) {
            L.i("stock unlock: patched " + hooked + " gate method(s)");
        } else {
            L.d("stock unlock: no known launcher classes found (this is fine - the module's own "
                    + "desktop surface does not need them)");
        }
    }

    private static int hookBooleans(Class<?> clazz) {
        int count = 0;
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        for (Method m : methods) {
            if (m.getReturnType() != boolean.class || m.getParameterCount() > 0) {
                continue;
            }
            String name = m.getName().toLowerCase();
            Boolean value = null;
            if (FORCE_FALSE.contains(name)) {
                value = Boolean.FALSE;
            } else if (FORCE_TRUE.contains(name)) {
                value = Boolean.TRUE;
            } else if (Cfg.unlockAggressive()) {
                value = guess(name);
            }
            if (value == null) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(value));
                L.d("stock unlock: " + clazz.getSimpleName() + "." + m.getName() + " -> " + value);
                count++;
            } catch (Throwable t) {
                L.d("could not hook " + m + ": " + t);
            }
        }
        return count;
    }

    /**
     * Aggressive mode: pattern-match names we have not seen before. Off by default because a
     * wrong guess here misbehaves in ways that are hard to attribute.
     */
    private static Boolean guess(String lowerName) {
        boolean mentionsEditing = lowerName.contains("drag") || lowerName.contains("edit")
                || lowerName.contains("folder") || lowerName.contains("widget")
                || lowerName.contains("reorder") || lowerName.contains("move");
        if (!mentionsEditing) {
            return null;
        }
        if (lowerName.startsWith("is") && (lowerName.contains("disable")
                || lowerName.contains("lock") || lowerName.contains("forbid"))) {
            return Boolean.FALSE;
        }
        if (lowerName.startsWith("can") || lowerName.startsWith("support")
                || lowerName.startsWith("allow")) {
            return Boolean.TRUE;
        }
        return null;
    }

    /** Applies {@code rules.json}; called once a launcher context exists. */
    public static void loadUserRules(Context ctx) {
        if (sUserRulesLoaded || sLoader == null) {
            return;
        }
        sUserRulesLoaded = true;
        String raw = Storage.read(Storage.file(ctx, "rules.json"));
        if (raw == null) {
            return;
        }
        int applied = 0;
        try {
            JSONArray rules = new JSONObject(raw).optJSONArray("rules");
            if (rules == null) {
                return;
            }
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                String className = rule.optString("class", "");
                Class<?> clazz = com.zuxos.desktopplus.core.Reflect.findClass(className, sLoader);
                if (clazz == null) {
                    L.w("rules.json: class not found: " + className);
                    continue;
                }
                boolean value = rule.optBoolean("returns", true);
                String exact = rule.optString("method", "");
                String contains = rule.optString("methodContains", "").toLowerCase();
                for (Method m : clazz.getDeclaredMethods()) {
                    boolean match = (!exact.isEmpty() && m.getName().equals(exact))
                            || (!contains.isEmpty() && m.getName().toLowerCase().contains(contains));
                    if (!match || m.getReturnType() != boolean.class) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(value));
                    applied++;
                }
            }
            L.i("rules.json: applied " + applied + " rule(s)");
        } catch (Throwable t) {
            L.e("rules.json could not be applied", t);
        }
    }
}
