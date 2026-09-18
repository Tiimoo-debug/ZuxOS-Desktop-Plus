package com.zuxos.desktopplus.notify;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Prefs;

/**
 * The window through which the launcher sees the notification list.
 *
 * <p>The listener and the launcher are different apps in different processes, so something has to
 * carry the list across. A provider is the sanctioned way, and it keeps the awkward half - sending
 * a notification's intent, cancelling it - in the process that is actually allowed to do those
 * things.
 *
 * <p>Exported, because the launcher is a different app and has to be able to ask. Not open,
 * though: every call checks the caller by name against the packages this module is set to hook.
 * Somebody else's app asking for the notification list gets nothing.
 */
public final class NotifyProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zuxos.desktopplus.notifications";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/active");

    /** What a row carries. Icons are not here: the launcher draws the app's own, which it has. */
    public static final String COL_KEY = "key";
    public static final String COL_PACKAGE = "package";
    public static final String COL_TITLE = "title";
    public static final String COL_TEXT = "text";
    public static final String COL_WHEN = "when";
    public static final String COL_CLEARABLE = "clearable";

    private static final String[] COLUMNS = {
            COL_KEY, COL_PACKAGE, COL_TITLE, COL_TEXT, COL_WHEN, COL_CLEARABLE,
    };

    /** {@code call} methods the launcher may use. */
    public static final String METHOD_OPEN = "open";
    public static final String METHOD_DISMISS = "dismiss";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args,
            String sort) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (!allowed()) {
            return cursor;
        }
        NotifyService service = NotifyService.connected();
        if (service == null) {
            return cursor;
        }
        StatusBarNotification[] active;
        try {
            active = service.getActiveNotifications();
        } catch (Throwable t) {
            L.d("notifications: could not read the list (" + t + ")");
            return cursor;
        }
        if (active == null) {
            return cursor;
        }
        for (StatusBarNotification sbn : active) {
            try {
                add(cursor, sbn);
            } catch (Throwable ignored) {
                // One awkward notification is not worth losing the rest.
            }
        }
        try {
            // What makes the launcher's observer fire when the shade moves.
            Context ctx = getContext();
            if (ctx != null) {
                cursor.setNotificationUri(ctx.getContentResolver(), URI);
            }
        } catch (Throwable ignored) {
            // Only costs live updates.
        }
        return cursor;
    }

    private void add(MatrixCursor cursor, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return;
        }
        // Group summaries repeat what their children already say.
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }
        Bundle extras = notification.extras;
        // And a media notification is the card the panel already draws for that same session,
        // which would otherwise appear twice, a row above itself.
        if (Notification.CATEGORY_TRANSPORT.equals(notification.category)
                || (extras != null && extras.containsKey("android.mediaSession"))) {
            return;
        }
        CharSequence title = extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence text = extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (text == null && extras != null) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }
        if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) {
            return;
        }
        cursor.addRow(new Object[]{
                sbn.getKey(),
                sbn.getPackageName(),
                title == null ? "" : title.toString(),
                text == null ? "" : text.toString(),
                sbn.getPostTime(),
                sbn.isClearable() ? 1 : 0,
        });
    }

    /**
     * Opens or dismisses one notification, here rather than in the launcher.
     *
     * <p>A {@link PendingIntent} belongs to the app that made it and cancelling belongs to the
     * listener; neither travels across a process boundary as data.
     */
    @Override
    public Bundle call(String method, String key, Bundle extras) {
        if (!allowed() || key == null) {
            return null;
        }
        NotifyService service = NotifyService.connected();
        if (service == null) {
            return null;
        }
        try {
            if (METHOD_DISMISS.equals(method)) {
                service.cancelNotification(key);
                return null;
            }
            if (METHOD_OPEN.equals(method)) {
                for (StatusBarNotification sbn : service.getActiveNotifications()) {
                    if (!key.equals(sbn.getKey())) {
                        continue;
                    }
                    PendingIntent intent = sbn.getNotification().contentIntent;
                    if (intent != null) {
                        // Onto the display the panel is on. Without this the app opens on the
                        // tablet's own screen while you are looking at the external one.
                        intent.send(getContext(), 0, null, null, null, null,
                                launchOptions(extras));
                        L.i("notifications: opened " + sbn.getPackageName());
                        if (sbn.isClearable()
                                && (sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) {
                            service.cancelNotification(key);
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            L.d("notifications: " + method + " failed (" + t + ")");
        }
        return null;
    }

    /** Puts whatever opens on the display the caller asked for. */
    private Bundle launchOptions(Bundle extras) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            int displayId = extras != null ? extras.getInt("displayId", -1) : -1;
            if (displayId >= 0) {
                options.setLaunchDisplayId(displayId);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // This process is in the background - it exists to listen, not to be looked at -
                // and since Android 14 a background process may not start an activity unless it
                // says so when sending the intent. Without this the row would close the panel
                // and open nothing.
                options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            return options.toBundle();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Only the launcher this module is set to hook, and the module itself. */
    private boolean allowed() {
        String caller = getCallingPackage();
        if (caller == null || caller.equals(Const.MODULE_PKG)) {
            return caller != null;
        }
        for (String target : targets()) {
            if (caller.equals(target)) {
                return true;
            }
        }
        L.d("notifications: refused " + caller);
        return false;
    }

    private String[] targets() {
        String extra = "";
        try {
            // Read straight from the settings file, not through Cfg: that one goes via
            // XSharedPreferences, which exists only inside a hooked process and would quietly
            // hand back defaults here - so a launcher the user added by hand would never be let
            // in.
            Context ctx = getContext();
            if (ctx != null) {
                extra = Prefs.get(ctx).getString(Const.KEY_EXTRA_TARGETS, "");
            }
        } catch (Throwable ignored) {
            // Preferences unreadable; the built-in names below still stand.
        }
        String joined = "com.zui.launcher,com.android.launcher3," + extra;
        String[] split = joined.split(",");
        for (int i = 0; i < split.length; i++) {
            split[i] = split[i].trim();
        }
        return split;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.zuxos.notification";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
