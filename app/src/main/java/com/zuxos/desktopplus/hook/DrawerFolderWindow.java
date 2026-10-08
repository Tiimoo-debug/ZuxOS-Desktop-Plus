package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Glass;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.DesktopHost;
import com.zuxos.desktopplus.desktop.DragPayload;
import com.zuxos.desktopplus.desktop.FolderStyle;
import com.zuxos.desktopplus.desktop.GlassPanel;
import com.zuxos.desktopplus.desktop.ItemView;
import com.zuxos.desktopplus.desktop.Menus;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.DrawerStore;
import com.zuxos.desktopplus.model.Item;

import java.util.List;

/**
 * Folder contents shown over the stock app drawer.
 *
 * <p>The taskbar drawer is a window the launcher owns, not a view tree we can inject into
 * safely, so an opened folder gets a window of its own on the same display.
 */
public final class DrawerFolderWindow {

    private static FrameLayout sCurrent;
    private static WindowManager sWm;
    private static WindowManager.LayoutParams sLp;
    /** The folder is out of the way of a drag that left it, and closes when the drag ends. */
    private static boolean sSteppedAside;
    private static GridLayout sGrid;
    private static Item sFolder;
    private static AppsRepo sRepo;
    private static DrawerStore sStore;
    private static int sDisplayId;
    private static int sIconSize;
    private static View sPanel;
    private static View sSource;

    private DrawerFolderWindow() {
    }

    /** Opens the folder out of {@code source}, the icon that was tapped, when there is one. */
    public static void show(Context ctx, Item folder, AppsRepo repo, int displayId,
            int iconSizePx, DrawerStore store, View source) {
        dismiss();
        // Opened from the taskbar, ctx is bound to the taskbar's window type and refuses an
        // overlay outright (type 2024 vs 2038), so every caller goes through a context that may.
        ctx = Overlays.windowContext(ctx);
        if (!canShow(ctx)) {
            toast(ctx, "Allow \"display over other apps\" for the launcher to open drawer folders");
            return;
        }
        try {
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(FolderStyle.SCRIM);

            LinearLayout panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            GlassPanel glass = new GlassPanel(ctx, Ui.dp(ctx, FolderStyle.RADIUS_DP),
                    FolderStyle.PANEL_TINT);
            glass.addView(panel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            int pad = Ui.dp(ctx, FolderStyle.PADDING_DP);
            panel.setPadding(pad, Ui.dp(ctx, 14), pad, pad);

            TextView title = new TextView(ctx);
            title.setText(folder.label != null ? folder.label : "Folder");
            FolderStyle.styleTitle(title);
            panel.addView(title);

            GridLayout grid = new GridLayout(ctx);
            sGrid = grid;
            sFolder = folder;
            sRepo = repo;
            sStore = store;
            sDisplayId = displayId;
            sIconSize = iconSizePx;
            grid.setColumnCount(FolderStyle.columns(folder.children.size()));
            grid.setOnDragListener((v, event) -> {
                Object local = event.getLocalState();
                if (!(local instanceof DragPayload)) {
                    return false;
                }
                DragPayload payload = (DragPayload) local;
                if (payload.folder != sFolder) {
                    return false;
                }
                switch (event.getAction()) {
                    case android.view.DragEvent.ACTION_DRAG_STARTED:
                    case android.view.DragEvent.ACTION_DRAG_LOCATION:
                        return true;
                    case android.view.DragEvent.ACTION_DROP:
                        reorder(payload.item, indexAt(event.getX(), event.getY()));
                        return true;
                    default:
                        return true;
                }
            });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.topMargin = Ui.dp(ctx, 10);
            panel.addView(grid, glp);

            populate(ctx);

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.CENTER;
            root.addView(glass, plp);
            // The stock drawer sits behind this window in a window of its own, so it is captured
            // first and our scrim second: the lens then bends the real app grid, dimmed, instead
            // of a flat sheet of colour.
            glass.addSource(TaskbarBridge.stockDrawerRootOn(displayId));
            glass.addSource(root);
            glass.post(glass::refresh);
            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP
                        && (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
                    close();
                    return true;
                }
                return false;
            });
            // Dragged off the panel: the folder and the drawer step aside so it can be dropped on
            // the desktop or the taskbar, and close for good once the drag is over.
            root.setOnDragListener((v, event) -> {
                Object local = event.getLocalState();
                if (!(local instanceof DragPayload) || ((DragPayload) local).folder != sFolder) {
                    return false;
                }
                switch (event.getAction()) {
                    case android.view.DragEvent.ACTION_DRAG_LOCATION:
                        if (!inside(glass, event.getX(), event.getY())) {
                            stepAsideForDrag();
                        }
                        return true;
                    case android.view.DragEvent.ACTION_DRAG_EXITED:
                        stepAsideForDrag();
                        return true;
                    case android.view.DragEvent.ACTION_DROP:
                        // Let go over the dimmed space round the panel: not a drop on anything.
                        return false;
                    case android.view.DragEvent.ACTION_DRAG_ENDED:
                        if (sSteppedAside) {
                            sSteppedAside = false;
                            v.post(DrawerFolderWindow::dismiss);
                        }
                        return true;
                    default:
                        return true;
                }
            });
            root.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE
                        || event.getAction() == MotionEvent.ACTION_DOWN) {
                    // A tap anywhere outside the panel closes the folder.
                    float x = event.getX();
                    float y = event.getY();
                    boolean insidePanel = x >= glass.getLeft() && x <= glass.getRight()
                            && y >= glass.getTop() && y <= glass.getBottom();
                    if (!insidePanel) {
                        close();
                        return true;
                    }
                }
                return false;
            });

            WindowManager wm = Overlays.windowManager(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus folder");
            Glass.blurBehind(ctx, lp, Glass.BEHIND_BLUR_DP);
            // The monitor's fastest refresh rate while this is up: its motion at what the
            // screen can show.
            com.zuxos.desktopplus.core.FrameRate.forWindow(lp, wm.getDefaultDisplay());
            com.zuxos.desktopplus.core.FrameRate.forView(root);
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
            sLp = lp;
            sSteppedAside = false;
            sPanel = glass;
            sSource = source;
            root.requestFocus();
            FolderStyle.zoomIn(glass, source);
        } catch (Throwable t) {
            L.e("could not open drawer folder", t);
        }
    }

    /** Fills the folder grid; called again after a reorder. */
    private static void populate(Context ctx) {
        GridLayout grid = sGrid;
        Item folder = sFolder;
        if (grid == null || folder == null) {
            return;
        }
        grid.removeAllViews();
        grid.setColumnCount(FolderStyle.columns(folder.children.size()));
        for (Item child : folder.children) {
            ItemView iv = new ItemView(ctx, sIconSize, true, false);
            iv.bind(child, sRepo);
            iv.setLabelColor(FolderStyle.LABEL);
            iv.setGestures(new ItemView.Gestures() {
                @Override
                public void onItemTap(ItemView view) {
                    launch(view.getItem(), view);
                }

                @Override
                public void onItemMenu(ItemView view) {
                    showMenu(view);
                }

                @Override
                public void onItemPickUp(ItemView view) {
                    Item item = view.getItem();
                    if (item == null) {
                        return;
                    }
                    // A drawer app, copied wherever it is dropped, as one dragged from the drawer's
                    // grid is - and written onto the drag, which has to cross windows: the desktop
                    // and the taskbar are not this one. A drag with no clip and no global flag
                    // never left the folder, which is why only a whole folder could be dragged
                    // out. The folder it came from still rides along, for reordering in here.
                    DragPayload payload = new DragPayload(item, DragPayload.SRC_DRAWER, sFolder);
                    view.startDragAndDrop(payload.toClip(), view.shadow(), payload,
                            DragPayload.FLAGS);
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = FolderStyle.cellWidth(ctx, sIconSize);
            lp.setMargins(Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6));
            grid.addView(iv, lp);
        }
    }

    /**
     * Launches and gets both windows out of the way.
     *
     * <p>The stock drawer does not know this launch happened, so without closing it the app
     * starts behind a drawer that stays open. The app is started first, while the tapped view is
     * still attached and can supply the launch bounds.
     */
    private static void launch(Item item, View source) {
        AppsRepo repo = sRepo;
        if (item == null || repo == null) {
            return;
        }
        // On the monitor, an app already open there is brought forward, not opened again.
        if (item.type != Item.TYPE_APP || !DesktopHost.isExternalOn(sDisplayId)
                || !TaskbarApps.bringIfOpen(source.getContext(), item.pkg, sDisplayId)) {
            repo.launch(item, source, sDisplayId);
        }
        stepAside();
    }

    /**
     * Closes this window and the stock drawer under it.
     *
     * <p>Anything that sends the user to another activity has to do this, app settings included -
     * otherwise the page they asked for opens underneath a drawer that is still on top.
     */
    private static void stepAside() {
        dismiss();
        try {
            TaskbarBridge.closeStockDrawer();
        } catch (Throwable t) {
            L.d("could not close the stock drawer: " + t);
        }
    }

    /** The hold menu for an app inside a drawer folder. */
    private static void showMenu(ItemView view) {
        final Item child = view.getItem();
        final Item folder = sFolder;
        final FrameLayout root = sCurrent;
        final AppsRepo repo = sRepo;
        if (child == null || folder == null || root == null || repo == null) {
            return;
        }
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float[] local = Menus.toLocal(root, loc[0] + view.getWidth() / 2f,
                loc[1] + view.getHeight() / 2f);

        List<Menus.Entry> entries = Menus.list();
        entries.add(new Menus.Entry("Open", () -> launch(child, view)));
        if (child.type == Item.TYPE_APP) {
            final int displayId = sDisplayId;
            entries.add(new Menus.Entry("App info", () -> {
                repo.showAppInfo(child, displayId);
                stepAside();
            }));
        }
        entries.add(new Menus.Entry("Remove from folder", () -> removeFromFolder(child)));
        Menus.showAt(view.getContext(), root, local[0], local[1], entries);
    }

    /**
     * Takes an app back out to the flat drawer list.
     *
     * <p>Nothing has to be added anywhere: the drawer hides exactly the apps that are inside a
     * folder, so dropping it from the folder is what puts it back.
     */
    private static void removeFromFolder(Item child) {
        Item folder = sFolder;
        if (folder == null) {
            return;
        }
        folder.children.remove(child);
        if (folder.children.size() <= 1) {
            // One app left is not a folder. Dissolve it and let both apps return to the list.
            folder.children.clear();
            if (sStore != null) {
                sStore.folders().remove(folder);
            }
            save();
            dismiss();
            return;
        }
        save();
        if (sGrid != null) {
            populate(sGrid.getContext());
        }
    }

    private static void save() {
        if (sStore != null) {
            sStore.save();
        }
    }

    private static int indexAt(float x, float y) {
        GridLayout grid = sGrid;
        if (grid == null) {
            return 0;
        }
        int best = grid.getChildCount();
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            double dx = x - (child.getLeft() + child.getWidth() / 2f);
            double dy = y - (child.getTop() + child.getHeight() / 2f);
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = dx < 0 ? i : i + 1;
            }
        }
        return best;
    }

    private static void reorder(Item child, int index) {
        Item folder = sFolder;
        if (folder == null) {
            return;
        }
        int from = folder.children.indexOf(child);
        if (from < 0) {
            return;
        }
        folder.children.remove(from);
        if (index > from) {
            index--;
        }
        index = Math.max(0, Math.min(index, folder.children.size()));
        folder.children.add(index, child);
        save();
        if (sGrid != null) {
            populate(sGrid.getContext());
        }
    }

    /**
     * Closes it back into the icon it came from.
     *
     * <p>For leaving it alone - a tap outside, back. Launching an app goes through {@link #dismiss}
     * instead: the app is on its way and a folder still shrinking over it would be in the way.
     */
    public static void close() {
        View panel = sPanel;
        View current = sCurrent;
        if (panel == null || current == null) {
            dismiss();
            return;
        }
        FolderStyle.zoomOut(panel, sSource, () -> {
            if (sCurrent == current) {
                dismiss();
            }
        });
    }

    private static boolean inside(View panel, float x, float y) {
        return x >= panel.getLeft() && x <= panel.getRight()
                && y >= panel.getTop() && y <= panel.getBottom();
    }

    /**
     * Out of the way of a drag that left the panel, without ending it: the window stays - it is
     * where the drag started - but draws nothing, blurs nothing and lets touches and the drop
     * through to whatever is under it. The stock drawer goes too; it covers the desktop.
     */
    private static void stepAsideForDrag() {
        View current = sCurrent;
        WindowManager wm = sWm;
        WindowManager.LayoutParams lp = sLp;
        if (sSteppedAside || current == null || wm == null || lp == null) {
            return;
        }
        sSteppedAside = true;
        current.animate().alpha(0f).setDuration(com.zuxos.desktopplus.core.Motion.SHORT)
                .setInterpolator(com.zuxos.desktopplus.core.Motion.EXIT).start();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            lp.setBlurBehindRadius(0);
        }
        try {
            wm.updateViewLayout(current, lp);
        } catch (Throwable t) {
            L.d("folder window: could not step aside (" + t + ")");
        }
        current.post(() -> {
            try {
                TaskbarBridge.closeStockDrawer();
            } catch (Throwable t) {
                L.d("could not close the stock drawer: " + t);
            }
        });
    }

    public static void dismiss() {
        if (sPanel != null) {
            // Gone without the closing animation - an app launched from it: the icon comes back.
            FolderStyle.show(sPanel);
        }
        sPanel = null;
        sSource = null;
        View current = sCurrent;
        WindowManager wm = sWm;
        sCurrent = null;
        sWm = null;
        sLp = null;
        sGrid = null;
        sFolder = null;
        sStore = null;
        if (current == null || wm == null) {
            return;
        }
        try {
            wm.removeViewImmediate(current);
        } catch (Throwable t) {
            L.d("folder window already gone: " + t);
        }
    }

    private static boolean canShow(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            L.w(msg);
        }
    }
}
