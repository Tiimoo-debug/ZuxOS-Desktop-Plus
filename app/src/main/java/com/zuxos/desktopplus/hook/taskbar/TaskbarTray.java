package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.graphics.Typeface;
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
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Thermals;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.TrayIcons;
import com.zuxos.desktopplus.core.theme.Bevel;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.hook.DisplayTimeline;
import com.zuxos.desktopplus.hook.Windows;
import com.zuxos.desktopplus.hook.drawer.DrawerAccountBar;
import com.zuxos.desktopplus.hook.drawer.DrawerGlass;
import com.zuxos.desktopplus.hook.panel.Bypass;
import com.zuxos.desktopplus.hook.panel.Notifications;
import com.zuxos.desktopplus.hook.panel.NotifyPanel;
import com.zuxos.desktopplus.hook.panel.QuickPanel;
import com.zuxos.desktopplus.hook.panel.Shortcuts;
import com.zuxos.desktopplus.hook.panel.SysState;
import com.zuxos.desktopplus.hook.shots.Shots;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
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
        TaskbarEdge.install(loader);
        TaskbarMenu.install(loader);
        TaskbarApps.install(loader);
        NavKeysHold.install(loader);
        TaskbarDiag.install(loader);
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
            Health.hooked("taskbar: windows seen (tray, row, start button)", hooked);
            L.i("tray: watching for the taskbar window x" + hooked);
        } catch (Throwable t) {
            L.e("tray: could not watch for windows", t);
        }
    }

    private static void onWindowAdded(View root) {
        if (!isTaskbar(root)) {
            // Every other window the launcher opens - among them the app drawer's own.
            DrawerGlass.onWindowAdded(root);
            DrawerAccountBar.onWindowAdded(root);
            return;
        }
        // The window's children are not laid out yet; the pieces go in once they are.
        root.post(() -> {
            DisplayTimeline.note("taskbar window up on display " + displayIdOf(root) + " ("
                    + TaskbarScope.label(root) + ")");
            applyAll(root);
        });
    }

    /** Puts each piece in or takes it out, following its setting. */
    private static void applyAll(View windowRoot) {
        ViewGroup root = dragLayerOf(windowRoot);
        if (root == null) {
            return;
        }
        View window = windowRoot.getRootView();
        clearWrapper(window, root);
        TaskbarEdge.apply(root);
        // The tray and the glass are for bars whose window is the drag layer itself - the
        // monitor's. The tablet's bars are wrapped, were never given either, and still are not:
        // recognising those bars here is for our row and start button, not a new look for them.
        if (window == root) {
            if (Cfg.taskbarTray()) {
                attach(root);
            } else {
                detach(root);
            }
            TaskbarGlass.apply(root);
        }
        TaskbarRunning.apply(root);
        // After the glass, because whether it went on is half of what decides the tone, and the
        // tray only repaints itself when the battery or the network moves - which could be
        // minutes away.
        retint();
        TaskbarApps.describeLongPress(root);
        TaskbarDiag.onBar(root);
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
                DrawerAccountBar.onWindowAdded(root);
            }
        }
    }

    /** Per bar: the row the tray follows, and the listener that does it. */
    private static final java.util.Map<View, Object[]> TRAY_FOLLOW = new java.util.WeakHashMap<>();

    private static void detach(View root) {
        try {
            TRAYS.remove(root);
            Object[] follow = TRAY_FOLLOW.remove(root);
            if (follow != null) {
                View reference = ((WeakReference<?>) follow[0]).get() instanceof View
                        ? (View) ((WeakReference<?>) follow[0]).get() : null;
                if (reference != null) {
                    reference.removeOnLayoutChangeListener(
                            (View.OnLayoutChangeListener) follow[1]);
                }
            }
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

    /** Whether a window root is a taskbar: its drag layer, or a wrapper holding one. */
    static boolean isTaskbar(View root) {
        return dragLayerOf(root) != null;
    }

    /**
     * The bar's own {@code TaskbarDragLayer} for any view of it: the view itself, else the
     * nearest one above it, else - for a window root that wraps it, as the tablet's desktop-mode
     * bar does - the first one inside it. Null for anything that is not part of a taskbar.
     *
     * <p>Everything of ours goes on this, never on the window's root: on a wrapped bar the root
     * is not the drag layer, and pieces put there were drawn a second time over the whole bar.
     */
    public static ViewGroup dragLayerOf(View view) {
        for (View v = view; v != null; ) {
            if (isDragLayer(v)) {
                return (ViewGroup) v;
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return view instanceof ViewGroup ? dragLayerBelow((ViewGroup) view, 3) : null;
    }

    private static ViewGroup dragLayerBelow(ViewGroup group, int depth) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isDragLayer(child)) {
                return (ViewGroup) child;
            }
        }
        if (depth > 1) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ViewGroup) {
                    ViewGroup found = dragLayerBelow((ViewGroup) child, depth - 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isDragLayer(View view) {
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        for (Class<?> c = view.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            if (c.getSimpleName().contains("TaskbarDragLayer")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes away any of our pieces left directly on a wrapping window root, which an earlier
     * build put there; they drew over the whole bar and took the start button's touches.
     */
    private static void clearWrapper(View root, ViewGroup dragLayer) {
        if (root == dragLayer || !(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup wrapper = (ViewGroup) root;
        for (int i = wrapper.getChildCount() - 1; i >= 0; i--) {
            View child = wrapper.getChildAt(i);
            if (child.getClass().getName().startsWith("com.zuxos.desktopplus.")) {
                wrapper.removeViewAt(i);
                L.i("tray: took " + child.getClass().getSimpleName()
                        + " off the bar's wrapping root");
            }
        }
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
                View.OnLayoutChangeListener follow =
                        (v, l, t, r, b, ol, ot, or, ob) -> syncGeometry(dragLayer, tray, reference);
                reference.addOnLayoutChangeListener(follow);
                // Taken off again in detach: each toggle of the setting left one behind,
                // holding a dead tray and setting its layout on every layout of the bar.
                TRAY_FOLLOW.put(root, new Object[]{new WeakReference<>(reference), follow});
            }
            L.i("tray: attached to the taskbar drag layer");
        } catch (Throwable t) {
            L.e("tray: could not attach", t);
        }
    }

    /** Our tray in this taskbar, or null when it is not up. */
    static View trayOf(ViewGroup dragLayer) {
        return dragLayer.findViewWithTag(TAG_TRAY);
    }

    /** How much of the bar's right-hand end the tray occupies, margin included. */
    static int trayWidth(ViewGroup dragLayer) {
        View tray = dragLayer.findViewWithTag(TAG_TRAY);
        int width = tray != null ? tray.getWidth() : 0;
        return width + Ui.dp(dragLayer.getContext(), EDGE_MARGIN_DP);
    }

    public static int displayIdOf(View view) {
        return Ui.displayOf(view);
    }

    /**
     * The row whose vertical position the tray copies.
     *
     * <p>The drag layer is taller than the visible bar - it reserves room for the stashed handle -
     * so aligning to it would float the tray above the taskbar. The icon row is the bar.
     */
    public static View rowReference(ViewGroup dragLayer) {
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

    public static int displayHeight(Context ctx) {
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

    /**
     * The row of indicators, which repaints itself whenever the state behind it moves. In the
     * Retro theme it is Windows 98's tray: a shallow sunken box, black icons, the pixel font.
     */
    private static final class TrayView extends LinearLayout {

        private final int mDisplayId;
        /** Which theme it was last dressed in, once it has been. */
        private Boolean mRetro;
        /** The labels' own faces, kept while the pixel font stands in for them. */
        private Typeface[] mOwnFaces;
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
        /**
         * The clock shows seconds, so it repaints once a second - only while the tray can be
         * seen: a bar hidden under a full-screen app, or a tray in a window that is not shown,
         * has nothing ticking. It is right again the moment it shows.
         */
        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                renderClock();
                postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L));
            }
        };

        private SysState mState;
        private boolean mVisible;
        /**
         * The clock's formats, made again only when what they were made for changes: the
         * 12/24-hour setting, the language, or the time zone (a format keeps the zone it was
         * made in).
         */
        private String mClockFor;
        private SimpleDateFormat mClockFormat;
        private SimpleDateFormat mDateFormat;
        private String mShownDate;
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
            // Hold or right-click the battery for bypass charging, as ZUI's Game Assistant offers.
            for (View battery : new View[]{mBatteryIcon, mBatteryText}) {
                battery.setOnLongClickListener(this::batteryMenu);
                battery.setOnTouchListener((v, e) -> {
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                            && e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                            && (e.getButtonState()
                            & android.view.MotionEvent.BUTTON_SECONDARY) != 0) {
                        return batteryMenu(v);
                    }
                    return false;
                });
            }

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

        /**
         * The battery's menu: bypass charging on or off, and the battery settings. Read when it
         * opens, so it shows what ZUI has now - Game Assistant switches the same thing.
         */
        private boolean batteryMenu(View anchor) {
            try {
                Context ctx = getContext();
                Boolean on = Bypass.on(ctx);
                java.util.List<TaskbarMenu.Entry> entries = new java.util.ArrayList<>();
                if (on != null) {
                    boolean turnOn = !on;
                    entries.add(new TaskbarMenu.Entry(
                            turnOn ? "Bypass charging: turn on" : "Bypass charging: turn off",
                            () -> {
                                if (!Bypass.set(ctx, turnOn)) {
                                    TaskbarMenu.toast(ctx, "ZUI did not switch bypass charging");
                                } else if (turnOn) {
                                    TaskbarMenu.toast(ctx, "Bypass charging on: with a "
                                            + "charger in, it powers the tablet and the battery "
                                            + "rests. Off again after a restart");
                                }
                            }));
                }
                entries.add(new TaskbarMenu.Entry("Battery settings", () -> TaskbarMenu.open(ctx,
                        android.content.Intent.ACTION_POWER_USAGE_SUMMARY, mDisplayId)));
                int[] at = new int[2];
                anchor.getLocationOnScreen(at);
                return TaskbarMenu.showEntries(anchor, mDisplayId,
                        at[0] + anchor.getWidth() / 2f, entries);
            } catch (Throwable t) {
                L.e("tray: battery menu failed", t);
                return false;
            }
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
        }

        /** Shown or hidden: the window, this view, or anything holding it. */
        @Override
        public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            mVisible = isVisible;
            removeCallbacks(mTick);
            if (isVisible) {
                mTick.run();
            }
            watchTemps();
        }

        /** Temperatures are read only while a tray that shows them can be seen. */
        private void watchTemps() {
            if (mState != null) {
                mState.watchTemps(this, mVisible && isAttachedToWindow() && Cfg.taskbarTemps());
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(mTick);
            if (mState != null) {
                mState.watchTemps(this, false);
                mState.removeListener(mOnChanged);
            }
            // These are windows of their own and would outlive the tray that opened them,
            // leaving the next tray with a stale "already open" and no way to show anything.
            //
            // Posted, never called from here. Closing them calls removeViewImmediate, which tears
            // a window down synchronously - and doing that while the framework is part way through
            // detaching this one re-enters the window manager mid-walk. That is the
            // dispatchDetachedFromWindow NPE the dropbox has collected fifty of: a ViewGroup
            // reading a child index that the re-entrant teardown had already shifted out from
            // under it. A frame later the same work is harmless.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                QuickPanel.dismiss();
                NotifyPanel.dismiss();
            });
        }

        /**
         * Retro's tray, or glass's, following the theme for this display. Asked on every repaint
         * - once a minute or on a state change - and done only when the theme has changed.
         */
        private void dress() {
            boolean retro = Theme.of(mDisplayId).retro();
            // Built as glass: only a change of theme has anything to do.
            boolean unchanged = mRetro == null ? !retro : mRetro == retro;
            mRetro = retro;
            if (unchanged) {
                return;
            }
            Context ctx = getContext();
            setBackground(retro ? Bevel.shallow(ctx) : null);
            TextView[] labels = {mBatteryText, mTemps, mClock, mDate};
            if (mOwnFaces == null) {
                mOwnFaces = new Typeface[labels.length];
                for (int i = 0; i < labels.length; i++) {
                    mOwnFaces[i] = labels[i].getTypeface();
                }
            }
            for (int i = 0; i < labels.length; i++) {
                labels[i].setTypeface(retro ? Theme.RETRO.font(ctx) : mOwnFaces[i]);
            }
            // Windows 98's tray has no round light under what is pressed.
            mDate.setBackground(retro ? null : Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 10)));
            mClock.setBackground(retro ? null : Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 10)));
            for (ImageView button : new ImageView[]{mScreenshot, mBell, mPanelButton}) {
                button.setBackground(retro ? null : Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 14)));
            }
            mShownColor = 0;
        }

        private void render() {
            SysState state = mState;
            if (state == null) {
                return;
            }
            dress();
            int color = mRetro ? Theme.RETRO.text() : textColor();
            boolean recolour = color != mShownColor;
            if (recolour) {
                mShownColor = color;
                mBatteryText.setTextColor(color);
                mClock.setTextColor(color);
                mDate.setTextColor(color);
                mTemps.setTextColor(mRetro ? Theme.RETRO.dimText() : dimTextColor());
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
            // The setting may have changed since the tray was shown.
            watchTemps();
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
                waiting = Notifications.count(getContext(), this::render) > 0;
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
            String clockFor = h24 + " " + Locale.getDefault() + " " + TimeZone.getDefault().getID();
            if (!clockFor.equals(mClockFor)) {
                mClockFor = clockFor;
                mClockFormat = new SimpleDateFormat(h24 ? "HH:mm:ss" : "h:mm:ss a",
                        Locale.getDefault());
                mDateFormat = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
            }
            mClock.setText(mClockFormat.format(now));
            // The date changes once a day; setting the same text still lays the bar out again.
            String date = mDateFormat.format(now);
            if (!date.equals(mShownDate)) {
                mShownDate = date;
                mDate.setText(date);
            }
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
