package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.widget.Toast;

import com.zuxos.desktopplus.core.L;

/**
 * Where the clock and the date in the tray take you.
 *
 * <p>Each has a chain rather than one intent, because the first choice can be absent and a tray
 * clock that does nothing when tapped is worse than one that opens something close.
 */
public final class Shortcuts {

    /** Asked for by name; the chain below covers it not being installed. */
    private static final String CALENDAR_PKG = "org.fossify.calendar";

    private Shortcuts() {
    }

    /** The clock app, at its alarms - the screen every clock app has. */
    public static void openClock(Context ctx, int displayId) {
        Intent alarms = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        if (start(ctx, alarms, displayId)) {
            return;
        }
        if (start(ctx, new Intent(AlarmClock.ACTION_SET_ALARM), displayId)) {
            return;
        }
        toast(ctx, "No clock app to open");
    }

    /**
     * The calendar, at today.
     *
     * <p>{@code content://com.android.calendar/time/<millis>} is the standard "show me this
     * moment" address, understood by every calendar app. It is aimed at Fossify first, then let
     * go of so whichever calendar is default answers instead.
     */
    public static void openCalendar(Context ctx, int displayId) {
        Uri today = CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .appendPath(Long.toString(System.currentTimeMillis()))
                .build();

        Intent fossify = new Intent(Intent.ACTION_VIEW, today).setPackage(CALENDAR_PKG);
        if (start(ctx, fossify, displayId)) {
            return;
        }
        if (start(ctx, new Intent(Intent.ACTION_VIEW, today), displayId)) {
            return;
        }
        // Neither resolved: open Fossify wherever it happens to start, then give up.
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(CALENDAR_PKG);
            if (launch != null && start(ctx, launch, displayId)) {
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to the message.
        }
        toast(ctx, "No calendar app to open");
    }

    /** True when something actually started. */
    private static boolean start(Context ctx, Intent intent, int displayId) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, QuickTiles.launchOptions(displayId));
            return true;
        } catch (Throwable t) {
            L.d("shortcut: " + intent.getAction() + " did not start (" + t + ")");
            return false;
        }
    }

    private static void toast(Context ctx, String message) {
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            L.w(message);
        }
    }
}
