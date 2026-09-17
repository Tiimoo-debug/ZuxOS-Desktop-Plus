package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Context menus anchored at an arbitrary point.
 *
 * <p>Desktop mode is mouse-driven, so menus have to appear where the pointer is. We park a
 * zero-size anchor view at that point, hang a {@link PopupMenu} off it, and clean up on dismiss.
 *
 * <p>A {@link PopupMenu} is its own window, and a window needs a token the window manager will
 * accept. Anchored inside an activity that is always true; anchored inside one of the module's
 * overlay windows it is not, so the menu falls back to one drawn inside the host view tree.
 */
public final class Menus {

    public static final class Entry {
        final String title;
        final Runnable action;
        boolean enabled = true;

        public Entry(String title, Runnable action) {
            this.title = title;
            this.action = action;
        }

        public Entry disabledIf(boolean disabled) {
            enabled = !disabled;
            return this;
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
        final View anchor = new View(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = (int) x;
        lp.topMargin = (int) y;
        root.addView(anchor, lp);

        try {
            PopupMenu popup = new PopupMenu(ctx, anchor);
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                popup.getMenu().add(0, i, i, e.title).setEnabled(e.enabled);
            }
            popup.setOnMenuItemClickListener(item -> {
                Entry e = entries.get(item.getItemId());
                if (e.action != null) {
                    try {
                        e.action.run();
                    } catch (Throwable t) {
                        L.e("menu action failed: " + e.title, t);
                    }
                }
                return true;
            });
            popup.setOnDismissListener(menu -> root.removeView(anchor));
            popup.show();
        } catch (Throwable t) {
            L.d("popup menu unavailable here, drawing the menu in place: " + t);
            root.removeView(anchor);
            showInPlace(ctx, root, x, y, entries);
        }
    }

    /**
     * The same menu, built as ordinary views inside {@code root}.
     *
     * <p>Used where a popup window cannot be added - inside the module's own overlay windows,
     * whose views have no application window token to hand a {@link PopupMenu}.
     */
    private static void showInPlace(Context ctx, FrameLayout root, float x, float y,
            List<Entry> entries) {
        final FrameLayout shade = new FrameLayout(ctx);
        // Catches the tap that dismisses the menu, and stops it reaching whatever is underneath.
        shade.setClickable(true);
        shade.setOnClickListener(v -> root.removeView(shade));

        LinearLayout menu = new LinearLayout(ctx);
        menu.setOrientation(LinearLayout.VERTICAL);
        int radius = Ui.dp(ctx, 14);
        menu.setBackground(Ui.stroked(Ui.COLOR_PANEL, Ui.dp(ctx, 1), 0x33FFFFFF, radius));
        menu.setElevation(Ui.dp(ctx, 8));
        int padV = Ui.dp(ctx, 6);
        menu.setPadding(0, padV, 0, padV);

        for (Entry entry : entries) {
            TextView row = new TextView(ctx);
            row.setText(entry.title);
            row.setTextColor(entry.enabled ? Ui.COLOR_TEXT : Ui.COLOR_TEXT_DIM);
            row.setTextSize(15);
            row.setSingleLine(true);
            int padH = Ui.dp(ctx, 18);
            row.setPadding(padH, Ui.dp(ctx, 11), padH, Ui.dp(ctx, 11));
            row.setMinimumWidth(Ui.dp(ctx, 180));
            if (entry.enabled) {
                row.setBackground(Ui.ripple(ctx, 0x00000000, 0));
                row.setOnClickListener(v -> {
                    root.removeView(shade);
                    if (entry.action != null) {
                        try {
                            entry.action.run();
                        } catch (Throwable t) {
                            L.e("menu action failed: " + entry.title, t);
                        }
                    }
                });
            }
            menu.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        mlp.gravity = Gravity.TOP | Gravity.START;
        mlp.leftMargin = (int) x;
        mlp.topMargin = (int) y;
        shade.addView(menu, mlp);
        root.addView(shade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Keep it on screen: the size is only known once it has been measured.
        menu.post(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
            lp.leftMargin = clamp(lp.leftMargin, shade.getWidth() - menu.getWidth());
            lp.topMargin = clamp(lp.topMargin, shade.getHeight() - menu.getHeight());
            menu.setLayoutParams(lp);
        });
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
