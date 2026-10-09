package com.zuxos.desktopplus.notify;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Prefs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    /**
     * Told when a notification arrives that should pop up on the monitor. It carries no key and
     * nothing else: an observer is not checked like a caller is, so what arrived is asked for
     * through {@link #METHOD_POPS}.
     */
    public static final Uri POSTED = Uri.parse("content://" + AUTHORITY + "/posted");
    /** The picture chosen for the account bar on the desktop's app drawer. */
    public static final Uri AVATAR = Uri.parse("content://" + AUTHORITY + "/avatar");
    /** Where the module keeps that picture, in its own files. */
    public static final String AVATAR_FILE = "avatar.png";

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
    public static final String METHOD_CLEAR_ALL = "clearAll";
    /** For the probe: what kinds of notification are up - never what they say. */
    public static final String METHOD_DESCRIBE = "describe";
    /**
     * The pop-ups that arrived after the time in the extras ({@link #EXTRA_SINCE}): a list of
     * bundles under {@link #EXTRA_POPS}, oldest first, with the {@code POP_*} keys.
     */
    public static final String METHOD_POPS = "pops";
    public static final String EXTRA_SINCE = "since";
    public static final String EXTRA_POPS = "pops";

    /** A pop-up's bundle. */
    public static final String POP_KEY = "key";
    public static final String POP_PACKAGE = "package";
    public static final String POP_TITLE = "title";
    public static final String POP_TEXT = "text";
    public static final String POP_WHEN = "when";
    /** The sender's picture (a contact, an album), small; absent when it has none. */
    public static final String POP_PICTURE = "picture";
    public static final String POP_INTENT = "pendingIntent";
    public static final String POP_AUTO_CANCEL = "autoCancel";
    /** Its buttons, as bundles with the {@code ACTION_*} keys. */
    public static final String POP_ACTIONS = "actions";
    public static final String ACTION_TITLE = "title";
    public static final String ACTION_INTENT = "intent";
    /** The typed answer it takes, when it is a reply: a {@link RemoteInput}. */
    public static final String ACTION_INPUT = "input";

    /** As many buttons as the tablet's own pop-ups show. */
    private static final int MAX_ACTIONS = 3;
    private static final int PICTURE_PX = 96;

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
        String title = title(notification);
        String text = text(notification);
        if (title.isEmpty() && text.isEmpty()) {
            return;
        }
        cursor.addRow(new Object[]{
                sbn.getKey(),
                sbn.getPackageName(),
                title,
                text,
                sbn.getPostTime(),
                sbn.isClearable() ? 1 : 0,
        });
    }

    static String title(Notification notification) {
        Bundle extras = notification.extras;
        CharSequence title = extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        return title == null ? "" : title.toString();
    }

    static String text(Notification notification) {
        Bundle extras = notification.extras;
        CharSequence text = extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (text == null && extras != null) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }
        return text == null ? "" : text.toString();
    }

    /** The pop-ups after {@code since}, still up, oldest first. */
    private Bundle pops(NotifyService service, long since) {
        ArrayList<Bundle> out = new ArrayList<>();
        List<String> keys = NotifyService.popsSince(since);
        StatusBarNotification[] found = keys.isEmpty() ? null
                : service.getActiveNotifications(keys.toArray(new String[0]));
        if (found != null) {
            List<StatusBarNotification> sorted = new ArrayList<>(Arrays.asList(found));
            sorted.removeIf(sbn -> sbn == null || sbn.getNotification() == null);
            sorted.sort((a, b) -> Long.compare(a.getPostTime(), b.getPostTime()));
            for (StatusBarNotification sbn : sorted) {
                try {
                    out.add(pop(sbn));
                } catch (Throwable t) {
                    L.d("notifications: no pop-up for " + sbn.getPackageName() + " (" + t + ")");
                }
            }
        }
        Bundle result = new Bundle();
        result.putParcelableArrayList(EXTRA_POPS, out);
        return result;
    }

    /**
     * One pop-up: what it says, what a tap opens, and its buttons. All of it parcels as it is,
     * except the picture, which is drawn small here so a large photo cannot overflow the call.
     */
    private Bundle pop(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle pop = new Bundle();
        pop.putString(POP_KEY, sbn.getKey());
        pop.putString(POP_PACKAGE, sbn.getPackageName());
        pop.putString(POP_TITLE, title(n));
        pop.putString(POP_TEXT, text(n));
        pop.putLong(POP_WHEN, sbn.getPostTime());
        pop.putParcelable(POP_INTENT, n.contentIntent);
        pop.putBoolean(POP_AUTO_CANCEL, sbn.isClearable()
                && (n.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        Bitmap picture = picture(n.getLargeIcon());
        if (picture != null) {
            pop.putParcelable(POP_PICTURE, picture);
        }
        ArrayList<Bundle> actions = new ArrayList<>();
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (actions.size() >= MAX_ACTIONS) {
                    break;
                }
                if (action == null || action.actionIntent == null || action.title == null
                        || action.isContextual()) {
                    continue;
                }
                Bundle b = new Bundle();
                b.putString(ACTION_TITLE, action.title.toString());
                b.putParcelable(ACTION_INTENT, action.actionIntent);
                RemoteInput[] inputs = action.getRemoteInputs();
                if (inputs != null) {
                    for (RemoteInput input : inputs) {
                        if (input != null && input.getAllowFreeFormInput()) {
                            b.putParcelable(ACTION_INPUT, input);
                            break;
                        }
                    }
                }
                actions.add(b);
            }
        }
        pop.putParcelableArrayList(POP_ACTIONS, actions);
        return pop;
    }

    private Bitmap picture(Icon icon) {
        Context ctx = getContext();
        if (icon == null || ctx == null) {
            return null;
        }
        try {
            Drawable d = icon.loadDrawable(ctx);
            if (d == null) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            d.setBounds(0, 0, PICTURE_PX, PICTURE_PX);
            d.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Dismisses, clears, or hands back what a notification points at.
     *
     * <p>Opening is deliberately not done here. This process exists to listen and is otherwise in
     * the background, and since Android 14 a background process may not start an activity - so
     * sending the intent from here failed silently. The {@link PendingIntent} is handed to the
     * launcher instead, which is the app in the foreground on that display, and it sends it.
     */
    @Override
    public Bundle call(String method, String key, Bundle extras) {
        if (!allowed()) {
            return null;
        }
        NotifyService service = NotifyService.connected();
        if (service == null) {
            return null;
        }
        try {
            if (METHOD_CLEAR_ALL.equals(method)) {
                service.cancelAllNotifications();
                return null;
            }
            if (METHOD_DESCRIBE.equals(method)) {
                Bundle result = new Bundle();
                result.putString("summary", describe(service.getActiveNotifications()));
                return result;
            }
            if (METHOD_POPS.equals(method)) {
                return pops(service, extras != null ? extras.getLong(EXTRA_SINCE) : 0L);
            }
            if (key == null) {
                return null;
            }
            if (METHOD_DISMISS.equals(method)) {
                service.cancelNotification(key);
                return null;
            }
            if (METHOD_OPEN.equals(method)) {
                for (StatusBarNotification sbn : service.getActiveNotifications()) {
                    if (!key.equals(sbn.getKey())) {
                        continue;
                    }
                    Notification notification = sbn.getNotification();
                    PendingIntent intent = notification.contentIntent;
                    if (intent == null) {
                        return null;
                    }
                    Bundle result = new Bundle();
                    result.putParcelable("pendingIntent", intent);
                    result.putBoolean("autoCancel", sbn.isClearable()
                            && (notification.flags & Notification.FLAG_AUTO_CANCEL) != 0);
                    return result;
                }
            }
        } catch (Throwable t) {
            L.d("notifications: " + method + " failed (" + t + ")");
        }
        return null;
    }

    /**
     * One line per notification up now, for the probe and the live notifications to come
     * (roadmap #5): its app, category and channel, whether it is ongoing, a foreground service,
     * promoted to a live update or asking to be, its style and whether it shows progress. Never
     * its title or text.
     */
    private static String describe(StatusBarNotification[] active) {
        StringBuilder sb = new StringBuilder();
        if (active == null || active.length == 0) {
            return "  none up\n";
        }
        for (StatusBarNotification sbn : active) {
            Notification n = sbn.getNotification();
            if (n == null) {
                continue;
            }
            Bundle extras = n.extras != null ? n.extras : new Bundle();
            String template = extras.getString(Notification.EXTRA_TEMPLATE);
            sb.append("  ").append(sbn.getPackageName())
                    .append(": category ").append(n.category)
                    .append(", channel ").append(n.getChannelId())
                    .append((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 ? ", ongoing" : "")
                    .append((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0
                            ? ", foreground service" : "")
                    // FLAG_PROMOTED_ONGOING, Android 16's live updates.
                    .append((n.flags & 0x40000) != 0 ? ", promoted (live update)" : "")
                    .append(extras.getBoolean("android.requestPromotedOngoing")
                            ? ", asks to be promoted" : "")
                    .append(template != null
                            ? ", style " + template.substring(template.lastIndexOf('$') + 1) : "")
                    .append(extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0
                            || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
                            ? ", progress" : "")
                    .append(extras.containsKey("android.shortCriticalText") ? ", chip text" : "")
                    .append((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 ? ", group summary"
                            : "")
                    .append('\n');
        }
        return sb.toString();
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

    /**
     * The account picture, read-only, to the same callers as everything else here.
     *
     * <p>The picture is chosen in the module's own activity, which is the only app that can open
     * Android's photo picker and keep what it returns; this is how the launcher gets to see it.
     */
    @Override
    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        if (!"/avatar".equals(uri.getPath()) || !allowed() || getContext() == null) {
            throw new java.io.FileNotFoundException(uri.toString());
        }
        java.io.File file = new java.io.File(getContext().getFilesDir(), AVATAR_FILE);
        if (!file.exists()) {
            throw new java.io.FileNotFoundException("no picture chosen");
        }
        return android.os.ParcelFileDescriptor.open(file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        if (uri != null && "/avatar".equals(uri.getPath())) {
            return "image/png";
        }
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
