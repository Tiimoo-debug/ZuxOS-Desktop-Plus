package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.hook.taskbar.TaskbarMenu;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Holding one of our folders in a drawer on the tablet: ZUI's own menu, with our folder's actions
 * in it.
 *
 * <p>Our folders sit in ZUI's drawers as entries of their own, and ZUI took a hold on one for a
 * hold on an app - its app menu came up (floating window, split screen, app info, app lock), and
 * every item acted on this module. So the hold is answered here instead, with ZUI's
 * {@code OptionsPopupView}: the popup ZUI opens from its home screen, built from the same
 * {@code system_shortcut} rows as its folder menu ("Enlarge folder", "Rename"), so it looks and
 * moves exactly like ZUI's.
 */
final class DrawerFolderMenu {

    private static final String POPUP = "com.android.launcher3.views.OptionsPopupView";
    private static final String ITEM = POPUP + "$OptionItem";
    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";
    private static final String EVENTS = "com.android.launcher3.logging.StatsLogManager";

    private static boolean sSaid;

    private DrawerFolderMenu() {
    }

    /** ZUI's popup for this folder; false where it cannot be built, so ZUI's hold goes on. */
    static boolean show(View source, Item folder) {
        try {
            ClassLoader loader = source.getContext().getClassLoader();
            Class<?> popup = Class.forName(POPUP, false, loader);
            Class<?> itemClass = Class.forName(ITEM, false, loader);
            Class<?> contextClass = Class.forName(CONTEXT, false, loader);
            Class<?> eventEnum = Class.forName(EVENTS + "$LauncherEvent", false, loader);
            Class<?> eventType = Class.forName(EVENTS + "$EventEnum", false, loader);
            Object activity = contextClass.getMethod("lookupContext", Context.class)
                    .invoke(null, source.getContext());
            if (activity == null) {
                return false;
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object ignore = Enum.valueOf((Class) eventEnum, "IGNORE");
            Constructor<?> make = itemClass.getConstructor(CharSequence.class, Drawable.class,
                    eventType, View.OnLongClickListener.class);
            int tint = textColour(source.getContext());
            List<Object> items = new ArrayList<>();
            for (TaskbarMenu.Entry entry : DrawerHold.menuEntries(source, folder)) {
                Drawable icon = entry.icon();
                if (icon != null) {
                    icon = icon.mutate();
                    icon.setTint(tint);
                }
                items.add(make.newInstance(entry.title(), icon, ignore,
                        (View.OnLongClickListener) v -> {
                            entry.run();
                            return true;
                        }));
            }
            View dragLayer = (View) contextClass.getMethod("getDragLayer").invoke(activity);
            int[] at = new int[2];
            int[] layer = new int[2];
            source.getLocationOnScreen(at);
            dragLayer.getLocationOnScreen(layer);
            float left = at[0] - layer[0];
            float top = at[1] - layer[1];
            RectF target = new RectF(left, top, left + source.getWidth(),
                    top + source.getHeight());
            Method show = popup.getMethod("show", contextClass, RectF.class, List.class,
                    boolean.class);
            show.invoke(null, activity, target, items, true);
            if (!sSaid) {
                sSaid = true;
                L.i("drawer folder menu: ZUI's own popup for our folders");
            }
            return true;
        } catch (Throwable t) {
            L.w("drawer folder menu: ZUI's popup not built (" + t + ")");
            return false;
        }
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
