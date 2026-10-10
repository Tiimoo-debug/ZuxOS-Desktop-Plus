package com.zuxos.desktopplus.hook.taskbar;

import android.graphics.Insets;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.hook.Windows;

import java.lang.ref.WeakReference;
import java.util.Map;

/**
 * Where a taskbar is on its screen, and how much of the screen it covers.
 *
 * <p>Read off the bar itself, never off the setting: the edge from its window's gravity, which
 * is the side the window hugs, and the depth from its icon row on screen. So it is right for the
 * tablet's bars, for the monitor's at the bottom, and for the monitor's moved to the top
 * ({@link TaskbarEdge}) - and right during ZUI's moments with the window stretched over the
 * whole screen, when the window's own height says nothing about the bar.
 *
 * <p>Everything that opens beside the bar asks here: menus, panels, pop-ups, previews, recents,
 * the drawer, the desktop's menus and Maximize.
 */
public final class BarEdge {

    /** The bar's guessed depth when its row cannot be measured yet. */
    private static final int GUESS_DP = 56;

    /** Per display, the row last measured: asked on every frame of a drawer slide or a drag. */
    private static final Map<Integer, WeakReference<View>> ROWS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private BarEdge() {
    }

    /**
     * What the bar on this display takes from the screen: one side set - the bottom, or the top
     * for a bar moved there - to how far in it reaches. {@link Insets#NONE} with no bar showing.
     */
    public static Insets reserved(int display) {
        View row = rowOn(display);
        return row != null ? measure(row) : Insets.NONE;
    }

    /**
     * The same for the bar {@code source} is part of: a menu or panel anchored to it. When its row
     * cannot be measured yet, a guess on the bar's side rather than nothing.
     */
    public static Insets reserved(View source) {
        if (source == null) {
            return Insets.NONE;
        }
        View root = source.getRootView();
        View row = root instanceof ViewGroup ? TaskbarTray.rowReference((ViewGroup) root) : null;
        if (row != null && row.getHeight() > 0) {
            return measure(row);
        }
        int guess = source.getHeight() > 0 ? source.getHeight() : Ui.dp(source.getContext(),
                GUESS_DP);
        return onTop(root) ? Insets.of(0, guess, 0, 0) : Insets.of(0, 0, 0, guess);
    }

    /**
     * How much of the top and of the bottom of {@code view} the bar on its display covers: only
     * the part that overlaps it, so a window that already stops short of the bar gets nothing.
     */
    public static Insets over(View view) {
        try {
            if (view == null || view.getDisplay() == null) {
                return Insets.NONE;
            }
            Insets bar = reserved(view.getDisplay().getDisplayId());
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            android.graphics.Point size = new android.graphics.Point();
            view.getDisplay().getRealSize(size);
            int screen = Math.max(view.getResources().getDisplayMetrics().heightPixels, size.y);
            int bottom = Math.max(0, Math.min(bar.bottom,
                    at[1] + view.getHeight() - (screen - bar.bottom)));
            int top = Math.max(0, bar.top - at[1]);
            return Insets.of(0, top, 0, bottom);
        } catch (Throwable t) {
            return Insets.NONE;
        }
    }

    /**
     * Where a bar at the bottom of this display begins, in screen pixels: for a drawer, which
     * runs on under it, to stop at. -1 when the bar there is at the top, or none is showing.
     */
    public static int bottomBarTop(int display) {
        View row = rowOn(display);
        if (row == null || row.getHeight() <= 0 || onTop(row.getRootView())) {
            return -1;
        }
        int[] at = new int[2];
        row.getLocationOnScreen(at);
        return at[1];
    }

    /** From the screen's edge to the far side of the row, on the side the window hugs. */
    private static Insets measure(View row) {
        if (row.getHeight() <= 0 || !row.isShown()) {
            return Insets.NONE;
        }
        View window = row.getRootView();
        int[] rowAt = new int[2];
        row.getLocationOnScreen(rowAt);
        if (onTop(window)) {
            return Insets.of(0, Math.max(0, rowAt[1] + row.getHeight()), 0, 0);
        }
        // The window's bottom is the screen's: from there up to the top of the row.
        int[] windowAt = new int[2];
        window.getLocationOnScreen(windowAt);
        return Insets.of(0, 0, 0, Math.max(0, windowAt[1] + window.getHeight() - rowAt[1]));
    }

    /** Whether a bar's window - the one {@code view} is in - hugs the top of its screen. */
    static boolean onTop(View view) {
        ViewGroup.LayoutParams lp = view.getRootView().getLayoutParams();
        return lp instanceof WindowManager.LayoutParams
                && (((WindowManager.LayoutParams) lp).gravity & Gravity.VERTICAL_GRAVITY_MASK)
                == Gravity.TOP;
    }

    /** The shown bar's row on this display, remembered until it goes. */
    private static View rowOn(int display) {
        WeakReference<View> known = ROWS.get(display);
        View row = known != null ? known.get() : null;
        if (row != null && row.isAttachedToWindow() && row.isShown()) {
            return row;
        }
        // Every bar's window, not only the ones holding our tray: with the tray switched off
        // there were none, and nothing knew where the bar was.
        for (View root : Windows.roots()) {
            ViewGroup dragLayer = root.isAttachedToWindow() ? TaskbarTray.dragLayerOf(root) : null;
            if (dragLayer == null || TaskbarTray.displayIdOf(dragLayer) != display
                    || !dragLayer.isShown()) {
                continue;
            }
            row = TaskbarTray.rowReference(dragLayer);
            if (row != null) {
                ROWS.put(display, new WeakReference<>(row));
                return row;
            }
        }
        return null;
    }
}
