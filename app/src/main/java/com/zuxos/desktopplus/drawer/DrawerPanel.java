package com.zuxos.desktopplus.drawer;

import android.content.Context;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Anim;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.DragPayload;
import com.zuxos.desktopplus.desktop.GlassPanel;
import com.zuxos.desktopplus.desktop.ItemView;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.DrawerStore;
import com.zuxos.desktopplus.model.Item;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The app drawer: a bottom sheet with search, a custom app order and drawer folders.
 *
 * <p>It deliberately does not cover the whole screen - leaving the upper part of the desktop
 * visible is what lets you drag an app straight out of the drawer onto the desktop.
 */
public class DrawerPanel extends FrameLayout implements View.OnDragListener {

    public interface Listener {
        void onLaunch(Item item, View source);

        void onOpenFolder(Item folder);

        void onItemMenu(Item item, View source, float rawX, float rawY);

        void onStartDrag(Item item, View source);

        void onStartDragBatch(java.util.List<Item> items, View source);

        void onDrawerChanged();

        void onDrawerVisibility(boolean open);

        void onAddSelectionToFolder(java.util.List<Item> items);

        void onOrderCustomised();

        int iconSizePx();

        boolean showLabels();

        boolean labelShadow();

        int sortMode();

        void onSortModeMenu(View anchor, float rawX, float rawY);
    }

    private final AppsRepo mRepo;
    private final DrawerStore mStore;
    private final Listener mListener;
    private final WrapGrid mGrid;
    private final EditText mSearch;
    private final List<Item> mEntries = new ArrayList<>();
    private final GlassPanel mSheet;

    private String mQuery = "";
    private boolean mOpenRequested;
    private int mBuiltIconSize;
    private boolean mBuiltLabels = true;
    private boolean mBuiltShadow = true;

    /** Shared by every item view: the view knows which item it is bound to. */
    private final ItemView.Gestures mGestures;
    private final OnContextClickListener mItemContextClick;
    private final java.util.LinkedHashMap<String, Item> mPicked = new java.util.LinkedHashMap<>();
    private final LinearLayout mSelectBar;
    private final TextView mSelectCount;
    private boolean mSelecting;

    public DrawerPanel(Context ctx, AppsRepo repo, DrawerStore store, Listener listener) {
        super(ctx);
        mRepo = repo;
        mStore = store;
        mListener = listener;
        setVisibility(GONE);

        mGestures = new ItemView.Gestures() {
            @Override
            public void onItemTap(ItemView view) {
                Item item = view.getItem();
                if (item == null) {
                    return;
                }
                if (mSelecting) {
                    togglePicked(item, view);
                } else if (item.type == Item.TYPE_FOLDER) {
                    mListener.onOpenFolder(item);
                } else {
                    mListener.onLaunch(item, view);
                }
            }

            @Override
            public void onItemMenu(ItemView view) {
                Item item = view.getItem();
                if (item == null) {
                    return;
                }
                if (mSelecting) {
                    togglePicked(item, view);
                    return;
                }
                int[] loc = new int[2];
                view.getLocationOnScreen(loc);
                mListener.onItemMenu(item, view, loc[0] + view.getWidth() / 2f,
                        loc[1] + view.getHeight() / 2f);
            }

            @Override
            public void onItemPickUp(ItemView view) {
                Item item = view.getItem();
                if (item == null) {
                    return;
                }
                if (mSelecting && !mPicked.isEmpty()) {
                    // Dragging one of the ticked items drags all of them.
                    mListener.onStartDragBatch(pickedItems(), view);
                } else {
                    mListener.onStartDrag(item, view);
                }
            }
        };
        mItemContextClick = v -> {
            Item item = ((ItemView) v).getItem();
            if (item != null) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                mListener.onItemMenu(item, v, loc[0] + v.getWidth() / 2f,
                        loc[1] + v.getHeight() / 2f);
            }
            return true;
        };

        GlassPanel sheet = new GlassPanel(ctx, Ui.dp(ctx, 28), 0x66141419);
        mSheet = sheet;
        sheet.setClickable(true);

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(ctx, 14);
        header.setPadding(pad, pad, pad, Ui.dp(ctx, 6));

        mSearch = new EditText(ctx);
        mSearch.setHint("Search apps");
        mSearch.setSingleLine(true);
        mSearch.setTextColor(Ui.COLOR_TEXT);
        mSearch.setHintTextColor(Ui.COLOR_TEXT_DIM);
        mSearch.setBackground(Ui.roundRect(0x22FFFFFF, Ui.dp(ctx, 18)));
        int sp = Ui.dp(ctx, 12);
        mSearch.setPadding(sp, sp / 2, sp, sp / 2);
        mSearch.addTextChangedListener(new com.zuxos.desktopplus.desktop.Dialogs.SimpleWatcher(text -> {
            mQuery = text.toLowerCase();
            rebuild();
        }));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(mSearch, slp);

        TextView more = new TextView(ctx);
        more.setText("⋮");
        more.setTextSize(20);
        more.setTextColor(Ui.COLOR_TEXT);
        more.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 6), 0);
        more.setOnClickListener(v -> {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            mListener.onSortModeMenu(v, loc[0], loc[1] + v.getHeight());
        });
        header.addView(more);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(Ui.COLOR_TEXT);
        close.setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 4), 0);
        close.setOnClickListener(v -> hide());
        header.addView(close);

        column.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mSelectBar = new LinearLayout(ctx);
        mSelectBar.setOrientation(LinearLayout.HORIZONTAL);
        mSelectBar.setGravity(Gravity.CENTER_VERTICAL);
        mSelectBar.setVisibility(GONE);
        mSelectBar.setPadding(pad, 0, pad, Ui.dp(ctx, 6));
        mSelectCount = new TextView(ctx);
        mSelectCount.setTextColor(Ui.COLOR_TEXT);
        mSelectBar.addView(mSelectCount, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mSelectBar.addView(barButton(ctx, "Add to folder",
                () -> mListener.onAddSelectionToFolder(pickedItems())));
        mSelectBar.addView(barButton(ctx, "Done", this::endSelection));
        column.addView(mSelectBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(ctx);
        mGrid = new WrapGrid(ctx, Ui.dp(ctx, 96), Ui.dp(ctx, 112));
        mGrid.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 16));
        mGrid.setOnDragListener(this);
        scroll.addView(mGrid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.BOTTOM;
        addView(sheet, lp);

        // Tapping the desktop above the sheet closes the drawer.
        setClickable(true);
        setOnClickListener(v -> hide());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // A bottom sheet rather than a full-screen drawer: the visible strip of desktop above it
        // is what lets you drag an app straight out of the drawer onto the desktop.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSheet.getLayoutParams();
        int target = (int) (h * 0.62f);
        if (lp.height != target) {
            lp.height = target;
            mSheet.setLayoutParams(lp);
        }
    }

    private TextView barButton(Context ctx, String text, Runnable action) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Ui.COLOR_ACCENT);
        int p = Ui.dp(ctx, 12);
        tv.setPadding(p, p / 2, p, p / 2);
        tv.setOnClickListener(v -> action.run());
        return tv;
    }

    /** Starts ticking items, seeded with the one the menu was opened on. */
    public void startSelection(Item seed) {
        mSelecting = true;
        mPicked.clear();
        if (seed != null) {
            mPicked.put(seed.key(), seed);
        }
        updateSelectBar();
        rebuild();
    }

    public void endSelection() {
        if (!mSelecting) {
            return;
        }
        mSelecting = false;
        mPicked.clear();
        updateSelectBar();
        rebuild();
    }

    public boolean isSelecting() {
        return mSelecting;
    }

    private void togglePicked(Item item, ItemView view) {
        if (item.type == Item.TYPE_FOLDER) {
            // Folders do not nest, so they cannot join a selection.
            return;
        }
        if (mPicked.remove(item.key()) == null) {
            mPicked.put(item.key(), item);
        }
        view.setPicked(mPicked.containsKey(item.key()));
        updateSelectBar();
    }

    private void updateSelectBar() {
        mSelectBar.setVisibility(mSelecting ? VISIBLE : GONE);
        mSelectCount.setText(mPicked.size() + " selected");
    }

    /** The ticked items, in the order they were ticked, whether or not the search still shows them. */
    public java.util.List<Item> pickedItems() {
        return new ArrayList<>(mPicked.values());
    }

    public boolean isOpen() {
        return mOpenRequested || getVisibility() == VISIBLE;
    }

    public void show() {
        mSearch.setText("");
        mQuery = "";
        rebuild();
        bringToFront();
        // Laid out but not yet drawn: the glass captures what is behind it, then slides in.
        mOpenRequested = true;
        setVisibility(INVISIBLE);
        post(() -> {
            if (!mOpenRequested) {
                // Closed again before the capture ran.
                setVisibility(GONE);
                return;
            }
            mSheet.refresh();
            Anim.slideUp(this, mSheet);
            mListener.onDrawerVisibility(true);
        });
    }

    /** The view the drawer's glass samples, normally the launcher's content root. */
    public void setBackdropSource(View source) {
        mSheet.setSource(source);
    }

    public void hide() {
        boolean wasOpen = mOpenRequested || getVisibility() == VISIBLE;
        mOpenRequested = false;
        if (wasOpen) {
            mListener.onDrawerVisibility(false);
        }
        if (getVisibility() != VISIBLE) {
            setVisibility(GONE);
            return;
        }
        Anim.slideDown(this, mSheet, null);
    }

    public void toggle() {
        if (isOpen()) {
            hide();
        } else {
            show();
        }
    }

    // --- content ---------------------------------------------------------

    /** Rebuilds the visible entry list from the repo, the folders and the saved order. */
    public void rebuild() {
        mEntries.clear();
        Set<String> inFolders = mStore.keysInFolders();
        for (AppsRepo.AppEntry app : mRepo.apps()) {
            String key = app.key();
            if (mStore.hidden().contains(key) || inFolders.contains(key)) {
                continue;
            }
            mEntries.add(app.toItem());
        }
        mEntries.addAll(mStore.folders());
        sortEntries();
        if (!mQuery.isEmpty()) {
            List<Item> filtered = new ArrayList<>();
            for (Item i : mEntries) {
                if (i.label != null && i.label.toLowerCase().contains(mQuery)) {
                    filtered.add(i);
                }
            }
            mEntries.clear();
            mEntries.addAll(filtered);
        }

        int iconSize = mListener.iconSizePx();
        boolean labels = mListener.showLabels();
        boolean shadow = mListener.labelShadow();
        if (iconSize != mBuiltIconSize || labels != mBuiltLabels || shadow != mBuiltShadow) {
            // Icon geometry is baked into the views, so only a size change needs new ones.
            mGrid.removeAllViews();
            mBuiltIconSize = iconSize;
            mBuiltLabels = labels;
            mBuiltShadow = shadow;
        }
        mGrid.setCellSize(iconSize + Ui.dp(getContext(), 44),
                iconSize + Ui.dp(getContext(), labels ? 52 : 20));

        // Rebind the views that are already there instead of building a fresh one per app:
        // this runs on every keystroke in the search box.
        while (mGrid.getChildCount() > mEntries.size()) {
            mGrid.removeViewAt(mGrid.getChildCount() - 1);
        }
        for (int i = 0; i < mEntries.size(); i++) {
            Item entry = mEntries.get(i);
            ItemView iv;
            if (i < mGrid.getChildCount()) {
                iv = (ItemView) mGrid.getChildAt(i);
            } else {
                iv = new ItemView(getContext(), iconSize, labels, shadow);
                mGrid.addView(iv);
            }
            iv.bind(entry, mRepo);
            iv.setGestures(mGestures);
            iv.setOnContextClickListener(mItemContextClick);
            iv.setPicked(mSelecting && mPicked.containsKey(entry.key()));
        }
    }

    private void sortEntries() {
        final Collator collator = Collator.getInstance();
        if (mListener.sortMode() == Const.SORT_CUSTOM && !mStore.order().isEmpty()) {
            // Positions up front: indexOf inside a comparator is a linear scan per comparison.
            final java.util.Map<String, Integer> order = new java.util.HashMap<>();
            List<String> saved = mStore.order();
            for (int i = 0; i < saved.size(); i++) {
                order.putIfAbsent(saved.get(i), i);
            }
            Collections.sort(mEntries, (a, b) -> {
                int ia = order.getOrDefault(a.key(), -1);
                int ib = order.getOrDefault(b.key(), -1);
                if (ia < 0 && ib < 0) {
                    return collator.compare(nz(a.label), nz(b.label));
                }
                if (ia < 0) {
                    return 1;
                }
                if (ib < 0) {
                    return -1;
                }
                return Integer.compare(ia, ib);
            });
        } else {
            Collections.sort(mEntries, (a, b) -> collator.compare(nz(a.label), nz(b.label)));
        }
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    /** Persists the current visible order as the custom order. */
    public void commitOrder() {
        List<String> order = mStore.order();
        order.clear();
        for (Item i : mEntries) {
            order.add(i.key());
        }
        mListener.onDrawerChanged();
    }

    // --- drag & drop inside the drawer -----------------------------------

    @Override
    public boolean onDrag(View v, DragEvent event) {
        Object local = event.getLocalState();
        if (!(local instanceof DragPayload)) {
            return false;
        }
        DragPayload payload = (DragPayload) local;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                // Only drags that started in the drawer rearrange the drawer.
                return payload.source == DragPayload.SRC_DRAWER;
            case DragEvent.ACTION_DROP:
                return handleDrop(payload, event.getX(), event.getY());
            default:
                return true;
        }
    }

    private boolean handleDrop(DragPayload payload, float x, float y) {
        try {
            int index = mGrid.indexAt(x, y);
            View target = index < mGrid.getChildCount() ? mGrid.getChildAt(index) : null;
            Item targetItem = target instanceof ItemView ? ((ItemView) target).getItem() : null;

            if (targetItem != null && targetItem != payload.item
                    && payload.item.type != Item.TYPE_FOLDER) {
                if (targetItem.type == Item.TYPE_FOLDER) {
                    addToFolder(targetItem, payload.item);
                    return true;
                }
                if (targetItem.type == Item.TYPE_APP || targetItem.type == Item.TYPE_SHORTCUT) {
                    createFolder(targetItem, payload.item);
                    return true;
                }
            }
            reorder(payload.item, index);
            return true;
        } catch (Throwable t) {
            L.e("drawer drop failed", t);
            return false;
        }
    }

    private void reorder(Item item, int index) {
        int from = indexOfKey(item.key());
        if (from < 0) {
            return;
        }
        Item moved = mEntries.remove(from);
        index = Math.max(0, Math.min(index, mEntries.size()));
        mEntries.add(index, moved);
        commitOrder();
        // Rearranging by hand means the user wants their own order, not A-Z.
        mListener.onOrderCustomised();
        rebuild();
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < mEntries.size(); i++) {
            if (mEntries.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public Item createFolder(Item first, Item second) {
        Item folder = Item.folder("Folder");
        folder.children.add(copyOf(first));
        folder.children.add(copyOf(second));
        mStore.folders().add(folder);
        // Keep the folder where the first app was so it does not jump to the end.
        int at = indexOfKey(first.key());
        List<String> order = mStore.order();
        order.remove(first.key());
        order.remove(second.key());
        if (at >= 0 && at <= order.size()) {
            order.add(at, folder.key());
        } else {
            order.add(folder.key());
        }
        mListener.onDrawerChanged();
        rebuild();
        return folder;
    }

    public void addToFolder(Item folder, Item item) {
        folder.children.add(copyOf(item));
        mStore.order().remove(item.key());
        mListener.onDrawerChanged();
        rebuild();
    }

    public void removeFromFolder(Item folder, Item child) {
        folder.children.remove(child);
        if (folder.children.size() <= 1) {
            // A folder with one app left is just an app: dissolve it.
            for (Item remaining : folder.children) {
                mStore.order().add(remaining.key());
            }
            mStore.folders().remove(folder);
            mStore.order().remove(folder.key());
        }
        mListener.onDrawerChanged();
        rebuild();
    }

    private static Item copyOf(Item src) {
        Item copy;
        switch (src.type) {
            case Item.TYPE_SHORTCUT:
                copy = src.intentUri != null
                        ? Item.intentShortcut(src.intentUri, src.label)
                        : Item.shortcut(src.pkg, src.shortcutId, src.userSerial, src.label);
                break;
            case Item.TYPE_FOLDER:
                copy = Item.folder(src.label);
                copy.children.addAll(src.children);
                break;
            default:
                copy = Item.app(src.pkg, src.cls, src.userSerial, src.label);
                break;
        }
        return copy;
    }
}
