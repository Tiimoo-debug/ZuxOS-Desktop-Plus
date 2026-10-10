package com.zuxos.desktopplus.hook.taskbar;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.Display;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.Glyphs;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.logic.PinList;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * On the tablet, holding an app shows ZUI's own app menu - in its drawers, on its home screen,
 * on its taskbar - with our actions in it as ZUI's own shortcuts.
 *
 * <p>ZUI builds the menu from a list of {@code SystemShortcut}s and hands it to
 * {@code ZuiPopupContainerWithArrow.populateAndShowRowsZui}, which lays them out (one row, or its
 * icon strip, or its grid past five), styles and animates them. Our actions join that list as
 * {@code RemoteActionShortcut}s - the shortcut Launcher3 has for actions from outside it, such as
 * Digital Wellbeing's - with ZUI's own icons in ZUI's shortcut colour. Nothing of ZUI's menu is
 * touched after it is built. A tap on one of ours runs our action instead of sending its intent,
 * and closes the menu as ZUI's own shortcuts do ({@code dismissTaskMenuView}).
 *
 * <p>Ours: pin to the taskbar (or unpin), and for an app with a window on the tablet, a new window
 * and close. The monitor keeps its own menus.
 */
final class ZuiAppRows {

    private static final String POPUP = "com.zui.launcher.views.ZuiPopupContainerWithArrow";
    private static final String REMOTE = "com.android.launcher3.popup.RemoteActionShortcut";
    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";

    /** Our actions, by the {@link RemoteAction} each of our shortcuts carries. Main thread only. */
    private static final Map<RemoteAction, Runnable> OURS = new WeakHashMap<>();

    private static Constructor<?> sMake;
    private static Field sAction;
    private static Method sLookup;
    private static PendingIntent sNothing;
    private static boolean sSaid;

    private ZuiAppRows() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> popup = Reflect.findClass(POPUP, loader);
            Class<?> remote = Reflect.findClass(REMOTE, loader);
            Class<?> context = Reflect.findClass(CONTEXT, loader);
            if (popup == null || remote == null || context == null) {
                L.w("zui app rows: ZUI's popup not found, its menu left as it is");
                return;
            }
            for (Constructor<?> c : remote.getConstructors()) {
                if (c.getParameterCount() == 4 && c.getParameterTypes()[0] == RemoteAction.class) {
                    sMake = c;
                }
            }
            for (Field f : remote.getDeclaredFields()) {
                if (f.getType() == RemoteAction.class) {
                    f.setAccessible(true);
                    sAction = f;
                }
            }
            sLookup = context.getMethod("lookupContext", Context.class);
            if (sMake == null || sAction == null) {
                L.w("zui app rows: ZUI's RemoteActionShortcut is not as expected, menu left as it is");
                return;
            }
            // The tap first: without it our shortcuts would do nothing, so none are added.
            int clicks = XposedBridge.hookAllMethods(remote, "onClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!OURS.isEmpty() && tapped(param.thisObject)) {
                        param.setResult(null);
                    }
                }
            }).size();
            if (clicks == 0) {
                L.w("zui app rows: no tap to hook, menu left as it is");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(popup, "populateAndShowRowsZui",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            addTo(param);
                        }
                    }).size();
            Health.hooked("taskbar: our shortcuts in ZUI's app menu", hooked);
            L.i("zui app rows: our actions as ZUI's own shortcuts on the tablet x" + hooked);
        } catch (Throwable t) {
            L.w("zui app rows: not installed (" + t + ")");
        }
    }

    /** One of ours tapped: ZUI's menu closes as for its own shortcuts, then the action runs. */
    private static boolean tapped(Object shortcut) {
        try {
            Runnable action = OURS.get((RemoteAction) sAction.get(shortcut));
            if (action == null) {
                return false;
            }
            shortcut.getClass().getMethod("dismissTaskMenuView").invoke(shortcut);
            action.run();
            return true;
        } catch (Throwable t) {
            L.d("zui app rows: tap not handled (" + t + ")");
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void addTo(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args.length != 4 || !(param.args[0] instanceof View)
                    || !(param.args[3] instanceof List) || !Cfg.taskbarAppMenu()) {
                return;
            }
            View icon = (View) param.args[0];
            List<Object> zui = (List<Object>) param.args[3];
            // An empty list is ZUI deciding there is no menu (private space, a task): left so.
            if (zui.isEmpty() || icon.getDisplay() == null
                    || icon.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) {
                return;
            }
            Object info = icon.getTag();
            String pkg = IconInfo.packageOf(info);
            ComponentName component = componentOf(info);
            if (pkg == null || component == null) {
                // Not an app: a folder, a widget, a shortcut - ZUI's menu as it is.
                return;
            }
            Context ctx = icon.getContext();
            Object target = sLookup.invoke(null, ctx);
            if (target == null) {
                return;
            }
            List<Object> all = new ArrayList<>(zui);
            int colour = shortcutColour(ctx);
            for (Row row : rowsFor(ctx, info, pkg, component)) {
                RemoteAction action = new RemoteAction(iconFor(ctx, row, colour), row.title,
                        row.title, nothing(ctx));
                OURS.put(action, row.action);
                all.add(sMake.newInstance(action, target, info, icon));
            }
            param.args[3] = all;
            if (!sSaid) {
                sSaid = true;
                L.i("zui app rows: " + (all.size() - zui.size()) + " of ours beside ZUI's "
                        + zui.size() + " for " + pkg);
            }
        } catch (Throwable t) {
            L.d("zui app rows: none added (" + t + ")");
        }
    }

    /** One of our actions, with the name of ZUI's own icon for it. */
    private static final class Row {
        final String title;
        final String zuiIcon;
        final Runnable action;

        Row(String title, String zuiIcon, Runnable action) {
            this.title = title;
            this.zuiIcon = zuiIcon;
            this.action = action;
        }
    }

    private static List<Row> rowsFor(Context ctx, Object info, String pkg,
            ComponentName component) {
        List<Row> rows = new ArrayList<>();
        UserHandle user = IconInfo.userOf(info);
        Item item = Item.app(pkg, component.getClassName(), serialOf(ctx, user), pkg);
        if (PinList.holds(TaskbarPins.pins(ctx), item.key())) {
            rows.add(new Row("Unpin from the taskbar", "ic_unpin", () -> {
                TaskbarPins.unpin(ctx, item.key());
                TaskbarRunning.refreshAll();
            }));
        } else {
            rows.add(new Row("Pin to the taskbar", "ic_pin_zui", () -> {
                TaskbarPins.pin(ctx, item, PinList.AT_THE_END);
                TaskbarRunning.refreshAll();
            }));
        }
        // A window of it on the tablet: a second one, and closing it.
        for (TaskbarMenu.Entry entry : TaskbarApps.entriesFor(ctx, pkg, user,
                Display.DEFAULT_DISPLAY)) {
            String title = entry.title();
            if ("New window".equals(title)) {
                rows.add(new Row(title, "desktop_mode_ic_taskbar_menu_new_window", entry::run));
            } else if (title.startsWith("Close")) {
                rows.add(new Row(title, "ic_close_option", entry::run));
            }
        }
        return rows;
    }

    /** ZUI's own icon for it in ZUI's shortcut colour; our glyph where ZUI has none. */
    private static Icon iconFor(Context ctx, Row row, int colour) {
        int id = ctx.getResources().getIdentifier(row.zuiIcon, "drawable", ctx.getPackageName());
        Icon icon;
        if (id != 0) {
            icon = Icon.createWithResource(ctx, id);
        } else {
            Drawable glyph = Glyphs.forTitle(row.title);
            int size = Ui.dp(ctx, 24);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            if (glyph != null) {
                glyph.setBounds(0, 0, size, size);
                glyph.draw(new Canvas(bitmap));
            }
            icon = Icon.createWithBitmap(bitmap);
        }
        return icon.setTint(colour);
    }

    /** The colour ZUI draws its own shortcut icons in ({@code ic_system_shortcut_solid}). */
    private static int shortcutColour(Context ctx) {
        int id = ctx.getResources().getIdentifier("ic_system_shortcut_solid", "color",
                ctx.getPackageName());
        return id != 0 ? ctx.getColor(id) : 0xFF666666;
    }

    /**
     * The intent a {@link RemoteAction} must carry. Never sent: a tap on ours runs our action
     * instead. One for all of them, made once.
     */
    private static PendingIntent nothing(Context ctx) {
        if (sNothing == null) {
            sNothing = PendingIntent.getBroadcast(ctx, 0,
                    new Intent("com.zuxos.desktopplus.ZUI_ROW").setPackage(ctx.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE);
        }
        return sNothing;
    }

    /** The app an icon starts, as ZUI's item says: {@code getTargetComponent()}. */
    private static ComponentName componentOf(Object info) {
        try {
            Object component = info.getClass().getMethod("getTargetComponent").invoke(info);
            return component instanceof ComponentName ? (ComponentName) component : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long serialOf(Context ctx, UserHandle user) {
        try {
            UserManager users = ctx.getSystemService(UserManager.class);
            return users == null || user == null ? 0L : users.getSerialNumberForUser(user);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
