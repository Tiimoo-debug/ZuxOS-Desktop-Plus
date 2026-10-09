package com.zuxos.desktopplus.notify;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * queries start coming back empty. For the monitor's pop-ups it remembers which of the last few
 * arrivals would have popped up on the tablet - their keys and times, never what they say.
 */
public final class NotifyService extends NotificationListenerService {

    /** The connected service, or null when the user has not granted access. */
    private static volatile NotifyService sConnected;

    /** Pop-ups not yet asked for are dropped past this many: only the latest few would show. */
    private static final int POPS_KEPT = 6;
    /** The arrivals that pop up, oldest first: key to post time. */
    private static final Map<String, Long> POPS = new LinkedHashMap<>();

    /** Every key up now, to tell a new notification from an update to one already there. */
    private final Set<String> mSeen = new HashSet<>();
    private final Ranking mRanking = new Ranking();

    static NotifyService connected() {
        return sConnected;
    }

    @Override
    public void onListenerConnected() {
        sConnected = this;
        L.i("notifications: listener connected");
        mSeen.clear();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    mSeen.add(sbn.getKey());
                }
            }
        } catch (Throwable t) {
            L.d("notifications: could not read the list on connect (" + t + ")");
        }
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
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        boolean update = !mSeen.add(sbn.getKey());
        boolean pops = false;
        try {
            pops = pops(sbn, rankingMap, update);
        } catch (Throwable t) {
            L.d("notifications: could not judge " + sbn.getPackageName() + " (" + t + ")");
        }
        changed();
        if (pops) {
            synchronized (POPS) {
                POPS.remove(sbn.getKey());
                POPS.put(sbn.getKey(), sbn.getPostTime());
                Iterator<String> oldest = POPS.keySet().iterator();
                while (POPS.size() > POPS_KEPT && oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            announce(NotifyProvider.POSTED);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        mSeen.remove(sbn.getKey());
        synchronized (POPS) {
            POPS.remove(sbn.getKey());
        }
        changed();
    }

    /**
     * Whether it would pop up on the tablet, which is when it should on the monitor: important
     * enough to interrupt, let through by Do not disturb, new - or an update the app wants heard
     * again - and not something that sits in the shade (ongoing, a group's summary, media).
     */
    private boolean pops(StatusBarNotification sbn, RankingMap rankingMap, boolean update) {
        Notification n = sbn.getNotification();
        if (n == null || (Const.MODULE_PKG.equals(sbn.getPackageName())
                && !n.extras.getBoolean(TestNotifications.EXTRA_TEST))) {
            // The module's own are not news - except the tests sent from its settings.
            return false;
        }
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY
                | Notification.FLAG_FOREGROUND_SERVICE)) != 0) {
            return false;
        }
        if (update && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
            return false;
        }
        Bundle extras = n.extras;
        if (Notification.CATEGORY_TRANSPORT.equals(n.category)
                || (extras != null && extras.containsKey("android.mediaSession"))) {
            return false;
        }
        if (NotifyProvider.title(n).isEmpty() && NotifyProvider.text(n).isEmpty()) {
            return false;
        }
        if (rankingMap == null || !rankingMap.getRanking(sbn.getKey(), mRanking)) {
            return false;
        }
        return mRanking.getImportance() >= NotificationManager.IMPORTANCE_HIGH
                && mRanking.matchesInterruptionFilter() && !mRanking.isSuspended();
    }

    /** The pop-ups that arrived after {@code since}, oldest first. */
    static List<String> popsSince(long since) {
        List<String> keys = new ArrayList<>();
        synchronized (POPS) {
            for (Map.Entry<String, Long> pop : POPS.entrySet()) {
                if (pop.getValue() > since) {
                    keys.add(pop.getKey());
                }
            }
        }
        return keys;
    }

    /**
     * Tells anyone watching that the list moved.
     *
     * <p>The panel reads afresh each time it opens, so this is only for a panel that is already
     * open - which is exactly when a notification arriving and not appearing looks broken.
     */
    private void changed() {
        announce(NotifyProvider.URI);
    }

    private void announce(android.net.Uri uri) {
        try {
            getContentResolver().notifyChange(uri, null);
        } catch (Throwable t) {
            L.d("notifications: could not announce the change (" + t + ")");
        }
    }
}
