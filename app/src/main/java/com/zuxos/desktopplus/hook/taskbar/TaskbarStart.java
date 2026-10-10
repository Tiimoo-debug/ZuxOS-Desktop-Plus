package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.AndroidRobot;
import com.zuxos.desktopplus.core.icons.PixelIcons;
import com.zuxos.desktopplus.core.motion.Hover;
import com.zuxos.desktopplus.core.theme.Bevel;
import com.zuxos.desktopplus.core.theme.Theme;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The start button, at the left end of the bar where a desktop keeps it.
 *
 * <p>Ours, drawn in the bar beside the navigation keys, and pressing it presses ZUI's own
 * drawer button - so the drawer is ZUI's, opened with ZUI's own animation. ZUI's button is set
 * invisible when ZUI builds its row, and left at that.
 *
 * <p>It used to be ZUI's own button, moved. Keeping it there meant undoing ZUI all the time:
 * laying it out again after every layout of the row, putting the robot back whenever ZUI reset
 * the icon, and holding the row visible and opaque whenever ZUI hid it - through hooks that ran
 * for every view in the launcher. Holding the row up was also what left icons where the tablet's
 * bar had been once it was switched off. Our own button needs none of that: it is placed when the
 * bar's geometry changes, and it follows the bar like the rest of ours ({@link TaskbarFollow}).
 */
final class TaskbarStart {

    private static final String TAG_START = "zux-start-button";

    /** ZUI's buttons we set invisible, so the setting going off can show them again. */
    private static final Map<View, Boolean> HIDDEN = new WeakHashMap<>();

    /** When our button was last pressed, per display: the drawer is on its way. */
    private static final android.util.SparseLongArray PRESSED = new android.util.SparseLongArray();

    private static boolean sSaid;
    private static final java.util.Set<String> SAID_PRESS = new java.util.HashSet<>();
    private static final java.util.Set<String> SAID_TOUCH = new java.util.HashSet<>();

    private TaskbarStart() {
    }

    /** Puts our button on this bar, or ZUI's back, following the setting and the bar. */
    static void apply(ViewGroup bar, ViewGroup icons) {
        try {
            ViewGroup dragLayer = TaskbarTray.dragLayerOf(bar);
            if (dragLayer == null) {
                return;
            }
            View zui = allAppsButton(icons);
            if (!Cfg.startButtonLeft()) {
                unapply(dragLayer, icons);
                return;
            }
            if (zui == null) {
                return;
            }
            if (zui.getVisibility() != View.INVISIBLE) {
                HIDDEN.put(zui, Boolean.TRUE);
                zui.setVisibility(View.INVISIBLE);
            }
            StartButton ours = buttonIn(dragLayer);
            if (ours == null) {
                ours = add(dragLayer);
                if (ours == null) {
                    return;
                }
            }
            ours.bind(zui, Theme.of(dragLayer));
            place(dragLayer, ours, zui);
            placeSearch(dragLayer, icons, ours);
        } catch (Throwable t) {
            L.d("taskbar start: not placed (" + t + ")");
        }
    }

    /** Our button off this bar and ZUI's shown again, as ZUI built it. */
    static void unapply(ViewGroup dragLayer, ViewGroup icons) {
        StartButton ours = buttonIn(dragLayer);
        if (ours != null) {
            dragLayer.removeView(ours);
        }
        View zui = allAppsButton(icons);
        if (zui != null && HIDDEN.remove(zui) != null) {
            zui.setVisibility(View.VISIBLE);
        }
        View pill = searchPill(icons);
        if (pill != null && SEARCH_MOVED.remove(pill) != null) {
            pill.setTranslationX(0f);
        }
    }

    /** ZUI's search pills we moved beside the start button, so they can be put back. */
    private static final Map<View, Boolean> SEARCH_MOVED = new WeakHashMap<>();

    /** The gap between the start button and the search pill. */
    private static final int SEARCH_GAP_DP = 8;

    /**
     * ZUI's search pill on the tablet's desktop-mode bar, or null where the bar has none. Found by
     * its class name ({@code ZuiTaskbarSearchContainer}), among the row's own children.
     */
    static View searchPill(ViewGroup icons) {
        if (icons == null) {
            return null;
        }
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getClass().getSimpleName().contains("Search")
                    && child.getVisibility() == View.VISIBLE) {
                return child;
            }
        }
        return null;
    }

    /**
     * The search pill right after the start button, as on a desktop's taskbar: start, search,
     * then the apps. Slid there rather than laid out again - ZUI keeps laying its row out and
     * the slide just follows - and the apps then start after it.
     */
    private static void placeSearch(ViewGroup dragLayer, ViewGroup icons, StartButton ours) {
        View pill = searchPill(icons);
        if (pill == null || pill.getWidth() <= 0
                || !(ours.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ours.getLayoutParams();
        if (lp.width <= 0) {
            return;
        }
        int target = lp.leftMargin + lp.width + Ui.dp(dragLayer.getContext(), SEARCH_GAP_DP);
        float slide = target - offsetIn(dragLayer, pill);
        SEARCH_MOVED.put(pill, Boolean.TRUE);
        if (Math.abs(pill.getTranslationX() - slide) > 0.5f) {
            pill.setTranslationX(slide);
        }
    }

    static StartButton buttonIn(ViewGroup dragLayer) {
        View found = dragLayer == null ? null : dragLayer.findViewWithTag(TAG_START);
        return found instanceof StartButton ? (StartButton) found : null;
    }

    private static StartButton add(ViewGroup dragLayer) {
        View reference = TaskbarTray.rowReference(dragLayer);
        ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            L.w("taskbar start: the drag layer's layout params are not reproducible, no button");
            return null;
        }
        StartButton button = new StartButton(dragLayer.getContext());
        button.setTag(TAG_START);
        dragLayer.addView(button, lp);
        if (!sSaid) {
            sSaid = true;
            L.i("taskbar start: our own start button beside the navigation keys; ZUI's is "
                    + "pressed through it");
        }
        return button;
    }

    /**
     * Beside the navigation keys, centred on the bar's row, at ZUI's own icon size. Retro's
     * button is a box a little shorter than that, as wide as the robot and its label need.
     */
    private static void place(ViewGroup dragLayer, StartButton button, View zui) {
        View reference = TaskbarTray.rowReference(dragLayer);
        if (reference == null || reference.getHeight() <= 0) {
            return;
        }
        int size = size(zui, dragLayer.getContext());
        int height = button.retro() ? size * 4 / 5 : size;
        int width = button.retro() ? button.retroWidth(height) : size;
        int left = navEnd(dragLayer);
        int top = reference.getTop() + (reference.getHeight() - height) / 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
        int gravity = Gravity.TOP | Gravity.START;
        if (lp.gravity == gravity && lp.width == width && lp.height == height
                && lp.leftMargin == left && lp.topMargin == top) {
            // Placed from layout listeners: unchanged params must not ask for another pass.
            return;
        }
        lp.gravity = gravity;
        lp.width = width;
        lp.height = height;
        lp.leftMargin = left;
        lp.topMargin = top;
        button.setLayoutParams(lp);
    }

    /** ZUI's own button's size, which is its icon size on this bar. */
    static int size(View zui, Context ctx) {
        if (zui != null && zui.getWidth() > 0) {
            return zui.getWidth();
        }
        return Ui.dp(ctx, 48);
    }

    /**
     * The right-hand end of the navigation keys, in the drag layer's coordinates, or 0 when
     * they are not on the left - the tablet's own bar keeps them on the right, or has none with
     * gestures.
     */
    private static int navEnd(ViewGroup dragLayer) {
        View keys = navKeys(dragLayer);
        if (keys == null) {
            return 0;
        }
        int right = drawnLeftIn(dragLayer, keys) + keys.getWidth();
        return right < dragLayer.getWidth() / 2 ? right : 0;
    }

    /**
     * Whether this child of ZUI's row is the drawer button we set invisible, or the search pill
     * we slid beside the start button: anything measuring where ZUI's icons end skips it.
     */
    static boolean isMoved(View child) {
        return HIDDEN.containsKey(child) || SEARCH_MOVED.containsKey(child);
    }

    /**
     * Where the start button ends on the bar, in the drag layer's coordinates. Falls back to
     * ZUI's own button and then to the end of the navigation keys; -1 when nothing can be
     * measured yet.
     */
    static int rightEdge(ViewGroup dragLayer, ViewGroup icons) {
        StartButton ours = buttonIn(dragLayer);
        if (ours != null && ours.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            // Where it is placed, not where it is drawn this frame: it follows the bar's own
            // movements, and a row measured from those jumped with them.
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ours.getLayoutParams();
            if (lp.width > 0) {
                int right = lp.leftMargin + lp.width;
                // The search pill, moved beside it, belongs to the same cluster: the apps start
                // after the pill.
                View pill = searchPill(icons);
                if (pill != null && SEARCH_MOVED.containsKey(pill) && pill.getWidth() > 0) {
                    right = Math.max(right, Math.round(offsetIn(dragLayer, pill)
                            + pill.getTranslationX()) + pill.getWidth());
                }
                return right;
            }
        }
        View zui = allAppsButton(icons);
        if (zui != null && zui.getVisibility() == View.VISIBLE && zui.getWidth() > 0) {
            return offsetIn(dragLayer, zui) + zui.getRight() - zui.getLeft();
        }
        View keys = navKeys(dragLayer);
        return keys != null ? drawnLeftIn(dragLayer, keys) + keys.getWidth() : -1;
    }

    /**
     * Whether ZUI's drawer is open on this display, or about to be: pressed in the last moment
     * and still on its way up.
     */
    static boolean drawerOpen(int display) {
        long now = SystemClock.uptimeMillis();
        long pressed = PRESSED.get(display, 0L);
        if (pressed > 0 && now - pressed < 600L) {
            return true;
        }
        // Asked on every frame while the tablet's bar is hidden; the window list is read at most
        // ten times a second, which a drawer opening or closing never outpaces.
        long checked = DRAWER_CHECKED.get(display, 0L);
        if (checked > 0 && now - checked < 100L) {
            return DRAWER_OPEN.get(display, false);
        }
        boolean open = TaskbarBridge.isStockDrawerOpen(display);
        DRAWER_CHECKED.put(display, now);
        DRAWER_OPEN.put(display, open);
        return open;
    }

    private static final android.util.SparseLongArray DRAWER_CHECKED =
            new android.util.SparseLongArray();
    private static final android.util.SparseBooleanArray DRAWER_OPEN =
            new android.util.SparseBooleanArray();

    /** ZUI's own all-apps button, by the name its class carries on every build. */
    static View allAppsButton(ViewGroup icons) {
        if (icons == null) {
            return null;
        }
        for (View view : Reflect.findByClassFragments(icons, "AllAppsButton")) {
            // The container, not the icon inside it: it is the row's own child.
            if (view.getParent() == icons) {
                return view;
            }
        }
        return null;
    }

    private static int offsetIn(ViewGroup dragLayer, View view) {
        int left = 0;
        for (View v = view; v != null && v != dragLayer; ) {
            left += v.getLeft();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return left;
    }

    /**
     * Where a view of ZUI's is drawn across the bar, its slide included: ZUI moves the navigation
     * keys from one end of the tablet's bar to the other by sliding them, not by laying them out
     * again, and measuring only their layout left our start button and row where the keys came.
     */
    static int drawnLeftIn(ViewGroup dragLayer, View view) {
        float left = 0f;
        for (View v = view; v != null && v != dragLayer; ) {
            left += v.getLeft() + v.getTranslationX();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return Math.round(left);
    }

    /** ZUI's navigation keys on this bar, or null when it has none showing. */
    static View navKeys(ViewGroup dragLayer) {
        for (View view : Reflect.findByIdNames(dragLayer, "end_nav_buttons")) {
            if (view.getVisibility() == View.VISIBLE && view.getWidth() > 0) {
                return view;
            }
        }
        return null;
    }

    /**
     * The button itself: the Android robot (or ZUI's own icon, with the robot off), a toggle for
     * ZUI's drawer, and the robot's eyes wide while the drawer is open.
     *
     * <p>On a Retro bar it is Windows 98's: a raised box with the pixel robot and "Start" in
     * bold, sunken while pressed and while the drawer it opened is up, and nothing that moves.
     */
    static final class StartButton extends ImageView {
        private static final String LABEL = "Start";
        /** The label's size, as a share of the box's height. */
        private static final float LABEL_SCALE = 0.42f;

        private View mZui;
        private final AndroidRobot mRobot = new AndroidRobot();
        private boolean mRobotShown;
        private int mZuiIconWidth = -1;
        private Theme mTheme = Theme.GLASS;
        /** Retro's robot and label, made the first time the bar is Retro. */
        private Drawable mPixelRobot;
        private Paint mLabel;

        private final Runnable mWatchDrawer = new Runnable() {
            @Override
            public void run() {
                // Only while the drawer is up, and a few times a second: the eyes close with it.
                if (!isAttachedToWindow()) {
                    return;
                }
                if (drawerOpen(TaskbarTray.displayIdOf(StartButton.this))) {
                    postDelayed(this, 300L);
                } else {
                    drawerShown(false);
                }
            }
        };

        StartButton(Context ctx) {
            super(ctx);
            setScaleType(ScaleType.FIT_CENTER);
            setContentDescription("Start");
            setOnClickListener(v -> press());
            setOnLongClickListener(v -> mZui != null && mZui.performLongClick());
            View.OnTouchListener press = new TaskbarRunning.Press();
            setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                        && SAID_TOUCH.add(TaskbarScope.label(v))) {
                    // Once per bar: a touch that arrives and no press after it says the press
                    // is lost on the way; no touch at all says the bar never handed it over.
                    L.i("start button: touched on " + TaskbarScope.label(v));
                }
                return press.onTouch(v, e);
            });
            setOnHoverListener((v, e) -> {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_ENTER && !mTheme.retro()) {
                    Hover.enter(v);
                } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                    Hover.exit(v);
                }
                return false;
            });
        }

        boolean retro() {
            return mTheme.retro();
        }

        /** Takes ZUI's button: what a press presses, and the icon size to match. */
        void bind(View zui, Theme theme) {
            mZui = zui;
            boolean robot = Cfg.startButtonRobot();
            int iconWidth = zuiIconWidth(zui);
            if (robot == mRobotShown && iconWidth == mZuiIconWidth && theme == mTheme
                    && getDrawable() != null) {
                return;
            }
            mRobotShown = robot;
            mZuiIconWidth = iconWidth;
            mTheme = theme;
            if (theme.retro()) {
                bindRetro(zui, robot);
                return;
            }
            setBackground(null);
            Drawable icon = robot ? mRobot : copyOfZuiIcon(zui);
            setImageDrawable(icon != null ? icon : mRobot);
            // The same margin round the icon as ZUI's button keeps round its own.
            int inset = iconWidth > 0 && zui.getWidth() > iconWidth
                    ? (zui.getWidth() - iconWidth) / 2 : 0;
            setPadding(inset, inset, inset, inset);
        }

        private void bindRetro(View zui, boolean robot) {
            Context ctx = getContext();
            if (mPixelRobot == null) {
                mPixelRobot = PixelIcons.robot();
                mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
                mLabel.setColor(Theme.RETRO.text());
                mLabel.setTypeface(Theme.RETRO.font(ctx));
                // Windows 98's label is bold: the font's own bold where it has one.
                if (!mLabel.setFontVariationSettings("'wght' 700")) {
                    mLabel.setFakeBoldText(true);
                }
            }
            Drawable icon = robot ? mPixelRobot : copyOfZuiIcon(zui);
            setImageDrawable(icon != null ? icon : mPixelRobot);
            setBackground(Bevel.button(ctx));
            setScaleX(1f);
            setScaleY(1f);
            setRotation(0f);
            retroPadding(getWidth(), getHeight());
        }

        /** Retro's box at {@code height}: the robot's square, the label, and a margin. */
        int retroWidth(int height) {
            if (mLabel == null) {
                return height;
            }
            mLabel.setTextSize(height * LABEL_SCALE);
            return height + Math.round(mLabel.measureText(LABEL)) + 2 * retroInset(height);
        }

        private static int retroInset(int height) {
            return Math.max(1, height / 8);
        }

        /** The robot in a square at the left; the label is drawn in the room to its right. */
        private void retroPadding(int width, int height) {
            if (width <= 0 || height <= 0) {
                return;
            }
            int inset = retroInset(height);
            setPadding(inset, inset, Math.max(inset, width - height + inset), inset);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (mTheme.retro()) {
                retroPadding(w, h);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!mTheme.retro() || mLabel == null) {
                super.onDraw(canvas);
                return;
            }
            int height = getHeight();
            // Pressed in, the face moves a pixel down and right, as Windows 98's buttons do.
            boolean down = isPressed() || isActivated();
            int shift = down ? Math.max(1, Ui.dp(getContext(), 1)) : 0;
            canvas.save();
            canvas.translate(shift, shift);
            super.onDraw(canvas);
            mLabel.setTextSize(height * LABEL_SCALE);
            Paint.FontMetrics m = mLabel.getFontMetrics();
            float baseline = (height - m.ascent - m.descent) / 2f;
            canvas.drawText(LABEL, height, baseline, mLabel);
            canvas.restore();
        }

        /**
         * The drawer this button opened is up, or gone: the robot's eyes, and on a Retro bar the
         * box held in.
         */
        private void drawerShown(boolean open) {
            if (!mTheme.retro()) {
                mRobot.setWide(open);
            }
            setActivated(open);
        }

        private void press() {
            int display = TaskbarTray.displayIdOf(this);
            if (SAID_PRESS.add(TaskbarScope.label(this))) {
                // Once per bar - the tablet has two on one screen: proof the press reached it.
                L.i("start button: pressed on " + TaskbarScope.label(this) + ", ZUI's button "
                        + (mZui == null ? "not found" : mZui.getClass().getSimpleName()
                        + (mZui.hasOnClickListeners() ? " with" : " without")
                        + " a click listener"));
            }
            if (TaskbarBridge.isStockDrawerOpen(display)) {
                // A second press closes it, as a start button does.
                PRESSED.delete(display);
                if (TaskbarBridge.closeStockDrawer(display)) {
                    drawerShown(false);
                    L.i("start button: closed the drawer on display " + display);
                    return;
                }
            }
            if (mZui == null) {
                return;
            }
            PRESSED.put(display, SystemClock.uptimeMillis());
            drawerShown(true);
            removeCallbacks(mWatchDrawer);
            postDelayed(mWatchDrawer, 600L);
            View zui = mZui;
            if (mThroughController) {
                L.i("start button: " + openThroughController(zui) + " on display " + display);
                return;
            }
            boolean handled = zui.performClick();
            postDelayed(() -> {
                if (TaskbarBridge.isStockDrawerOpen(display)) {
                    L.i("start button: ZUI's button opened the drawer on display " + display);
                    return;
                }
                // ZUI's button did not bring its drawer up - on some of its bars the press is
                // handled elsewhere. Its drawer's own controller is asked instead, and on this
                // bar from now on, so later presses open at once.
                String how = openThroughController(zui);
                mThroughController = how.startsWith("opened");
                L.i("start button: ZUI's button " + (handled ? "took" : "ignored")
                        + " the press on display " + display + " and no drawer came up; "
                        + how);
            }, 450L);
        }

        /** This bar's button opens nothing; its drawer's controller is asked straight away. */
        private boolean mThroughController;

        /**
         * Opens ZUI's drawer through the taskbar's all-apps controller - a field whose name the
         * probe shows kept on this build - by the first of its usual entry points it has.
         */
        private static String openThroughController(View zui) {
            try {
                Object controllers = Reflect.field(zui.getContext(), "mControllers");
                Object allApps = controllers == null ? null
                        : Reflect.field(controllers, "taskbarAllAppsController");
                if (allApps == null) {
                    return "no all-apps controller found";
                }
                for (String name : new String[]{"toggle", "show", "open"}) {
                    for (Class<?> c = allApps.getClass(); c != null; c = c.getSuperclass()) {
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                                m.setAccessible(true);
                                m.invoke(allApps);
                                return "opened through " + allApps.getClass().getSimpleName()
                                        + "." + name + "()";
                            }
                        }
                    }
                }
                StringBuilder methods = new StringBuilder();
                for (java.lang.reflect.Method m : allApps.getClass().getDeclaredMethods()) {
                    methods.append(' ').append(m.getName()).append('(')
                            .append(m.getParameterCount()).append(')');
                }
                return "its controller has none of toggle/show/open:" + methods;
            } catch (Throwable t) {
                return "the controller would not open it (" + t + ")";
            }
        }

        /** How wide ZUI draws its own icon inside its button. */
        private static int zuiIconWidth(View zui) {
            if (zui instanceof android.widget.TextView) {
                for (Drawable d : ((android.widget.TextView) zui).getCompoundDrawables()) {
                    if (d != null && d.getBounds().width() > 0) {
                        return d.getBounds().width();
                    }
                }
            }
            return -1;
        }

        private static Drawable copyOfZuiIcon(View zui) {
            if (!(zui instanceof android.widget.TextView)) {
                return null;
            }
            for (Drawable d : ((android.widget.TextView) zui).getCompoundDrawables()) {
                if (d != null && d.getConstantState() != null) {
                    return d.getConstantState().newDrawable().mutate();
                }
            }
            return null;
        }
    }
}
