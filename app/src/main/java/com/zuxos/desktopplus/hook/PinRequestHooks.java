package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.UserManager;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.desktop.DesktopHost;
import com.zuxos.desktopplus.model.DesktopStore;
import com.zuxos.desktopplus.model.Item;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Catches "add to home screen" requests.
 *
 * <p>When an app pins a shortcut - a web page from a browser, a file, a chat - the system hands
 * the request to the default home app, which drops it on the tablet's home screen. The desktop
 * mode home never sees it. We watch the launcher accept the request and put a copy of the
 * shortcut on the desktop too.
 */
public final class PinRequestHooks {

    private static final int REQUEST_TYPE_SHORTCUT = 1;

    private static boolean sInstalled;

    private PinRequestHooks() {
    }

    public static void install() {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> cls = LauncherApps.PinItemRequest.class;
            int hooks = XposedBridge.hookAllMethods(cls, "accept", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.FALSE.equals(param.getResult())) {
                        return;
                    }
                    onAccepted(param.thisObject);
                }
            }).size();
            L.i("pinned shortcuts: watching PinItemRequest.accept x" + hooks);
        } catch (Throwable t) {
            L.e("pinned shortcuts: could not watch pin requests", t);
        }
    }

    private static void onAccepted(Object request) {
        try {
            if (!Cfg.enabled() || !Cfg.catchPinnedShortcuts()) {
                return;
            }
            Object type = Reflect.call(request, "getRequestType");
            if (!(type instanceof Integer) || (Integer) type != REQUEST_TYPE_SHORTCUT) {
                return;
            }
            Object info = Reflect.call(request, "getShortcutInfo");
            if (!(info instanceof ShortcutInfo)) {
                return;
            }
            ShortcutInfo shortcut = (ShortcutInfo) info;
            Context ctx = AppCtx.get();
            if (ctx == null) {
                L.w("pinned shortcuts: no context yet, dropping " + shortcut.getId());
                return;
            }
            CharSequence label = shortcut.getShortLabel() != null
                    ? shortcut.getShortLabel() : shortcut.getLongLabel();
            Item item = Item.shortcut(shortcut.getPackage(), shortcut.getId(),
                    serialOf(ctx, shortcut), label != null ? label.toString() : shortcut.getId());

            DesktopHost host = DesktopHost.current();
            if (host != null) {
                host.addPinnedItem(item);
            } else {
                // No desktop attached right now: write it straight to the layout so it is there
                // the next time the external display comes up.
                DesktopStore store = new DesktopStore(ctx, true);
                store.load();
                store.add(item);
                store.save();
            }
            L.i("pinned shortcuts: added " + item.label + " to the desktop");
        } catch (Throwable t) {
            L.e("pinned shortcuts: could not add the shortcut", t);
        }
    }

    private static long serialOf(Context ctx, ShortcutInfo shortcut) {
        try {
            UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
            return um != null ? um.getSerialNumberForUser(shortcut.getUserHandle()) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
