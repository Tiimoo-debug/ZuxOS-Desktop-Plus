package com.zuxos.desktopplus.model;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;

import com.zuxos.desktopplus.core.L;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All launchable apps, their icons, and the launching itself.
 *
 * <p>Everything goes through {@link LauncherApps}, which behaves correctly for work profiles
 * and - importantly for desktop mode - lets us pin the launch to a specific display.
 */
public final class AppsRepo {

    /** One launchable activity. */
    public static final class AppEntry {
        public final ComponentName cn;
        public final UserHandle user;
        public final long serial;
        public final String label;
        private final LauncherActivityInfo mInfo;
        private Drawable mIcon;

        AppEntry(LauncherActivityInfo info, ComponentName cn, UserHandle user, long serial,
                String label) {
            this.mInfo = info;
            this.cn = cn;
            this.user = user;
            this.serial = serial;
            this.label = label;
        }

        public String key() {
            return cn.getPackageName() + "/" + cn.getClassName() + "#" + serial;
        }

        public Item toItem() {
            return Item.app(cn.getPackageName(), cn.getClassName(), serial, label);
        }
    }

    private final Context mCtx;
    private final LauncherApps mLauncherApps;
    private final UserManager mUserManager;
    private final List<AppEntry> mApps = new ArrayList<>();
    private final Map<String, AppEntry> mByKey = new HashMap<>();
    private final Map<String, Drawable> mIconCache = new HashMap<>();
    private final int mDensity;
    private LauncherApps.Callback mCallback;
    private volatile boolean mStale = true;
    private volatile boolean mWatching;

    public AppsRepo(Context ctx) {
        mCtx = ctx;
        mLauncherApps = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        mUserManager = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        mDensity = ctx.getResources().getDisplayMetrics().densityDpi;
    }

    public List<AppEntry> apps() {
        return mApps;
    }

    public AppEntry byKey(String key) {
        return mByKey.get(key);
    }

    /**
     * Watches for app installs and updates.
     *
     * <p>Without this the desktop re-queried every launchable activity each time it resumed,
     * which is a hundred-odd package lookups to discover that nothing had changed.
     */
    public void startWatching() {
        if (mCallback != null || mLauncherApps == null) {
            return;
        }
        mCallback = new LauncherApps.Callback() {
            @Override
            public void onPackageRemoved(String packageName, UserHandle user) {
                mStale = true;
            }

            @Override
            public void onPackageAdded(String packageName, UserHandle user) {
                mStale = true;
            }

            @Override
            public void onPackageChanged(String packageName, UserHandle user) {
                mStale = true;
            }

            @Override
            public void onPackagesAvailable(String[] packageNames, UserHandle user,
                    boolean replacing) {
                mStale = true;
            }

            @Override
            public void onPackagesUnavailable(String[] packageNames, UserHandle user,
                    boolean replacing) {
                mStale = true;
            }
        };
        try {
            mLauncherApps.registerCallback(mCallback);
            mWatching = true;
        } catch (Throwable t) {
            L.d("could not watch for app changes: " + t);
            mCallback = null;
            mWatching = false;
        }
    }

    public void stopWatching() {
        if (mCallback == null) {
            return;
        }
        try {
            mLauncherApps.unregisterCallback(mCallback);
        } catch (Throwable ignored) {
            // Already gone with the process.
        }
        mCallback = null;
        mWatching = false;
    }

    /**
     * Reloads only when an app actually changed. Returns true when the list was rebuilt.
     *
     * <p>Without a working change callback there is no way to know, so it reloads every time -
     * the old behaviour - rather than silently going blind to installs.
     */
    public boolean reloadIfStale() {
        if (mWatching && !mStale && !mApps.isEmpty()) {
            return false;
        }
        reload();
        return true;
    }

    public synchronized void reload() {
        mStale = false;
        mApps.clear();
        mByKey.clear();
        if (mLauncherApps == null || mUserManager == null) {
            L.w("LauncherApps unavailable - cannot list apps");
            return;
        }
        for (UserHandle user : mUserManager.getUserProfiles()) {
            long serial = mUserManager.getSerialNumberForUser(user);
            List<LauncherActivityInfo> list;
            try {
                list = mLauncherApps.getActivityList(null, user);
            } catch (Throwable t) {
                L.e("getActivityList failed for user " + serial, t);
                continue;
            }
            for (LauncherActivityInfo info : list) {
                AppEntry e = new AppEntry(info, info.getComponentName(), user, serial,
                        String.valueOf(info.getLabel()));
                mApps.add(e);
                mByKey.put(e.key(), e);
            }
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(mApps, (a, b) -> collator.compare(a.label, b.label));
        L.d("loaded " + mApps.size() + " launchable activities");
    }

    public UserHandle userFor(long serial) {
        if (mUserManager == null) {
            return android.os.Process.myUserHandle();
        }
        UserHandle u = mUserManager.getUserForSerialNumber(serial);
        return u != null ? u : android.os.Process.myUserHandle();
    }

    public Drawable iconFor(Item item) {
        if (item == null) {
            return null;
        }
        if (item.type == Item.TYPE_FOLDER) {
            return null;
        }
        String key = item.key();
        Drawable cached = mIconCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (item.type == Item.TYPE_APP) {
            // Share the entry's icon rather than decoding a second copy of the same bitmap.
            AppEntry entry = mByKey.get(key);
            if (entry != null) {
                Drawable shared = iconFor(entry);
                if (shared != null) {
                    mIconCache.put(key, shared);
                    return shared;
                }
            }
        }
        Drawable d = loadIcon(item);
        if (d != null) {
            mIconCache.put(key, d);
        }
        return d;
    }

    public Drawable iconFor(AppEntry entry) {
        if (entry.mIcon != null) {
            return entry.mIcon;
        }
        Drawable d = null;
        if (entry.mInfo != null) {
            try {
                d = entry.mInfo.getBadgedIcon(mDensity);
            } catch (Throwable t) {
                L.d("badged icon failed for " + entry.cn + ": " + t);
            }
        }
        if (d == null) {
            d = loadActivityIcon(entry.cn, entry.user);
        }
        entry.mIcon = d;
        return d;
    }

    public void clearIconCache() {
        mIconCache.clear();
        for (AppEntry e : mApps) {
            e.mIcon = null;
        }
    }

    private Drawable loadIcon(Item item) {
        try {
            if (item.type == Item.TYPE_APP && item.pkg != null && item.cls != null) {
                return loadActivityIcon(new ComponentName(item.pkg, item.cls), userFor(item.userSerial));
            }
            if (item.type == Item.TYPE_SHORTCUT) {
                if (item.shortcutId != null) {
                    ShortcutInfo si = findShortcut(item);
                    if (si != null) {
                        Drawable d = mLauncherApps.getShortcutBadgedIconDrawable(si, mDensity);
                        if (d != null) {
                            return d;
                        }
                    }
                    // Fall back to the owning app's icon so the item is still recognisable.
                    return mCtx.getPackageManager().getApplicationIcon(item.pkg);
                }
                if (item.intentUri != null) {
                    Intent intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                    ComponentName cn = intent.getComponent();
                    if (cn != null) {
                        return mCtx.getPackageManager().getActivityIcon(cn);
                    }
                }
            }
        } catch (Throwable t) {
            L.d("icon lookup failed for " + item.key() + ": " + t);
        }
        return null;
    }

    private Drawable loadActivityIcon(ComponentName cn, UserHandle user) {
        try {
            List<LauncherActivityInfo> list = mLauncherApps.getActivityList(cn.getPackageName(), user);
            for (LauncherActivityInfo info : list) {
                if (info.getComponentName().equals(cn)) {
                    return info.getBadgedIcon(mDensity);
                }
            }
            if (!list.isEmpty()) {
                return list.get(0).getBadgedIcon(mDensity);
            }
        } catch (Throwable t) {
            L.d("activity icon lookup failed for " + cn + ": " + t);
        }
        return null;
    }

    private ShortcutInfo findShortcut(Item item) {
        try {
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(item.pkg);
            q.setShortcutIds(Collections.singletonList(item.shortcutId));
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> res = mLauncherApps.getShortcuts(q, userFor(item.userSerial));
            if (res != null && !res.isEmpty()) {
                return res.get(0);
            }
        } catch (Throwable t) {
            L.d("shortcut lookup failed: " + t);
        }
        return null;
    }

    /** Deep shortcuts published by {@code pkg}, or an empty list when unavailable. */
    public List<ShortcutInfo> shortcutsFor(String pkg, UserHandle user) {
        try {
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(pkg);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> res = mLauncherApps.getShortcuts(q, user);
            return res != null ? res : Collections.emptyList();
        } catch (Throwable t) {
            // Requires being the default launcher; ZUI home usually is, but never assume.
            L.d("getShortcuts denied for " + pkg + ": " + t);
            return Collections.emptyList();
        }
    }

    public boolean launch(Item item, View source, int displayId) {
        Bundle opts = launchOptions(displayId);
        Rect bounds = sourceBounds(source);
        try {
            if (item.type == Item.TYPE_APP) {
                mLauncherApps.startMainActivity(new ComponentName(item.pkg, item.cls),
                        userFor(item.userSerial), bounds, opts);
                return true;
            }
            if (item.type == Item.TYPE_SHORTCUT) {
                if (item.shortcutId != null) {
                    mLauncherApps.startShortcut(item.pkg, item.shortcutId, bounds, opts,
                            userFor(item.userSerial));
                    return true;
                }
                if (item.intentUri != null) {
                    Intent intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setSourceBounds(bounds);
                    mCtx.startActivity(intent, opts);
                    return true;
                }
            }
        } catch (Throwable t) {
            L.e("launch failed for " + item.key(), t);
        }
        return false;
    }

    public boolean launchApp(AppEntry entry, View source, int displayId) {
        try {
            mLauncherApps.startMainActivity(entry.cn, entry.user, sourceBounds(source),
                    launchOptions(displayId));
            return true;
        } catch (Throwable t) {
            L.e("launch failed for " + entry.key(), t);
            return false;
        }
    }

    public void showAppInfo(Item item, int displayId) {
        try {
            mLauncherApps.startAppDetailsActivity(new ComponentName(item.pkg, item.cls),
                    userFor(item.userSerial), null, launchOptions(displayId));
        } catch (Throwable t) {
            L.e("app info failed", t);
        }
    }

    /**
     * Launch options pinned to the display the desktop is showing on - without this, apps
     * started from the external desktop can end up on the tablet screen.
     */
    public Bundle launchOptions(int displayId) {
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && displayId >= 0) {
                opts.setLaunchDisplayId(displayId);
            }
            return opts.toBundle();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Rect sourceBounds(View v) {
        if (v == null) {
            return null;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
    }
}
