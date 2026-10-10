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
     * Read-only trace while the owner's report is looked into: bypass holds, but under load the
     * battery gives current and at some point charges again. Whether ZUI says it is on - asked
     * once when the tray starts, then kept from the switch.
     */
    private static boolean sHeld;
    private static boolean sAsked;
    /** What the battery was last seen doing while bypass is on: one of the three below. */
    private static String sFlow;

    private static final String CHARGING = "charging";
    private static final String RESTING = "resting";
    private static final String GIVING = "giving";

    /** The skin sensor and the charger's cooling device the thermal-engine uses, found once. */
    private static String sSkin;
    private static String sCooling;
    private static boolean sFound;

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
            sFlow = null;
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
     * the battery changes between charging, resting and giving - read from its current, since ZUI
     * keeps the status at "not charging" through it - with the input, the skin temperature and
     * the thermal-engine's charger level. Nothing is read while bypass is off.
     */
    public static void batteryChanged(Context ctx, android.content.Intent intent) {
        if (!sAsked) {
            sAsked = true;
            sHeld = Boolean.TRUE.equals(on(ctx));
        }
        if (!sHeld) {
            sFlow = null;
            return;
        }
        if (!Boolean.TRUE.equals(on(ctx))) {
            sHeld = false;
            sFlow = null;
            L.i("bypass charging: turned off without the taskbar; battery " + battery(ctx, intent)
                    + "; " + thermal());
            around();
            return;
        }
        String flow = flow(ctx);
        if (flow == null || flow.equals(sFlow)) {
            return;
        }
        String was = sFlow;
        sFlow = flow;
        L.i("bypass charging: on, battery " + (was == null ? "" : was + " -> ") + flow + "; battery "
                + battery(ctx, intent) + "; input " + read("/sys/class/power_supply/usb/voltage_now")
                + " uV " + read("/sys/class/power_supply/usb/current_now") + " uA; " + thermal());
        if (CHARGING.equals(flow) && was != null) {
            around();
        }
    }

    /** The battery's current as a flow: into it, out of it, or neither, with some slack. */
    private static String flow(Context ctx) {
        try {
            android.os.BatteryManager bm = ctx.getSystemService(android.os.BatteryManager.class);
            int ma = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    / 1000;
            // This tablet's sign: positive is the battery giving current.
            return ma < -150 ? CHARGING : ma > 500 ? GIVING : RESTING;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The skin temperature the thermal-engine watches for the charger (quiet-therm) and the level
     * it holds the charger at (the "battery" cooling device).
     */
    private static String thermal() {
        if (!sFound) {
            sFound = true;
            sSkin = byType("/sys/class/thermal", "thermal_zone", "quiet-therm", "temp");
            sCooling = byType("/sys/class/thermal", "cooling_device", "battery", "cur_state");
        }
        return "skin " + (sSkin != null ? read(sSkin) : "?") + " mC, charger held at level "
                + (sCooling != null ? read(sCooling) : "?");
    }

    /** The file {@code leaf} of the entry under {@code dir} whose type is {@code type}. */
    private static String byType(String dir, String prefix, String type, String leaf) {
        String[] names = new java.io.File(dir).list();
        if (names == null) {
            return null;
        }
        for (String name : names) {
            if (name.startsWith(prefix) && type.equals(read(dir + "/" + name + "/type"))) {
                return dir + "/" + name + "/" + leaf;
            }
        }
        return null;
    }

    /** One line of a small system file, or "?" where it cannot be read. */
    private static String read(String path) {
        try (java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.FileReader(path))) {
            String line = in.readLine();
            return line != null ? line.trim() : "?";
        } catch (Throwable t) {
            return "?";
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
