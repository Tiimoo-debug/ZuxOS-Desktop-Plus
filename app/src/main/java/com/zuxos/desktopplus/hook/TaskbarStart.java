package com.zuxos.desktopplus.hook;

import android.view.View;
import android.view.ViewGroup;

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
 * <p>Moved with {@code translationX}, not by re-parenting or re-laying-out: the button stays a
 * child of {@code TaskbarView} exactly where ZUI put it, so nothing of the launcher's idea of its
 * own row changes, and taking the translation off puts it back. A translated view is drawn and
 * touched where it appears, so it works where it is seen.
 */
final class TaskbarStart {

    /** ZUI's buttons we have moved, so the setting going off can put them back. */
    private static final Map<View, Boolean> MOVED = new WeakHashMap<>();

    private TaskbarStart() {
    }

    /** Moves the launcher's button to the left of the bar, or back where ZUI put it. */
    static void apply(ViewGroup dragLayer, ViewGroup icons) {
        try {
            View button = allAppsButton(icons);
            if (button == null) {
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
     * Whether this child of the icon row is the button we moved away.
     *
     * <p>Its laid-out slot is still at the end of the cluster, empty now, and anything measuring
     * where the launcher's icons end has to skip it or it measures to a hole.
     */
    static boolean isMoved(View child) {
        return MOVED.containsKey(child) && child.getTranslationX() != 0f;
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
