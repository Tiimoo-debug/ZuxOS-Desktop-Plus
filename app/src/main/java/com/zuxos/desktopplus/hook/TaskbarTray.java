package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Thermals;
import com.zuxos.desktopplus.core.TrayIcons;
import com.zuxos.desktopplus.core.Ui;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A status area inside the stock taskbar: network, battery and a clock.
 *
 * <p>The taskbar has none of this - it is app icons and the three nav buttons, and nothing else -
 * so on an external display there is no way to see whether the ethernet is up without leaving
 * what you are doing. The tray goes into the nav-button row rather than floating over it: that
 * row is an ordinary {@code LinearLayout}, so the taskbar lays our views out itself, sizes itself
 * around them, and keeps working if a future firmware moves the row somewhere else.
 */
public final class TaskbarTray {

    private static final String TAG_TRAY = "zux-desktop-plus-tray";

    /** How many layout passes to wait for before judging the placement. */
    private static final int VERIFY_ATTEMPTS = 6;

    /** The tray in each taskbar, so the common "already there" case costs one lookup. */
    private static final Map<View, View> TRAYS = new WeakHashMap<>();
    /** Taskbars the tray took itself back out of; never tried again while they live. */
    private static final Set<View> REFUSED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean sInstalled;

    private TaskbarTray() {
    }

    /**
     * Watches for the taskbar's window.
     *
     * <p>Every window in the process goes through {@code WindowManagerImpl.addView}, which makes
     * it the one place that sees the taskbar appear - and it is called once per window rather
     * than once per frame, so hooking it costs nothing.
     */
    public static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> impl = Reflect.findClass("android.view.WindowManagerImpl", loader);
            if (impl == null) {
                L.w("tray: WindowManagerImpl not found, the tray will only attach on resume");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(impl, "addView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof View) {
                        onWindowAdded((View) param.args[0]);
                    }
                }
            }).size();
            L.i("tray: watching for the taskbar window x" + hooked);
        } catch (Throwable t) {
            L.e("tray: could not watch for windows", t);
        }
    }

    private static void onWindowAdded(View root) {
        if (!isTaskbar(root)) {
            return;
        }
        // The window's children are not laid out yet; the tray goes in once they are.
        root.post(() -> attach(root));
    }

    /**
     * Re-checks every taskbar; called when the desktop resumes, so a missed window self-heals.
     *
     * <p>This also runs the setting in reverse: turning the tray off takes it out of a taskbar
     * that already has one, rather than leaving it there until the launcher restarts.
     */
    public static void refresh() {
        boolean wanted = Cfg.taskbarTray();
        for (View root : Windows.roots()) {
            if (!isTaskbar(root)) {
                continue;
            }
            if (wanted) {
                attach(root);
            } else {
                detach(root);
            }
        }
    }

    private static void detach(View root) {
        try {
            View tray = TRAYS.remove(root);
            if (tray == null && root instanceof ViewGroup) {
                tray = root.findViewWithTag(TAG_TRAY);
            }
            if (tray != null && tray.getParent() instanceof ViewGroup) {
                ((ViewGroup) tray.getParent()).removeView(tray);
                L.i("tray: removed, the setting is off");
            }
            REFUSED.remove(root);
        } catch (Throwable t) {
            L.d("tray: could not remove (" + t + ")");
        }
    }

    private static boolean isTaskbar(View root) {
        for (Class<?> c = root.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            if (c.getSimpleName().contains("TaskbarDragLayer")) {
                return true;
            }
        }
        return false;
    }

    private static void attach(View root) {
        if (REFUSED.contains(root) || !(root instanceof ViewGroup)) {
            return;
        }
        // Remembered rather than searched for: this runs on every resume, and walking the
        // taskbar's tree resolving resource names is not free. A tray whose parent is gone means
        // the launcher rebuilt the nav row, and it has to be put back.
        View existing = TRAYS.get(root);
        if (existing != null && existing.getParent() != null) {
            return;
        }
        try {
            ViewGroup host = hostRow((ViewGroup) root);
            if (host == null) {
                L.w("tray: no nav-button row in the taskbar, leaving it alone");
                return;
            }
            if (host.findViewWithTag(TAG_TRAY) != null) {
                return;
            }
            View tray = new TrayView(root.getContext(), root.getDisplay() != null
                    ? root.getDisplay().getDisplayId() : 0);
            tray.setTag(TAG_TRAY);
            // Index 0: the row is anchored at the end of the taskbar and grows leftwards into
            // empty space, so the tray lands to the left of back/home/recents.
            host.addView(tray, 0, rowParams(host));
            TRAYS.put(root, tray);
            L.i("tray: attached to " + Reflect.idName(host));
            tray.post(() -> verify(root, host, tray, VERIFY_ATTEMPTS));
        } catch (Throwable t) {
            L.e("tray: could not attach", t);
        }
    }

    /**
     * Undoes the attach if it pushed a navigation button off the display.
     *
     * <p>Widening the nav row assumes it is anchored at the end and grows towards the middle. On
     * a firmware where it is anchored the other way the buttons would slide off the edge instead,
     * and an unreachable Home button is not something to leave behind - so the tray checks its
     * own work and removes itself rather than betting on the layout.
     */
    private static void verify(View root, ViewGroup host, View tray, int attemptsLeft) {
        try {
            if (tray.getParent() != host) {
                return;
            }
            int width = root.getWidth();
            if (width <= 0 || tray.getWidth() == 0) {
                // Nothing has been laid out yet, so there is nothing to judge.
                if (attemptsLeft > 0) {
                    tray.post(() -> verify(root, host, tray, attemptsLeft - 1));
                }
                return;
            }
            for (View button : Reflect.findByIdNames(root, "back", "home", "recent_apps")) {
                if (button.getVisibility() != View.VISIBLE || button.getWidth() == 0) {
                    continue;
                }
                int[] loc = new int[2];
                int[] rootLoc = new int[2];
                button.getLocationOnScreen(loc);
                root.getLocationOnScreen(rootLoc);
                int left = loc[0] - rootLoc[0];
                if (left < 0 || left + button.getWidth() > width) {
                    host.removeView(tray);
                    TRAYS.remove(root);
                    REFUSED.add(root);
                    L.w("tray: removed - it pushed " + Reflect.idName(button) + " off the display");
                    return;
                }
            }
        } catch (Throwable t) {
            L.d("tray: could not verify placement (" + t + ")");
        }
    }

    /**
     * Where the tray goes.
     *
     * <p>{@code end_nav_buttons} is the row holding back, home and recents. Its layout params are
     * ordinary, it is already positioned at the end of the taskbar, and it sizes itself to its
     * children - so adding to it needs no measurement of our own.
     */
    private static ViewGroup hostRow(ViewGroup root) {
        List<View> found = Reflect.findByIdNames(root, "end_nav_buttons");
        for (View v : found) {
            if (v instanceof LinearLayout) {
                return (ViewGroup) v;
            }
        }
        for (View v : found) {
            if (v instanceof ViewGroup) {
                return (ViewGroup) v;
            }
        }
        return null;
    }

    private static ViewGroup.LayoutParams rowParams(ViewGroup host) {
        if (host instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.rightMargin = Ui.dp(host.getContext(), 6);
            return lp;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        return lp;
    }

    /** The row of indicators, which repaints itself whenever the state behind it moves. */
    private static final class TrayView extends LinearLayout {

        private final int mDisplayId;
        private final ImageView mNetIcon;
        private final ImageView mBatteryIcon;
        private final TextView mBatteryText;
        private final TextView mTemps;
        private final TextView mClock;
        private final Runnable mOnChanged = this::render;

        private SysState mState;
        private int mShownNetType = -1;
        private int mShownNetLevel = -1;
        private int mShownBattery = Integer.MIN_VALUE;
        private boolean mShownCharging;

        TrayView(Context ctx, int displayId) {
            super(ctx);
            mDisplayId = displayId;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int padH = Ui.dp(ctx, 10);
            int padV = Ui.dp(ctx, 4);
            setPadding(padH, padV, padH, padV);
            setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 16)));
            setClickable(true);
            setFocusable(true);
            setContentDescription("System status");

            int icon = Ui.dp(ctx, 18);
            mNetIcon = new ImageView(ctx);
            addView(mNetIcon, new LayoutParams(icon, icon));

            mBatteryIcon = new ImageView(ctx);
            LayoutParams blp = new LayoutParams(icon, icon);
            blp.leftMargin = Ui.dp(ctx, 10);
            addView(mBatteryIcon, blp);

            mBatteryText = label(ctx, 11f);
            LayoutParams tlp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = Ui.dp(ctx, 3);
            addView(mBatteryText, tlp);

            mTemps = label(ctx, 11f);
            mTemps.setTextColor(Ui.COLOR_TEXT_DIM);
            LayoutParams templp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            templp.leftMargin = Ui.dp(ctx, 10);
            addView(mTemps, templp);

            mClock = label(ctx, 13f);
            LayoutParams clp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Ui.dp(ctx, 10);
            addView(mClock, clp);

            setOnClickListener(v -> QuickPanel.toggle(getContext(), v, mDisplayId));
        }

        private TextView label(Context ctx, float sizeSp) {
            TextView tv = new TextView(ctx);
            tv.setTextSize(sizeSp);
            tv.setTextColor(Ui.COLOR_TEXT);
            tv.setSingleLine(true);
            tv.setShadowLayer(Ui.dp(ctx, 2), 0, Ui.dp(ctx, 1), 0x80000000);
            return tv;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                mState = SysState.get(getContext());
                mState.addListener(mOnChanged);
            } catch (Throwable t) {
                L.e("tray: no system state", t);
            }
            render();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (mState != null) {
                mState.removeListener(mOnChanged);
            }
            // The panel is a window of its own and would outlive the tray that opened it,
            // leaving the next tray with a stale "already open" and no way to show anything.
            QuickPanel.dismiss();
        }

        private void render() {
            SysState state = mState;
            if (state == null) {
                return;
            }
            // The temperatures change every few seconds; the icons almost never do. Rebuilding
            // a drawable for an unchanged indicator is pure allocation on a view that is on
            // screen the whole time the desktop is.
            if (state.netType() != mShownNetType || state.netLevel() != mShownNetLevel) {
                mShownNetType = state.netType();
                mShownNetLevel = state.netLevel();
                mNetIcon.setImageDrawable(netIcon(state));
            }
            int percent = state.batteryPercent();
            if (percent != mShownBattery || state.charging() != mShownCharging) {
                mShownBattery = percent;
                mShownCharging = state.charging();
                mBatteryIcon.setImageDrawable(TrayIcons.battery(
                        percent < 0 ? 0 : percent, state.charging(), Ui.COLOR_TEXT));
                mBatteryText.setText(percent < 0 ? "" : percent + "%");
            }
            renderTemps(state);
            mClock.setText(new SimpleDateFormat(
                    android.text.format.DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm a",
                    Locale.getDefault()).format(new Date()));
        }

        /**
         * The temperatures, as "CPU 48 GPU 42 BAT 31".
         *
         * <p>Whatever is unreadable is left out rather than shown as a dash: a sensor the kernel
         * will not let the launcher see is not information the taskbar should spend room on.
         */
        private void renderTemps(SysState state) {
            if (!Cfg.taskbarTemps()) {
                mTemps.setVisibility(GONE);
                return;
            }
            StringBuilder sb = new StringBuilder();
            append(sb, "CPU", state.cpuTemp());
            append(sb, "GPU", state.gpuTemp());
            append(sb, "BAT", state.batteryTemp());
            mTemps.setText(sb);
            mTemps.setVisibility(sb.length() == 0 ? GONE : VISIBLE);
        }

        private void append(StringBuilder sb, String name, float celsius) {
            String value = Thermals.format(celsius);
            if (value.isEmpty()) {
                return;
            }
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(name).append(' ').append(value);
        }

        private Drawable netIcon(SysState state) {
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
    }
}
