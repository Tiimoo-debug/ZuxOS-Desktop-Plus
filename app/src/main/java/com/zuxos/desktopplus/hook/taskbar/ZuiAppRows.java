package com.zuxos.desktopplus.hook.taskbar;

import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.ZuiMenu;
import com.zuxos.desktopplus.logic.PinList;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * On the tablet, holding an app shows ZUI's own app menu - in its drawers, on its home screen,
 * on its taskbar - with our actions added to it as rows of ZUI's own kind.
 *
 * <p>ZUI's popup ({@code PopupContainerWithArrow}) is filled and then shown; just before its
 * {@code show()}, which measures it and gives each section ZUI's margins and background, our rows
 * go in: pin it to the taskbar (or unpin), and for an app with a window on the tablet, a new
 * window and close - what our own menu offered that ZUI's does not. ZUI's rows are left as they
 * are. The monitor keeps its own menus.
 */
final class ZuiAppRows {

    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";
    private static final String ARROW = "com.android.launcher3.popup.ArrowPopup";

    private static Field sOriginal;

    private ZuiAppRows() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> popup = Reflect.findClass(POPUP, loader);
            Class<?> arrow = Reflect.findClass(ARROW, loader);
            if (popup == null || arrow == null) {
                L.w("zui app rows: ZUI's popup not found, its menu left as it is");
                return;
            }
            sOriginal = popup.getDeclaredField("mOriginalIcon");
            sOriginal.setAccessible(true);
            int hooked = XposedBridge.hookAllMethods(arrow, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 && popup.isInstance(param.thisObject)) {
                        addTo((ViewGroup) param.thisObject);
                    }
                }
            }).size();
            Health.hooked("taskbar: rows in ZUI's app menu", hooked);
            L.i("zui app rows: our actions in ZUI's app menu on the tablet x" + hooked);
        } catch (Throwable t) {
            L.w("zui app rows: not installed (" + t + ")");
        }
    }

    private static void addTo(ViewGroup popup) {
        try {
            View icon = (View) sOriginal.get(popup);
            if (!Cfg.taskbarAppMenu() || icon == null || icon.getDisplay() == null
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
            ZuiMenu.addRows(popup, rowsFor(icon.getContext(), info, pkg, component));
        } catch (Throwable t) {
            L.d("zui app rows: none added (" + t + ")");
        }
    }

    private static List<TaskbarMenu.Entry> rowsFor(Context ctx, Object info, String pkg,
            ComponentName component) {
        List<TaskbarMenu.Entry> rows = new ArrayList<>();
        UserHandle user = IconInfo.userOf(info);
        Item item = Item.app(pkg, component.getClassName(), serialOf(ctx, user), pkg);
        if (PinList.holds(TaskbarPins.pins(ctx), item.key())) {
            rows.add(new TaskbarMenu.Entry("Unpin from the taskbar", () -> {
                TaskbarPins.unpin(ctx, item.key());
                TaskbarRunning.refreshAll();
            }));
        } else {
            rows.add(new TaskbarMenu.Entry("Pin to the taskbar", () -> {
                TaskbarPins.pin(ctx, item, PinList.AT_THE_END);
                TaskbarRunning.refreshAll();
            }));
        }
        // A window of it on the tablet: a second one, and closing it.
        for (TaskbarMenu.Entry entry : TaskbarApps.entriesFor(ctx, pkg, user,
                Display.DEFAULT_DISPLAY)) {
            String title = entry.title();
            if ("New window".equals(title) || title.startsWith("Close")) {
                rows.add(entry);
            }
        }
        return rows;
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
