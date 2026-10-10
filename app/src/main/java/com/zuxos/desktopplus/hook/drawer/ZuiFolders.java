package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconPress;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.model.DrawerStore;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Our drawer folders as ZUI's own folders, in ZUI's drawers on the tablet.
 *
 * <p>ZUI keeps folders to its home screen and taskbar, but its folder code runs for any of its
 * contexts: {@code FolderIcon.inflateFolderAndIcon} builds the icon and its {@code Folder} from a
 * {@code FolderInfo}, and outside {@code Launcher} the folder gets ZUI's taskbar delegate. So each
 * of our folders becomes a {@code FolderInfo} - its apps made by ZUI from the drawer's own entries
 * ({@code AppInfo.makeWorkspaceItem}) - and takes its place in ZUI's list as a {@code FolderIcon}:
 * its look, its opening and closing, its title editing and its pages are ZUI's.
 *
 * <p>In ZUI's list the entry keeps the icon span but is born with a cell type of its own
 * ({@link #TYPE}), which ZUI's adapter has no case for; its cell is made and filled here. Whatever the folder would
 * write goes to our model, never ZUI's database ({@link ZuiFolderWrites}). Holding an app inside
 * shows ZUI's app popup, as ZUI does for a hold without a drag. The monitor keeps our own folder
 * window until its own step.
 */
public final class ZuiFolders {

    /** Our cell type: the icon bit, so it spans one cell, and a bit ZUI never uses. */
    static final int TYPE = 2 | (1 << 16);
    /** Our folders' ids: far below every id and container ZUI uses. */
    static final int ID_BASE = -1_000_000;

    private static final String ADAPTER = "com.android.launcher3.allapps.BaseAllAppsAdapter";
    private static final String FOLDER = "com.android.launcher3.folder.Folder";
    private static final String FOLDER_ICON = "com.android.launcher3.folder.FolderIcon";
    private static final String FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";
    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";

    /** One of our folders as ZUI's, in one of ZUI's contexts. */
    private static final class Built {
        final View icon;
        int version;

        Built(View icon, int version) {
            this.icon = icon;
            this.version = version;
        }
    }

    /** Per ZUI context (ZUX Home's, the taskbar drawer's): its folders by our folder id. */
    private static final Map<Object, Map<String, Built>> BUILT = new WeakHashMap<>();
    /** Our folder behind each id handed to ZUI. */
    private static final Map<Integer, Item> BY_ID = new HashMap<>();
    /** The app of ours each of ZUI's items inside our folders stands for. */
    private static final Map<Object, Item> CHILDREN = new WeakHashMap<>();
    /** The drawer's own entries for the apps filed in folders, by our key, as last seen. */
    private static Map<String, Object> sFiled = new HashMap<>();

    private static Field sViewType;
    private static Field sItemInfo;
    private static Field sActivity;
    private static Field sFolderInfo;
    private static Constructor<?> sHolder;
    private static Method sType;
    private static Field sItemView;
    private static Method sInflate;
    private static boolean sOff;
    private static boolean sSaidIcon;

    private ZuiFolders() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> adapter = Reflect.findClass(ADAPTER, loader);
            Class<?> item = Reflect.findClass(ADAPTER + "$AdapterItem", loader);
            Class<?> holder = Reflect.findClass(ADAPTER + "$ViewHolder", loader);
            Class<?> icon = Reflect.findClass(FOLDER_ICON, loader);
            Class<?> folder = Reflect.findClass(FOLDER, loader);
            if (adapter == null || item == null || holder == null || icon == null
                    || folder == null) {
                off("ZUI's drawer or folder classes not found");
                return;
            }
            sViewType = item.getDeclaredField("viewType");
            sViewType.setAccessible(true);
            sItemInfo = item.getDeclaredField("itemInfo");
            sItemInfo.setAccessible(true);
            sActivity = adapter.getDeclaredField("mActivityContext");
            sActivity.setAccessible(true);
            sFolderInfo = folder.getDeclaredField("mInfo");
            sFolderInfo.setAccessible(true);
            sHolder = holder.getConstructor(View.class);
            sType = holder.getMethod("getItemViewType");
            sItemView = holder.getField("itemView");
            for (Method m : icon.getDeclaredMethods()) {
                if (m.getName().equals("inflateFolderAndIcon") && m.getParameterCount() == 4) {
                    sInflate = m;
                }
            }
            if (sInflate == null) {
                off("ZUI's FolderIcon.inflateFolderAndIcon not found");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(adapter, "onCreateViewHolder",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 2 && param.args[1] instanceof Integer
                                    && (Integer) param.args[1] == TYPE) {
                                param.setResult(cell((ViewGroup) param.args[0], param.thisObject));
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(adapter, "onBindViewHolder",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length >= 2 && param.args[1] instanceof Integer
                                    && bind(param.thisObject, param.args[0],
                                    (Integer) param.args[1])) {
                                param.setResult(null);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(item, "asApp", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    born(param.getResult());
                }
            }).size();
            hooked += XposedBridge.hookAllMethods(folder, "onLongClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && param.args[0] instanceof View
                            && holdInside(param.thisObject, (View) param.args[0])) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            }).size();
            ZuiFolderWrites.install(loader);
            Health.hooked("drawer: our folders as ZUI's", hooked);
            L.i("zui folders: our drawer folders as ZUI's own on the tablet x" + hooked);
        } catch (Throwable t) {
            off("not installed (" + t + ")");
        }
    }

    /** Whether our folders are ZUI's in the tablet's drawers. */
    static boolean on() {
        return !sOff && sInflate != null && Cfg.enabled() && Cfg.nativeDrawer();
    }

    private static void off(String why) {
        if (!sOff) {
            sOff = true;
            L.w("zui folders: off, our own folder window instead - " + why);
        }
    }

    // --- ZUI's list ------------------------------------------------------

    /** The drawer's entries for the apps in our folders, from the latest pass over its list. */
    static void remember(Map<String, Object> filed) {
        sFiled = filed;
    }

    /** Whether the list ZUI is building now is a drawer's on the tablet, for its items' birth. */
    private static final ThreadLocal<Boolean> TABLET_LIST = new ThreadLocal<>();

    /**
     * ZUI starts building a drawer's list. Its items are told apart from one another by their
     * type ({@code AdapterItem.isSameAs}), so ours get our type as ZUI makes them, not after:
     * changed afterwards, every rebuild would take our folders for new items.
     */
    static void building(Object appsList) {
        TABLET_LIST.set(on() && onTablet(appsList));
    }

    static void built() {
        TABLET_LIST.remove();
    }

    private static boolean onTablet(Object appsList) {
        try {
            Object adapter = adapterOf(appsList);
            Object activity = adapter == null ? null : sActivity.get(adapter);
            return activity instanceof Context
                    && displayOf((Context) activity) == Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    /** One of ZUI's list items as it is made: ours, in a tablet drawer, get our type. */
    private static void born(Object item) {
        try {
            if (!Boolean.TRUE.equals(TABLET_LIST.get()) || item == null) {
                return;
            }
            Object info = sItemInfo.get(item);
            if (info != null && NativeDrawerHooks.folderOfEntry(info) != null) {
                sViewType.setInt(item, TYPE);
            }
        } catch (Throwable t) {
            off("could not mark our folders in ZUI's list (" + t + ")");
        }
    }

    /** A cell for one of our folders: the size of ZUI's own drawer cells, filled when bound. */
    private static Object cell(ViewGroup parent, Object adapter) {
        FrameLayout cell = new FrameLayout(parent.getContext());
        int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        try {
            Object activity = sActivity.get(adapter);
            Object profile = Reflect.call(activity, "getDeviceProfile");
            Object cellHeight = Reflect.field(profile, "allAppsCellHeightPx");
            if (cellHeight instanceof Integer) {
                height = (Integer) cellHeight;
            }
            cell.setLayoutParams(new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
            return sHolder.newInstance(cell);
        } catch (Throwable t) {
            off("could not make a cell (" + t + ")");
            try {
                return sHolder.newInstance(cell);
            } catch (Throwable never) {
                throw new IllegalStateException(never);
            }
        }
    }

    /** Fills one of our cells with ZUI's folder icon; false for every cell that is not ours. */
    private static boolean bind(Object adapter, Object holder, int position) {
        try {
            // Every cell of the drawer comes through here: the type first, nothing else for
            // ZUI's own.
            if (sType == null || (Integer) sType.invoke(holder) != TYPE) {
                return false;
            }
            View cell = (View) sItemView.get(holder);
            if (!(cell instanceof FrameLayout)) {
                return false;
            }
            Object list = Reflect.field(adapter, "mApps");
            Object items = Reflect.call(list, "getAdapterItems");
            if (!(items instanceof List) || position < 0 || position >= ((List<?>) items).size()) {
                return false;
            }
            Object item = ((List<?>) items).get(position);
            if (sViewType.getInt(item) != TYPE) {
                return false;
            }
            Item folder = NativeDrawerHooks.folderOfEntry(sItemInfo.get(item));
            Object activity = sActivity.get(adapter);
            FrameLayout frame = (FrameLayout) cell;
            View icon = folder == null ? null : iconFor((Context) activity, frame, folder);
            if (icon == null) {
                frame.removeAllViews();
                return true;
            }
            if (icon.getParent() != frame) {
                if (icon.getParent() instanceof ViewGroup) {
                    ((ViewGroup) icon.getParent()).removeView(icon);
                }
                frame.removeAllViews();
                frame.addView(icon, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            }
            describeOnce(icon);
            return true;
        } catch (Throwable t) {
            L.d("zui folders: cell not filled (" + t + ")");
            return false;
        }
    }

    /** ZUI's folder icon for one of our folders in this context, built once per change. */
    private static View iconFor(Context activity, ViewGroup cell, Item folder) throws Exception {
        Context app = AppCtx.get() != null ? AppCtx.get() : activity;
        int version = DrawerStore.shared(app).version();
        Map<String, Built> mine = BUILT.get(activity);
        if (mine == null) {
            mine = new HashMap<>();
            BUILT.put(activity, mine);
        }
        Built built = mine.get(folder.id);
        if (built != null && (built.version == version || isOpen(built))) {
            return built.icon;
        }
        Object info = folderInfo(activity, folder);
        if (info == null) {
            return null;
        }
        int layout = activity.getResources().getIdentifier("folder_icon", "layout",
                activity.getPackageName());
        View icon = (View) sInflate.invoke(null, layout, activity, cell, info);
        drawerLabel(activity, icon);
        if (!isLauncher(activity)) {
            // ZUI's taskbar would open it in the bar's window and close the drawer
            // (expandFolder); in the drawer it opens where it is, as ZUI's taskbar opens its own.
            icon.setOnClickListener(v -> openHere(v));
        }
        // Held, our hold hook gives it ZUI's folder menu; ZUI's own hold needs it long-clickable.
        icon.setOnLongClickListener(v -> true);
        mine.put(folder.id, new Built(icon, version));
        return icon;
    }

    /**
     * The name under the icon in the colour ZUI gives every name in its drawer
     * ({@code all_app_item_text_color}, as its adapter sets it) - the folder icon's own is the
     * home screen's, made for the wallpaper.
     */
    private static void drawerLabel(Context ctx, View icon) {
        int label = ctx.getResources().getIdentifier("folder_icon_name", "id",
                ctx.getPackageName());
        int colour = ctx.getResources().getIdentifier("all_app_item_text_color", "color",
                ctx.getPackageName());
        View name = label != 0 ? icon.findViewById(label) : null;
        if (name instanceof android.widget.TextView && colour != 0) {
            ((android.widget.TextView) name).setTextColor(ctx.getColor(colour));
        }
    }

    /** Our folder as ZUI's {@code FolderInfo}: its title and its apps, in our order. */
    private static Object folderInfo(Context activity, Item folder) throws Exception {
        Class<?> cls = Class.forName(FOLDER_INFO, false, activity.getClassLoader());
        Object info = cls.getConstructor().newInstance();
        int id = idOf(folder);
        setInt(info, "id", id);
        setField(info, "title", folder.label != null ? folder.label : "Folder");
        Method add = cls.getMethod("add", Class.forName(
                "com.android.launcher3.model.data.ItemInfo", false, activity.getClassLoader()));
        int rank = 0;
        for (Item child : folder.children) {
            Object entry = sFiled.get(child.key());
            if (entry == null) {
                continue;
            }
            Object made = entry.getClass().getMethod("makeWorkspaceItem", Context.class)
                    .invoke(entry, activity);
            if (made == null) {
                continue;
            }
            setInt(made, "container", id);
            setInt(made, "rank", rank++);
            add.invoke(info, made);
            CHILDREN.put(made, child);
        }
        return rank == 0 ? null : info;
    }

    private static synchronized int idOf(Item folder) {
        for (Map.Entry<Integer, Item> e : BY_ID.entrySet()) {
            if (e.getValue().id.equals(folder.id)) {
                e.setValue(folder);
                return e.getKey();
            }
        }
        int id = ID_BASE - BY_ID.size();
        BY_ID.put(id, folder);
        return id;
    }

    // --- opening and holding ----------------------------------------------

    /** Opens the folder of a folder icon where it is, as ZUI's taskbar does ({@code x0}). */
    private static void openHere(View icon) {
        try {
            IconPress.release(icon);
            Object folder = icon.getClass().getMethod("getFolder").invoke(icon);
            folder.getClass().getMethod("animateOpen").invoke(folder);
            L.i("zui folders: opened in the drawer, as ZUI's taskbar opens its folders");
        } catch (Throwable t) {
            L.w("zui folders: could not open (" + t + ")");
        }
    }

    /**
     * An app held inside one of our folders: ZUI's app popup without a drag, as ZUI shows it in
     * that context ({@code LauncherCustom.skipHotseatDrag}; the taskbar's
     * {@code showPopupMenuForIcon}). Drag inside our folders comes with the drag build.
     */
    private static boolean holdInside(Object folder, View view) {
        try {
            Object info = sFolderInfo.get(folder);
            if (info == null || ((Integer) Reflect.field(info, "id")) > ID_BASE) {
                return false;
            }
            Context ctx = view.getContext();
            if (isLauncher(lookup(ctx))) {
                Class.forName(POPUP, false, ctx.getClassLoader())
                        .getMethod("showForIcon", View.class).invoke(null, view);
            } else {
                Object activity = lookup(ctx);
                activity.getClass().getMethod("showPopupMenuForIcon", View.class)
                        .invoke(activity, view);
            }
            IconPress.release(view);
            return true;
        } catch (Throwable t) {
            L.d("zui folders: no popup inside our folder (" + t + ")");
            return false;
        }
    }

    // --- our model ---------------------------------------------------------

    /** Our folder behind an id handed to ZUI, or null. */
    static synchronized Item folderOfId(int id) {
        return BY_ID.get(id);
    }

    /** The app of ours one of ZUI's items inside our folders stands for, or null. */
    static Item childOf(Object item) {
        return item == null ? null : CHILDREN.get(item);
    }

    /** Our folder behind one of ZUI's folder icons of ours, or null. */
    static Item folderOfIcon(View view) {
        Object tag = view == null ? null : view.getTag();
        Object id = tag == null ? null : Reflect.field(tag, "id");
        return id instanceof Integer && (Integer) id <= ID_BASE ? folderOfId((Integer) id) : null;
    }

    /**
     * "Remove from folder" for one of ZUI's items inside our folders - for ZUI's app popup - or
     * null for any other item.
     */
    public static Runnable removeAction(Object item) {
        return childOf(item) == null ? null : () -> removeFromFolder(item);
    }

    /** Takes an app out of the folder it is in, as {@code DrawerFolderWindow} does. */
    static void removeFromFolder(Object item) {
        Item child = childOf(item);
        Context ctx = AppCtx.get();
        if (child == null || ctx == null) {
            return;
        }
        DrawerStore store = DrawerStore.shared(ctx);
        Item folder = store.folderContaining(child.key());
        if (folder == null) {
            return;
        }
        folder.children.remove(child);
        if (folder.children.size() <= 1) {
            // One app left is not a folder: both go back to the list.
            folder.children.clear();
            store.folders().remove(folder);
        }
        store.save();
        closeOpenFolders();
        NativeDrawerHooks.refreshList();
    }

    /** Renamed in ZUI's folder: our folder takes the name. */
    static void renamed(int id, CharSequence title) {
        Item folder = folderOfId(id);
        Context ctx = AppCtx.get();
        String name = title == null ? "" : title.toString();
        if (folder == null || ctx == null || name.equals(folder.label)) {
            return;
        }
        folder.label = name;
        saveKeepingIcons(ctx, folder);
        L.i("zui folders: renamed in ZUI's folder - saved to ours");
    }

    /** Rearranged in ZUI's folder: our folder takes the order of the ranks ZUI gave its apps. */
    static void reordered(int id, List<?> items) {
        Item folder = folderOfId(id);
        Context ctx = AppCtx.get();
        if (folder == null || ctx == null) {
            return;
        }
        java.util.TreeMap<Integer, Item> byRank = new java.util.TreeMap<>();
        for (Object item : items) {
            Item child = childOf(item);
            Object rank = Reflect.field(item, "rank");
            if (child != null && rank instanceof Integer) {
                byRank.put((Integer) rank, child);
            }
        }
        // ZUI's order for the apps it shows; any of ours it does not (not installed) after them.
        List<Item> now = new java.util.ArrayList<>(byRank.values());
        for (Item child : folder.children) {
            if (!now.contains(child)) {
                now.add(child);
            }
        }
        if (!now.equals(folder.children)) {
            folder.children.clear();
            folder.children.addAll(now);
            saveKeepingIcons(ctx, folder);
        }
    }

    /** Saves a change made in ZUI's folder itself, which already shows it: no rebuild for it. */
    private static void saveKeepingIcons(Context ctx, Item folder) {
        DrawerStore store = DrawerStore.shared(ctx);
        store.save();
        int version = store.version();
        for (Map<String, Built> mine : BUILT.values()) {
            Built built = mine.get(folder.id);
            if (built != null) {
                built.version = version;
            }
        }
    }

    private static void closeOpenFolders() {
        for (Map<String, Built> mine : BUILT.values()) {
            for (Built built : mine.values()) {
                if (isOpen(built)) {
                    try {
                        Object folder = built.icon.getClass().getMethod("getFolder")
                                .invoke(built.icon);
                        folder.getClass().getMethod("close", boolean.class).invoke(folder, true);
                    } catch (Throwable t) {
                        L.d("zui folders: could not close (" + t + ")");
                    }
                }
            }
        }
    }

    // --- small things --------------------------------------------------------

    private static boolean isOpen(Built built) {
        try {
            Object folder = built.icon.getClass().getMethod("getFolder").invoke(built.icon);
            return Boolean.TRUE.equals(folder.getClass().getMethod("isOpen").invoke(folder));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object adapterOf(Object appsList) {
        for (Field f : Mirror.fields(appsList.getClass())) {
            if (f.getType().getName().equals(ADAPTER)) {
                return Mirror.get(f, appsList);
            }
        }
        return null;
    }

    private static boolean isLauncher(Object activity) {
        try {
            return activity != null && Class.forName(LAUNCHER, false,
                    activity.getClass().getClassLoader()).isInstance(activity);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object lookup(Context ctx) throws Exception {
        return Class.forName("com.android.launcher3.views.ActivityContext", false,
                ctx.getClassLoader()).getMethod("lookupContext", Context.class).invoke(null, ctx);
    }

    private static int displayOf(Context ctx) {
        try {
            Display display = ctx.getDisplay();
            return display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return Display.DEFAULT_DISPLAY;
        }
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        Field f = fieldOf(target.getClass(), name);
        f.setInt(target, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        fieldOf(target.getClass(), name).set(target, value);
    }

    private static Field fieldOf(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException next) {
                // Up a level.
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Once: ZUI's folder icon beside the drawer's own icons, to compare their sizes. */
    private static void describeOnce(View icon) {
        if (sSaidIcon) {
            return;
        }
        sSaidIcon = true;
        icon.post(() -> {
            View neighbour = null;
            ViewGroup list = icon.getParent() != null && icon.getParent().getParent()
                    instanceof ViewGroup ? (ViewGroup) icon.getParent().getParent() : null;
            if (list != null) {
                for (int i = 0; i < list.getChildCount() && neighbour == null; i++) {
                    View c = list.getChildAt(i);
                    if (!(c instanceof FrameLayout) || c.getClass() != FrameLayout.class) {
                        neighbour = c;
                    }
                }
            }
            L.i("zui folders: ZUI's folder icon " + icon.getWidth() + "x" + icon.getHeight()
                    + (neighbour != null ? ", a drawer icon beside it " + neighbour.getWidth()
                    + "x" + neighbour.getHeight() : ""));
        });
    }
}
