package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.MenuRows;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.Glyphs;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.hook.ZuiLook;
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
        final Theme theme = Theme.of(root);
        final FrameLayout shade = new FrameLayout(ctx);
        // Catches the tap that dismisses the menu, and stops it reaching whatever is underneath.
        shade.setClickable(true);
        shade.setOnClickListener(v -> MenuRows.close(shade, theme, () -> root.removeView(shade)));
        shade.setFocusableInTouchMode(true);
        shade.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                MenuRows.close(shade, theme, () -> root.removeView(shade));
                return true;
            }
            return false;
        });

        // On the tablet, ZUI's own popup panel and colours - no glass there. Elsewhere liquid
        // glass: the desktop behind the menu is ours to capture, so it is bent through the lens
        // rather than only blurred.
        final boolean zui = Ui.displayOf(root) == Display.DEFAULT_DISPLAY;
        final FrameLayout pane;
        final Runnable settled;
        if (zui) {
            pane = new FrameLayout(ctx);
            Drawable panel = ZuiLook.popupBackground(ctx);
            pane.setBackground(panel != null ? panel : Ui.roundRect(0xFAFAFAFA, Ui.dp(ctx, 16)));
            pane.setElevation(Ui.dp(ctx, 8));
            settled = null;
        } else {
            GlassPanel glass = new GlassPanel(ctx, Ui.dp(ctx, 16), 0x591C1C22).theme(theme);
            glass.setSource(root);
            pane = glass;
            settled = glass::refresh;
        }
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));
        pane.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        List<Drawable> icons = new ArrayList<>();
        boolean anyIcon = false;
        for (Entry entry : entries) {
            Drawable icon = entry.icon();
            icons.add(icon);
            anyIcon |= icon != null;
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            View row = MenuRows.row(ctx, theme, entry.title, icons.get(i), anyIcon,
                    entry.enabled,
                    v -> {
                        MenuRows.close(shade, theme, () -> root.removeView(shade));
                        if (entry.action != null) {
                            try {
                                entry.action.run();
                            } catch (Throwable t) {
                                L.e("menu action failed: " + entry.title, t);
                            }
                        }
                    });
            if (zui) {
                inZuiColours(row, entry.enabled);
            }
            body.addView(row, new LinearLayout.LayoutParams(
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
            Insets bar = BarEdge.over(root);
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
        MenuRows.popIn(pane, theme, above, settled);
    }

    /** A row in the colours of ZUI's popup rows: ZUI's text, its line icons in ZUI's grey. */
    private static void inZuiColours(View row, boolean enabled) {
        if (!(row instanceof ViewGroup)) {
            return;
        }
        Context ctx = row.getContext();
        int text = ZuiLook.popupText(ctx);
        ViewGroup group = (ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(text);
                child.setAlpha(enabled ? 1f : 0.4f);
            } else if (child instanceof ImageView) {
                Drawable icon = ((ImageView) child).getDrawable();
                if (icon != null && Glyphs.isGlyph(icon)) {
                    icon.setTint(ZuiLook.shortcutIcon(ctx));
                }
            }
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
