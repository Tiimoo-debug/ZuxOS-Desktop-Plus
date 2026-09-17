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
import com.zuxos.desktopplus.desktop.DragPayload;
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
    private static GridLayout sGrid;
    private static Item sFolder;
    private static AppsRepo sRepo;
    private static DrawerStore sStore;
    private static int sDisplayId;
    private static int sIconSize;

    private DrawerFolderWindow() {
    }

    public static void show(Context ctx, Item folder, AppsRepo repo, int displayId,
            int iconSizePx, DrawerStore store) {
        dismiss();
        if (!canShow(ctx)) {
            toast(ctx, "Allow \"display over other apps\" for the launcher to open drawer folders");
            return;
        }
        try {
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(Ui.COLOR_SCRIM);

            LinearLayout panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            GlassPanel glass = new GlassPanel(ctx, Ui.dp(ctx, 26), 0x4D1C1C22);
            glass.addView(panel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            int pad = Ui.dp(ctx, 20);
            panel.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(ctx);
            title.setText(folder.label != null ? folder.label : "Folder");
            title.setTextColor(Ui.COLOR_TEXT);
            title.setTextSize(18);
            panel.addView(title);

            GridLayout grid = new GridLayout(ctx);
            sGrid = grid;
            sFolder = folder;
            sRepo = repo;
            sStore = store;
            sDisplayId = displayId;
            sIconSize = iconSizePx;
            grid.setColumnCount(Math.max(1, Math.min(5, folder.children.size())));
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
            glp.topMargin = Ui.dp(ctx, 12);
            panel.addView(grid, glp);

            populate(ctx);

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.CENTER;
            root.addView(glass, plp);
            // The stock drawer sits behind this window in a window of its own, so it is captured
            // first and our scrim second: the lens then bends the real app grid, dimmed, instead
            // of a flat sheet of colour.
            glass.addSource(TaskbarBridge.stockDrawerRoot());
            glass.addSource(root);
            glass.post(glass::refresh);
            root.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE
                        || event.getAction() == MotionEvent.ACTION_DOWN) {
                    // A tap anywhere outside the panel closes the folder.
                    float x = event.getX();
                    float y = event.getY();
                    boolean insidePanel = x >= glass.getLeft() && x <= glass.getRight()
                            && y >= glass.getTop() && y <= glass.getBottom();
                    if (!insidePanel) {
                        dismiss();
                        return true;
                    }
                }
                return false;
            });

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus folder");
            Glass.blurBehind(ctx, lp, Glass.BEHIND_BLUR_DP);
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
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
        grid.setColumnCount(Math.max(1, Math.min(5, folder.children.size())));
        for (Item child : folder.children) {
            ItemView iv = new ItemView(ctx, sIconSize, true, false);
            iv.bind(child, sRepo);
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
                    DragPayload payload = new DragPayload(item, DragPayload.SRC_FOLDER, sFolder);
                    view.startDragAndDrop(null, view.shadow(), payload, View.DRAG_FLAG_OPAQUE);
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = sIconSize + Ui.dp(ctx, 44);
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
        repo.launch(item, source, sDisplayId);
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
                dismiss();
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

    public static void dismiss() {
        View current = sCurrent;
        WindowManager wm = sWm;
        sCurrent = null;
        sWm = null;
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
