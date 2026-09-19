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

    /** Which user the icon belongs to - a work-profile app is not the personal one. */
    static UserHandle userOf(Object info) {
        Object user = Reflect.field(info, "user");
        return user instanceof UserHandle ? (UserHandle) user : Process.myUserHandle();
    }
}
