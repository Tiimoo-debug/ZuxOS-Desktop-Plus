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

    /**
     * Turned on from the taskbar and not yet seen off. Read-only trace while the owner's report
     * that ZUI sometimes ignores the switch is looked into: what the battery does once it is on,
     * and when it goes off without the taskbar.
     */
    private static boolean sHeld;

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
            L.i("bypass charging: " + (on ? "on" : "off") + (done ? "" : " refused by ZUI")
                    + "; battery " + battery(ctx, null));
            sHeld = on && done;
            if (sHeld) {
                Context app = ctx.getApplicationContext();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                        L.i("bypass charging: 10 s on - ZUI says " + on(app) + "; battery "
                                + battery(app, null)), 10_000L);
            }
            return done;
        } catch (Throwable t) {
            L.w("bypass charging: cannot switch it (" + t + ")");
            return false;
        }
    }

    /**
     * Each battery broadcast while the taskbar holds it on: logs once when it has gone off
     * without the taskbar - ZUI, or Game Assistant, which turns it off as a game ends. Nothing
     * is asked while it is not held.
     */
    public static void batteryChanged(Context ctx, android.content.Intent intent) {
        if (!sHeld || !Boolean.FALSE.equals(on(ctx))) {
            return;
        }
        sHeld = false;
        L.i("bypass charging: turned off without the taskbar; battery " + battery(ctx, intent));
    }

    /** What the battery is doing: current in mA (positive charging), status, plug, level. */
    private static String battery(Context ctx, android.content.Intent intent) {
        try {
            android.os.BatteryManager bm = ctx.getSystemService(android.os.BatteryManager.class);
            if (intent == null) {
                intent = ctx.registerReceiver(null, new android.content.IntentFilter(
                        android.content.Intent.ACTION_BATTERY_CHANGED));
            }
            int current = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            return (current / 1000)
                    + " mA, status " + (intent != null ? intent.getIntExtra("status", -1) : -1)
                    + ", plugged " + (intent != null ? intent.getIntExtra("plugged", -1) : -1)
                    + ", level " + (intent != null ? intent.getIntExtra("level", -1) : -1);
        } catch (Throwable t) {
            return "unread (" + t + ")";
        }
    }

    private static Object manager(Context ctx) throws ReflectiveOperationException {
        Class<?> cls = Class.forName(MANAGER);
        return cls.getMethod("getService", Context.class).invoke(null, ctx);
    }
}
