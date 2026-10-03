package com.zuxos.desktopplus.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Process;
import android.os.UserHandle;

import com.zuxos.desktopplus.core.Reflect;

/**
 * What is behind a launcher icon.
 *
 * <p>Both the taskbar's menu and the running-apps row need the same answer - which app is this
 * icon, and whose - and both were working it out for themselves. They agree here instead, because
 * they have to agree about one case in particular: a folder in the stock app drawer is a synthetic
 * entry this module builds, and it carries this module's own package so that we can recognise it
 * again. Anything that reads that package as "the app behind this icon" ends up offering app info
 * for Desktop Plus on a folder, which is exactly the bug this is here to stop.
 */
final class IconInfo {

    private IconInfo() {
    }

    /**
     * The package an icon stands for, or null when it does not stand for a single app.
     *
     * <p>The component first: an entry copied from a real app keeps that app's intent, so an
     * intent is the last thing to trust, not the first.
     */
    static String packageOf(Object info) {
        if (info == null || NativeDrawerHooks.isFolderEntry(info)) {
            return null;
        }
        if (info instanceof String) {
            // Our own icons, which carry their package and nothing else.
            return ((String) info).isEmpty() ? null : (String) info;
        }
        ComponentName component = NativeDrawerHooks.componentOf(info);
        if (component != null) {
            return component.getPackageName();
        }
        Object direct = Reflect.field(info, "packageName");
        if (direct instanceof String && !((String) direct).isEmpty()) {
            return (String) direct;
        }
        Object intent = Reflect.field(info, "intent");
        if (intent instanceof Intent) {
            Intent i = (Intent) intent;
            return i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
        }
        return null;
    }

    /** The package behind a view's tag, for a view that is a launcher icon. */
    static String packageOfView(android.view.View icon) {
        return icon == null ? null : packageOf(icon.getTag());
    }

    /**
     * Everything an icon stands for - one app, or every app in a folder.
     *
     * <p>A folder in the taskbar is not one package, and treating it as none is what made a folder
     * holding an open app count as closed and get hidden. This decides whether a folder stays and
     * carries a mark; whether the open app also gets an icon of its own is decided from the
     * folder-free {@link #packageOfView}. The contents list is found by type, the way everything
     * else about the launcher's minified classes is found.
     */
    static java.util.List<String> packagesOfView(android.view.View icon) {
        java.util.List<String> out = new java.util.ArrayList<>();
        collect(icon == null ? null : icon.getTag(), out, 0);
        return out;
    }

    private static void collect(Object info, java.util.List<String> out, int depth) {
        if (info == null || depth > 2 || out.size() > 64) {
            return;
        }
        String pkg = packageOf(info);
        if (pkg != null) {
            if (!out.contains(pkg)) {
                out.add(pkg);
            }
            return;
        }
        for (Object child : childrenOf(info)) {
            collect(child, out, depth + 1);
        }
    }

    /**
     * What a folder holds, if this is a folder.
     *
     * <p>By type and nothing else: {@code FolderInfo.contents} is minified to a single letter on
     * this firmware, but it is still the only list of item-infos a folder carries.
     */
    private static java.util.List<Object> childrenOf(Object info) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (info == null || NativeDrawerHooks.isFolderEntry(info)) {
            // One of our own synthetic drawer folders; its children are ours, not the launcher's.
            return out;
        }
        for (java.lang.reflect.Field field : Mirror.fields(info.getClass())) {
            if (!java.util.List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Object value = Mirror.get(field, info);
            if (!(value instanceof java.util.List) || ((java.util.List<?>) value).isEmpty()) {
                continue;
            }
            for (Object child : (java.util.List<?>) value) {
                // Only a list of things that are themselves icons - a folder's contents, not a
                // list of listeners or of strings that happens to be on the same class.
                if (child != null && NativeDrawerHooks.componentOf(child) != null) {
                    out.add(child);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return out;
    }

    /** Which user the icon belongs to - a work-profile app is not the personal one. */
    static UserHandle userOf(Object info) {
        Object user = Reflect.field(info, "user");
        return user instanceof UserHandle ? (UserHandle) user : Process.myUserHandle();
    }
}
