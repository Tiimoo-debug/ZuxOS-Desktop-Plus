package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ZUI's own menus, carrying our actions - on the tablet, where everything looks like ZUI.
 *
 * <p>ZUI's {@code OptionsPopupView}, its home screen's popup, filled with our rows: the
 * {@code system_shortcut} rows of ZUI's folder menu ("Enlarge folder", "Rename"). Over the
 * taskbar, the bar's window is made room for as ZUI does for its own popups
 * ({@code onPopupVisibilityChanged}). Icons are tinted in ZUI's text colour, as its own are drawn.
 */
public final class ZuiMenu {

    /** One row: what it says, its icon, what it does. */
    public interface Row {
        String title();

        Drawable icon();

        void run();

        /** An icon of its own - an app's shortcut's - drawn as it is, not in ZUI's colour. */
        default boolean ownIcon() {
            return false;
        }
    }

    private static final String POPUP = "com.android.launcher3.views.OptionsPopupView";
    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";
    private static final String EVENTS = "com.android.launcher3.logging.StatsLogManager";

    private static boolean sSaidShow;

    private ZuiMenu() {
    }

    /** ZUI's popup with these rows, at {@code anchor}; false where it cannot be built. */
    public static boolean show(View anchor, List<? extends Row> rows) {
        try {
            ClassLoader loader = anchor.getContext().getClassLoader();
            Class<?> popup = Class.forName(POPUP, false, loader);
            Class<?> itemClass = Class.forName(POPUP + "$OptionItem", false, loader);
            Class<?> contextClass = Class.forName(CONTEXT, false, loader);
            Class<?> eventEnum = Class.forName(EVENTS + "$LauncherEvent", false, loader);
            Class<?> eventType = Class.forName(EVENTS + "$EventEnum", false, loader);
            Object activity = contextClass.getMethod("lookupContext", Context.class)
                    .invoke(null, anchor.getContext());
            if (activity == null) {
                return false;
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object ignore = Enum.valueOf((Class) eventEnum, "IGNORE");
            Constructor<?> make = itemClass.getConstructor(CharSequence.class, Drawable.class,
                    eventType, View.OnLongClickListener.class);
            int tint = textColour(anchor.getContext());
            List<Object> items = new ArrayList<>();
            for (Row row : rows) {
                items.add(make.newInstance(row.title(), iconOf(row, tint), ignore,
                        (View.OnLongClickListener) v -> {
                            row.run();
                            return true;
                        }));
            }
            View dragLayer = (View) contextClass.getMethod("getDragLayer").invoke(activity);
            int[] at = new int[2];
            int[] layer = new int[2];
            anchor.getLocationOnScreen(at);
            dragLayer.getLocationOnScreen(layer);
            float left = at[0] - layer[0];
            float top = at[1] - layer[1];
            RectF target = new RectF(left, top, left + anchor.getWidth(),
                    top + anchor.getHeight());
            Method show = popup.getMethod("show", contextClass, RectF.class, List.class,
                    boolean.class);
            Object shown = show.invoke(null, activity, target, items, true);
            roomOverTheBar(activity, shown, dragLayer);
            if (!sSaidShow) {
                sSaidShow = true;
                L.i("zui menu: ZUI's own popup with our rows");
            }
            return shown != null;
        } catch (Throwable t) {
            L.w("zui menu: ZUI's popup not built (" + t + ")");
            return false;
        }
    }

    /**
     * Over the taskbar its window is only as tall as the bar: ZUI makes room for each of its
     * popups and gives it back when the popup closes, and this does the same.
     */
    private static void roomOverTheBar(Object activity, Object popup, View dragLayer) {
        if (popup == null) {
            return;
        }
        try {
            Method room = activity.getClass().getMethod("onPopupVisibilityChanged",
                    boolean.class);
            room.invoke(activity, true);
            popup.getClass().getMethod("addOnCloseCallback", Runnable.class).invoke(popup,
                    (Runnable) () -> dragLayer.post(() -> {
                        try {
                            room.invoke(activity, false);
                        } catch (Throwable ignored) {
                            // The bar is gone with its window.
                        }
                    }));
        } catch (NoSuchMethodException notTheBar) {
            // Not over the taskbar: the drawer and the home screen have the room already.
        } catch (Throwable t) {
            L.d("zui menu: no room made over the bar (" + t + ")");
        }
    }

    private static Drawable iconOf(Row row, int tint) {
        Drawable icon = row.icon();
        if (icon == null || row.ownIcon()) {
            return icon;
        }
        Drawable mine = icon.mutate();
        mine.setTint(tint);
        return mine;
    }

    /** The text colour of ZUI's theme, which its menu icons are drawn in. */
    private static int textColour(Context ctx) {
        TypedArray a = ctx.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            return a.getColor(0, 0xFF000000);
        } finally {
            a.recycle();
        }
    }
}
