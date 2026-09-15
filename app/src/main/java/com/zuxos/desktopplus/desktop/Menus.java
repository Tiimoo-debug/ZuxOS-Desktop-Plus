package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

import com.zuxos.desktopplus.core.L;

import java.util.ArrayList;
import java.util.List;

/**
 * Context menus anchored at an arbitrary point.
 *
 * <p>Desktop mode is mouse-driven, so menus have to appear where the pointer is. We park a
 * zero-size anchor view at that point, hang a {@link PopupMenu} off it, and clean up on dismiss.
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
            L.e("could not show menu", t);
            root.removeView(anchor);
        }
    }

    /** Converts screen coordinates into coordinates inside {@code root}. */
    public static float[] toLocal(View root, float rawX, float rawY) {
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return new float[]{rawX - loc[0], rawY - loc[1]};
    }
}
