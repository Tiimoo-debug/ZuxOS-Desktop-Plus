package com.zuxos.desktopplus.hook;

import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.AndroidRobot;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

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
            toggles(button);
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
        AndroidRobot robot = ROBOTS.get(button);
        if (robot == null) {
            robot = new AndroidRobot();
            ROBOTS.put(button, robot);
        }
        robot.setBounds(icons[at].getBounds());
        android.graphics.drawable.Drawable[] next = icons.clone();
        next[at] = robot;
        text.setCompoundDrawables(next[0], next[1], next[2], next[3]);
        if (!sSaidRobot) {
            sSaidRobot = true;
            L.i("taskbar start: the Android robot is on the drawer button");
        }
    }

    private static final String BUTTON_CLASS =
            "com.android.launcher3.taskbar.customization.TaskbarAllAppsButtonContainer";
    /** The button's class, compared by identity: the hook below sees every text view. */
    private static Class<?> sButtonClass;
    /** One robot per button, so a re-set icon keeps its blink rather than starting over. */
    private static final Map<View, AndroidRobot> ROBOTS = new WeakHashMap<>();
    private static boolean sSaidGuard;

    /**
     * Keeps the robot on the button through ZUI setting its own icon again.
     *
     * <p>Rotating the tablet - any configuration change - makes ZUI rebuild the button's icon,
     * and its own showed for a moment until the next taskbar refresh put the robot back. Here the
     * icon is swapped as ZUI hands it over, before it is ever drawn.
     */
    static void guardIcon(ClassLoader loader) {
        sButtonClass = Reflect.findClass(BUTTON_CLASS, loader);
        if (sButtonClass == null) {
            L.d("taskbar start: no " + BUTTON_CLASS + " to guard");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.widget.TextView.class,
                    "setCompoundDrawables", new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object view = param.thisObject;
                            if (view == null || view.getClass() != sButtonClass
                                    || param.args.length != 4 || !Cfg.startButtonRobot()) {
                                return;
                            }
                            for (int i = 0; i < 4; i++) {
                                Object d = param.args[i];
                                if (d instanceof android.graphics.drawable.Drawable
                                        && !(d instanceof AndroidRobot)) {
                                    param.args[i] = robotFor((View) view, i,
                                            (android.graphics.drawable.Drawable) d);
                                    if (!sSaidGuard) {
                                        sSaidGuard = true;
                                        L.i("taskbar start: kept the robot on the button through"
                                                + " the launcher re-setting its icon");
                                    }
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            L.d("taskbar start: could not guard the button's icon (" + t + ")");
        }
    }

    /** The button's robot, at the bounds ZUI gave its own icon; ZUI's icon kept for later. */
    private static AndroidRobot robotFor(View button, int slot,
            android.graphics.drawable.Drawable zui) {
        android.graphics.drawable.Drawable[] original = new android.graphics.drawable.Drawable[4];
        original[slot] = zui;
        ORIGINAL_ICONS.put(button, original);
        AndroidRobot robot = ROBOTS.get(button);
        if (robot == null) {
            robot = new AndroidRobot();
            ROBOTS.put(button, robot);
        }
        robot.setBounds(zui.getBounds());
        return robot;
    }

    /**
     * Whether the drawer is open, per icon row, as last told by the row's own fade.
     *
     * <p>ZUI fades its row out as its drawer opens and back in as it closes - the one signal
     * there is, since the drawer itself is a window of the launcher's own making.
     */
    private static final Map<View, Boolean> DRAWER_OPEN = new WeakHashMap<>();

    /** Told by the row's fade: the drawer is opening ({@code open}) or closing. */
    static void drawerShowing(View row, boolean open) {
        if (!(row instanceof ViewGroup)) {
            return;
        }
        Boolean was = DRAWER_OPEN.put(row, open);
        if (was == null || was != open) {
            eyes((ViewGroup) row, open);
        }
    }

    /** The robot's eyes on this row's button, wide or not. */
    private static void eyes(ViewGroup row, boolean wide) {
        View button = allAppsButton(row);
        if (!(button instanceof android.widget.TextView)) {
            return;
        }
        for (android.graphics.drawable.Drawable d
                : ((android.widget.TextView) button).getCompoundDrawables()) {
            if (d instanceof AndroidRobot) {
                ((AndroidRobot) d).setWide(wide);
            }
        }
    }

    private static boolean sSaidToggle;

    /**
     * Makes the button a toggle: pressed with the drawer open, it closes it.
     *
     * <p>ZUI's click only ever opens the drawer. That never mattered while ZUI hid the button
     * with its row; kept on screen, a second press reopened the drawer instead of closing it,
     * which no start button does.
     */
    private static void toggles(View button) {
        Object info = Reflect.field(button, "mListenerInfo");
        Object current = info == null ? null : Reflect.field(info, "mOnClickListener");
        if (current instanceof StartClick || !(current instanceof View.OnClickListener)) {
            return;
        }
        button.setOnClickListener(new StartClick((View.OnClickListener) current));
    }

    private static final class StartClick implements View.OnClickListener {
        private final View.OnClickListener mOriginal;

        StartClick(View.OnClickListener original) {
            mOriginal = original;
        }

        @Override
        public void onClick(View v) {
            View row = v.getParent() instanceof View ? (View) v.getParent() : null;
            int display = TaskbarTray.displayIdOf(v);
            // Open only when both agree: the row's fade says so, and the drawer's window is
            // really there - a window kept around after closing must not swallow the press.
            boolean open = !Boolean.FALSE.equals(DRAWER_OPEN.get(row))
                    && TaskbarBridge.isStockDrawerOpen(display);
            if (open) {
                try {
                    if (TaskbarBridge.closeStockDrawer(display)) {
                        if (row != null) {
                            drawerShowing(row, false);
                        }
                        if (!sSaidToggle) {
                            sSaidToggle = true;
                            L.i("start button: closed the drawer, as a second press should");
                        }
                        return;
                    }
                } catch (Throwable t) {
                    L.d("start button: could not close the drawer (" + t + ")");
                }
            }
            if (row != null) {
                // At the press, not when the fade gets round to it: the eyes answer the finger.
                drawerShowing(row, true);
            }
            mOriginal.onClick(v);
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
        // Right against the keys, the way Start sits against the corner: the space belongs
        // between the button and the apps, not between the button and the keys. The recents
        // key's own padding already leaves a visible gap.
        int gap = 0;
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
