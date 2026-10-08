package com.zuxos.desktopplus.hook.shots;

import android.content.Context;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.MenuRows;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.glass.GlassSurface;
import com.zuxos.desktopplus.core.glass.LiquidGlass;
import com.zuxos.desktopplus.core.motion.FrameRate;
import com.zuxos.desktopplus.core.motion.Hover;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.taskbar.TaskbarRunning;

import java.util.List;

/**
 * The apps a screenshot can go to, as an iOS share sheet: a grid of icons on liquid glass that
 * springs up from the button that asked for it.
 *
 * <p>Android's own chooser was a white sheet on this screen, and launched what was picked as a
 * floating window - which ZUI's photo editor refuses, closing at once with "not support split
 * screen". Here the pick comes back to the caller, which launches it the way it needs.
 */
final class ShotTargets {

    interface Pick {
        void picked(ResolveInfo target);
    }

    private static final int COLUMNS = 4;
    private static final int TILE_DP = 84;
    private static final int ICON_DP = 48;
    /** Past this many rows the grid scrolls. */
    private static final float ROWS_SHOWN = 3.4f;

    private static View sRoot;
    private static WindowManager sWm;
    private static String sKind;
    private static long sClosedAt;
    private static String sClosedKind;

    private ShotTargets() {
    }

    static boolean showing() {
        return sRoot != null;
    }

    /** Its window's root while it is up, else null - for a screenshot to leave out. */
    static View current() {
        return sRoot;
    }

    /**
     * Opens the sheet above {@code card}, rising from {@code from}. A second press of the same
     * button closes it instead - the press arrives just after the tap outside that closed it.
     */
    static void show(Context ctx, WindowManager wm, View card, View from, String title,
            String kind, List<ResolveInfo> targets, Pick pick, Runnable closed) {
        if (sRoot != null) {
            boolean same = kind.equals(sKind);
            dismiss();
            if (same) {
                return;
            }
        } else if (kind.equals(sClosedKind)
                && android.os.SystemClock.uptimeMillis() - sClosedAt < 300L) {
            return;
        }
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        int pad = Ui.dp(ctx, 12);
        int tile = Ui.dp(ctx, TILE_DP);

        GlassSurface pane = new GlassSurface(ctx, Ui.dp(ctx, 22), 0x401C1C22, LiquidGlass.MENU);
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);
        pane.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(ctx);
        heading.setText(title);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setTextColor(0xB3FFFFFF);
        heading.setPadding(Ui.dp(ctx, 6), 0, 0, Ui.dp(ctx, 8));
        column.addView(heading);

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < targets.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            ResolveInfo target = targets.get(i);
            row.addView(tile(ctx, pm, target, () -> {
                dismiss();
                pick.picked(target);
            }), new LinearLayout.LayoutParams(tile, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        int rows = (targets.size() + COLUMNS - 1) / COLUMNS;
        int maxHeight = Math.round(Ui.dp(ctx, 100) * Math.min(rows, ROWS_SHOWN));
        ScrollView scroll = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec,
                        MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(grid);
        column.addView(scroll, new LinearLayout.LayoutParams(tile * COLUMNS,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout root = new FrameLayout(ctx);
        root.addView(pane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });
        root.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        // Above the card, its left edge on the card's; never off the top of the screen.
        int[] at = new int[2];
        card.getLocationOnScreen(at);
        int gap = Ui.dp(ctx, 10);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0);
        lp.x = at[0];
        lp.y = Math.max(Ui.dp(ctx, 16), at[1] - root.getMeasuredHeight() - gap);
        lp.setTitle("ZuxOS Desktop Plus screenshot " + kind);
        FrameRate.forWindow(lp, wm.getDefaultDisplay());
        FrameRate.forView(root);
        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            L.d("screenshot sheet: could not show (" + t + ")");
            return;
        }
        sRoot = root;
        sWm = wm;
        sKind = kind;
        sOnClosed = closed;

        // Springing up out of the button that asked for it.
        int[] button = new int[2];
        from.getLocationOnScreen(button);
        pane.setPivotX(button[0] + from.getWidth() / 2f - lp.x);
        pane.setPivotY(root.getMeasuredHeight());
        pane.setAlpha(0f);
        pane.setScaleX(0.92f);
        pane.setScaleY(0.92f);
        pane.setTranslationY(Ui.dp(ctx, 12));
        pane.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(Motion.IOS_MS).setInterpolator(Motion.IOS).withLayer().start();
    }

    private static Runnable sOnClosed;

    private static View tile(Context ctx, android.content.pm.PackageManager pm,
            ResolveInfo target, Runnable onClick) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(ctx, 6);
        tile.setPadding(pad, pad, pad, pad);
        tile.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 14)));

        ImageView icon = new ImageView(ctx);
        try {
            icon.setImageDrawable(target.loadIcon(pm));
        } catch (Throwable ignored) {
            // A tile with its name alone.
        }
        int size = Ui.dp(ctx, ICON_DP);
        tile.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView label = new TextView(ctx);
        CharSequence name;
        try {
            name = target.loadLabel(pm);
        } catch (Throwable t) {
            name = target.activityInfo.packageName;
        }
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTextColor(0xF2FFFFFF);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(ctx, 4);
        tile.addView(label, llp);

        tile.setContentDescription(name);
        tile.setOnClickListener(v -> onClick.run());
        tile.setOnTouchListener(new TaskbarRunning.Press());
        tile.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) {
                Hover.enter(icon);
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                Hover.exit(icon);
            }
            return false;
        });
        return tile;
    }

    /** Fades back down into the button. */
    static void dismiss() {
        View root = sRoot;
        WindowManager wm = sWm;
        Runnable closed = sOnClosed;
        sClosedKind = sKind;
        sClosedAt = android.os.SystemClock.uptimeMillis();
        sRoot = null;
        sWm = null;
        sKind = null;
        sOnClosed = null;
        if (root == null || wm == null) {
            return;
        }
        MenuRows.close(root, () -> {
            try {
                wm.removeViewImmediate(root);
            } catch (Throwable ignored) {
                // Already gone.
            }
        });
        if (closed != null) {
            closed.run();
        }
    }
}
