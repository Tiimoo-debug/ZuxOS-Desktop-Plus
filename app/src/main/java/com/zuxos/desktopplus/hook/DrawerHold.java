package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.DesktopHost;
import com.zuxos.desktopplus.logic.PinList;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Hold an app in the stock drawer: let go for its menu, move to drag it out.
 *
 * <p>The drag starts on the hold either way - by the time anyone knows whether the finger will
 * move, the hold has already fired - so what happens next is decided by watching it. A
 * see-through view laid over the drawer for the length of the drag hears where it goes:
 * <ul>
 * <li>moved within the drawer, it rearranges the drawer ({@link DrawerReorder});
 * <li>moved out of the drawer's panel or onto the taskbar, the drawer gets out of the way so it
 * can be dropped on the desktop or the bar;
 * <li>let go where it was picked up, nothing was dropped and the menu opens instead.
 * </ul>
 *
 * <p>It used to close the drawer the moment the hold fired, which is why holding an app only ever
 * dragged it.
 */
final class DrawerHold {

    /** How far the finger may wander before the hold counts as a drag. */
    private static final float MOVE_DP = 24f;

    private DrawerHold() {
    }

    /** Called just before the drag starts, so the watcher is there for its first event. */
    static void watch(View source, Item item) {
        View root = source.getRootView();
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        Watcher watcher = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof Watcher) {
                watcher = (Watcher) group.getChildAt(i);
            }
        }
        if (watcher == null) {
            watcher = new Watcher(source.getContext());
            group.addView(watcher, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        watcher.arm(source, item);
    }

    /** Not touchable and draws nothing; it only listens to the drag. */
    private static final class Watcher extends View {

        private View mSource;
        private Item mItem;
        private float mStartX = -1f;
        private float mStartY;
        private boolean mMoved;
        /** Rearranging the drawer while the drag is over it; null where that cannot be done. */
        private DrawerReorder mReorder;
        /** The drag has left the drawer, which has stepped aside for it. */
        private boolean mLeft;
        /** Let go over the drawer, in a new place in it. */
        private boolean mDroppedHere;

        Watcher(Context ctx) {
            super(ctx);
            setClickable(false);
            setFocusable(false);
            setOnDragListener(this::onDrag);
        }

        void arm(View source, Item item) {
            mSource = source;
            mItem = item;
            mStartX = -1f;
            mMoved = false;
            mLeft = false;
            mDroppedHere = false;
            mReorder = DrawerReorder.begin(source);
        }

        /** The drawer steps aside, once, for a drag on its way to the desktop or the taskbar. */
        private void leave() {
            mMoved = true;
            if (mLeft) {
                return;
            }
            mLeft = true;
            if (mReorder != null) {
                mReorder.cancel();
            }
            // Posted: the window this view is in is the one being closed.
            post(TaskbarBridge::closeStockDrawer);
        }

        private boolean onDrag(View v, DragEvent event) {
            if (mSource == null) {
                return false;
            }
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION: {
                    float x = event.getX();
                    float y = event.getY();
                    if (mStartX < 0) {
                        mStartX = x;
                        mStartY = y;
                        return true;
                    }
                    if (!mMoved && Math.hypot(x - mStartX, y - mStartY)
                            > Ui.dp(getContext(), MOVE_DP)) {
                        // A drag after all, not a hold.
                        mMoved = true;
                        if (mReorder == null) {
                            // Nothing to rearrange here: the drawer covers the desktop, so it
                            // goes now, as it always did.
                            leave();
                        }
                    }
                    if (mMoved && mReorder != null && !mLeft) {
                        if (mReorder.over(x, y)) {
                            mReorder.moveTo(x, y);
                        } else {
                            leave();
                        }
                    }
                    return true;
                }
                case DragEvent.ACTION_DRAG_EXITED:
                    leave();
                    return true;
                case DragEvent.ACTION_DROP:
                    if (mReorder != null && mMoved && !mLeft) {
                        // Let go over the drawer after moving: a new place in it.
                        mDroppedHere = true;
                        mReorder.drop();
                        return true;
                    }
                    // Let go where it was picked up: not a drop on anything.
                    return false;
                case DragEvent.ACTION_DRAG_ENDED: {
                    View source = mSource;
                    Item item = mItem;
                    boolean menu = !mMoved && !event.getResult();
                    if (mReorder != null && !mDroppedHere) {
                        // Dropped elsewhere, or nowhere: the drawer's icons all back in place,
                        // the picked-up one showing again for the next time it opens.
                        mReorder.cancel();
                        mReorder.drop();
                    }
                    mReorder = null;
                    mSource = null;
                    mItem = null;
                    if (menu && source != null && item != null) {
                        post(() -> showMenu(source, item));
                    }
                    // Out of the launcher's drawer again: it is the launcher's view, and nothing
                    // of ours should sit in it longer than the gesture that needed it.
                    post(() -> {
                        if (getParent() instanceof ViewGroup) {
                            ((ViewGroup) getParent()).removeView(this);
                        }
                    });
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    /** The same menu a taskbar icon has, plus the two things the drawer is for. */
    private static void showMenu(View source, Item item) {
        try {
            Context ctx = source.getContext();
            int display = TaskbarTray.displayIdOf(source);
            List<TaskbarMenu.Entry> entries = new ArrayList<>();
            if (item.type == Item.TYPE_FOLDER) {
                entries.add(new TaskbarMenu.Entry("Open folder",
                        () -> NativeDrawerHooks.openFolderFor(source)));
            } else if (item.pkg != null) {
                entries.addAll(TaskbarApps.entriesFor(ctx, item.pkg,
                        android.os.Process.myUserHandle(), display));
            }
            entries.add(new TaskbarMenu.Entry("Pin to the taskbar", () -> {
                TaskbarPins.pin(ctx, item, PinList.AT_THE_END);
                TaskbarRunning.refreshAll();
            }));
            DesktopHost desktop = DesktopHost.current();
            if (desktop != null) {
                entries.add(new TaskbarMenu.Entry("Add to desktop", () -> {
                    desktop.addPinnedItem(copyOf(item));
                    TaskbarBridge.closeStockDrawer();
                }));
            }
            int[] at = new int[2];
            source.getLocationOnScreen(at);
            TaskbarMenu.showEntries(source, display, at[0] + source.getWidth() / 2f,
                    at[1] + source.getHeight(), entries);
        } catch (Throwable t) {
            L.d("drawer hold: no menu (" + t + ")");
        }
    }

    /** A copy for the desktop, under an id of its own so the two never get confused. */
    private static Item copyOf(Item item) {
        try {
            Item copy = Item.fromJson(item.toJson());
            copy.id = java.util.UUID.randomUUID().toString();
            return copy;
        } catch (org.json.JSONException e) {
            // Every field of an item round-trips; if one ever does not, the original will do.
            return item;
        }
    }
}
