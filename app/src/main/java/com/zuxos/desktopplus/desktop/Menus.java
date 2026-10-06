package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.Glyphs;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.MenuRows;

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
        shade.setOnClickListener(v -> MenuRows.close(shade, () -> root.removeView(shade)));
        shade.setFocusableInTouchMode(true);
        shade.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                MenuRows.close(shade, () -> root.removeView(shade));
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
            body.addView(MenuRows.row(ctx, entry.title, icons.get(i), anyIcon, entry.enabled,
                    v -> {
                        MenuRows.close(shade, () -> root.removeView(shade));
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
            mlp.gravity = Gravity.TOP | Gravity.START;
            mlp.leftMargin = (int) x;
            mlp.topMargin = (int) y;
        }
        shade.addView(pane, mlp);
        root.addView(shade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        shade.requestFocus();
        if (above) {
            MenuRows.popIn(pane, true, pane::refresh);
            return;
        }

        // Keep it on screen: the size is only known once it has been measured. Opening upwards
        // when it would run off the bottom, the way a menu by the taskbar has to.
        // Placed in the last step before its first frame, once it has a size: a posted job can
        // run before the first layout, see a height of 0 and leave the menu where it was asked
        // for - the 16:53 recording, a menu near the bottom cut off behind the taskbar.
        pane.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (pane.getHeight() <= 0 || shade.getHeight() <= 0) {
                            return true;
                        }
                        pane.getViewTreeObserver().removeOnPreDrawListener(this);
                        FrameLayout.LayoutParams lp =
                                (FrameLayout.LayoutParams) pane.getLayoutParams();
                        int left = clamp(lp.leftMargin, shade.getWidth() - pane.getWidth());
                        // The usable height stops at the taskbar: the activity runs on under it.
                        int usable = shade.getHeight() - taskbarOver(shade);
                        int top = lp.topMargin;
                        if (top + pane.getHeight() > usable) {
                            top = top - pane.getHeight();
                        }
                        top = clamp(top, usable - pane.getHeight());
                        if (left == lp.leftMargin && top == lp.topMargin) {
                            return true;
                        }
                        lp.leftMargin = left;
                        lp.topMargin = top;
                        pane.setLayoutParams(lp);
                        // This frame would show it in the wrong place: skip it.
                        return false;
                    }
                });
        MenuRows.popIn(pane, false, pane::refresh);
    }

    /** How much of the bottom of this view the taskbar covers. */
    private static int taskbarOver(View view) {
        try {
            if (view.getDisplay() == null) {
                return 0;
            }
            int bar = com.zuxos.desktopplus.hook.Windows.taskbarHeight(
                    view.getDisplay().getDisplayId());
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            int screen = view.getResources().getDisplayMetrics().heightPixels;
            android.graphics.Point size = new android.graphics.Point();
            view.getDisplay().getRealSize(size);
            screen = Math.max(screen, size.y);
            // Only the part of the bar that actually overlaps this view.
            int viewBottom = at[1] + view.getHeight();
            return Math.max(0, Math.min(bar, viewBottom - (screen - bar)));
        } catch (Throwable t) {
            return 0;
        }
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
