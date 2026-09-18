package com.zuxos.desktopplus.hook;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.TrayIcons;
import com.zuxos.desktopplus.core.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The notification list, as the panel shows it.
 *
 * <p>Read from the module's own listener through its provider - see
 * {@code com.zuxos.desktopplus.notify.NotifyProvider}. The launcher holds no notification access
 * of its own and cannot be given any, so when the user has not switched the listener on this
 * simply has nothing to show, and says so once.
 */
final class Notifications {

    private static final Uri URI = Uri.parse("content://com.zuxos.desktopplus.notifications/active");
    private static final String METHOD_OPEN = "open";
    private static final String METHOD_DISMISS = "dismiss";
    private static final String METHOD_CLEAR_ALL = "clearAll";

    /** How many fit above the sliders without the panel becoming a shade. */
    private static final int MAX_ROWS = 4;

    /** The last thing said about the shade, so the same line is not repeated every open. */
    private static String sLastReport;

    /** App icons, kept: the package manager is a binder call and this runs on the UI thread. */
    private static final Map<String, Drawable> ICONS = new HashMap<>();
    private static final Map<String, String> NAMES = new HashMap<>();

    private Notifications() {
    }

    /** The provider's URI, for a caller that wants to be told when the shade moves. */
    static Uri uri() {
        return URI;
    }

    /**
     * Whether the module's listener has actually been switched on.
     *
     * <p>A local settings read, and worth doing before anything else: querying the provider is a
     * cross-process call that would start the module's process from cold just to be handed an
     * empty cursor, every time the panel opened, for a user who never granted access.
     */
    static boolean available(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            return enabled != null && enabled.contains(Const.MODULE_PKG);
        } catch (Throwable t) {
            return false;
        }
    }

    static final class Note {
        final String key;
        final String pkg;
        final String title;
        final String text;
        final long when;
        final boolean clearable;

        Note(String key, String pkg, String title, String text, long when, boolean clearable) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.when = when;
            this.clearable = clearable;
        }
    }

    static List<Note> list(Context ctx, int limit) {
        List<Note> out = new ArrayList<>();
        try (Cursor cursor = ctx.getContentResolver().query(URI, null, null, null, null)) {
            if (cursor == null) {
                report("the module's notification provider did not answer");
                return out;
            }
            while (cursor.moveToNext()) {
                out.add(new Note(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4),
                        cursor.getInt(5) == 1));
            }
            // Newest first, then cut: the system hands them over in its own order, so taking the
            // first four unsorted could hide the one that just arrived behind three old ones.
            Collections.sort(out, (a, b) -> Long.compare(b.when, a.when));
            while (limit > 0 && out.size() > limit) {
                out.remove(out.size() - 1);
            }
            if (out.isEmpty()) {
                report("nothing in the shade");
            }
        } catch (Throwable t) {
            report("unreadable (" + t + ")");
        }
        return out;
    }

    /**
     * Says something once.
     *
     * <p>Once per distinct thing, not once ever: an empty shade is the ordinary case and not
     * worth a line per panel open, but the reason being a different one is worth hearing.
     */
    private static void report(String what) {
        if (!what.equals(sLastReport)) {
            sLastReport = what;
            L.i("notifications: " + what);
        }
    }

    /**
     * How many are waiting, for the tray's bell.
     *
     * <p>Held for a moment between asks. The tray repaints on every change of state - a
     * temperature is enough - and each ask is a call into another process on the UI thread.
     */
    private static final long COUNT_TTL_MS = 3000L;
    private static int sCount;
    private static long sCountedAt;

    static int count(Context ctx) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sCountedAt < COUNT_TTL_MS) {
            return sCount;
        }
        sCountedAt = now;
        sCount = available(ctx) ? list(ctx, 0).size() : 0;
        return sCount;
    }

    /** Called when the shade moves, so the next ask is answered fresh. */
    static void countChanged() {
        sCountedAt = 0;
    }

    static void addRows(Context ctx, LinearLayout body, List<Note> notes, int displayId) {
        PackageManager pm = ctx.getPackageManager();
        for (Note note : notes) {
            body.addView(row(ctx, pm, note, displayId));
        }
    }

    static void clearAll(Context ctx) {
        call(ctx, METHOD_CLEAR_ALL, "", -1);
    }

    private static View row(Context ctx, PackageManager pm, Note note, int displayId) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(ctx, 8);
        line.setPadding(pad, pad, pad, pad);
        line.setBackground(Ui.ripple(ctx, 0x0FFFFFFF, Ui.dp(ctx, 12)));

        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(appIcon(pm, note.pkg));
        int size = Ui.dp(ctx, 22);
        line.addView(icon, new LinearLayout.LayoutParams(size, size));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(ctx, 10);
        line.addView(text, tlp);

        TextView title = new TextView(ctx);
        title.setText(note.title.isEmpty() ? appName(pm, note.pkg) : note.title);
        title.setTextColor(Ui.COLOR_TEXT);
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);

        if (!note.text.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(note.text);
            sub.setTextColor(Ui.COLOR_TEXT_DIM);
            sub.setTextSize(11);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(sub);
        }

        line.setOnClickListener(v -> {
            NotifyPanel.dismiss();
            open(ctx, note, displayId);
        });

        if (note.clearable) {
            ImageView clear = new ImageView(ctx);
            clear.setImageDrawable(TrayIcons.close(Ui.COLOR_TEXT_DIM));
            int button = Ui.dp(ctx, 26);
            int inset = Ui.dp(ctx, 6);
            clear.setPadding(inset, inset, inset, inset);
            clear.setBackground(Ui.ripple(ctx, 0x00000000, button / 2));
            clear.setContentDescription("Dismiss");
            clear.setOnClickListener(v -> {
                call(ctx, METHOD_DISMISS, note.key, -1);
                // Taken away here and now. Cancelling is a round trip through the system, so a
                // rebuild at this moment would read the list before it has gone and draw the row
                // straight back - which reads as a button that does nothing.
                line.setVisibility(View.GONE);
            });
            line.addView(clear, new LinearLayout.LayoutParams(button, button));
        }
        return line;
    }

    /**
     * Opens what the notification points at.
     *
     * <p>The intent is fetched from the module's process and sent from here, which is the whole
     * trick: the module's process is a background one, and since Android 14 a background process
     * may not start an activity - so sending it over there quietly did nothing. The launcher is
     * the foreground app on this display, so from here it simply works, and it can put the
     * activity on the right display while it is at it.
     */
    private static void open(Context ctx, Note note, int displayId) {
        try {
            Bundle result = ctx.getContentResolver().call(URI, METHOD_OPEN, note.key, null);
            PendingIntent intent = result == null ? null
                    : result.getParcelable("pendingIntent");
            if (intent == null) {
                L.i("notifications: " + note.pkg + " has nothing to open");
                return;
            }
            Bundle options = null;
            try {
                ActivityOptions opts = ActivityOptions.makeBasic();
                if (displayId >= 0) {
                    opts.setLaunchDisplayId(displayId);
                }
                options = opts.toBundle();
            } catch (Throwable ignored) {
                // Without options it still opens, just on the default display.
            }
            intent.send(ctx, 0, null, null, null, null, options);
            if (result.getBoolean("autoCancel")) {
                call(ctx, METHOD_DISMISS, note.key, -1);
            }
        } catch (Throwable t) {
            L.e("notifications: could not open " + note.pkg, t);
        }
    }

    private static void call(Context ctx, String method, String key, int displayId) {
        try {
            Bundle extras = null;
            if (displayId >= 0) {
                extras = new Bundle();
                // The provider sends the notification's intent, so it is the one that has to be
                // told which display to open it on.
                extras.putInt("displayId", displayId);
            }
            ctx.getContentResolver().call(URI, method, key, extras);
        } catch (Throwable t) {
            L.d("notifications: " + method + " failed (" + t + ")");
        }
    }

    private static Drawable appIcon(PackageManager pm, String pkg) {
        if (pkg == null) {
            return TrayIcons.bell(false, Ui.COLOR_TEXT);
        }
        Drawable cached = ICONS.get(pkg);
        if (cached != null) {
            return cached;
        }
        Drawable icon;
        try {
            icon = pm.getApplicationIcon(pkg);
        } catch (Throwable t) {
            icon = TrayIcons.bell(false, Ui.COLOR_TEXT);
        }
        ICONS.put(pkg, icon);
        return icon;
    }

    private static String appName(PackageManager pm, String pkg) {
        if (pkg == null) {
            return "Notification";
        }
        String cached = NAMES.get(pkg);
        if (cached != null) {
            return cached;
        }
        String name;
        try {
            name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            name = pkg;
        }
        NAMES.put(pkg, name);
        return name;
    }
}
