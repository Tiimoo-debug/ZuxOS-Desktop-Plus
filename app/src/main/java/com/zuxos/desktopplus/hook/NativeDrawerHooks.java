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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Brings the module's folders and custom order to the launcher's own app drawer - the one the
 * taskbar opens.
 *
 * <p>ZuxOS ships a minified Launcher3: the class names survive but every field and many methods
 * are renamed to single letters, so nothing here is looked up by name. The app list is found by
 * looking for the {@code List} whose elements are app entries, an app entry's fields are learned
 * from a real instance (see {@link Mirror}), and clicks are intercepted at {@code View} level,
 * which the obfuscator cannot touch.
 */
public final class NativeDrawerHooks {

    /** Package used on synthetic entries so we can recognise our own items again. */
    private static final String FOLDER_PKG = Const.MODULE_PKG;
    private static final String FOLDER_PREFIX = "folder.";

    private static boolean sInstalled;

    private static DrawerStore sStore;
    private static long sStoreStamp = -1;
    private static long sStoreChecked;
    private static AppsRepo sRepo;

    private static Field sAppsField;
    private static Class<?> sAppsFieldOwner;
    private static Class<?> sAppInfoCls;
    private static Mirror.AppInfoShape sShape;
    private static List<Field> sLabelFields = Collections.emptyList();
    private static final Map<Class<?>, Field> sComponentFields = new HashMap<>();
    private static final Set<Class<?>> sWithoutComponent = new HashSet<>();
    private static final Map<String, Object> sFolderEntries = new HashMap<>();
    private static final ThreadLocal<Boolean> sRefreshing = new ThreadLocal<>();
    private static String sLastNote;

    private NativeDrawerHooks() {
    }

    public static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;

        Class<?> listCls = Reflect.findClass(
                "com.android.launcher3.allapps.AlphabeticalAppsList", loader);
        if (listCls == null) {
            L.w("native drawer: AlphabeticalAppsList not found - the stock drawer is left alone");
            return;
        }

        int hooked = hookUpTheHierarchy(listCls, "updateAdapterItems", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                customise(param.thisObject);
            }
        });
        int hookedUpdates = hookUpTheHierarchy(listCls, "onAppsUpdated", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(sRefreshing.get()) || !customise(param.thisObject)) {
                    return;
                }
                sRefreshing.set(Boolean.TRUE);
                try {
                    Reflect.call(param.thisObject, "updateAdapterItems");
                } finally {
                    sRefreshing.set(Boolean.FALSE);
                }
            }
        });
        L.i("native drawer: " + listCls.getName() + " - updateAdapterItems x" + hooked
                + ", onAppsUpdated x" + hookedUpdates);
        if (hooked == 0 && hookedUpdates == 0) {
            L.w("native drawer: no entry point on this build");
            dumpCandidates(listCls);
            return;
        }

        // Clicks are intercepted on View itself: ItemClickHandler's methods are minified away,
        // but every click still goes through performClick, and the check below is just a tag test.
        try {
            int clicks = XposedBridge.hookAllMethods(View.class, "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View
                                    && openFolderFor((View) param.thisObject)) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    }).size();
            L.i("native drawer: click interception installed x" + clicks);
        } catch (Throwable t) {
            L.e("native drawer: could not intercept clicks", t);
        }
    }

    private static int hookUpTheHierarchy(Class<?> clazz, String name, XC_MethodHook callback) {
        int count = 0;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                count += XposedBridge.hookAllMethods(c, name, callback).size();
            } catch (Throwable t) {
                L.d("native drawer: hooking " + c.getName() + "." + name + " failed: " + t);
            }
        }
        return count;
    }

    private static void dumpCandidates(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            StringBuilder sb = new StringBuilder("  " + c.getName() + ":");
            try {
                for (Method m : c.getDeclaredMethods()) {
                    sb.append(' ').append(m.getName()).append('/').append(m.getParameterCount());
                }
            } catch (Throwable t) {
                sb.append(" <unreadable>");
            }
            L.i(sb.toString());
        }
    }

    // --- list rewriting --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static boolean customise(Object appsList) {
        try {
            if (!Cfg.enabled() || !Cfg.nativeDrawer()) {
                return false;
            }
            Context ctx = AppCtx.get();
            if (ctx == null) {
                note("no context yet - open the desktop once so the module can find one");
                return false;
            }
            List<Object> apps = appsListOf(appsList);
            if (apps == null || apps.isEmpty()) {
                return false;
            }
            DrawerStore store = store(ctx);
            Set<String> filed = store.keysInFolders();
            Set<String> hidden = store.hidden();
            if (filed.isEmpty() && hidden.isEmpty() && store.order().isEmpty()
                    && store.folders().isEmpty()) {
                note("nothing to apply yet: make a folder in the module's drawer first");
                return false;
            }
            if (!learnShape(ctx, apps)) {
                return false;
            }

            List<Object> kept = new ArrayList<>(apps.size());
            int iconPx = 0;
            for (Object app : apps) {
                if (folderIdOf(app) != null) {
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
            if (kept.isEmpty()) {
                note("refusing to empty the drawer - no entries survived filtering");
                return false;
            }

            Object sample = kept.get(0);
            for (Item folder : store.folders()) {
                Object entry = folderEntry(ctx, folder, sample, iconPx);
                if (entry != null) {
                    kept.add(entry);
                }
            }

            sortEntries(ctx, kept, store);
            apps.clear();
            apps.addAll(kept);
            note("applied: " + kept.size() + " entries, " + store.folders().size() + " folder(s), "
                    + hidden.size() + " hidden, order " + store.order().size());
            return true;
        } catch (Throwable t) {
            L.e("native drawer: rewriting the app list failed", t);
            return false;
        }
    }

    /** The launcher's app list, found by element type because its field name is minified. */
    @SuppressWarnings("unchecked")
    private static List<Object> appsListOf(Object appsList) {
        if (sAppsField != null && sAppsFieldOwner == appsList.getClass()) {
            Object value = Mirror.get(sAppsField, appsList);
            return value instanceof List ? (List<Object>) value : null;
        }
        // The element type is whatever the launcher puts in there; identify it by finding the
        // list whose entries carry a ComponentName, which app entries always do.
        for (Field f : Mirror.fields(appsList.getClass())) {
            if (!List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            Object value = Mirror.get(f, appsList);
            if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
                continue;
            }
            Object first = ((List<?>) value).get(0);
            if (first == null || componentOf(first) == null) {
                continue;
            }
            sAppsField = f;
            sAppsFieldOwner = appsList.getClass();
            sAppInfoCls = first.getClass();
            L.i("native drawer: app list is field '" + f.getName() + "' holding "
                    + sAppInfoCls.getName());
            return (List<Object>) value;
        }
        note("no app list found on " + appsList.getClass().getName());
        return null;
    }

    /** Learns which fields of an app entry hold the component, label, icon and user. */
    private static boolean learnShape(Context ctx, List<Object> apps) {
        if (sShape != null && sShape.usable()) {
            return true;
        }
        sShape = Mirror.learnAppInfo(apps, entry -> {
            ComponentName cn = componentOf(entry);
            return cn != null ? labelFor(ctx, cn) : null;
        });
        if (sShape.usable()) {
            L.i("native drawer: app entry shape - " + sShape);
            sLabelFields = Mirror.labelFields(apps.get(0).getClass());
            return true;
        }
        note("could not work out the shape of an app entry");
        return false;
    }

    private static CharSequence labelFor(Context ctx, ComponentName cn) {
        for (AppsRepo.AppEntry e : repo(ctx).apps()) {
            if (e.cn.equals(cn)) {
                return e.label;
            }
        }
        return null;
    }

    private static void sortEntries(Context ctx, List<Object> entries, DrawerStore store) {
        final Collator collator = Collator.getInstance();
        final List<String> order = store.order();

        // Both keys are reflective and positions are a linear scan, so resolve each entry once
        // rather than on every comparison - this list is ~150 long and sorted on every refresh.
        final Map<Object, String> titles = new IdentityHashMap<>(entries.size());
        for (Object entry : entries) {
            titles.put(entry, titleOf(entry));
        }
        if (order.isEmpty()) {
            // No custom order: keep it alphabetical so the A-Z fast scroller stays honest.
            Collections.sort(entries, (a, b) -> collator.compare(titles.get(a), titles.get(b)));
            return;
        }
        final Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            positions.putIfAbsent(order.get(i), i);
        }
        final Map<Object, Integer> ranks = new IdentityHashMap<>(entries.size());
        for (Object entry : entries) {
            ranks.put(entry, positions.getOrDefault(entryKey(ctx, entry), -1));
        }
        Collections.sort(entries, (a, b) -> {
            int ia = ranks.get(a);
            int ib = ranks.get(b);
            if (ia < 0 && ib < 0) {
                return collator.compare(titles.get(a), titles.get(b));
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

    private static Object folderEntry(Context ctx, Item folder, Object sample, int iconPx) {
        String label = folder.label != null ? folder.label : "Folder";
        Object cached = sFolderEntries.get(folder.id);
        if (cached != null && label.equals(titleOf(cached))) {
            return cached;
        }
        try {
            Object info = Mirror.instantiateLike(sample);
            if (info == null) {
                return null;
            }
            ComponentName cn = new ComponentName(FOLDER_PKG, FOLDER_PREFIX + folder.id);
            Mirror.set(sShape.component, info, cn);
            Mirror.set(sShape.title, info, label);
            // The entry is a copy of a real app, so any label field left holding that app's name
            // would show through. Overwrite every one of them.
            for (Field f : sLabelFields) {
                Mirror.set(f, info, label);
            }
            String shown = titleOf(info);
            if (!label.equals(shown)) {
                L.w("native drawer: folder label did not take (shows '" + shown + "', wanted '"
                        + label + "') - title field was " + sShape);
            }
            if (sShape.user != null) {
                Mirror.set(sShape.user, info, android.os.Process.myUserHandle());
            }
            if (sShape.intent != null) {
                Mirror.set(sShape.intent, info, new Intent(Intent.ACTION_MAIN).setComponent(cn));
            }
            applyFolderIcon(ctx, info, folder, iconPx > 0 ? iconPx : Ui.dp(ctx, 48));
            sFolderEntries.put(folder.id, info);
            return info;
        } catch (Throwable t) {
            L.e("native drawer: could not build a folder entry", t);
            return null;
        }
    }

    private static void applyFolderIcon(Context ctx, Object info, Item folder, int sizePx) {
        if (sShape.bitmap == null) {
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

            Object bitmapInfo = makeBitmapInfo(sShape.bitmap.getType(), bitmap);
            if (bitmapInfo != null) {
                Mirror.set(sShape.bitmap, info, bitmapInfo);
            }
        } catch (Throwable t) {
            L.d("native drawer: folder icon fell back to the default: " + t);
        }
    }

    /** BitmapInfo's factory is minified too, so it is matched by signature. */
    private static Object makeBitmapInfo(Class<?> bitmapInfoCls, Bitmap bitmap) {
        for (Method m : bitmapInfoCls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Bitmap.class
                    && bitmapInfoCls.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    return m.invoke(null, bitmap);
                } catch (Throwable ignored) {
                    // Try the next candidate.
                }
            }
        }
        for (Constructor<?> c : bitmapInfoCls.getDeclaredConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            if (types.length >= 1 && types[0] == Bitmap.class) {
                try {
                    c.setAccessible(true);
                    Object[] args = new Object[types.length];
                    args[0] = bitmap;
                    for (int i = 1; i < types.length; i++) {
                        args[i] = types[i] == int.class ? Integer.valueOf(0) : null;
                    }
                    return c.newInstance(args);
                } catch (Throwable ignored) {
                    // Try the next candidate.
                }
            }
        }
        L.d("native drawer: no way to build a BitmapInfo - folder keeps a default icon");
        return null;
    }

    private static boolean openFolderFor(View view) {
        if (sFolderEntries.isEmpty()) {
            // No synthetic entries exist, so no view can be carrying one.
            return false;
        }
        try {
            Object tag = view.getTag();
            if (tag == null) {
                return false;
            }
            String folderId = folderIdOf(tag);
            if (folderId == null) {
                return false;
            }
            Context ctx = view.getContext();
            Context storeCtx = AppCtx.get() != null ? AppCtx.get() : ctx;
            final DrawerStore drawerStore = store(storeCtx);
            for (Item folder : drawerStore.folders()) {
                if (folder.id.equals(folderId)) {
                    int displayId = view.getDisplay() != null ? view.getDisplay().getDisplayId() : 0;
                    DrawerFolderWindow.show(ctx, folder, repo(ctx), displayId,
                            Ui.dp(ctx, Cfg.iconSizeDp()), drawerStore::save);
                    return true;
                }
            }
        } catch (Throwable t) {
            L.e("native drawer: opening a folder failed", t);
        }
        return false;
    }

    // --- small helpers ---------------------------------------------------

    /** The ComponentName an app entry carries, located by type and cached per class. */
    private static ComponentName componentOf(Object entry) {
        if (entry == null) {
            return null;
        }
        Class<?> cls = entry.getClass();
        if (sWithoutComponent.contains(cls)) {
            return null;
        }
        Field f = sComponentFields.get(cls);
        if (f == null) {
            for (Field candidate : Mirror.fields(cls)) {
                if (ComponentName.class.isAssignableFrom(candidate.getType())) {
                    f = candidate;
                    sComponentFields.put(cls, f);
                    break;
                }
            }
            if (f == null) {
                // Remember the miss: this runs on every click in the launcher.
                sWithoutComponent.add(cls);
                return null;
            }
        }
        Object value = Mirror.get(f, entry);
        return value instanceof ComponentName ? (ComponentName) value : null;
    }

    private static String folderIdOf(Object entry) {
        ComponentName component = componentOf(entry);
        if (component == null || !FOLDER_PKG.equals(component.getPackageName())
                || !component.getClassName().startsWith(FOLDER_PREFIX)) {
            return null;
        }
        return component.getClassName().substring(FOLDER_PREFIX.length());
    }

    private static String titleOf(Object entry) {
        if (sShape == null || sShape.title == null) {
            return "";
        }
        Object title = Mirror.get(sShape.title, entry);
        return title != null ? title.toString() : "";
    }

    private static String keyOf(Context ctx, Object entry) {
        ComponentName component = componentOf(entry);
        if (component == null) {
            return null;
        }
        long serial = 0;
        if (sShape != null && sShape.user != null) {
            Object user = Mirror.get(sShape.user, entry);
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
        }
        return component.getPackageName() + "/" + component.getClassName() + "#" + serial;
    }

    private static int iconSizeOf(Object entry) {
        try {
            if (sShape == null || sShape.bitmap == null) {
                return 0;
            }
            Object bitmapInfo = Mirror.get(sShape.bitmap, entry);
            if (bitmapInfo == null) {
                return 0;
            }
            Field iconField = Mirror.fieldOfType(bitmapInfo.getClass(), Bitmap.class);
            Object icon = iconField != null ? Mirror.get(iconField, bitmapInfo) : null;
            if (icon instanceof Bitmap) {
                return ((Bitmap) icon).getWidth();
            }
        } catch (Throwable ignored) {
            // Any sensible default will do.
        }
        return 0;
    }

    /** Logs a state message once, so a per-frame hook cannot flood the log. */
    private static void note(String message) {
        if (!message.equals(sLastNote)) {
            sLastNote = message;
            L.i("native drawer: " + message);
        }
    }

    /** Re-read the drawer state whenever our own drawer has written to it. */
    private static synchronized DrawerStore store(Context ctx) {
        long now = android.os.SystemClock.uptimeMillis();
        if (sStore != null && now - sStoreChecked < 500) {
            return sStore;
        }
        sStoreChecked = now;
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
