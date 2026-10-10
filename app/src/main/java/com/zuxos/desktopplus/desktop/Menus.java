package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.MenuRows;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.Glyphs;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.hook.taskbar.BarEdge;

import java.util.ArrayList;
import java.util.List;

/**
 * Context menus anchored at an arbitrary point.
 *
 * <p>Desktop mode is mouse-driven, so menus have to appear where the pointer is. They are drawn
 * as views inside the host - the same glass pane, rows and icons as the taskbar's menu, from
 * {@link MenuRows} - rather than as the system's popup, which could be neither glass nor shown
 * from inside the module's own overlay windows.
 */
public final class Menus {

    public static final class Entry {
        final String title;
        final Runnable action;
        boolean enabled = true;
        Drawable icon;

        public Entry(String title, Runnable action) {
            this.title = title;
            this.action = action;
        }

        public Entry disabledIf(boolean disabled) {
            enabled = !disabled;
            return this;
        }

        /** An icon of its own - an app shortcut's, say - instead of the one its title suggests. */
        public Entry withIcon(Drawable drawable) {
            icon = drawable;
            return this;
        }

        Drawable icon() {
            return icon != null ? icon : Glyphs.forTitle(title);
        }
    }

    private Menus() {
    }

    public static List<Entry> list() {
        return new ArrayList<>();
    }

    /** {@code x}/{@code y} are relative to {@code root}. */
    public static void showAt(Context ctx, FrameLayout root, float x, float y, List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try {
            showInPlace(ctx, root, x, y, entries);
        } catch (Throwable t) {
            L.e("menu could not be shown", t);
        }
    }

    /**
     * A menu whose bottom-right corner sits at {@code rightX}/{@code bottomY} in {@code root} -
     * for a button at the bottom of something, whose menu has to open upwards and to the left.
     *
     * <p>Placed by gravity and margins from the root's own size, so where it lands does not wait
     * on the menu being measured. The power button on the drawer needed exactly that: its menu
     * opened downwards into the part of the drawer cut off at the taskbar, and all that showed
     * was a sliver.
     */
    public static void showAbove(Context ctx, FrameLayout root, float rightX, float bottomY,
            List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try {
            showInPlace(ctx, root, rightX, bottomY, entries, true);
        } catch (Throwable t) {
            L.e("menu could not be shown", t);
        }
    }

    private static void showInPlace(Context ctx, FrameLayout root, float x, float y,
            List<Entry> entries) {
        showInPlace(ctx, root, x, y, entries, false);
    }

    private static void showInPlace(Context ctx, FrameLayout root, float x, float y,
            List<Entry> entries, boolean above) {
        final FrameLayout shade = new FrameLayout(ctx);
        // Catches the tap that dismisses the menu, and stops it reaching whatever is underneath.
        shade.setClickable(true);
        shade.setOnClickListener(v -> MenuRows.close(shade, Theme.GLASS, () -> root.removeView(shade)));
        shade.setFocusableInTouchMode(true);
        shade.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                MenuRows.close(shade, Theme.GLASS, () -> root.removeView(shade));
                return true;
            }
            return false;
        });

        // Liquid glass: the desktop behind the menu is ours to capture, so it is bent through the
        // lens rather than only blurred.
        GlassPanel pane = new GlassPanel(ctx, Ui.dp(ctx, 16), 0x591C1C22);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));
        pane.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        pane.setSource(root);
        List<Drawable> icons = new ArrayList<>();
        boolean anyIcon = false;
        for (Entry entry : entries) {
            Drawable icon = entry.icon();
            icons.add(icon);
            anyIcon |= icon != null;
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            body.addView(MenuRows.row(ctx, Theme.GLASS, entry.title, icons.get(i), anyIcon,
                    entry.enabled,
                    v -> {
                        MenuRows.close(shade, Theme.GLASS, () -> root.removeView(shade));
                        if (entry.action != null) {
                            try {
                                entry.action.run();
                            } catch (Throwable t) {
                                L.e("menu action failed: " + entry.title, t);
                            }
                        }
                    }), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        if (above) {
            mlp.gravity = Gravity.BOTTOM | Gravity.END;
            mlp.rightMargin = Math.max(0, root.getWidth() - (int) x);
            mlp.bottomMargin = Math.max(0, root.getHeight() - (int) y);
        } else {
            // Placed from its own full size, measured before it is added: a pane put at the
            // pointer first is measured with only the room left below the pointer, so by the
            // taskbar it read a fraction of its height, moved up by that little and ran on under
            // the bar. Opens upwards when it would pass the bar, and stays on screen.
            pane.measure(fitIn(root.getWidth()), fitIn(root.getHeight()));
            int width = pane.getMeasuredWidth();
            int height = pane.getMeasuredHeight();
            // The usable height stops at the taskbar, at either edge: the activity runs on
            // under it.
            Insets bar = taskbarOver(root);
            int usable = root.getHeight() - bar.bottom;
            int top = (int) y;
            if (top + height > usable) {
                top -= height;
            }
            mlp.gravity = Gravity.TOP | Gravity.START;
            mlp.leftMargin = clamp((int) x, root.getWidth() - width);
            mlp.topMargin = Math.max(bar.top, clamp(top, usable - height));
        }
        shade.addView(pane, mlp);
        root.addView(shade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        shade.requestFocus();
        MenuRows.popIn(pane, Theme.GLASS, above, pane::refresh);
    }

    /** How much of the top and of the bottom of this view the taskbar covers. */
    private static Insets taskbarOver(View view) {
        try {
            if (view.getDisplay() == null) {
                return Insets.NONE;
            }
            Insets bar = BarEdge.reserved(view.getDisplay().getDisplayId());
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            int screen = view.getResources().getDisplayMetrics().heightPixels;
            android.graphics.Point size = new android.graphics.Point();
            view.getDisplay().getRealSize(size);
            screen = Math.max(screen, size.y);
            // Only the part of the bar that actually overlaps this view.
            int viewBottom = at[1] + view.getHeight();
            int bottom = Math.max(0, Math.min(bar.bottom, viewBottom - (screen - bar.bottom)));
            int top = Math.max(0, bar.top - at[1]);
            return Insets.of(0, top, 0, bottom);
        } catch (Throwable t) {
            return Insets.NONE;
        }
    }

    /** At most {@code size}, or anything while the host has not been laid out yet. */
    private static int fitIn(int size) {
        return size > 0 ? View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, Math.max(0, max)));
    }

    /** Converts screen coordinates into coordinates inside {@code root}. */
    public static float[] toLocal(View root, float rawX, float rawY) {
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return new float[]{rawX - loc[0], rawY - loc[1]};
    }
}
