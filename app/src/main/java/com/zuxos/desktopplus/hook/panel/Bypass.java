package com.zuxos.desktopplus.hook.panel;

import android.content.Context;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Su;

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
     * Read-only trace while the owner's report is looked into: bypass holds with both USB cables
     * in, then at some point the battery charges again. Whether ZUI says it is on - asked once
     * when the tray starts, then kept from the switch - and the last battery status seen.
     */
    private static boolean sHeld;
    private static boolean sAsked;
    private static int sLastStatus = -1;

    /** What the system logged around the moment, read as root: the battery, port and game tags. */
    private static final String AROUND = "logcat -d -t 400 -b main,system 2>/dev/null"
            + " | grep -E 'ZBMS_|DualPort|ItemBypassCharging|GameHelper|BatteryService|healthd'"
            + " | tail -60; dmesg 2>/dev/null | grep -i -E 'douusb|DOU_USB|bypass|chg_dis|charg'"
            + " | tail -20";

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
            sAsked = true;
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
     * Each battery broadcast: while bypass is on, logs once when it has gone off, and each time
     * the battery goes back to charging (status 2; bypass reads 4, not charging), with what the
     * system logged around that moment. Nothing is asked while bypass is off.
     */
    public static void batteryChanged(Context ctx, android.content.Intent intent) {
        if (!sAsked) {
            sAsked = true;
            sHeld = Boolean.TRUE.equals(on(ctx));
        }
        int status = intent.getIntExtra("status", -1);
        int last = sLastStatus;
        sLastStatus = status;
        if (!sHeld) {
            return;
        }
        if (!Boolean.TRUE.equals(on(ctx))) {
            sHeld = false;
            L.i("bypass charging: turned off without the taskbar; battery " + battery(ctx, intent));
            around();
            return;
        }
        if (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING && last != status) {
            L.i("bypass charging: on, but the battery charges again (status " + last + " -> "
                    + status + "); battery " + battery(ctx, intent));
            around();
        }
    }

    /** The system's own lines from just before, so the log says who decided. */
    private static void around() {
        Su.read((outcome, text) -> {
            if (text == null || text.isEmpty()) {
                L.i("bypass charging: system log not read (" + outcome + ")");
                return;
            }
            for (String line : text.split("\n")) {
                L.i("bypass charging: | " + line);
            }
        }, AROUND);
    }

    /**
     * What the battery is doing: its current in mA with the kernel's sign - on this tablet
     * positive is the battery giving current, as the owner's probes showed - then status, plug,
     * level, and what the charger offers when the broadcast says.
     */
    private static String battery(Context ctx, android.content.Intent intent) {
        try {
            android.os.BatteryManager bm = ctx.getSystemService(android.os.BatteryManager.class);
            if (intent == null) {
                intent = ctx.registerReceiver(null, new android.content.IntentFilter(
                        android.content.Intent.ACTION_BATTERY_CHANGED));
            }
            int current = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            return (current / 1000) + " mA (positive = from the battery)"
                    + ", status " + (intent != null ? intent.getIntExtra("status", -1) : -1)
                    + ", plugged " + (intent != null ? intent.getIntExtra("plugged", -1) : -1)
                    + ", level " + (intent != null ? intent.getIntExtra("level", -1) : -1)
                    + ", charger max " + (intent != null
                    ? intent.getIntExtra("max_charging_voltage", -1) / 1000 + " mV "
                    + intent.getIntExtra("max_charging_current", -1) / 1000 + " mA" : "?");
        } catch (Throwable t) {
            return "unread (" + t + ")";
        }
    }

    private static Object manager(Context ctx) throws ReflectiveOperationException {
        Class<?> cls = Class.forName(MANAGER);
        return cls.getMethod("getService", Context.class).invoke(null, ctx);
    }
}
