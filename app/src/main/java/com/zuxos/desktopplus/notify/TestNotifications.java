package com.zuxos.desktopplus.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.icons.AndroidRobot;
import com.zuxos.desktopplus.ui.MainActivity;

/**
 * Test notifications, sent from the settings, to see the monitor's pop-ups for real rather than
 * wait for an app to send one: three in a row, a moment apart, so they stack.
 *
 * <ol>
 *   <li>A message with a picture, a button and a reply box. A reply updates it quietly, the way
 *       a chat app does, and that update must not pop up again.</li>
 *   <li>A long text, to see it cut at three lines, with two buttons.</li>
 *   <li>A plain one with no buttons, to swipe away or let go on its own.</li>
 * </ol>
 *
 * <p>Tapping any of them opens these settings. The listener lets them pop up although they are
 * the module's own, by {@link #EXTRA_TEST}.
 */
public final class TestNotifications extends BroadcastReceiver {

    /** Marks a test notification, which the listener lets pop up. */
    static final String EXTRA_TEST = "com.zuxos.desktopplus.test";

    private static final String CHANNEL = "test";
    private static final String ACTION_READ = "com.zuxos.desktopplus.test.READ";
    private static final String ACTION_REPLY = "com.zuxos.desktopplus.test.REPLY";
    private static final String KEY_REPLY = "reply";
    private static final String EXTRA_ID = "id";
    private static final int MESSAGE = 9001;
    private static final int LONG = 9002;
    private static final int PLAIN = 9003;
    private static final long GAP_MS = 1500L;
    private static final int PICTURE_PX = 192;

    /** Sends the three. False when notifications are not allowed for this app. */
    public static boolean send(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) {
            return false;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Test notifications",
                NotificationManager.IMPORTANCE_HIGH));
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> nm.notify(MESSAGE, message(app, null)));
        main.postDelayed(() -> nm.notify(LONG, longText(app)), GAP_MS);
        main.postDelayed(() -> nm.notify(PLAIN, plain(app)), 2 * GAP_MS);
        L.i("test notifications: sending three");
        return true;
    }

    private static Notification message(Context ctx, CharSequence replied) {
        Notification.Builder b = base(ctx)
                .setContentTitle("Desktop Plus")
                .setContentText(replied == null
                        ? "A test message. Reply here, or tap it to open the settings."
                        : "You replied: " + replied)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setLargeIcon(Icon.createWithBitmap(picture()));
        if (replied != null) {
            // An update: heard once, when the message came, not again for the reply.
            return b.setOnlyAlertOnce(true).build();
        }
        RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel("Reply to the test")
                .build();
        Notification.Action reply = new Notification.Action.Builder(null, "Reply",
                PendingIntent.getBroadcast(ctx, MESSAGE, action(ctx, ACTION_REPLY, MESSAGE),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE))
                .addRemoteInput(input).build();
        return b.addAction(read(ctx, MESSAGE, "Mark as read")).addAction(reply).build();
    }

    private static Notification longText(Context ctx) {
        String text = "A longer test, to see how much of a notification fits on the card: it "
                + "shows three lines and cuts the rest, the way the tablet's own pop-ups do. "
                + "These words are here only to run past that, so the cut shows where it falls.";
        return base(ctx)
                .setContentTitle("A long one, with two buttons")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .addAction(new Notification.Action.Builder(null, "Open settings",
                        open(ctx)).build())
                .addAction(read(ctx, LONG, "Dismiss"))
                .build();
    }

    private static Notification plain(Context ctx) {
        return base(ctx)
                .setContentTitle("A plain one")
                .setContentText("No buttons: swipe it to the right, or leave it - it goes on "
                        + "its own.")
                .build();
    }

    private static Notification.Builder base(Context ctx) {
        Bundle extras = new Bundle();
        extras.putBoolean(EXTRA_TEST, true);
        return new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(Icon.createWithResource(ctx, ctx.getApplicationInfo().icon))
                .setContentIntent(open(ctx))
                .setAutoCancel(true)
                .addExtras(extras);
    }

    private static PendingIntent open(Context ctx) {
        return PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Action read(Context ctx, int id, String title) {
        return new Notification.Action.Builder(null, title,
                PendingIntent.getBroadcast(ctx, id + 100, action(ctx, ACTION_READ, id),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    private static Intent action(Context ctx, String action, int id) {
        return new Intent(action).setClass(ctx, TestNotifications.class).putExtra(EXTRA_ID, id);
    }

    /** The sender's picture: the Android robot, on its own. */
    private static Bitmap picture() {
        Bitmap bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888);
        AndroidRobot robot = new AndroidRobot();
        int inset = PICTURE_PX / 8;
        robot.setBounds(inset, inset, PICTURE_PX - inset, PICTURE_PX - inset);
        robot.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** A button pressed, or a reply sent - from the monitor's pop-up or the tablet's shade. */
    @Override
    public void onReceive(Context ctx, Intent intent) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || intent == null) {
            return;
        }
        int id = intent.getIntExtra(EXTRA_ID, 0);
        if (ACTION_READ.equals(intent.getAction())) {
            L.i("test notifications: button pressed on " + id);
            nm.cancel(id);
            return;
        }
        if (ACTION_REPLY.equals(intent.getAction())) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = results != null ? results.getCharSequence(KEY_REPLY) : null;
            L.i("test notifications: reply received (" + (text == null ? "empty" : text.length()
                    + " characters") + ")");
            nm.notify(MESSAGE, message(ctx, text == null ? "" : text));
        }
    }
}
