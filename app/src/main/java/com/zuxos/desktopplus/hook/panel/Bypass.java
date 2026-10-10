package com.zuxos.desktopplus.hook.panel;

import android.content.Context;

import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Method;

/**
 * ZUI's bypass charging: the charger powers the tablet and the battery is left alone, so it
 * neither fills nor warms.
 *
 * <p>Switched exactly as ZUI's Game Assistant switches it ({@code XpuKt.openBypassCharging}):
 * {@code ZuiBatteryManager.setBypassChargingStatus}, ZUI's battery service. That service lets any
 * system app call it, and ZUX Home is one, so no root is involved. ZUI turns it off itself at
 * every boot. Hidden API, so reached by reflection; on a build without it the menu says so.
 */
public final class Bypass {

    private static final String MANAGER = "android.hardware.battery.ZuiBatteryManager";

    private Bypass() {
    }

    /** Whether bypass charging is on; null where ZUI's battery service is not there. */
    public static Boolean on(Context ctx) {
        try {
            Object manager = manager(ctx);
            Method get = manager.getClass().getMethod("getBypassChargingStatus");
            return (Boolean) get.invoke(manager);
        } catch (Throwable t) {
            L.d("bypass charging: cannot read it (" + t + ")");
            return null;
        }
    }

    /** Turns it on or off; false when ZUI's service refused or is not there. */
    public static boolean set(Context ctx, boolean on) {
        try {
            Object manager = manager(ctx);
            Method set = manager.getClass().getMethod("setBypassChargingStatus", boolean.class);
            boolean done = Boolean.TRUE.equals(set.invoke(manager, on));
            L.i("bypass charging: " + (on ? "on" : "off") + (done ? "" : " refused by ZUI"));
            return done;
        } catch (Throwable t) {
            L.w("bypass charging: cannot switch it (" + t + ")");
            return false;
        }
    }

    private static Object manager(Context ctx) throws ReflectiveOperationException {
        Class<?> cls = Class.forName(MANAGER);
        return cls.getMethod("getService", Context.class).invoke(null, ctx);
    }
}
