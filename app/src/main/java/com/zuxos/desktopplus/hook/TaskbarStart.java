package com.zuxos.desktopplus.hook;

import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.AndroidRobot;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The launcher's drawer button, moved to the left end of the bar where a desktop keeps it.
 *
 * <p>ZUI lays its all-apps button out at the right-hand end of the centred icon cluster, which
 * leaves it floating in the middle of the bar once open apps sit either side of it. This moves
 * <em>that</em> button - ZUI's own view, its own icon, its own click and animation - to just right
 * of the navigation keys. Nothing is hidden and nothing is drawn in its place.
 *
 * <p>Moved by layout: straight after {@code TaskbarView} lays its children out, the button is laid
 * out again at the left (see {@link #relayout}, called from the {@code onLayout} hook in
 * {@link TaskbarRebind}). It used to be moved with {@code translationX}, but Launcher3 animates its
 * taskbar icons through a translation delegate of its own that rewrites {@code translationX} on
 * every icon whenever an animation ticks - which put the button back in the middle several times
 * a second, and the row measured from it jumped with it. Translation is left to the launcher now;
 * the position is ours, and holds until the row lays out again, when it is set again.
 *
 * <p>Only if that hook cannot be installed does it fall back to the translation.
 */
final class TaskbarStart {

    /** ZUI's buttons we have moved, so the setting going off can put them back. */
    private static final Map<View, Boolean> MOVED = new WeakHashMap<>();

    /** Whether {@code TaskbarView.onLayout} is hooked, so the button can be moved by layout. */
    static volatile boolean sLayoutHooked;
    private static boolean sSaidMoved;

    private TaskbarStart() {
    }

    /** Moves the launcher's button to the left of the bar, or back where ZUI put it. */
    static void apply(ViewGroup dragLayer, ViewGroup icons) {
        try {
            View button = allAppsButton(icons);
            if (button == null) {
                return;
            }
            robot(button);
            if (sLayoutHooked) {
                // The move itself happens in relayout(); all this does is ask for a layout when
                // the button is not where it should be, or no longer should be.
                if (!Cfg.startButtonLeft()) {
                    if (MOVED.remove(button) != null) {
                        icons.requestLayout();
                    }
                    return;
                }
                if (button.getWidth() > 0 && icons.getWidth() > 0) {
                    int target = targetLeft(dragLayer, icons);
                    if (target >= 0 && button.getLeft() != target) {
                        icons.requestLayout();
                    }
                }
                return;
            }
            if (!Cfg.startButtonLeft()) {
                if (MOVED.remove(button) != null) {
                    button.setTranslationX(0f);
                }
                return;
            }
            if (button.getWidth() <= 0 || icons.getWidth() <= 0) {
                // Not laid out yet; the layout listener in TaskbarRunning brings us back.
                return;
            }
            int target = targetLeft(dragLayer, icons);
            if (target < 0) {
                return;
            }
            float shift = target - button.getLeft();
            if (button.getTranslationX() != shift) {
                boolean first = !MOVED.containsKey(button);
                MOVED.put(button, Boolean.TRUE);
                button.setTranslationX(shift);
                if (first) {
                    L.i("taskbar start: moved the launcher's drawer button by " + (int) shift
                            + "px, to x=" + target + " beside the navigation keys");
                }
            }
        } catch (Throwable t) {
            L.d("taskbar start: not moved (" + t + ")");
        }
    }

    /**
     * Lays the button out at the left, right after {@code TaskbarView} has laid out its row.
     *
     * <p>Called from inside the layout pass, so it only calls {@code layout()} on the one child -
     * nothing that would ask for another pass.
     */
    static void relayout(ViewGroup icons) {
        try {
            if (!Cfg.startButtonLeft()) {
                return;
            }
            View button = allAppsButton(icons);
            View root = icons.getRootView();
            if (button == null || button.getWidth() <= 0 || !(root instanceof ViewGroup)) {
                return;
            }
            int target = targetLeft((ViewGroup) root, icons);
            if (target < 0 || button.getLeft() == target) {
                return;
            }
            button.layout(target, button.getTop(), target + button.getWidth(),
                    button.getBottom());
            MOVED.put(button, Boolean.TRUE);
            if (!sSaidMoved) {
                sSaidMoved = true;
                L.i("taskbar start: the drawer button is laid out at x=" + target
                        + " in the row, beside the navigation keys");
            }
        } catch (Throwable t) {
            L.d("taskbar start: not laid out (" + t + ")");
        }
    }

    /** The button's own icons, kept so the setting going off can give them back. */
    private static final Map<View, android.graphics.drawable.Drawable[]> ORIGINAL_ICONS =
            new WeakHashMap<>();
    private static boolean sSaidRobot;

    /**
     * Puts the Android robot on the button, or ZUI's own icon back.
     *
     * <p>The button is a {@code BubbleTextView}, which draws its icon as a compound drawable of
     * the text view it is - so the icon is swapped there, at the bounds ZUI gave its own. Checked
     * on every refresh: ZUI sets its icon again when the theme or the bar changes, and a robot
     * that quietly turned back into ZUI's icon would look like a bug.
     */
    private static void robot(View button) {
        if (!(button instanceof android.widget.TextView)) {
            if (!sSaidRobot) {
                sSaidRobot = true;
                L.i("taskbar start: the drawer button is a " + button.getClass().getName()
                        + ", which has no icon to swap");
            }
            return;
        }
        android.widget.TextView text = (android.widget.TextView) button;
        android.graphics.drawable.Drawable[] icons = text.getCompoundDrawables();
        int at = -1;
        for (int i = 0; i < icons.length; i++) {
            if (icons[i] != null) {
                at = i;
                break;
            }
        }
        boolean showing = at >= 0 && icons[at] instanceof AndroidRobot;
        if (!Cfg.startButtonRobot()) {
            android.graphics.drawable.Drawable[] original = ORIGINAL_ICONS.remove(button);
            if (showing && original != null) {
                text.setCompoundDrawables(original[0], original[1], original[2], original[3]);
            }
            return;
        }
        if (showing) {
            return;
        }
        if (at < 0) {
            if (!sSaidRobot) {
                sSaidRobot = true;
                L.i("taskbar start: the drawer button draws its icon some other way; the robot "
                        + "cannot go on it");
            }
            return;
        }
        ORIGINAL_ICONS.put(button, icons.clone());
        AndroidRobot robot = new AndroidRobot();
        robot.setBounds(icons[at].getBounds());
        android.graphics.drawable.Drawable[] next = icons.clone();
        next[at] = robot;
        text.setCompoundDrawables(next[0], next[1], next[2], next[3]);
        if (!sSaidRobot) {
            sSaidRobot = true;
            L.i("taskbar start: the Android robot is on the drawer button");
        }
    }

    /**
     * Whether this child of the icon row is the button we moved away.
     *
     * <p>Its laid-out slot is still at the end of the cluster, empty now, and anything measuring
     * where the launcher's icons end has to skip it or it measures to a hole.
     */
    static boolean isMoved(View child) {
        return MOVED.containsKey(child) && (sLayoutHooked || child.getTranslationX() != 0f);
    }

    /**
     * Where the drawer button ends on the bar, in the drag layer's coordinates - where it is
     * drawn, translation included. Falls back to the end of the navigation keys when there is no
     * button to be seen; -1 when neither can be measured yet.
     */
    static int rightEdge(ViewGroup dragLayer, ViewGroup icons) {
        View button = allAppsButton(icons);
        if (Cfg.startButtonLeft() && button != null && icons.getWidth() > 0) {
            // Where the button belongs, not where it happens to be drawn this frame: scrolling,
            // the launcher's animations and its own visibility changes all move it about, and a
            // row measured from it jumped every time they did. Its slot does not move.
            int target = targetLeft(dragLayer, icons);
            if (target >= 0) {
                int width = button.getWidth() > 0 ? button.getWidth() : 60;
                return offsetIn(dragLayer, icons) + target + width;
            }
        }
        if (button != null && button.getVisibility() == View.VISIBLE && button.getWidth() > 0) {
            // Where it is laid out. Under the layout route the translation is the launcher's own
            // and comes and goes with its animations; following it is how the row learned to jump.
            int shift = sLayoutHooked ? 0 : Math.round(button.getTranslationX());
            return offsetIn(dragLayer, icons) + button.getRight() + shift;
        }
        for (View view : Reflect.findByIdNames(dragLayer, "end_nav_buttons")) {
            if (view.getVisibility() == View.VISIBLE && view.getWidth() > 0) {
                return offsetIn(dragLayer, view) + view.getWidth();
            }
        }
        return -1;
    }

    /** The launcher's own all-apps button, by the name its class carries on every build. */
    private static View allAppsButton(ViewGroup icons) {
        if (icons == null) {
            return null;
        }
        for (View view : Reflect.findByClassFragments(icons, "AllAppsButton")) {
            // The container, not the icon inside it: it is the row's own child, and only a
            // child of the row has a left edge in the row's coordinates.
            if (view.getParent() == icons) {
                return view;
            }
        }
        return null;
    }

    /**
     * Where the button should start, in the icon row's coordinates.
     *
     * <p>Measured against {@code end_nav_buttons} itself and nothing broader. The previous build
     * also accepted {@code navbuttons_view}, which on this bar is the full 2560px wide - and so
     * put the button at x=2576, off the edge of the screen.
     */
    private static int targetLeft(ViewGroup dragLayer, ViewGroup icons) {
        int gap = Ui.dp(dragLayer.getContext(), 16);
        int navEnd = -1;
        for (View view : Reflect.findByIdNames(dragLayer, "end_nav_buttons")) {
            if (view.getVisibility() == View.VISIBLE && view.getWidth() > 0) {
                int right = offsetIn(dragLayer, view) + view.getWidth();
                // Keys on the right-hand side (the tablet's own bar) say nothing about where the
                // left end of the bar is.
                if (right < dragLayer.getWidth() / 2) {
                    navEnd = right;
                }
                break;
            }
        }
        int left = (navEnd >= 0 ? navEnd : 0) + gap;
        return left - offsetIn(dragLayer, icons);
    }

    private static int offsetIn(ViewGroup dragLayer, View view) {
        int left = 0;
        for (View v = view; v != null && v != dragLayer; ) {
            left += v.getLeft();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return left;
    }
}
