package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

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
 * taskbar, opened as ZUI opens its own popups there ({@code showPopupMenuForIcon}). Icons are
 * tinted in ZUI's text colour, as its own are drawn.
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

    /**
     * ZUI's popup with these rows, at {@code anchor}; false where it cannot be built.
     *
     * <p>On the taskbar, as ZUI opens its own ({@code showPopupMenuForIcon}): the bar's window is
     * made full screen first and the popup shown once it has the room, so it opens above the bar.
     * ZUI shrinks the window back itself when the popup goes ({@code onDragEndOrViewRemoved}).
     */
    public static boolean show(View anchor, List<? extends Row> rows) {
        try {
            ClassLoader loader = anchor.getContext().getClassLoader();
            Class<?> contextClass = Class.forName(CONTEXT, false, loader);
            Object activity = contextClass.getMethod("lookupContext", Context.class)
                    .invoke(null, anchor.getContext());
            if (activity == null) {
                return false;
            }
            View dragLayer = (View) contextClass.getMethod("getDragLayer").invoke(activity);
            Method fullscreen = taskbarMethod(activity, "setTaskbarWindowFullscreen",
                    boolean.class);
            Method isFull = taskbarMethod(activity, "isTaskbarWindowFullscreen");
            if (fullscreen == null || isFull == null
                    || Boolean.TRUE.equals(isFull.invoke(activity))) {
                return open(anchor, rows, activity, dragLayer, loader);
            }
            fullscreen.invoke(activity, true);
            Runnable[] once = new Runnable[1];
            View.OnLayoutChangeListener[] laid = new View.OnLayoutChangeListener[1];
            once[0] = () -> {
                dragLayer.removeOnLayoutChangeListener(laid[0]);
                dragLayer.removeCallbacks(once[0]);
                if (!open(anchor, rows, activity, dragLayer, loader)) {
                    // Nothing shown, so nothing of ZUI's will give the bar its size back.
                    try {
                        fullscreen.invoke(activity, false);
                    } catch (Throwable ignored) {
                        // The bar is gone with its window.
                    }
                }
            };
            laid[0] = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (b - t != ob - ot) {
                    v.post(once[0]);
                }
            };
            dragLayer.addOnLayoutChangeListener(laid[0]);
            // Should the window be laid out without a change of size, the popup still comes.
            dragLayer.postDelayed(once[0], ROOM_WAIT_MS);
            return true;
        } catch (Throwable t) {
            L.w("zui menu: ZUI's popup not built (" + t + ")");
            return false;
        }
    }

    /** How long a popup over the taskbar waits for the bar's window to grow, at most. */
    private static final long ROOM_WAIT_MS = 250L;

    private static boolean open(View anchor, List<? extends Row> rows, Object activity,
            View dragLayer, ClassLoader loader) {
        try {
            Class<?> popup = Class.forName(POPUP, false, loader);
            Class<?> itemClass = Class.forName(POPUP + "$OptionItem", false, loader);
            Class<?> contextClass = Class.forName(CONTEXT, false, loader);
            Class<?> eventEnum = Class.forName(EVENTS + "$LauncherEvent", false, loader);
            Class<?> eventType = Class.forName(EVENTS + "$EventEnum", false, loader);
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
            Method show = popup.getMethod("show", contextClass, RectF.class, List.class,
                    boolean.class);
            Object shown = show.invoke(null, activity, targetIn(dragLayer, anchor), items, true);
            focusOverTheBar(activity, shown, dragLayer);
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

    /** Where the anchor is in the drag layer, laid out as it is now. */
    private static RectF targetIn(View dragLayer, View anchor) {
        Rect r = new Rect(0, 0, anchor.getWidth(), anchor.getHeight());
        for (android.view.ViewParent p = anchor.getParent(); p instanceof View;
                p = p.getParent()) {
            if (p == dragLayer) {
                ((ViewGroup) dragLayer).offsetDescendantRectToMyCoords(anchor, r);
                return new RectF(r);
            }
        }
        // In another window: by where both are on the screen.
        int[] at = new int[2];
        int[] layer = new int[2];
        anchor.getLocationOnScreen(at);
        dragLayer.getLocationOnScreen(layer);
        r.offset(at[0] - layer[0], at[1] - layer[1]);
        return new RectF(r);
    }

    /** A public method of the taskbar's context, or null where the context is not the bar's. */
    private static Method taskbarMethod(Object activity, String name, Class<?>... args) {
        try {
            return activity.getClass().getMethod(name, args);
        } catch (NoSuchMethodException notTheBar) {
            return null;
        }
    }

    /**
     * Over the taskbar, the bar's window takes the keyboard's focus while a popup is up and gives
     * it back after, as ZUI's {@code showForIcon} has it do.
     */
    private static void focusOverTheBar(Object activity, Object popup, View dragLayer) {
        if (popup == null) {
            return;
        }
        Method focus = taskbarMethod(activity, "onPopupVisibilityChanged", boolean.class);
        if (focus == null) {
            // Not over the taskbar: the drawer and the home screen keep their own focus.
            return;
        }
        try {
            focus.invoke(activity, true);
            popup.getClass().getMethod("addOnCloseCallback", Runnable.class).invoke(popup,
                    (Runnable) () -> dragLayer.post(() -> {
                        try {
                            focus.invoke(activity, false);
                        } catch (Throwable ignored) {
                            // The bar is gone with its window.
                        }
                    }));
        } catch (Throwable t) {
            L.d("zui menu: focus not given to the bar (" + t + ")");
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
