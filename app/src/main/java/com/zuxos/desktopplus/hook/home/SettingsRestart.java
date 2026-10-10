package com.zuxos.desktopplus.hook.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;

/**
 * The launcher restarting itself when a setting it reads only as it starts has changed - the
 * theme, the monitor's bar position, which hooks are in - so nobody has to force-stop it.
 *
 * <p>The settings app says when one of those changed ({@link Const#ACTION_SETTINGS_CHANGED}).
 * The launcher reads them again and compares with what it started with; only a real difference
 * restarts it, so a stray broadcast does nothing. Android brings home straight back.
 */
public final class SettingsRestart {

    private static String sStartedWith;
    private static boolean sListening;

    private SettingsRestart() {
    }

    /** What the launcher starts with, read as its hooks go in. */
    public static void remember() {
        sStartedWith = current();
    }

    /** Listens once, from the first activity that comes up. */
    static void listen(Context ctx) {
        if (sListening || sStartedWith == null) {
            return;
        }
        sListening = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    changed();
                }
            };
            IntentFilter filter = new IntentFilter(Const.ACTION_SETTINGS_CHANGED);
            Context app = ctx.getApplicationContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            L.w("settings: not listening for changes (" + t + "), a restart takes them");
        }
    }

    private static void changed() {
        try {
            Cfg.reload();
            String now = current();
            if (now.equals(sStartedWith)) {
                return;
            }
            L.i("settings: " + sStartedWith + " -> " + now + ", the launcher restarts to take "
                    + "them");
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable t) {
            L.w("settings: could not restart for the change (" + t + ")");
        }
    }

    /** The start-only settings, as one line. */
    private static String current() {
        return "enabled=" + Cfg.enabled() + " theme=" + Cfg.theme() + " edge=" + Cfg.taskbarEdge()
                + " drawer=" + Cfg.nativeDrawer() + " tray=" + Cfg.taskbarTray()
                + " popups=" + Cfg.notifyPopups() + " unlock=" + Cfg.unlockStock();
    }
}
