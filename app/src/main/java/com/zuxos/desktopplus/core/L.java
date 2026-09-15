package com.zuxos.desktopplus.core;

import android.util.Log;

/**
 * Logging that works both in the settings app and inside a hooked process.
 *
 * <p>Xposed classes are absent in our own process, so every call into them is guarded:
 * a module must never take down the launcher just because it wanted to log.
 */
public final class L {

    private static boolean sDebug;
    private static Boolean sXposedAvailable;

    private L() {
    }

    public static void setDebug(boolean debug) {
        sDebug = debug;
    }

    public static boolean isDebug() {
        return sDebug;
    }

    public static void d(String msg) {
        if (!sDebug) {
            return;
        }
        Log.d(Const.TAG, msg);
        xposed("[D] " + msg);
    }

    public static void i(String msg) {
        Log.i(Const.TAG, msg);
        xposed("[I] " + msg);
    }

    public static void w(String msg) {
        Log.w(Const.TAG, msg);
        xposed("[W] " + msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(Const.TAG, msg, t);
        xposed("[E] " + msg + ": " + Log.getStackTraceString(t));
    }

    private static void xposed(String msg) {
        if (sXposedAvailable == null) {
            try {
                Class.forName("de.robv.android.xposed.XposedBridge");
                sXposedAvailable = Boolean.TRUE;
            } catch (Throwable t) {
                sXposedAvailable = Boolean.FALSE;
            }
        }
        if (!sXposedAvailable) {
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.log(Const.TAG + " " + msg);
        } catch (Throwable ignored) {
            sXposedAvailable = Boolean.FALSE;
        }
    }
}
