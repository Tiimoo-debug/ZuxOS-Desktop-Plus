package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Thermals;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.TrayIcons;
import com.zuxos.desktopplus.core.Ui;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A status area inside the stock taskbar: network, battery, temperatures and a clock.
 *
 * <p>The taskbar has none of this - app icons, three navigation buttons, nothing else - so on an
 * external display there is no way to see whether the ethernet is up without leaving what you are
 * doing.
 *
 * <p>The tray is a direct child of the taskbar's drag layer, pinned to the right-hand edge. An
 * earlier version put it inside the navigation-button row so the launcher would lay it out, but
 * that row turned out to live at the left of this firmware's taskbar and sits inside a
 * {@code NearestTouchFrame}, which routes touches to whichever button it decides is nearest - so
 * the tray appeared in the wrong place and never saw a tap. Owning the position costs a little
 * geometry and leaves the launcher's own views completely alone.
 */
public final class TaskbarTray {

    private static final String TAG_TRAY = "zux-desktop-plus-tray";

    /** Distance from the right edge of the taskbar. */
    private static final int EDGE_MARGIN_DP = 12;

    /** The tray in each taskbar, so the common "already there" case costs one lookup. */
    private static final Map<View, WeakReference<View>> TRAYS = new WeakHashMap<>();

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
        // First, and outside the block below: neither of these depends on spotting the window,
        // and an early return from that search used to take them both down with it.
        TaskbarGlass.install(loader);
        TaskbarMenu.install(loader);
        TaskbarApps.install(loader);
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
            // Every other window the launcher opens - among them the app drawer's own.
            DrawerGlass.onWindowAdded(root);
            return;
        }
        // The window's children are not laid out yet; the pieces go in once they are.
        root.post(() -> applyAll(root));
    }

    /** Puts each piece in or takes it out, following its setting. */
    private static void applyAll(View root) {
        if (Cfg.taskbarTray()) {
            attach(root);
        } else {
            detach(root);
        }
        TaskbarGlass.apply(root);
        if (root instanceof ViewGroup) {
            TaskbarRunning.apply((ViewGroup) root);
        }
        // After the glass, because whether it went on is half of what decides the tone, and the
        // tray only repaints itself when the battery or the network moves - which could be
        // minutes away.
        retint();
        if (root instanceof ViewGroup) {
            TaskbarApps.describeLongPress((ViewGroup) root);
        }
    }

    /** Repaints every tray, for when the reason its colour might change is not its own state. */
    private static void retint() {
        // The glyphs as well as the tray: they are tinted once when the glass goes on, so
        // changing the setting afterwards would otherwise recolour the clock and leave back,
        // home and recents as they were.
        TaskbarGlass.retintNav();
        for (WeakReference<View> ref : TRAYS.values()) {
            View tray = ref != null ? ref.get() : null;
            if (tray instanceof TrayView && tray.getParent() != null) {
                ((TrayView) tray).render();
            }
        }
    }

    /**
     * Re-checks every taskbar; called when the desktop resumes, so a missed window self-heals.
     *
     * <p>This also runs the settings in reverse: turning a piece off takes it back out of a
     * taskbar that already has it, rather than leaving it there until the launcher restarts.
     */
    public static void refresh() {
        for (View root : Windows.roots()) {
            if (isTaskbar(root)) {
                applyAll(root);
            } else {
                // The drawer's window may already have been open when the setting changed.
                DrawerGlass.onWindowAdded(root);
            }
        }
    }

    private static void detach(View root) {
        try {
            TRAYS.remove(root);
            View tray = root.findViewWithTag(TAG_TRAY);
            if (tray != null && tray.getParent() instanceof ViewGroup) {
                ((ViewGroup) tray.getParent()).removeView(tray);
                L.i("tray: removed, the setting is off");
            }
        } catch (Throwable t) {
            L.d("tray: could not remove (" + t + ")");
        }
    }

    /**
     * Whether a point on the taskbar lands on our tray.
     *
     * <p>The tray is mostly labels - temperatures, the clock - and a label is not clickable, so
     * without this a hold anywhere along it counted as bare taskbar and brought up the menu.
     */
    static boolean isOnTray(ViewGroup dragLayer, float x, float y) {
        View tray = dragLayer.findViewWithTag(TAG_TRAY);
        if (tray == null || tray.getVisibility() != View.VISIBLE || tray.getWidth() <= 0) {
            return false;
        }
        return x >= tray.getLeft() && x <= tray.getRight()
                && y >= tray.getTop() && y <= tray.getBottom();
    }

    static boolean isTaskbar(View root) {
        for (Class<?> c = root.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            if (c.getSimpleName().contains("TaskbarDragLayer")) {
                return true;
            }
        }
        return false;
    }

    private static void attach(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        // Remembered rather than searched for: this runs on every resume. A tray whose parent is
        // gone means the launcher rebuilt the taskbar, and it has to be put back.
        WeakReference<View> ref = TRAYS.get(root);
        View existing = ref != null ? ref.get() : null;
        if (existing != null && existing.getParent() != null) {
            return;
        }
        try {
            ViewGroup dragLayer = (ViewGroup) root;
            if (dragLayer.findViewWithTag(TAG_TRAY) != null) {
                return;
            }
            View reference = rowReference(dragLayer);
            ViewGroup.LayoutParams lp = dragLayerParams(dragLayer, reference);
            if (lp == null) {
                L.w("tray: the drag layer's layout params are not reproducible, no tray");
                return;
            }
            TrayView tray = new TrayView(dragLayer.getContext(), displayIdOf(dragLayer));
            tray.setTag(TAG_TRAY);
            dragLayer.addView(tray, lp);
            TRAYS.put(root, new WeakReference<>(tray));
            syncGeometry(dragLayer, tray, reference);
            if (reference != null) {
                reference.addOnLayoutChangeListener(
                        (v, l, t, r, b, ol, ot, or, ob) -> syncGeometry(dragLayer, tray, reference));
            }
            L.i("tray: attached to the taskbar drag layer");
        } catch (Throwable t) {
            L.e("tray: could not attach", t);
        }
    }

    /** How much of the bar's right-hand end the tray occupies, margin included. */
    static int trayWidth(ViewGroup dragLayer) {
        View tray = dragLayer.findViewWithTag(TAG_TRAY);
        int width = tray != null ? tray.getWidth() : 0;
        return width + Ui.dp(dragLayer.getContext(), EDGE_MARGIN_DP);
    }

    static int displayIdOf(View view) {
        try {
            return view.getDisplay() != null ? view.getDisplay().getDisplayId() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The row whose vertical position the tray copies.
     *
     * <p>The drag layer is taller than the visible bar - it reserves room for the stashed handle -
     * so aligning to it would float the tray above the taskbar. The icon row is the bar.
     */
    static View rowReference(ViewGroup dragLayer) {
        List<View> found = Reflect.findByIdNames(dragLayer, "taskbar_view", "navbuttons_view");
        for (View v : found) {
            if (v.getVisibility() == View.VISIBLE) {
                return v;
            }
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Layout params the drag layer will accept, or null when they cannot be reproduced.
     *
     * <p>Launcher3's drag layer is an {@code InsettableFrameLayout}, which casts every child's
     * params to its own type when it applies window insets - so a plain
     * {@code FrameLayout.LayoutParams} would crash it. Cloning the class off a view that is
     * already in there gets the right type without naming it.
     */
    static ViewGroup.LayoutParams dragLayerParams(ViewGroup dragLayer, View sibling) {
        ViewGroup.LayoutParams lp = null;
        View model = sibling != null ? sibling
                : (dragLayer.getChildCount() > 0 ? dragLayer.getChildAt(0) : null);
        if (model != null && model.getLayoutParams() != null) {
            try {
                lp = (ViewGroup.LayoutParams) model.getLayoutParams().getClass()
                        .getConstructor(int.class, int.class)
                        .newInstance(ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
            } catch (Throwable t) {
                L.d("tray: could not clone the drag layer's layout params (" + t + ")");
            }
        }
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            // Guessing here is what would crash the launcher: its inset pass casts every child's
            // params to its own type. Better to add nothing than to add something it cannot hold.
            return null;
        }
        // The launcher shifts children by the inset deltas unless they opt out, which would drag
        // the tray away from the position computed below.
        try {
            Field ignore = lp.getClass().getField("ignoreInsets");
            ignore.setBoolean(lp, true);
        } catch (Throwable ignored) {
            // Not an Insettable layout, so there is nothing to opt out of.
        }
        return lp;
    }

    /** Pins the tray to the right-hand end of the visible bar. */
    private static void syncGeometry(ViewGroup dragLayer, View tray, View reference) {
        try {
            ViewGroup.LayoutParams raw = tray.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.rightMargin = Ui.dp(dragLayer.getContext(), EDGE_MARGIN_DP);
            if (reference != null && reference.getHeight() > 0) {
                lp.height = reference.getHeight();
                lp.topMargin = reference.getTop();
            } else {
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.topMargin = 0;
            }
            tray.setLayoutParams(lp);
        } catch (Throwable t) {
            L.d("tray: could not place the tray (" + t + ")");
        }
    }

    /**
     * How far the visible bar reaches up from the bottom of the display, in pixels.
     *
     * <p>What a popup anchored to the taskbar needs, and the one measurement that cannot be got
     * wrong by asking the drag layer. The drag layer is taller than the bar - it reserves room for
     * the stashed handle - and its own height is the whole window, which grows to fill the display
     * while the app drawer is open. Reading the row's position on screen sidesteps both: it is
     * where the bar actually is.
     *
     * @return the gap to leave under a popup, or a sane guess when the bar cannot be measured
     */
    static int barInset(View source) {
        if (source == null) {
            return 0;
        }
        View root = source;
        while (root.getParent() instanceof View) {
            root = (View) root.getParent();
        }
        View reference = root instanceof ViewGroup ? rowReference((ViewGroup) root) : null;
        if (reference != null && reference.getHeight() > 0) {
            int display = displayHeight(reference.getContext());
            int[] at = new int[2];
            reference.getLocationOnScreen(at);
            int fromBottom = display - at[1];
            if (display > 0 && fromBottom > 0 && fromBottom <= display) {
                return fromBottom;
            }
            // Not on screen yet, or on a display we could not measure; the row's own height is
            // still closer to the truth than the window's.
            return reference.getHeight();
        }
        return source.getHeight() > 0 ? source.getHeight() : Ui.dp(source.getContext(), 56);
    }

    private static int displayHeight(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    return wm.getCurrentWindowMetrics().getBounds().height();
                }
            }
        } catch (Throwable ignored) {
            // Not a display-bound context; the metrics below are the next best thing.
        }
        try {
            return ctx.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** One decision for everything on the bar; see {@code core/Tone.java}. */
    static int textColor() {
        return Tone.text(AppCtx.get());
    }

    static int dimTextColor() {
        return Tone.dimText(AppCtx.get());
    }

    /** The row of indicators, which repaints itself whenever the state behind it moves. */
    private static final class TrayView extends LinearLayout {

        private final int mDisplayId;
        private final ImageView mNetIcon;
        private final ImageView mBatteryIcon;
        private final TextView mBatteryText;
        private final TextView mTemps;
        private final TextView mClock;
        private final TextView mDate;
        private final ImageView mScreenshot;
        private final ImageView mBell;
        private final ImageView mPanelButton;
        private final Runnable mOnChanged = this::render;
        /** The clock shows seconds, so it repaints once a second while the tray is on screen. */
        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                renderClock();
                postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L));
            }
        };

        private SysState mState;
        private int mShownNetType = -1;
        private int mShownNetLevel = -1;
        private int mShownBattery = Integer.MIN_VALUE;
        private boolean mShownCharging;
        private boolean mShownWaiting;
        private int mShownColor;

        TrayView(Context ctx, int displayId) {
            super(ctx);
            mDisplayId = displayId;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int padH = Ui.dp(ctx, 6);
            int padV = Ui.dp(ctx, 4);
            setPadding(padH, padV, padH, padV);
            // The row itself is no longer a button. Each piece answers for itself, which is what
            // makes the panel's own button possible.
            setContentDescription("System status");

            int icon = Ui.dp(ctx, 18);
            mScreenshot = iconButton(ctx, "Screenshot",
                    () -> Shots.take(getContext(), mDisplayId));
            addView(mScreenshot, buttonParams(ctx, 0));

            mNetIcon = new ImageView(ctx);
            LayoutParams nlp = new LayoutParams(icon, icon);
            nlp.leftMargin = Ui.dp(ctx, 8);
            addView(mNetIcon, nlp);

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
            LayoutParams templp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            templp.leftMargin = Ui.dp(ctx, 10);
            addView(mTemps, templp);

            mDate = label(ctx, 13f);
            mDate.setGravity(Gravity.CENTER_VERTICAL);
            mDate.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0);
            mDate.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 10)));
            mDate.setOnClickListener(v -> Shortcuts.openCalendar(getContext(), mDisplayId));
            LayoutParams dlp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT);
            dlp.leftMargin = Ui.dp(ctx, 6);
            addView(mDate, dlp);

            mClock = label(ctx, 13f);
            // The clock and the date are as tall as the row so their ripple fills it, which
            // leaves their text sitting at the top unless it is told to centre. That is what put
            // them off the line the icons sit on.
            mClock.setGravity(Gravity.CENTER_VERTICAL);
            mClock.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0);
            mClock.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 10)));
            mClock.setOnClickListener(v -> Shortcuts.openClock(getContext(), mDisplayId));
            LayoutParams clp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT);
            clp.leftMargin = Ui.dp(ctx, 2);
            addView(mClock, clp);

            mBell = iconButton(ctx, "Notifications",
                    () -> NotifyPanel.toggle(getContext(), TrayView.this, mDisplayId));
            addView(mBell, buttonParams(ctx, 4));

            mPanelButton = iconButton(ctx, "Quick settings",
                    () -> QuickPanel.toggle(getContext(), TrayView.this, mDisplayId));
            addView(mPanelButton, buttonParams(ctx, 4));
        }

        /** A round, tappable icon in the tray row. */
        private ImageView iconButton(Context ctx, String description, Runnable action) {
            ImageView button = new ImageView(ctx);
            int inset = Ui.dp(ctx, 5);
            button.setPadding(inset, inset, inset, inset);
            button.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 14)));
            button.setContentDescription(description);
            button.setOnClickListener(v -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    L.e("tray: " + description + " failed", t);
                }
            });
            return button;
        }

        private LayoutParams buttonParams(Context ctx, int leftMarginDp) {
            int size = Ui.dp(ctx, 28);
            LayoutParams lp = new LayoutParams(size, size);
            lp.leftMargin = Ui.dp(ctx, leftMarginDp);
            return lp;
        }

        private TextView label(Context ctx, float sizeSp) {
            TextView tv = new TextView(ctx);
            tv.setTextSize(sizeSp);
            tv.setSingleLine(true);
            // Every label on the same line as the icons, whatever height it ends up with.
            tv.setGravity(Gravity.CENTER_VERTICAL);
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
            mTick.run();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(mTick);
            if (mState != null) {
                mState.removeListener(mOnChanged);
            }
            // These are windows of their own and would outlive the tray that opened them,
            // leaving the next tray with a stale "already open" and no way to show anything.
            QuickPanel.dismiss();
            NotifyPanel.dismiss();
        }

        private void render() {
            SysState state = mState;
            if (state == null) {
                return;
            }
            int color = textColor();
            boolean recolour = color != mShownColor;
            if (recolour) {
                mShownColor = color;
                mBatteryText.setTextColor(color);
                mClock.setTextColor(color);
                mDate.setTextColor(color);
                mTemps.setTextColor(dimTextColor());
                mScreenshot.setImageDrawable(TrayIcons.screenshot(color));
                mPanelButton.setImageDrawable(TrayIcons.panelChevron(color));
            }
            renderBell(color, recolour);
            // The temperatures change every few seconds; the icons almost never do. Rebuilding
            // a drawable for an unchanged indicator is pure allocation on a view that is on
            // screen the whole time the desktop is.
            if (recolour || state.netType() != mShownNetType
                    || state.netLevel() != mShownNetLevel) {
                mShownNetType = state.netType();
                mShownNetLevel = state.netLevel();
                mNetIcon.setImageDrawable(netIcon(state, color));
            }
            int percent = state.batteryPercent();
            if (recolour || percent != mShownBattery || state.charging() != mShownCharging) {
                mShownBattery = percent;
                mShownCharging = state.charging();
                mBatteryIcon.setImageDrawable(
                        TrayIcons.battery(percent < 0 ? 0 : percent, state.charging(), color));
                mBatteryText.setText(percent < 0 ? "" : percent + "%");
            }
            renderTemps(state);
            renderClock();
        }

        /**
         * The bell, with a dot on it when the shade has something in it.
         *
         * <p>The count is a query into another process, so it is asked when the tray repaints -
         * once a minute or on a state change - and not on the clock's every second.
         */
        private void renderBell(int color, boolean recolour) {
            if (!Cfg.notifications()) {
                mBell.setVisibility(GONE);
                return;
            }
            mBell.setVisibility(VISIBLE);
            boolean waiting = false;
            try {
                waiting = Notifications.count(getContext()) > 0;
            } catch (Throwable t) {
                L.d("tray: could not count notifications (" + t + ")");
            }
            if (recolour || waiting != mShownWaiting) {
                mShownWaiting = waiting;
                mBell.setImageDrawable(TrayIcons.bell(waiting, color));
            }
        }

        /**
         * The time with seconds, and the date beside it.
         *
         * <p>Separate from {@link #render} because it runs every second while the rest of the row
         * changes once a minute at most.
         */
        private void renderClock() {
            Date now = new Date();
            boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            mClock.setText(new SimpleDateFormat(h24 ? "HH:mm:ss" : "h:mm:ss a",
                    Locale.getDefault()).format(now));
            mDate.setText(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now));
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

        private Drawable netIcon(SysState state, int color) {
            switch (state.netType()) {
                case SysState.NET_ETHERNET:
                    return TrayIcons.ethernet(color);
                case SysState.NET_WIFI:
                    return TrayIcons.wifi(state.netLevel(), color);
                case SysState.NET_CELLULAR:
                    return TrayIcons.cellular(state.netLevel(), color);
                default:
                    return TrayIcons.wifiOff(color);
            }
        }

    }
}
