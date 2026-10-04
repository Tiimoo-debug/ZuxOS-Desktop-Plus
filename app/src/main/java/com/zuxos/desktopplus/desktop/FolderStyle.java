package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Motion;
import com.zuxos.desktopplus.core.Ui;

/**
 * How an open folder looks and moves - the same on the home screen, in the drawer and on the
 * taskbar, and modelled on the folders ZUI opens from its own taskbar.
 *
 * <p>Those are compact: a panel just big enough for its icons, the name small in the top-left
 * corner, and the whole thing grows out of the icon you tapped and shrinks back into it. Ours
 * were a large fixed panel with a big heading that simply appeared. ZUI's own folder view cannot
 * be borrowed - it needs the launcher's activity and its model objects behind every icon - so
 * this matches it instead, at the icon size set in the module.
 */
public final class FolderStyle {

    /** Corner radius of the panel. */
    public static final float RADIUS_DP = 24;
    /** Space between the panel's edge and what is in it. */
    public static final float PADDING_DP = 16;
    /** What a cell adds to the icon's width for its label. */
    public static final float CELL_EXTRA_DP = 28;
    /** Behind the panel: enough to set it apart, not enough to black the screen out. */
    public static final int SCRIM = 0x40000000;

    private static final float FROM_SCALE = 0.25f;

    private FolderStyle() {
    }

    /** Columns for this many icons: a square-ish block, never wider than four. */
    public static int columns(int count) {
        if (count <= 1) {
            return 1;
        }
        return Math.min(4, (int) Math.ceil(Math.sqrt(count)));
    }

    public static int cellWidth(Context ctx, int iconSizePx) {
        return iconSizePx + Ui.dp(ctx, CELL_EXTRA_DP);
    }

    /** The folder's name: small, top-left, a little dimmer than the labels under it. */
    public static void styleTitle(TextView title) {
        title.setTextSize(14);
        title.setTextColor(Ui.COLOR_TEXT_DIM);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        title.setPadding(Ui.dp(title.getContext(), 4), 0, 0, 0);
    }

    /**
     * Grows the panel out of the icon that was tapped.
     *
     * <p>The pivot is the icon's centre in the panel's own coordinates, which may well be outside
     * the panel - that is the point: scaling about a point outside it is what makes the panel
     * travel from the icon rather than swell where it stands. With no icon to come from it grows
     * from its own centre. Measured while the panel is at rest, so its position on screen is its
     * laid-out one.
     */
    public static void zoomIn(View panel, View source) {
        panel.animate().cancel();
        panel.setVisibility(View.VISIBLE);
        if (!Cfg.animations()) {
            reset(panel);
            return;
        }
        panel.setAlpha(0f);
        panel.post(() -> {
            pivotAt(panel, source);
            panel.setScaleX(FROM_SCALE);
            panel.setScaleY(FROM_SCALE);
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS)
                    .setInterpolator(Motion.SPRING)
                    .withEndAction(() -> {
                        // The glass waits for this: captured mid-zoom it is sized and placed for
                        // a panel a quarter of its size.
                        if (panel instanceof GlassPanel) {
                            ((GlassPanel) panel).refresh();
                        }
                    }).start();
        });
    }

    /** Shrinks it back into the icon it came from, then runs {@code onEnd}. */
    public static void zoomOut(View panel, View source, Runnable onEnd) {
        panel.animate().cancel();
        if (!Cfg.animations() || panel.getWidth() == 0) {
            reset(panel);
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        pivotAt(panel, source);
        panel.animate().alpha(0f).scaleX(FROM_SCALE).scaleY(FROM_SCALE).setDuration(Motion.SHORT + 40)
                .setInterpolator(Motion.EXIT)
                .withEndAction(() -> {
                    reset(panel);
                    if (onEnd != null) {
                        onEnd.run();
                    }
                }).start();
    }

    private static void reset(View panel) {
        panel.setAlpha(1f);
        panel.setScaleX(1f);
        panel.setScaleY(1f);
    }

    private static void pivotAt(View panel, View source) {
        if (source == null || !source.isAttachedToWindow() || panel.getWidth() == 0) {
            panel.setPivotX(panel.getWidth() / 2f);
            panel.setPivotY(panel.getHeight() / 2f);
            return;
        }
        int[] from = new int[2];
        int[] at = new int[2];
        source.getLocationOnScreen(from);
        panel.getLocationOnScreen(at);
        panel.setPivotX(from[0] + source.getWidth() / 2f - at[0]);
        panel.setPivotY(from[1] + source.getHeight() / 2f - at[1]);
    }
}
