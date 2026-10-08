package com.zuxos.desktopplus.hook.drawer;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rearranging ZUI's own app drawer by dragging an app within it, the way iOS rearranges a page.
 *
 * <p>While a held app moves over the drawer, the icons between where it was and where it is now
 * slide over by one place on a spring, opening the gap it will drop into; held near the top or
 * the bottom of the grid, the grid scrolls. Let go over the grid and the new order is kept - the
 * same order the module's own drawer uses - and ZUI's list re-sorts from where the icons are
 * drawn, so nothing jumps. Dragged out of the drawer's panel, or onto the taskbar, the drawer
 * gets out of the way so the app can be dropped on the desktop or the bar, as before.
 *
 * <p>Only the drawer's views and their geometry are used: the grid's own classes are minified on
 * this firmware, and none of their methods can be counted on by name.
 */
final class DrawerReorder {

    /** How close to the grid's top or bottom edge the drag has to be to scroll it. */
    private static final int EDGE_DP = 56;
    private static final int SCROLL_STEP_DP = 14;

    private final ViewGroup mRoot;
    private final ViewGroup mGrid;
    private final View mSheet;
    private final String mKey;
    private final int mDisplay;
    private List<String> mOrder;
    private int mFrom;
    private int mTo;
    /** Where each icon was last sent, so an unchanged target sends nothing again. */
    private final Map<View, float[]> mSent = new HashMap<>();

    private DrawerReorder(ViewGroup root, ViewGroup grid, View sheet, String key, int display) {
        mRoot = root;
        mGrid = grid;
        mSheet = sheet;
        mKey = key;
        mDisplay = display;
        mOrder = NativeDrawerHooks.shownOrder();
        mFrom = mOrder.indexOf(key);
        mTo = mFrom;
    }

    /** For a drag picked up from {@code icon} in the stock drawer; null when it cannot be done. */
    static DrawerReorder begin(View icon) {
        String key = NativeDrawerHooks.keyOfIcon(icon);
        if (key == null || !(icon.getParent() instanceof ViewGroup)
                || !(icon.getRootView() instanceof ViewGroup)) {
            return null;
        }
        ViewGroup grid = (ViewGroup) icon.getParent();
        ViewGroup root = (ViewGroup) icon.getRootView();
        List<View> sheets = Reflect.findByIdNames(root, "bottom_sheet_background");
        DrawerReorder reorder = new DrawerReorder(root, grid,
                sheets.isEmpty() ? grid : sheets.get(0), key, TaskbarTray.displayIdOf(icon));
        return reorder.mFrom >= 0 ? reorder : null;
    }

    /**
     * Whether a point of the drawer's window is over the drawer itself: inside its panel, and
     * above the taskbar - the panel runs on under the bar, and the bar is somewhere to drop.
     */
    boolean over(float x, float y) {
        Rect panel = onScreen(mSheet);
        int barTop = TaskbarTray.barTopOnScreen(mDisplay);
        if (barTop > 0) {
            panel.bottom = Math.min(panel.bottom, barTop);
        }
        int[] root = new int[2];
        mRoot.getLocationOnScreen(root);
        return panel.contains(Math.round(x) + root[0], Math.round(y) + root[1]);
    }

    /** The drag is at ({@code x}, {@code y}) of the drawer's window, over the drawer. */
    void moveTo(float x, float y) {
        Rect grid = onScreen(mGrid);
        int[] root = new int[2];
        mRoot.getLocationOnScreen(root);
        float sx = x + root[0];
        float sy = y + root[1];
        int edge = Ui.dp(mGrid.getContext(), EDGE_DP);
        int step = Ui.dp(mGrid.getContext(), SCROLL_STEP_DP);
        if (sy < grid.top + edge) {
            mGrid.scrollBy(0, -step);
        } else if (sy > grid.bottom - edge) {
            mGrid.scrollBy(0, step);
        }
        View under = iconAt(sx - grid.left, sy - grid.top);
        if (under != null) {
            String key = NativeDrawerHooks.keyOfIcon(under);
            int at = key == null ? -1 : mOrder.indexOf(key);
            if (at >= 0) {
                mTo = at;
            }
        }
        preview();
    }

    /** Back to how the drawer was, for a drag that left the drawer. */
    void cancel() {
        mTo = mFrom;
        preview();
    }

    /**
     * Let go over the drawer: the order kept and the drawer re-sorted. The icons are left where
     * the preview drew them, and from there they settle into their new places.
     */
    void drop() {
        if (mTo != mFrom && mFrom >= 0) {
            NativeDrawerHooks.moveInDrawer(mKey, mTo);
        }
        // On the next frame, once the grid has laid out the new order: anything still off its
        // place springs home, and the dropped app appears where it landed.
        mGrid.post(() -> settle(mGrid));
    }

    /** Opens the gap at the target: every icon between the two places moves over by one. */
    private void preview() {
        List<View> icons = visibleIcons();
        if (icons.size() < 2) {
            return;
        }
        Map<Integer, View> byIndex = new HashMap<>();
        for (View icon : icons) {
            String key = NativeDrawerHooks.keyOfIcon(icon);
            int index = key == null ? -1 : mOrder.indexOf(key);
            if (index >= 0) {
                byIndex.put(index, icon);
            }
        }
        Geometry grid = Geometry.of(icons);
        for (Map.Entry<Integer, View> entry : byIndex.entrySet()) {
            int index = entry.getKey();
            View icon = entry.getValue();
            if (index == mFrom) {
                // Its place is the gap now; the drag shadow is the app.
                icon.setAlpha(0f);
                send(icon, 0f, 0f);
                continue;
            }
            icon.setAlpha(1f);
            int place = index;
            if (mFrom < mTo && index > mFrom && index <= mTo) {
                place = index - 1;
            } else if (mTo < mFrom && index >= mTo && index < mFrom) {
                place = index + 1;
            }
            if (place == index) {
                send(icon, 0f, 0f);
                continue;
            }
            View there = byIndex.get(place);
            float[] cell = there != null
                    ? new float[]{there.getLeft(), there.getTop()}
                    : grid.next(icon, place < index);
            send(icon, cell[0] - icon.getLeft(), cell[1] - icon.getTop());
        }
    }

    private void send(View icon, float dx, float dy) {
        float[] last = mSent.get(icon);
        if (last != null && last[0] == dx && last[1] == dy) {
            return;
        }
        mSent.put(icon, new float[]{dx, dy});
        icon.animate().translationX(dx).translationY(dy).setDuration(Motion.SPRING_MS)
                .setInterpolator(Motion.SPRING_FIRM).start();
    }

    /** Every icon home and showing, on a spring from wherever it is. */
    private static void settle(ViewGroup grid) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            if (child.getTranslationX() != 0f || child.getTranslationY() != 0f) {
                child.animate().translationX(0f).translationY(0f).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING_FIRM).start();
            }
            if (child.getAlpha() < 1f) {
                child.setScaleX(0.8f);
                child.setScaleY(0.8f);
                child.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING).start();
            }
        }
    }

    /** The grid's app icons on screen, in reading order. */
    private List<View> visibleIcons() {
        List<View> icons = new ArrayList<>();
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            View child = mGrid.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0
                    && NativeDrawerHooks.keyOfIcon(child) != null) {
                icons.add(child);
            }
        }
        Collections.sort(icons, (a, b) -> a.getTop() != b.getTop()
                ? Integer.compare(a.getTop(), b.getTop())
                : Integer.compare(a.getLeft(), b.getLeft()));
        return icons;
    }

    /** The icon whose laid-out cell holds this point of the grid, or null. */
    private View iconAt(float x, float y) {
        for (View icon : visibleIcons()) {
            if (x >= icon.getLeft() && x < icon.getRight()
                    && y >= icon.getTop() && y < icon.getBottom()) {
                return icon;
            }
        }
        return null;
    }

    private static Rect onScreen(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /** The grid's columns and row height, for a cell whose icon is scrolled out of sight. */
    private static final class Geometry {
        final List<Integer> lefts = new ArrayList<>();
        int rowHeight;

        static Geometry of(List<View> icons) {
            Geometry g = new Geometry();
            int firstTop = icons.get(0).getTop();
            for (View icon : icons) {
                if (icon.getTop() == firstTop) {
                    g.lefts.add(icon.getLeft());
                } else if (g.rowHeight == 0) {
                    g.rowHeight = icon.getTop() - firstTop;
                }
            }
            if (g.rowHeight == 0) {
                g.rowHeight = icons.get(0).getHeight();
            }
            return g;
        }

        /** The cell one place on from {@code icon}, forward or back, wrapping at the row ends. */
        float[] next(View icon, boolean back) {
            int column = lefts.indexOf(icon.getLeft());
            if (column < 0) {
                return new float[]{icon.getLeft(), icon.getTop()};
            }
            if (back) {
                return column == 0
                        ? new float[]{lefts.get(lefts.size() - 1), icon.getTop() - rowHeight}
                        : new float[]{lefts.get(column - 1), icon.getTop()};
            }
            return column == lefts.size() - 1
                    ? new float[]{lefts.get(0), icon.getTop() + rowHeight}
                    : new float[]{lefts.get(column + 1), icon.getTop()};
        }
    }
}
