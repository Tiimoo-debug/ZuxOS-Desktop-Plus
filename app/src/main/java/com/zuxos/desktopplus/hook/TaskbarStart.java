package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.zuxos.desktopplus.core.AndroidRobot;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Hover;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

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

    private TaskbarStart() {
    }

    /** Puts our button on this bar, or ZUI's back, following the setting and the bar. */
    static void apply(ViewGroup dragLayer, ViewGroup icons) {
        try {
            View zui = allAppsButton(icons);
            if (!Cfg.startButtonLeft() || !TaskbarScope.ours(dragLayer)) {
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
            ours.bind(zui);
            place(dragLayer, ours, zui);
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

    /** Beside the navigation keys, centred on the bar's row, at ZUI's own icon size. */
    private static void place(ViewGroup dragLayer, StartButton button, View zui) {
        View reference = TaskbarTray.rowReference(dragLayer);
        if (reference == null || reference.getHeight() <= 0) {
            return;
        }
        int size = size(zui, dragLayer.getContext());
        int left = navEnd(dragLayer);
        int top = reference.getTop() + (reference.getHeight() - size) / 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
        int gravity = Gravity.TOP | Gravity.START;
        if (lp.gravity == gravity && lp.width == size && lp.height == size
                && lp.leftMargin == left && lp.topMargin == top) {
            // Placed from layout listeners: unchanged params must not ask for another pass.
            return;
        }
        lp.gravity = gravity;
        lp.width = size;
        lp.height = size;
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
        for (View view : Reflect.findByIdNames(dragLayer, "end_nav_buttons")) {
            if (view.getVisibility() == View.VISIBLE && view.getWidth() > 0) {
                int right = offsetIn(dragLayer, view) + view.getWidth();
                return right < dragLayer.getWidth() / 2 ? right : 0;
            }
        }
        return 0;
    }

    /**
     * Whether this child of ZUI's row is the drawer button we set invisible: anything measuring
     * where ZUI's icons end skips it.
     */
    static boolean isMoved(View child) {
        return HIDDEN.containsKey(child);
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
                return lp.leftMargin + lp.width;
            }
        }
        View zui = allAppsButton(icons);
        if (zui != null && zui.getVisibility() == View.VISIBLE && zui.getWidth() > 0) {
            return offsetIn(dragLayer, zui) + zui.getRight() - zui.getLeft();
        }
        for (View view : Reflect.findByIdNames(dragLayer, "end_nav_buttons")) {
            if (view.getVisibility() == View.VISIBLE && view.getWidth() > 0) {
                return offsetIn(dragLayer, view) + view.getWidth();
            }
        }
        return -1;
    }

    /**
     * Whether ZUI's drawer is open on this display, or about to be: pressed in the last moment
     * and still on its way up.
     */
    static boolean drawerOpen(int display) {
        long pressed = PRESSED.get(display, 0L);
        return (pressed > 0 && SystemClock.uptimeMillis() - pressed < 600L)
                || TaskbarBridge.isStockDrawerOpen(display);
    }

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
     * The button itself: the Android robot (or ZUI's own icon, with the robot off), a toggle for
     * ZUI's drawer, and the robot's eyes wide while the drawer is open.
     */
    static final class StartButton extends ImageView {
        private View mZui;
        private final AndroidRobot mRobot = new AndroidRobot();
        private boolean mRobotShown;
        private int mZuiIconWidth = -1;

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
                    mRobot.setWide(false);
                }
            }
        };

        StartButton(Context ctx) {
            super(ctx);
            setScaleType(ScaleType.FIT_CENTER);
            setContentDescription("Start");
            setOnClickListener(v -> press());
            setOnLongClickListener(v -> mZui != null && mZui.performLongClick());
            setOnTouchListener(new TaskbarRunning.Press());
            setOnHoverListener((v, e) -> {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_ENTER) {
                    Hover.enter(v);
                } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                    Hover.exit(v);
                }
                return false;
            });
        }

        /** Takes ZUI's button: what a press presses, and the icon size to match. */
        void bind(View zui) {
            mZui = zui;
            boolean robot = Cfg.startButtonRobot();
            int iconWidth = zuiIconWidth(zui);
            if (robot == mRobotShown && iconWidth == mZuiIconWidth && getDrawable() != null) {
                return;
            }
            mRobotShown = robot;
            mZuiIconWidth = iconWidth;
            Drawable icon = robot ? mRobot : copyOfZuiIcon(zui);
            setImageDrawable(icon != null ? icon : mRobot);
            // The same margin round the icon as ZUI's button keeps round its own.
            int inset = iconWidth > 0 && zui.getWidth() > iconWidth
                    ? (zui.getWidth() - iconWidth) / 2 : 0;
            setPadding(inset, inset, inset, inset);
        }

        private void press() {
            int display = TaskbarTray.displayIdOf(this);
            if (TaskbarBridge.isStockDrawerOpen(display)) {
                // A second press closes it, as a start button does.
                PRESSED.delete(display);
                if (TaskbarBridge.closeStockDrawer(display)) {
                    mRobot.setWide(false);
                    return;
                }
            }
            if (mZui == null) {
                return;
            }
            PRESSED.put(display, SystemClock.uptimeMillis());
            mRobot.setWide(true);
            removeCallbacks(mWatchDrawer);
            postDelayed(mWatchDrawer, 600L);
            mZui.performClick();
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
