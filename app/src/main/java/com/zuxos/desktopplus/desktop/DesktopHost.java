package com.zuxos.desktopplus.desktop;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Storage;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.drawer.DrawerPanel;
import com.zuxos.desktopplus.hook.OemBridge;
import com.zuxos.desktopplus.hook.Probe;
import com.zuxos.desktopplus.hook.StockUnlockHooks;
import com.zuxos.desktopplus.hook.SurfaceAttacher;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.DesktopStore;
import com.zuxos.desktopplus.model.DrawerStore;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Everything the module adds to one desktop-mode home activity.
 *
 * <p>The surface is our own view tree layered into the launcher's content view, so the features
 * do not depend on the stock launcher having any of them: rearranging, folders, shortcuts and
 * widgets are implemented here and only the wallpaper and the system bars stay the OEM's.
 */
public class DesktopHost implements CellLayoutView.Callbacks, WidgetFrame.Host,
        WidgetHostCtl.PlacementListener, FolderOverlay.Listener, DrawerPanel.Listener,
        WidgetResizeFrame.Callback {

    private static final Map<Activity, DesktopHost> ACTIVE = new WeakHashMap<>();

    private final Activity mActivity;
    private final boolean mExternal;
    private final int mDisplayId;

    private final AppsRepo mRepo;
    private final DesktopStore mStore;
    private final DrawerStore mDrawerStore;

    private final FrameLayout mRoot;
    private final CellLayoutView mGrid;
    private final FolderOverlay mFolders;
    private final DrawerPanel mDrawer;
    private final TextView mAppsButton;
    private final TextView mTrash;
    private final WidgetHostCtl mWidgets;

    private SurfaceAttacher.Target mTarget;
    private WidgetResizeFrame mResizeFrame;
    private View mResizeScrim;
    private View mResizeBar;
    private View mDragView;
    private int[] mPendingWidgetCell;
    private int[] mMenuCell;
    private int mSortMode;

    private DesktopHost(Activity activity, boolean external) {
        mActivity = activity;
        AppCtx.set(activity);
        mExternal = external;
        mDisplayId = displayIdOf(activity);
        mSortMode = Cfg.drawerSort();

        mRepo = new AppsRepo(activity);
        mRepo.reload();
        mStore = new DesktopStore(activity, external);
        mStore.load();
        mDrawerStore = new DrawerStore(activity);
        mDrawerStore.load();

        mRoot = new FrameLayout(activity);
        mGrid = new CellLayoutView(activity, Ui.dp(activity, override(activity,
                Const.KEY_CELL_SIZE, Cfg.cellSizeDp())));
        mGrid.setCallbacks(this);
        mGrid.setFoldersEnabled(Cfg.foldersEnabled());
        int gridPad = Ui.dp(activity, 8);
        mGrid.setPadding(gridPad, gridPad, gridPad, gridPad);
        mRoot.addView(mGrid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mTrash = buildTrash();
        mRoot.addView(mTrash);

        mFolders = new FolderOverlay(activity, mRepo, this);
        mRoot.addView(mFolders, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mDrawer = new DrawerPanel(activity, mRepo, mDrawerStore, this);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        dlp.topMargin = 0;
        mRoot.addView(mDrawer, dlp);

        mAppsButton = buildAppsButton();
        mRoot.addView(mAppsButton);

        mWidgets = new WidgetHostCtl(activity, this);

        installGestures();
    }

    // --- lifecycle -------------------------------------------------------

    public static synchronized DesktopHost attach(Activity activity, boolean external) {
        DesktopHost existing = ACTIVE.get(activity);
        if (existing != null) {
            return existing;
        }
        SurfaceAttacher.Target target = SurfaceAttacher.resolve(activity);
        if (target == null) {
            // Normal on the first resume of a launcher that installs its layout later; the
            // activity watcher retries, and hooks setContentView so we attach the moment it does.
            L.w("nowhere to attach yet: " + SurfaceAttacher.diagnose(activity));
            if (Cfg.probe()) {
                Probe.dump(activity, null);
            }
            return null;
        }
        DesktopHost host = new DesktopHost(activity, external);
        if (!target.attach(activity, host.mRoot)) {
            return null;
        }
        host.mTarget = target;
        ACTIVE.put(activity, host);

        OemBridge.applyTakeover(activity, target.container, host.mRoot, Cfg.takeover());
        StockUnlockHooks.loadUserRules(activity);
        if (Cfg.probe()) {
            Probe.dump(activity, target.container);
        }
        host.mWidgets.start();
        host.mGrid.post(host::rebuildItems);
        L.i("desktop surface attached to " + activity.getClass().getName()
                + " via " + target.describe()
                + " (display " + host.mDisplayId + ", external=" + external + ")");
        return host;
    }

    public static synchronized DesktopHost of(Activity activity) {
        return ACTIVE.get(activity);
    }

    public static synchronized void detach(Activity activity) {
        DesktopHost host = ACTIVE.remove(activity);
        if (host == null) {
            return;
        }
        try {
            host.mWidgets.stop();
            host.save();
            if (host.mTarget != null) {
                host.mTarget.detach(activity, host.mRoot);
            }
            OemBridge.restore(activity);
        } catch (Throwable t) {
            L.e("detach failed", t);
        }
    }

    public void onResume() {
        mWidgets.start();
        Cfg.reload();
        mRepo.reload();
        rebuildItems();
    }

    public void onPause() {
        save();
    }

    public boolean onBackPressed() {
        if (mResizeFrame != null) {
            endResize();
            return true;
        }
        if (mFolders.isOpen()) {
            mFolders.close();
            return true;
        }
        if (mDrawer.isOpen()) {
            mDrawer.hide();
            return true;
        }
        return false;
    }

    public void closeOverlays() {
        mFolders.close();
        mDrawer.hide();
        endResize();
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return mWidgets.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Desktop mode usually comes with a keyboard: All-apps/Search opens the drawer and Escape
     * closes whatever is open.
     */
    public boolean onKeyDown(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_ALL_APPS:
            case android.view.KeyEvent.KEYCODE_SEARCH:
                mDrawer.toggle();
                return true;
            case android.view.KeyEvent.KEYCODE_ESCAPE:
                if (mFolders.isOpen() || mDrawer.isOpen()) {
                    closeOverlays();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    // --- surface ---------------------------------------------------------

    private TextView buildAppsButton() {
        TextView btn = new TextView(mActivity);
        btn.setText("Apps");
        btn.setTextColor(Ui.COLOR_TEXT);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(Ui.ripple(mActivity, 0xCC2B2B2E, Ui.dp(mActivity, 22)));
        int h = Ui.dp(mActivity, 44);
        btn.setPadding(Ui.dp(mActivity, 20), 0, Ui.dp(mActivity, 20), 0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, h);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.leftMargin = Ui.dp(mActivity, 20);
        lp.bottomMargin = Ui.dp(mActivity, 20);
        btn.setLayoutParams(lp);
        btn.setVisibility(Cfg.drawerButton() ? View.VISIBLE : View.GONE);
        btn.setOnClickListener(v -> mDrawer.toggle());
        btn.setOnLongClickListener(v -> {
            showDesktopMenu(v.getLeft(), v.getTop());
            return true;
        });
        return btn;
    }

    private TextView buildTrash() {
        TextView trash = new TextView(mActivity);
        trash.setText("Remove");
        trash.setTextColor(Ui.COLOR_TEXT);
        trash.setGravity(Gravity.CENTER);
        trash.setBackground(Ui.roundRect(0xCCB3261E, Ui.dp(mActivity, 20)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Ui.dp(mActivity, 180), Ui.dp(mActivity, 40));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Ui.dp(mActivity, 16);
        trash.setLayoutParams(lp);
        trash.setVisibility(View.GONE);
        trash.setOnDragListener((v, event) -> {
            Object local = event.getLocalState();
            if (!(local instanceof DragPayload)) {
                return false;
            }
            DragPayload payload = (DragPayload) local;
            switch (event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return payload.source != DragPayload.SRC_DRAWER;
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                    v.setAlpha(1f);
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                    v.setAlpha(0.75f);
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    removeItem(payload);
                    return true;
                default:
                    return true;
            }
        });
        return trash;
    }

    private void installGestures() {
        boolean owns = Cfg.takeover() != Const.TAKEOVER_NONE;
        mGrid.setClickable(owns);
        mGrid.setLongClickable(owns);
        mGrid.setOnLongClickListener(v -> {
            showDesktopMenu(mLastTouchX, mLastTouchY);
            return true;
        });
        mGrid.setOnContextClickListener(v -> {
            showDesktopMenu(mLastTouchX, mLastTouchY);
            return true;
        });
        mGrid.setOnTouchListener((v, event) -> {
            mLastTouchX = event.getX();
            mLastTouchY = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                showDesktopMenu(mLastTouchX, mLastTouchY);
                return true;
            }
            return false;
        });
    }

    private float mLastTouchX;
    private float mLastTouchY;

    // --- items -----------------------------------------------------------

    /** Rebuilds every desktop view from the store; safe to call at any time. */
    public void rebuildItems() {
        if (mGrid.getWidth() == 0 || mGrid.getHeight() == 0) {
            // Cell geometry is only known after the first measure pass.
            mGrid.post(this::rebuildItems);
            return;
        }
        try {
            endResize();
            mGrid.removeAllViews();
            List<Item> dead = new ArrayList<>();
            for (Item item : mStore.items()) {
                if (!addItemView(item)) {
                    dead.add(item);
                }
            }
            for (Item item : dead) {
                mStore.remove(item);
            }
            if (!dead.isEmpty()) {
                save();
            }
        } catch (Throwable t) {
            L.e("rebuild failed", t);
        }
    }

    private boolean addItemView(Item item) {
        int iconSize = iconSizePx();
        if (item.type == Item.TYPE_WIDGET) {
            if (!Cfg.widgetsEnabled()) {
                return true;
            }
            AppWidgetHostView view = mWidgets.createView(item);
            if (view == null) {
                mWidgets.deleteWidget(item.widgetId);
                return false;
            }
            WidgetFrame frame = new WidgetFrame(mActivity, item, this);
            frame.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            place(frame, item);
            mWidgets.updateSize(view, mGrid.getCellWidth() * item.spanX,
                    mGrid.getCellHeight() * item.spanY);
            return true;
        }

        ItemView iv = new ItemView(mActivity, iconSize, Cfg.showLabels(), Cfg.labelShadow());
        iv.bind(item, mRepo);
        iv.setOnClickListener(v -> openItem(item, v));
        iv.setOnLongClickListener(v -> {
            startDrag(item, v, DragPayload.SRC_DESKTOP, null);
            return true;
        });
        iv.setOnContextClickListener(v -> {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            showItemMenu(item, loc[0] + v.getWidth() / 2f, loc[1] + v.getHeight() / 2f);
            return true;
        });
        place(iv, item);
        return true;
    }

    private void place(View view, Item item) {
        int spanX = Math.min(item.spanX, Math.max(1, mGrid.getCols()));
        int spanY = Math.min(item.spanY, Math.max(1, mGrid.getRows()));
        if (item.x < 0 || item.y < 0 || !mGrid.isFree(item.x, item.y, spanX, spanY, null)) {
            int[] free = item.x < 0
                    ? mGrid.findFreeCell(spanX, spanY, null)
                    : mGrid.findNearestFreeCell(item.x, item.y, spanX, spanY, null);
            if (free == null) {
                L.w("no room on the desktop for " + item.key());
                free = new int[]{0, 0};
            }
            item.x = free[0];
            item.y = free[1];
        }
        mGrid.addItemView(view, item.x, item.y, spanX, spanY);
    }

    private void openItem(Item item, View source) {
        if (item.type == Item.TYPE_FOLDER) {
            mFolders.open(item, iconSizePx(), Cfg.showLabels(), Cfg.labelShadow());
            return;
        }
        if (!mRepo.launch(item, source, mDisplayId)) {
            toast("Could not open " + (item.label != null ? item.label : "app"));
        }
    }

    public void startDrag(Item item, View source, int dragSource, Item folder) {
        try {
            DragPayload payload = new DragPayload(item, dragSource, folder);
            View.DragShadowBuilder shadow = source instanceof ItemView
                    ? ((ItemView) source).shadow() : new View.DragShadowBuilder(source);
            source.startDragAndDrop(null, shadow, payload, View.DRAG_FLAG_OPAQUE);
            if (dragSource == DragPayload.SRC_DESKTOP) {
                mDragView = source;
                source.setVisibility(View.INVISIBLE);
            }
            mTrash.setVisibility(dragSource == DragPayload.SRC_DRAWER ? View.GONE : View.VISIBLE);
            mTrash.setAlpha(0.75f);
            mTrash.bringToFront();
        } catch (Throwable t) {
            L.e("could not start drag", t);
        }
    }

    // --- CellLayoutView.Callbacks ----------------------------------------

    @Override
    public void onDropOnCell(DragPayload payload, int cellX, int cellY) {
        try {
            switch (payload.source) {
                case DragPayload.SRC_DRAWER: {
                    Item copy = copyForDesktop(payload.item);
                    copy.x = cellX;
                    copy.y = cellY;
                    mStore.add(copy);
                    addItemView(copy);
                    mDrawer.hide();
                    break;
                }
                case DragPayload.SRC_FOLDER: {
                    if (payload.folder != null) {
                        payload.folder.children.remove(payload.item);
                        dissolveIfEmpty(payload.folder);
                    }
                    payload.item.x = cellX;
                    payload.item.y = cellY;
                    mStore.add(payload.item);
                    rebuildItems();
                    break;
                }
                default: {
                    View view = mGrid.viewForItem(payload.item);
                    payload.item.x = cellX;
                    payload.item.y = cellY;
                    if (view != null) {
                        mGrid.moveItemView(view, cellX, cellY);
                    } else {
                        addItemView(payload.item);
                    }
                    break;
                }
            }
            save();
        } catch (Throwable t) {
            L.e("drop on cell failed", t);
        }
    }

    @Override
    public void onDropOnItem(DragPayload payload, Item target) {
        if (!Cfg.foldersEnabled()) {
            onDropOnCell(payload, target.x, target.y);
            return;
        }
        try {
            Item dragged = payload.source == DragPayload.SRC_DRAWER
                    ? copyForDesktop(payload.item) : payload.item;
            if (payload.source == DragPayload.SRC_FOLDER && payload.folder != null) {
                payload.folder.children.remove(dragged);
                dissolveIfEmpty(payload.folder);
            }
            if (target.type == Item.TYPE_FOLDER) {
                mStore.remove(dragged);
                target.children.add(dragged);
            } else {
                Item folder = Item.folder("Folder");
                folder.x = target.x;
                folder.y = target.y;
                folder.children.add(target);
                folder.children.add(dragged);
                mStore.remove(target);
                mStore.remove(dragged);
                mStore.add(folder);
            }
            save();
            rebuildItems();
        } catch (Throwable t) {
            L.e("folder merge failed", t);
        }
    }

    @Override
    public void onDropRejected(DragPayload payload) {
        toast("No room on the desktop");
    }

    @Override
    public void onDragEnded(DragPayload payload) {
        if (mDragView != null) {
            mDragView.setVisibility(View.VISIBLE);
            mDragView = null;
        }
        mTrash.setVisibility(View.GONE);
    }

    @Override
    public void onEmptySpaceMenu(float x, float y) {
        showDesktopMenu(x, y);
    }

    private void removeItem(DragPayload payload) {
        try {
            if (payload.source == DragPayload.SRC_FOLDER && payload.folder != null) {
                payload.folder.children.remove(payload.item);
                dissolveIfEmpty(payload.folder);
            } else {
                if (payload.item.type == Item.TYPE_WIDGET) {
                    mWidgets.deleteWidget(payload.item.widgetId);
                }
                mStore.remove(payload.item);
            }
            save();
            rebuildItems();
        } catch (Throwable t) {
            L.e("remove failed", t);
        }
    }

    private void dissolveIfEmpty(Item folder) {
        if (folder.children.size() > 1) {
            return;
        }
        for (Item remaining : new ArrayList<>(folder.children)) {
            remaining.x = folder.x;
            remaining.y = folder.y;
            mStore.add(remaining);
        }
        folder.children.clear();
        mStore.remove(folder);
        if (mFolders.getFolder() == folder) {
            mFolders.close();
        }
    }

    private Item copyForDesktop(Item src) {
        switch (src.type) {
            case Item.TYPE_SHORTCUT:
                return src.intentUri != null
                        ? Item.intentShortcut(src.intentUri, src.label)
                        : Item.shortcut(src.pkg, src.shortcutId, src.userSerial, src.label);
            case Item.TYPE_FOLDER: {
                Item copy = Item.folder(src.label);
                copy.children.addAll(src.children);
                return copy;
            }
            default:
                return Item.app(src.pkg, src.cls, src.userSerial, src.label);
        }
    }

    // --- menus -----------------------------------------------------------

    private void showItemMenu(Item item, float rawX, float rawY) {
        float[] local = Menus.toLocal(mRoot, rawX, rawY);
        List<Menus.Entry> entries = Menus.list();
        if (item.type == Item.TYPE_FOLDER) {
            entries.add(new Menus.Entry("Open folder", () -> openItem(item, null)));
            entries.add(new Menus.Entry("Rename folder", () -> Dialogs.prompt(mActivity,
                    "Rename folder", item.label, name -> {
                        item.label = name;
                        save();
                        rebuildItems();
                    })));
            entries.add(new Menus.Entry("Unpack folder", () -> unpack(item)));
            entries.add(new Menus.Entry("Remove folder", () -> {
                mStore.remove(item);
                save();
                rebuildItems();
            }));
        } else if (item.type == Item.TYPE_WIDGET) {
            entries.add(new Menus.Entry("Resize widget", () -> beginResize(item)));
            entries.add(new Menus.Entry("Resize by numbers", () -> Dialogs.resize(mActivity, item,
                    mGrid.getCols(), mGrid.getRows(), (spanX, spanY) -> {
                        item.spanX = spanX;
                        item.spanY = spanY;
                        save();
                        rebuildItems();
                    })));
            entries.add(new Menus.Entry("Remove widget", () -> {
                mWidgets.deleteWidget(item.widgetId);
                mStore.remove(item);
                save();
                rebuildItems();
            }));
        } else {
            entries.add(new Menus.Entry("Open", () -> openItem(item, null)));
            if (item.type == Item.TYPE_APP) {
                entries.add(new Menus.Entry("Pin a shortcut from this app",
                        () -> Dialogs.pickShortcut(mActivity, mRepo, item.pkg,
                                mRepo.userFor(item.userSerial), this::pinShortcut)));
                entries.add(new Menus.Entry("App info",
                        () -> mRepo.showAppInfo(item, mDisplayId)));
            }
            entries.add(new Menus.Entry("Rename", () -> Dialogs.prompt(mActivity, "Rename",
                    item.label, name -> {
                        item.label = name;
                        save();
                        rebuildItems();
                    })));
            entries.add(new Menus.Entry("Remove from desktop", () -> {
                mStore.remove(item);
                save();
                rebuildItems();
            }));
        }
        Menus.showAt(mActivity, mRoot, local[0], local[1], entries);
    }

    private void showDesktopMenu(float x, float y) {
        mMenuCell = new int[]{mGrid.cellXForPixel(x), mGrid.cellYForPixel(y)};
        List<Menus.Entry> entries = Menus.list();
        entries.add(new Menus.Entry("Add app", () -> Dialogs.pickApp(mActivity, mRepo, "Add app",
                entry -> {
                    Item item = entry.toItem();
                    placeAtMenuCell(item);
                    mStore.add(item);
                    addItemView(item);
                    save();
                })));
        entries.add(new Menus.Entry("Add widget", () -> {
            mPendingWidgetCell = mMenuCell;
            mWidgets.showPicker();
        }).disabledIf(!Cfg.widgetsEnabled()));
        entries.add(new Menus.Entry("Add shortcut", this::addShortcutFlow));
        entries.add(new Menus.Entry("New empty folder", () -> Dialogs.prompt(mActivity,
                "Folder name", "Folder", name -> {
                    Item folder = Item.folder(name.isEmpty() ? "Folder" : name);
                    placeAtMenuCell(folder);
                    mStore.add(folder);
                    addItemView(folder);
                    save();
                })).disabledIf(!Cfg.foldersEnabled()));
        entries.add(new Menus.Entry(mDrawer.isOpen() ? "Close app drawer" : "Open app drawer",
                mDrawer::toggle));
        entries.add(new Menus.Entry("Tidy up icons", this::tidyUp));
        entries.add(new Menus.Entry("Icon size", () -> Dialogs.slider(mActivity, "Icon size", "dp",
                override(mActivity, Const.KEY_ICON_SIZE, Cfg.iconSizeDp()), 32, 96, value -> {
                    writeSetting(Const.KEY_ICON_SIZE, value);
                    rebuildItems();
                })));
        entries.add(new Menus.Entry("Grid cell size", () -> Dialogs.slider(mActivity,
                "Grid cell size", "dp", override(mActivity, Const.KEY_CELL_SIZE, Cfg.cellSizeDp()),
                72, 180, value -> {
                    writeSetting(Const.KEY_CELL_SIZE, value);
                    mGrid.setPreferredCellPx(Ui.dp(mActivity, value));
                    mGrid.post(this::rebuildItems);
                })));
        entries.add(new Menus.Entry("Export layout + launcher info", this::exportDiagnostics));
        entries.add(new Menus.Entry("Module settings", this::openModuleSettings));
        Menus.showAt(mActivity, mRoot, x, y, entries);
    }

    /** Places a newly created item where the menu was opened, if that spot is free. */
    private void placeAtMenuCell(Item item) {
        if (mMenuCell == null) {
            return;
        }
        if (mGrid.isFree(mMenuCell[0], mMenuCell[1], item.spanX, item.spanY, null)) {
            item.x = mMenuCell[0];
            item.y = mMenuCell[1];
        }
    }

    private void unpack(Item folder) {
        for (Item child : new ArrayList<>(folder.children)) {
            child.x = -1;
            child.y = -1;
            mStore.add(child);
        }
        folder.children.clear();
        mStore.remove(folder);
        save();
        rebuildItems();
    }

    private void tidyUp() {
        int cols = Math.max(1, mGrid.getCols());
        int index = 0;
        for (Item item : mStore.items()) {
            if (item.type == Item.TYPE_WIDGET) {
                continue;
            }
            item.x = index % cols;
            item.y = index / cols;
            index++;
        }
        save();
        rebuildItems();
    }

    private void addShortcutFlow() {
        Dialogs.pickApp(mActivity, mRepo, "Shortcut from which app?", entry ->
                Dialogs.pickShortcut(mActivity, mRepo, entry.cn.getPackageName(), entry.user,
                        this::pinShortcut));
    }

    private void pinShortcut(ShortcutInfo info) {
        CharSequence label = info.getShortLabel() != null ? info.getShortLabel() : info.getLongLabel();
        Item item = Item.shortcut(info.getPackage(), info.getId(), serialOf(info),
                label != null ? label.toString() : info.getId());
        placeAtMenuCell(item);
        mStore.add(item);
        addItemView(item);
        save();
    }

    private long serialOf(ShortcutInfo info) {
        try {
            android.os.UserManager um = (android.os.UserManager)
                    mActivity.getSystemService(Activity.USER_SERVICE);
            return um != null ? um.getSerialNumberForUser(info.getUserHandle()) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void exportDiagnostics() {
        StringBuilder sb = new StringBuilder();
        ViewGroup content = mRoot.getParent() instanceof ViewGroup
                ? (ViewGroup) mRoot.getParent() : null;
        sb.append(Probe.describe(mActivity, content));
        sb.append(Probe.describeAllWindows());
        sb.append("\n\n--- desktop layout ---\n").append(mStore.exportJson());
        java.io.File out = Storage.exportCopy(mActivity, Const.FILE_PROBE, sb.toString());
        Dialogs.message(mActivity, "Exported", out != null
                ? "Written to:\n" + out.getAbsolutePath()
                : "Could not write to external storage - the same dump is in the LSPosed log.");
        L.i(sb.toString());
    }

    private void openModuleSettings() {
        try {
            Intent intent = mActivity.getPackageManager()
                    .getLaunchIntentForPackage(Const.MODULE_PKG);
            if (intent == null) {
                toast("Settings app not installed");
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivity.startActivity(intent, mRepo.launchOptions(mDisplayId));
        } catch (Throwable t) {
            L.e("could not open settings", t);
        }
    }

    /**
     * Writes a setting back from inside the launcher.
     *
     * <p>Our own prefs file is not writable from here, so the value is remembered in the
     * launcher-side layout file and applied by {@link #applyLocalOverrides()}.
     */
    private void writeSetting(String key, int value) {
        try {
            java.io.File f = Storage.file(mActivity, "overrides.json");
            org.json.JSONObject o = new org.json.JSONObject();
            String existing = Storage.read(f);
            if (existing != null) {
                o = new org.json.JSONObject(existing);
            }
            o.put(key, value);
            Storage.write(f, o.toString());
            sOverrides = o;
        } catch (Throwable t) {
            L.e("could not store override " + key, t);
        }
    }

    private static org.json.JSONObject sOverrides;

    /** Local overrides beat the settings app, since they were set on the desktop itself. */
    public static int override(android.content.Context ctx, String key, int fallback) {
        try {
            if (sOverrides == null) {
                String raw = Storage.read(Storage.file(ctx, "overrides.json"));
                sOverrides = raw != null ? new org.json.JSONObject(raw) : new org.json.JSONObject();
            }
            return sOverrides.optInt(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    public void applyLocalOverrides() {
        mGrid.setPreferredCellPx(Ui.dp(mActivity,
                override(mActivity, Const.KEY_CELL_SIZE, Cfg.cellSizeDp())));
    }

    // --- WidgetFrame.Host -------------------------------------------------

    @Override
    public void onWidgetLongPress(WidgetFrame frame) {
        beginResize(frame.getItem());
    }

    /**
     * Shows the resize frame around a widget: drag an edge to resize, the middle to move, and
     * tap anywhere else to finish.
     */
    public void beginResize(Item item) {
        endResize();
        View target = mGrid.viewForItem(item);
        if (target == null) {
            return;
        }
        mResizeScrim = new View(mActivity);
        mResizeScrim.setClickable(true);
        mResizeScrim.setOnClickListener(v -> endResize());
        mRoot.addView(mResizeScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mResizeFrame = new WidgetResizeFrame(mActivity, mGrid, item, target, this);
        mRoot.addView(mResizeFrame);
        mResizeFrame.syncToItem();
        mResizeBar = buildResizeBar(item);
        mRoot.addView(mResizeBar);
    }

    /** Remove / Done buttons shown while a widget is being resized. */
    private View buildResizeBar(Item item) {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.roundRect(0xEE2B2B2E, Ui.dp(mActivity, 22)));
        int padH = Ui.dp(mActivity, 8);
        bar.setPadding(padH, padH / 2, padH, padH / 2);

        bar.addView(barButton("Remove widget", 0xFFFF6B6B, () -> {
            endResize();
            mWidgets.deleteWidget(item.widgetId);
            mStore.remove(item);
            save();
            rebuildItems();
        }));
        bar.addView(barButton("Done", Ui.COLOR_TEXT, this::endResize));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Ui.dp(mActivity, 16);
        bar.setLayoutParams(lp);
        return bar;
    }

    private TextView barButton(String text, int color, Runnable action) {
        TextView tv = new TextView(mActivity);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        int pad = Ui.dp(mActivity, 14);
        tv.setPadding(pad, Ui.dp(mActivity, 8), pad, Ui.dp(mActivity, 8));
        tv.setBackground(Ui.ripple(mActivity, 0x00000000, Ui.dp(mActivity, 18)));
        tv.setOnClickListener(v -> action.run());
        return tv;
    }

    public void endResize() {
        if (mResizeFrame != null) {
            Item item = mResizeFrame.getItem();
            mRoot.removeView(mResizeFrame);
            mResizeFrame = null;
            rebuildWidgetView(item);
            save();
        }
        if (mResizeScrim != null) {
            mRoot.removeView(mResizeScrim);
            mResizeScrim = null;
        }
        if (mResizeBar != null) {
            mRoot.removeView(mResizeBar);
            mResizeBar = null;
        }
    }

    /**
     * Rebuilds a widget's view after a resize.
     *
     * <p>Telling a widget its new size is not always enough: content that animates (a spinning
     * disc, say) keeps the pivot and bounds it was inflated with, so it ends up drawing outside
     * the frame. A fresh host view inflates against the new size and behaves.
     */
    private void rebuildWidgetView(Item item) {
        if (item.type != Item.TYPE_WIDGET) {
            return;
        }
        View old = mGrid.viewForItem(item);
        if (old == null) {
            return;
        }
        mGrid.removeView(old);
        if (!addItemView(item)) {
            mStore.remove(item);
        }
    }

    @Override
    public void onFrameChanged(Item item, int cellX, int cellY, int spanX, int spanY) {
        View target = mGrid.viewForItem(item);
        if (target == null) {
            return;
        }
        item.x = cellX;
        item.y = cellY;
        item.spanX = spanX;
        item.spanY = spanY;
        mGrid.setItemCell(target, cellX, cellY, spanX, spanY);
        // Live feedback while dragging; the view is rebuilt properly on release.
        resizeWidgetView(item);
    }

    @Override
    public void onFrameReleased(Item item) {
        rebuildWidgetView(item);
        save();
    }

    /** Tells the widget its new size so its layout adapts, not just its bounds. */
    private void resizeWidgetView(Item item) {
        View target = mGrid.viewForItem(item);
        if (!(target instanceof WidgetFrame)) {
            return;
        }
        View widget = ((WidgetFrame) target).widgetView();
        if (widget instanceof android.appwidget.AppWidgetHostView) {
            mWidgets.updateSize((android.appwidget.AppWidgetHostView) widget,
                    mGrid.getCellWidth() * item.spanX, mGrid.getCellHeight() * item.spanY);
        }
    }

    @Override
    public void onWidgetMenu(WidgetFrame frame, float rawX, float rawY) {
        showItemMenu(frame.getItem(), rawX, rawY);
    }

    // --- WidgetHostCtl.PlacementListener ---------------------------------

    @Override
    public void onWidgetReady(int widgetId, AppWidgetProviderInfo info) {
        try {
            int[] spans = mWidgets.minSpans(info, mGrid.getCellWidth(), mGrid.getCellHeight());
            String label = info != null ? mWidgets.labelOf(info) : "Widget";
            ComponentName provider = info != null ? info.provider : null;
            Item item = Item.widget(widgetId,
                    provider != null ? provider.getPackageName() : null,
                    provider != null ? provider.getClassName() : null,
                    spans[0], spans[1], label);
            if (mPendingWidgetCell != null) {
                item.x = mPendingWidgetCell[0];
                item.y = mPendingWidgetCell[1];
                mPendingWidgetCell = null;
            }
            mStore.add(item);
            addItemView(item);
            save();
        } catch (Throwable t) {
            L.e("placing widget failed", t);
        }
    }

    @Override
    public void onWidgetCancelled(int widgetId) {
        mPendingWidgetCell = null;
    }

    // --- FolderOverlay.Listener ------------------------------------------

    @Override
    public void onOpenChild(Item folder, Item child, View source) {
        openItem(child, source);
    }

    @Override
    public void onChildDragOut(Item folder, Item child, View source) {
        startDrag(child, source, DragPayload.SRC_FOLDER, folder);
        mFolders.close();
    }

    @Override
    public void onRenamed(Item folder, String name) {
        folder.label = name;
        save();
    }

    @Override
    public void onClosed(Item folder) {
        rebuildItems();
    }

    // --- DrawerPanel.Listener --------------------------------------------

    @Override
    public void onLaunch(Item item, View source) {
        if (!mRepo.launch(item, source, mDisplayId)) {
            toast("Could not open " + (item.label != null ? item.label : "app"));
        }
    }

    @Override
    public void onOpenFolder(Item folder) {
        mFolders.open(folder, iconSizePx(), Cfg.showLabels(), Cfg.labelShadow());
    }

    @Override
    public void onItemMenu(Item item, View source, float rawX, float rawY) {
        float[] local = Menus.toLocal(mRoot, rawX, rawY);
        List<Menus.Entry> entries = Menus.list();
        entries.add(new Menus.Entry("Add to desktop", () -> {
            Item copy = copyForDesktop(item);
            mStore.add(copy);
            addItemView(copy);
            save();
            mDrawer.hide();
        }));
        if (item.type == Item.TYPE_FOLDER) {
            entries.add(new Menus.Entry("Rename folder", () -> Dialogs.prompt(mActivity,
                    "Rename folder", item.label, name -> {
                        item.label = name;
                        mDrawerStore.save();
                        mDrawer.rebuild();
                    })));
            entries.add(new Menus.Entry("Break up folder", () -> {
                for (Item child : new ArrayList<>(item.children)) {
                    mDrawerStore.order().add(child.key());
                }
                item.children.clear();
                mDrawerStore.folders().remove(item);
                mDrawerStore.order().remove(item.key());
                mDrawerStore.save();
                mDrawer.rebuild();
            }));
        } else {
            entries.add(new Menus.Entry("Put into a folder", () -> Dialogs.pickApp(mActivity, mRepo,
                    "Group with which app?", entry -> {
                        if (mDrawerStore.folderContaining(entry.key()) != null) {
                            toast("That app is already in a folder");
                            return;
                        }
                        mDrawer.createFolder(item, entry.toItem());
                    })));
            entries.add(new Menus.Entry("Hide from drawer", () -> {
                mDrawerStore.hidden().add(item.key());
                mDrawerStore.save();
                mDrawer.rebuild();
            }));
            if (item.type == Item.TYPE_APP) {
                entries.add(new Menus.Entry("App info", () -> mRepo.showAppInfo(item, mDisplayId)));
            }
        }
        Menus.showAt(mActivity, mRoot, local[0], local[1], entries);
    }

    @Override
    public void onStartDrag(Item item, View source) {
        startDrag(item, source, DragPayload.SRC_DRAWER, null);
    }

    @Override
    public void onDrawerChanged() {
        mDrawerStore.save();
    }

    @Override
    public void onOrderCustomised() {
        mSortMode = Const.SORT_CUSTOM;
    }

    @Override
    public int iconSizePx() {
        return Ui.dp(mActivity, override(mActivity, Const.KEY_ICON_SIZE, Cfg.iconSizeDp()));
    }

    @Override
    public boolean showLabels() {
        return Cfg.showLabels();
    }

    @Override
    public boolean labelShadow() {
        return Cfg.labelShadow();
    }

    @Override
    public int sortMode() {
        return mSortMode;
    }

    @Override
    public void onSortModeMenu(View anchor, float rawX, float rawY) {
        float[] local = Menus.toLocal(mRoot, rawX, rawY);
        List<Menus.Entry> entries = Menus.list();
        entries.add(new Menus.Entry("Sort A-Z", () -> {
            mSortMode = Const.SORT_ALPHA;
            mDrawer.rebuild();
        }));
        entries.add(new Menus.Entry("My own order", () -> {
            mSortMode = Const.SORT_CUSTOM;
            mDrawer.rebuild();
        }));
        entries.add(new Menus.Entry("Save current order as mine", () -> {
            mSortMode = Const.SORT_CUSTOM;
            mDrawer.commitOrder();
            mDrawer.rebuild();
        }));
        entries.add(new Menus.Entry("Reset order", () -> {
            mDrawerStore.order().clear();
            mDrawerStore.save();
            mDrawer.rebuild();
        }));
        entries.add(new Menus.Entry("Show hidden apps again", () -> {
            mDrawerStore.hidden().clear();
            mDrawerStore.save();
            mDrawer.rebuild();
        }));
        Menus.showAt(mActivity, mRoot, local[0], local[1], entries);
    }

    // --- misc ------------------------------------------------------------

    private void save() {
        mStore.save();
    }

    private void toast(String message) {
        try {
            Toast.makeText(mActivity, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            L.w(message);
        }
    }

    private static int displayIdOf(Activity activity) {
        try {
            android.view.Display display = activity.getDisplay();
            return display != null ? display.getDisplayId() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public boolean isExternal() {
        return mExternal;
    }
}
