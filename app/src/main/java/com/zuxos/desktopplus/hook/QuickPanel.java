package com.zuxos.desktopplus.hook;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.TrayIcons;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.GlassSurface;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick settings, in the shape Samsung DeX uses: the connection at the top, a grid of round
 * toggles, then the sliders.
 *
 * <p>A window of its own, because the taskbar's window is a strip that clips everything inside
 * it. The window covers the display so that a press anywhere outside the panel closes it - the
 * way a desktop popup behaves - and asks for no blur behind, since on this firmware that flag
 * blurs the whole screen whatever the window's size.
 */
public final class QuickPanel {

    /** Tiles per row: all six on one line, the way DeX keeps its toggles to a single row. */
    private static final int COLUMNS = 6;

    /** Panel width. Wide enough that six captions fit without stacking. */
    private static final int WIDTH_DP = 380;

    /** Clear of the screen's right-hand edge, the same distance the tray keeps. */
    private static final int EDGE_MARGIN_DP = 8;

    /** Bursts of state changes are collapsed into one rebuild this far apart. */
    private static final long REBUILD_DELAY_MS = 60L;

    /** How long after an outside touch closed the panel a tap is taken as part of that press. */
    private static final long REOPEN_GUARD_MS = 400L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static View sCurrent;
    private static WindowManager sWm;

    /** Rebuilds the open panel's contents; null when no panel is open. */
    private static Runnable sRebuild;
    private static final Runnable REBUILD_TASK = new Runnable() {
        @Override
        public void run() {
            Runnable rebuild = sRebuild;
            if (rebuild == null) {
                return;
            }
            if (sInteracting) {
                // Rebuilding replaces every row, which would take the slider out from under the
                // finger that is dragging it. Whatever changed can wait until it is let go.
                MAIN.postDelayed(this, REBUILD_DELAY_MS * 4);
                return;
            }
            rebuild.run();
        }
    };

    /** The settle passes, which must survive a {@code removeCallbacks} on {@link #REBUILD_TASK}. */
    private static final Runnable SETTLE_TASK = REBUILD_TASK::run;

    /** True while a finger is on a slider; see {@link #REBUILD_TASK}. */
    private static volatile boolean sInteracting;

    /**
     * When to look again after an action whose effect lands later.
     *
     * <p>Some of this cannot be listened for at all. {@code BluetoothAdapter.ACTION_STATE_CHANGED}
     * is only delivered to receivers holding {@code BLUETOOTH_CONNECT}, which this launcher does
     * not have - the same permission that stopped it toggling Bluetooth in the first place. So
     * after a root command the panel simply looks again, a few times, over the seconds it takes a
     * radio to come up. Reading the state needs no permission; only hearing about it does.
     */
    private static final long[] SETTLE_MS = {250L, 750L, 1500L, 3000L};

    /** When an outside touch last closed the panel. See {@link #REOPEN_GUARD_MS}. */
    private static long sClosedByTouchAt;

    /** The tray row the open panel belongs to, for telling its own button's press apart. */
    private static View sAnchor;

    /** Watches the switches that answer slowly. See {@link #startReceiver}. */
    private static BroadcastReceiver sWatcher;
    private static Context sWatcherCtx;
    private static final List<Watch> WATCHED_SESSIONS = new ArrayList<>();

    private QuickPanel() {
    }

    public static void toggle(Context ctx, View anchor, int displayId) {
        if (sCurrent != null) {
            dismiss();
            return;
        }
        if (android.os.SystemClock.uptimeMillis() - sClosedByTouchAt < REOPEN_GUARD_MS) {
            // The press that closed the panel went on to reach the tray button underneath it -
            // which is what NOT_TOUCH_MODAL is for, and which would otherwise reopen the panel
            // the same tap just closed. The outside touch arrives on DOWN, the click on UP.
            return;
        }
        show(ctx, anchor, displayId);
    }

    /** Identifies the open panel, so a slow callback cannot close a newer one. */
    static Object token() {
        return sCurrent;
    }

    /**
     * Closes the panel because the taskbar was pressed.
     *
     * <p>The taskbar's window sits above this one, so a press on the bar is delivered there and
     * never arrives here as a touch outside - which is why the panel stayed open when you tapped
     * the bar. The taskbar's own touch watcher passes it on instead.
     */
    static void onTaskbarPressed(float rawX, float rawY) {
        if (sCurrent == null) {
            return;
        }
        // If that press was on the tray, the button's click follows on the way up and must not
        // reopen what this is about to close.
        if (hits(sAnchor, rawX, rawY)) {
            sClosedByTouchAt = android.os.SystemClock.uptimeMillis();
        }
        dismiss();
    }

    /** Whether the panel {@code token} came from is still the open one. */
    static boolean isStill(Object token) {
        return token != null && sCurrent == token;
    }

    /** Closes the panel only if it is still the one {@code token} came from. */
    static void dismissIf(Object token) {
        if (token != null && sCurrent == token) {
            dismiss();
        }
    }

    public static void dismiss() {
        View current = sCurrent;
        WindowManager wm = sWm;
        if (current == null || wm == null) {
            stopWatching();
            return;
        }
        try {
            wm.removeViewImmediate(current);
        } catch (IllegalArgumentException notThere) {
            // Already gone - the window manager's own answer.
            L.d("quick panel already gone: " + notThere);
        } catch (Throwable t) {
            // Something else. Ask again the asynchronous way, then let go regardless: holding
            // the reference to retry later sounds careful, but nothing ever retries, so it only
            // wedges the panel shut for the life of the process.
            L.e("quick panel would not close", t);
            try {
                wm.removeView(current);
            } catch (Throwable ignored) {
                L.w("quick panel: a window may have been left behind");
            }
        }
        sCurrent = null;
        sWm = null;
        sAnchor = null;
        stopWatching();
    }

    private static void show(Context taskbarCtx, View anchor, int displayId) {
        if (!canShow(taskbarCtx)) {
            toast(taskbarCtx, "Allow \"display over other apps\" for the launcher to open the tray");
            return;
        }
        // A panel from a previous attempt would otherwise sit there with a second on top of it.
        dismiss();
        // The taskbar's own context is bound to the taskbar's window type, and the window manager
        // refuses a window of any other type from it.
        final Context ctx = Overlays.windowContext(taskbarCtx);
        try {
            final int inset = TaskbarTray.barInset(anchor);
            FrameLayout root = new FrameLayout(ctx);
            GlassSurface glass = new GlassSurface(ctx, Ui.dp(ctx, 22), 0x14FFFFFF);

            ScrollView scroller = new ScrollView(ctx);
            scroller.setVerticalScrollBarEnabled(false);
            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(ctx, 16);
            body.setPadding(pad, pad, pad, pad);
            scroller.addView(body, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            glass.addView(scroller, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));

            fill(ctx, body, displayId);

            // The root is the whole display, so the panel's own margins place it: clear of the
            // right edge, and exactly the bar's height up from the bottom of the screen.
            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                    panelWidth(ctx), FrameLayout.LayoutParams.WRAP_CONTENT);
            glp.gravity = Gravity.BOTTOM | Gravity.END;
            glp.rightMargin = Ui.dp(ctx, EDGE_MARGIN_DP);
            glp.bottomMargin = inset;
            root.addView(glass, glp);

            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP
                        && (keyCode == KeyEvent.KEYCODE_BACK
                        || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                    dismiss();
                    return true;
                }
                return false;
            });
            final View tray = anchor;
            root.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_OUTSIDE) {
                    // A press on the taskbar, which floats above this window. If it was on the
                    // tray, the button's own click follows on the UP and must not reopen us.
                    if (hits(tray, event.getRawX(), event.getRawY())) {
                        sClosedByTouchAt = android.os.SystemClock.uptimeMillis();
                    }
                    dismiss();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    float x = event.getX();
                    float y = event.getY();
                    boolean inside = x >= glass.getLeft() && x <= glass.getRight()
                            && y >= glass.getTop() && y <= glass.getBottom();
                    if (!inside) {
                        dismiss();
                        return true;
                    }
                }
                return false;
            });

            WindowManager wm = Overlays.windowManager(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus tray");
            edgeToEdge(lp);
            // No blur behind. On this firmware the flag blurs the entire display whatever the
            // window's size, and there is no public way to blur only under a plain window.
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
            sAnchor = anchor;
            // Set before the receiver goes on, and after fill() has registered its sessions -
            // an earlier version set it first and let the receiver's own tear-down pass
            // clear it again, which left the panel with no live refresh at all.
            sRebuild = () -> fill(ctx, body, displayId);
            startReceiver(ctx);
            root.requestFocus();
        } catch (Throwable t) {
            // fill() has already registered whatever it found; without this they would follow
            // sessions for a panel that never opened.
            stopWatching();
            L.e("could not open the tray panel", t);
        }
    }

    /**
     * Lets the window reach the screen's edges.
     *
     * <p>By default an overlay is laid out inside the system bars - and the taskbar is one, so a
     * window that stops at its top edge and then adds the bar's height again floats a whole bar
     * above it. That was the gap.
     */
    static void edgeToEdge(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.setFitInsetsTypes(0);
        }
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    }

    /** Whether a screen position falls on {@code view}; false when it cannot be asked. */
    private static boolean hits(View view, float rawX, float rawY) {
        if (view == null || view.getWindowToken() == null || view.getWidth() <= 0) {
            // Nothing to compare against; arming the guard is the safer half of the trade, since
            // the alternative is the tray button reopening the panel it just closed.
            return true;
        }
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return rawX >= at[0] && rawX <= at[0] + view.getWidth()
                && rawY >= at[1] && rawY <= at[1] + view.getHeight();
    }

    /** The panel's width, kept inside a display too narrow to hold it. */
    private static int panelWidth(Context ctx) {
        int wanted = Ui.dp(ctx, WIDTH_DP);
        try {
            int display = ctx.getResources().getDisplayMetrics().widthPixels;
            if (display > 0) {
                return Math.min(wanted, display - Ui.dp(ctx, 2 * EDGE_MARGIN_DP));
            }
        } catch (Throwable ignored) {
            // Unmeasurable; the intended width is still the best answer.
        }
        return wanted;
    }

    // --- watching the things that answer late -----------------------------

    /**
     * Listens for the state changes the panel cannot see happen.
     *
     * <p>This is what the panel was missing. {@code svc bluetooth enable} returns as soon as the
     * command is accepted, not when the adapter is on; a media app reports its new playback state
     * a moment after the transport call. Rebuilding straight afterwards - which is what it did -
     * reads the old value and paints it, and the toggle only turns blue when the panel is closed
     * and opened again. So rather than guess at a delay, the panel listens, and repaints when the
     * thing it asked for has actually happened.
     */
    private static void startReceiver(Context ctx) {
        // Only the receiver. The sessions are registered by fill(), which has already run by the
        // time this is called, and tearing anything down here would take them with it.
        unregisterReceiver();
        Context app = AppCtx.get();
        final Context target = app != null ? app : ctx;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    scheduleRebuild();
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // All three are protected system broadcasts, so they still arrive; the flag is
                // only there because Android 14 insists one is named.
                target.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                target.registerReceiver(receiver, filter);
            }
            sWatcher = receiver;
            sWatcherCtx = target;
        } catch (Throwable t) {
            L.d("quick panel: could not listen for state changes (" + t + ")");
        }
    }

    /** Everything the open panel was listening to, undone. Called once, on dismissal. */
    private static void stopWatching() {
        MAIN.removeCallbacks(REBUILD_TASK);
        MAIN.removeCallbacks(SETTLE_TASK);
        sRebuild = null;
        // The panel is gone, so no finger is on any of its sliders. Left set, this would defer
        // every rebuild of the next panel by four turns of the handler, for ever.
        sInteracting = false;
        unregisterReceiver();
        unwatchSessions();
    }

    private static void unregisterReceiver() {
        BroadcastReceiver receiver = sWatcher;
        Context ctx = sWatcherCtx;
        sWatcher = null;
        sWatcherCtx = null;
        if (receiver != null && ctx != null) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
                // Never registered, or already gone with its context.
            }
        }
    }

    /**
     * Looks again over the next few seconds, for state that no broadcast will announce.
     *
     * <p>Posted as its own runnable rather than as four copies of {@link #REBUILD_TASK}: a
     * broadcast arriving mid-settle calls {@code removeCallbacks} on that one, which would take
     * the remaining passes with it and leave the panel showing a radio that had not come up yet.
     */
    static void settle() {
        if (sRebuild == null) {
            return;
        }
        MAIN.removeCallbacks(SETTLE_TASK);
        for (long delay : SETTLE_MS) {
            MAIN.postDelayed(SETTLE_TASK, delay);
        }
    }

    /** Told by the sliders, so a rebuild cannot happen under a moving finger. */
    static void setInteracting(boolean interacting) {
        sInteracting = interacting;
    }

    /** Coalesces a burst of changes into one rebuild, and keeps it off the callback's stack. */
    private static void scheduleRebuild() {
        MAIN.removeCallbacks(REBUILD_TASK);
        MAIN.postDelayed(REBUILD_TASK, REBUILD_DELAY_MS);
    }

    /**
     * Follows each playing app, so its card shows what it is doing rather than what it was doing.
     *
     * <p>Playback states arrive far more often than they change - some apps repost one every
     * second to move the position - so a rebuild only happens when the state itself moves.
     */
    private static void watchSessions(List<MediaController> controllers) {
        for (MediaController controller : controllers) {
            try {
                PlaybackState now = controller.getPlaybackState();
                final int[] last = {now == null ? -1 : now.getState()};
                final String[] lastTrack = {describe(controller.getMetadata())};
                MediaController.Callback callback = new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        int value = state == null ? -1 : state.getState();
                        if (value == last[0]) {
                            return;
                        }
                        last[0] = value;
                        scheduleRebuild();
                    }

                    @Override
                    public void onMetadataChanged(MediaMetadata metadata) {
                        // Metadata is reposted as often as playback state is, and for the same
                        // reason. Only a different track is worth redrawing the card - and the
                        // card's album art is decoded on this thread.
                        String track = describe(metadata);
                        if (track.equals(lastTrack[0])) {
                            return;
                        }
                        lastTrack[0] = track;
                        scheduleRebuild();
                    }

                    @Override
                    public void onSessionDestroyed() {
                        scheduleRebuild();
                    }
                };
                controller.registerCallback(callback);
                WATCHED_SESSIONS.add(new Watch(controller, callback));
            } catch (Throwable t) {
                L.d("quick panel: could not follow " + controller.getPackageName()
                        + " (" + t + ")");
            }
        }
    }

    /** What the card would show, as one string, so an unchanged track can be recognised. */
    private static String describe(MediaMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        try {
            return metadata.getString(MediaMetadata.METADATA_KEY_TITLE) + "\u0000"
                    + metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) + "\u0000"
                    + metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void unwatchSessions() {
        for (Watch watch : WATCHED_SESSIONS) {
            try {
                watch.controller.unregisterCallback(watch.callback);
            } catch (Throwable ignored) {
                // The session is gone, which is the same outcome.
            }
        }
        WATCHED_SESSIONS.clear();
    }

    private static final class Watch {
        final MediaController controller;
        final MediaController.Callback callback;

        Watch(MediaController controller, MediaController.Callback callback) {
            this.controller = controller;
            this.callback = callback;
        }
    }

    /** Rebuilds the contents in place, so a toggle repaints without the panel blinking. */
    private static void fill(Context ctx, LinearLayout body, int displayId) {
        body.removeAllViews();
        // The cards these belonged to are gone; the new ones register their own.
        unwatchSessions();
        SysState state = SysState.get(ctx);
        Runnable rebuild = () -> fill(ctx, body, displayId);

        body.addView(networkHeader(ctx, state));
        body.addView(tiles(ctx, state, displayId, rebuild));
        body.addView(divider(ctx));
        watchSessions(SoundRows.addTo(ctx, body, displayId, rebuild));
        body.addView(divider(ctx));
        body.addView(batteryRow(ctx, state));
        body.addView(action(ctx, "Network & internet",
                () -> QuickTiles.open(ctx, Settings.ACTION_WIFI_SETTINGS, displayId)));
        body.addView(action(ctx, "All settings",
                () -> QuickTiles.open(ctx, Settings.ACTION_SETTINGS, displayId)));
    }

    private static View tiles(Context ctx, SysState state, int displayId, Runnable onActed) {
        List<QuickTiles.Tile> tiles = new ArrayList<>();
        tiles.add(QuickTiles.wifi(ctx, state, displayId));
        tiles.add(QuickTiles.bluetooth(ctx, displayId, onActed));
        tiles.add(QuickTiles.torch(ctx));
        tiles.add(QuickTiles.rotation(ctx, displayId, onActed));
        tiles.add(QuickTiles.flightMode(ctx, displayId, onActed));
        tiles.add(QuickTiles.eyeProtection(ctx, displayId, onActed));

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(COLUMNS);
        for (QuickTiles.Tile tile : tiles) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            // Equal columns: an undefined spec with a weight is how GridLayout is told to share
            // the width rather than size each cell to its own caption.
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.width = 0;
            grid.addView(QuickTiles.view(ctx, tile, onActed), lp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Ui.dp(ctx, 12);
        grid.setLayoutParams(glp);
        return grid;
    }

    // --- rows ------------------------------------------------------------

    private static View networkHeader(Context ctx, SysState state) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(headerIcon(state));
        int size = Ui.dp(ctx, 26);
        line.addView(icon, new LinearLayout.LayoutParams(size, size));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(ctx, 12);
        line.addView(text, tlp);

        TextView title = new TextView(ctx);
        title.setText(state.netLabel());
        title.setTextColor(Ui.COLOR_TEXT);
        title.setTextSize(16);
        text.addView(title);

        String detail = state.netDetail();
        if (!detail.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(detail);
            sub.setTextColor(Ui.COLOR_TEXT_DIM);
            sub.setTextSize(12);
            text.addView(sub);
        }
        return line;
    }

    private static Drawable headerIcon(SysState state) {
        switch (state.netType()) {
            case SysState.NET_ETHERNET:
                return TrayIcons.ethernet(Ui.COLOR_TEXT);
            case SysState.NET_WIFI:
                return TrayIcons.wifi(state.netLevel(), Ui.COLOR_TEXT);
            case SysState.NET_CELLULAR:
                return TrayIcons.cellular(state.netLevel(), Ui.COLOR_TEXT);
            default:
                return TrayIcons.wifiOff(Ui.COLOR_TEXT);
        }
    }

    /**
     * The battery line.
     *
     * <p>Temperatures are deliberately not here. They belong in the tray, where they are glanced
     * at; a panel you opened to change something should not be a sensor readout.
     */
    private static View batteryRow(Context ctx, SysState state) {
        int percent = state.batteryPercent();
        String value = percent < 0 ? "Unknown"
                : percent + "%" + (state.charging() ? " - charging" : "");
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(ctx, 8);
        line.setPadding(pad, pad, pad, pad);

        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(TrayIcons.battery(percent < 0 ? 0 : percent, state.charging(),
                Ui.COLOR_TEXT));
        int size = Ui.dp(ctx, 20);
        line.addView(iv, new LinearLayout.LayoutParams(size, size));

        TextView label = new TextView(ctx);
        label.setText("Battery");
        label.setTextColor(Ui.COLOR_TEXT);
        label.setTextSize(14);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = Ui.dp(ctx, 12);
        line.addView(label, llp);

        TextView valueView = new TextView(ctx);
        valueView.setText(value);
        valueView.setTextColor(Ui.COLOR_TEXT_DIM);
        valueView.setTextSize(13);
        line.addView(valueView);
        return line;
    }

    private static View action(Context ctx, String title, Runnable action) {
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(Ui.COLOR_TEXT);
        tv.setTextSize(14);
        int pad = Ui.dp(ctx, 10);
        tv.setPadding(pad, pad, pad, pad);
        tv.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 12)));
        tv.setOnClickListener(v -> {
            dismiss();
            try {
                action.run();
            } catch (Throwable t) {
                L.e("tray action failed: " + title, t);
            }
        });
        return tv;
    }

    /** A small caption above a group of rows. */
    static View sectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Ui.COLOR_TEXT_DIM);
        tv.setTextSize(11);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 10);
        lp.leftMargin = Ui.dp(ctx, 6);
        lp.bottomMargin = Ui.dp(ctx, 2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 0.5f)));
        lp.topMargin = Ui.dp(ctx, 10);
        lp.bottomMargin = Ui.dp(ctx, 6);
        v.setLayoutParams(lp);
        return v;
    }

    // --- plumbing --------------------------------------------------------

    private static boolean canShow(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            L.w(msg);
        }
    }
}
