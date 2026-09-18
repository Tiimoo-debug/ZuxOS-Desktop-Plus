package com.zuxos.desktopplus.notify;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.zuxos.desktopplus.core.L;

/**
 * The module's ear on the notification shade.
 *
 * <p>No app may read another's notifications, and no amount of hooking the launcher changes that -
 * the list lives in the system and is handed only to services the user has explicitly allowed. So
 * this is an ordinary {@link NotificationListenerService} in the module's own app, switched on
 * once in Settings, and {@link NotifyProvider} is how the launcher asks it what is there.
 *
 * <p>Nothing is stored. The service holds no copy of anything; every read goes to the live list
 * the system keeps, and when the user revokes access the service is torn down and the launcher's
 * queries start coming back empty.
 */
public final class NotifyService extends NotificationListenerService {

    /** The connected service, or null when the user has not granted access. */
    private static volatile NotifyService sConnected;

    static NotifyService connected() {
        return sConnected;
    }

    @Override
    public void onListenerConnected() {
        sConnected = this;
        L.i("notifications: listener connected");
        // The first query from a cold process arrives before the bind completes and is answered
        // with nothing. This is what tells an already-open panel to ask again.
        changed();
    }

    @Override
    public void onListenerDisconnected() {
        // Only if it is still us: on a rebind the new instance connects before the old one is
        // told it has gone, and clearing blindly would drop a listener that is live.
        if (sConnected == this) {
            sConnected = null;
        }
        L.i("notifications: listener disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        changed();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        changed();
    }

    /**
     * Tells anyone watching that the list moved.
     *
     * <p>The panel reads afresh each time it opens, so this is only for a panel that is already
     * open - which is exactly when a notification arriving and not appearing looks broken.
     */
    private void changed() {
        try {
            getContentResolver().notifyChange(NotifyProvider.URI, null);
        } catch (Throwable t) {
            L.d("notifications: could not announce the change (" + t + ")");
        }
    }
}
