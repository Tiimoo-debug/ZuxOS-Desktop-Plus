package com.zuxos.desktopplus.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Storage;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.FolderIconDrawable;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.DrawerStore;
import com.zuxos.desktopplus.model.Item;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Brings the module's folders and custom order to the launcher's own app drawer - the one the
 * taskbar opens - rather than only to the drawer this module draws itself.
 *
 * <p>The drawer is Launcher3's, so the list it renders is Launcher3's {@code AlphabeticalAppsList}.
 * We reorder that list before it becomes adapter items, drop apps the user hid or filed away, and
 * splice in one synthetic entry per folder which opens the folder instead of launching anything.
 */
public final class NativeDrawerHooks {

    /** Package used on synthetic entries so we can recognise our own items again. */
    private static final String FOLDER_PKG = Const.MODULE_PKG;
    private static final String FOLDER_PREFIX = "folder.";

    private static ClassLoader sLoader;
    private static boolean sInstalled;

    private static DrawerStore sStore;
    private static long sStoreStamp = -1;
    private static AppsRepo sRepo;
    private static Class<?> sAppInfoCls;
    private static Class<?> sBitmapInfoCls;
    private static final Map<String, Object> sFolderEntries = new HashMap<>();

    private NativeDrawerHooks() {
    }

    public static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        sLoader = loader;

        Class<?> listCls = findFirst(loader,
                "com.android.launcher3.allapps.AlphabeticalAppsList",
                "com.zui.launcher.allapps.AlphabeticalAppsList");
        if (listCls == null) {
            L.w("native drawer: AlphabeticalAppsList not found - the stock drawer is left alone. "
                    + "Use 'Export layout + launcher info' and send the dump.");
            return;
        }
        sAppInfoCls = findFirst(loader,
                "com.android.launcher3.model.data.AppInfo",
                "com.android.launcher3.AppInfo");
        sBitmapInfoCls = findFirst(loader, "com.android.launcher3.icons.BitmapInfo");

        try {
            XposedBridge.hookAllMethods(listCls, "updateAdapterItems", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    customise(param.thisObject);
                }
            });
            L.i("native drawer: hooked " + listCls.getName());
        } catch (Throwable t) {
            L.e("native drawer: could not hook the app list", t);
        }

        Class<?> clickCls = findFirst(loader,
                "com.android.launcher3.touch.ItemClickHandler",
                "com.zui.launcher.touch.ItemClickHandler");
        if (clickCls == null) {
            L.w("native drawer: ItemClickHandler not found - folders will show but not open");
            return;
        }
        try {
            XposedBridge.hookAllMethods(clickCls, "onClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && param.args[0] instanceof View
                            && openFolderFor((View) param.args[0])) {
                        // Our entry: never let the launcher try to launch it.
                        param.setResult(null);
                    }
                }
            });
            L.i("native drawer: hooked " + clickCls.getName() + ".onClick");
        } catch (Throwable t) {
            L.e("native drawer: could not hook item clicks", t);
        }
    }

    private static Class<?> findFirst(ClassLoader loader, String... names) {
        for (String name : names) {
            Class<?> c = Reflect.findClass(name, loader);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    // --- list rewriting --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void customise(Object appsList) {
        try {
            if (!Cfg.enabled() || !Cfg.nativeDrawer()) {
                return;
            }
            // Search results are a different list; leave them exactly as the user typed them.
            if (Reflect.field(appsList, "mSearchResults") != null) {
                return;
            }
            Object appsField = Reflect.field(appsList, "mApps");
            if (!(appsField instanceof List)) {
                return;
            }
            List<Object> apps = (List<Object>) appsField;
            if (apps.isEmpty()) {
                return;
            }
            Context ctx = AppCtx.get();
            if (ctx == null) {
                return;
            }
            DrawerStore store = store(ctx);
            Set<String> filed = store.keysInFolders();
            Set<String> hidden = store.hidden();
            if (filed.isEmpty() && hidden.isEmpty() && store.order().isEmpty()
                    && store.folders().isEmpty()) {
                return;
            }

            List<Object> kept = new ArrayList<>(apps.size());
            int iconPx = 0;
            for (Object app : apps) {
                if (isOurs(app)) {
                    // Left over from an earlier pass - rebuilt below.
                    continue;
                }
                if (iconPx == 0) {
                    iconPx = iconSizeOf(app);
                }
                String key = keyOf(ctx, app);
                if (key != null && (hidden.contains(key) || filed.contains(key))) {
                    continue;
                }
                kept.add(app);
            }

            for (Item folder : store.folders()) {
                Object entry = folderEntry(ctx, folder, iconPx);
                if (entry != null) {
                    kept.add(entry);
                }
            }

            sortEntries(ctx, kept, store);
            apps.clear();
            apps.addAll(kept);
        } catch (Throwable t) {
            L.e("native drawer: rewriting the app list failed", t);
        }
    }

    private static void sortEntries(Context ctx, List<Object> entries, DrawerStore store) {
        final Collator collator = Collator.getInstance();
        final List<String> order = store.order();
        if (order.isEmpty()) {
            // No custom order: keep it alphabetical so the A-Z fast scroller stays honest.
            Collections.sort(entries, (a, b) -> collator.compare(titleOf(a), titleOf(b)));
            return;
        }
        Collections.sort(entries, (a, b) -> {
            int ia = order.indexOf(entryKey(ctx, a));
            int ib = order.indexOf(entryKey(ctx, b));
            if (ia < 0 && ib < 0) {
                return collator.compare(titleOf(a), titleOf(b));
            }
            if (ia < 0) {
                return 1;
            }
            if (ib < 0) {
                return -1;
            }
            return Integer.compare(ia, ib);
        });
    }

    private static String entryKey(Context ctx, Object entry) {
        String folderId = folderIdOf(entry);
        if (folderId != null) {
            return "folder:" + folderId;
        }
        String key = keyOf(ctx, entry);
        return key != null ? key : "";
    }

    // --- synthetic folder entries ----------------------------------------

    private static Object folderEntry(Context ctx, Item folder, int iconPx) {
        Object cached = sFolderEntries.get(folder.id);
        if (cached != null && titleOf(cached).equals(folder.label != null ? folder.label : "Folder")) {
            return cached;
        }
        if (sAppInfoCls == null) {
            return null;
        }
        try {
            Object info = sAppInfoCls.getDeclaredConstructor().newInstance();
            ComponentName cn = new ComponentName(FOLDER_PKG, FOLDER_PREFIX + folder.id);
            setField(info, "title", folder.label != null ? folder.label : "Folder");
            setField(info, "componentName", cn);
            setField(info, "user", android.os.Process.myUserHandle());
            Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(cn);
            setField(info, "intent", intent);
            applyFolderIcon(ctx, info, folder, iconPx > 0 ? iconPx : Ui.dp(ctx, 48));
            sFolderEntries.put(folder.id, info);
            return info;
        } catch (Throwable t) {
            L.e("native drawer: could not build a folder entry", t);
            return null;
        }
    }

    private static void applyFolderIcon(Context ctx, Object info, Item folder, int sizePx) {
        if (sBitmapInfoCls == null) {
            return;
        }
        try {
            List<Drawable> previews = new ArrayList<>();
            AppsRepo repo = repo(ctx);
            for (Item child : folder.children) {
                Drawable d = repo.iconFor(child);
                if (d != null) {
                    previews.add(d);
                }
                if (previews.size() == 4) {
                    break;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            FolderIconDrawable icon = new FolderIconDrawable(previews, sizePx);
            icon.setBounds(0, 0, sizePx, sizePx);
            icon.draw(canvas);
            Object bitmapInfo = sBitmapInfoCls
                    .getMethod("fromBitmap", Bitmap.class).invoke(null, bitmap);
            setField(info, "bitmap", bitmapInfo);
        } catch (Throwable t) {
            L.d("native drawer: folder icon fell back to the default: " + t);
        }
    }

    private static boolean openFolderFor(View view) {
        try {
            String folderId = folderIdOf(view.getTag());
            if (folderId == null) {
                return false;
            }
            Context ctx = view.getContext();
            DrawerStore store = store(AppCtx.get() != null ? AppCtx.get() : ctx);
            for (Item folder : store.folders()) {
                if (folder.id.equals(folderId)) {
                    int displayId = view.getDisplay() != null ? view.getDisplay().getDisplayId() : 0;
                    DrawerFolderWindow.show(ctx, folder, repo(ctx), displayId,
                            Ui.dp(ctx, Cfg.iconSizeDp()));
                    return true;
                }
            }
        } catch (Throwable t) {
            L.e("native drawer: opening a folder failed", t);
        }
        return false;
    }

    // --- small helpers ---------------------------------------------------

    private static boolean isOurs(Object entry) {
        return folderIdOf(entry) != null;
    }

    private static String folderIdOf(Object entry) {
        if (entry == null) {
            return null;
        }
        Object cn = Reflect.field(entry, "componentName");
        if (!(cn instanceof ComponentName)) {
            return null;
        }
        ComponentName component = (ComponentName) cn;
        if (!FOLDER_PKG.equals(component.getPackageName())
                || !component.getClassName().startsWith(FOLDER_PREFIX)) {
            return null;
        }
        return component.getClassName().substring(FOLDER_PREFIX.length());
    }

    private static String titleOf(Object entry) {
        Object title = Reflect.field(entry, "title");
        return title != null ? title.toString() : "";
    }

    private static String keyOf(Context ctx, Object entry) {
        Object cn = Reflect.field(entry, "componentName");
        if (!(cn instanceof ComponentName)) {
            return null;
        }
        ComponentName component = (ComponentName) cn;
        long serial = 0;
        Object user = Reflect.field(entry, "user");
        if (user instanceof UserHandle) {
            try {
                UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
                if (um != null) {
                    serial = um.getSerialNumberForUser((UserHandle) user);
                }
            } catch (Throwable ignored) {
                // Fall through with the default profile.
            }
        }
        return component.getPackageName() + "/" + component.getClassName() + "#" + serial;
    }

    private static int iconSizeOf(Object entry) {
        try {
            Object bitmapInfo = Reflect.field(entry, "bitmap");
            Object icon = Reflect.field(bitmapInfo, "icon");
            if (icon instanceof Bitmap) {
                return ((Bitmap) icon).getWidth();
            }
        } catch (Throwable ignored) {
            // Any sensible default will do.
        }
        return 0;
    }

    private static void setField(Object target, String name, Object value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (Throwable ignored) {
                // Try the superclass.
            }
        }
        L.d("native drawer: no field " + name + " on " + target.getClass().getName());
    }

    /** Re-read the drawer state whenever our own drawer has written to it. */
    private static synchronized DrawerStore store(Context ctx) {
        long stamp = Storage.file(ctx, Const.FILE_DRAWER).lastModified();
        if (sStore == null || stamp != sStoreStamp) {
            sStore = new DrawerStore(ctx);
            sStore.load();
            sStoreStamp = stamp;
            sFolderEntries.clear();
        }
        return sStore;
    }

    private static synchronized AppsRepo repo(Context ctx) {
        if (sRepo == null) {
            sRepo = new AppsRepo(ctx);
            sRepo.reload();
        }
        return sRepo;
    }
}
