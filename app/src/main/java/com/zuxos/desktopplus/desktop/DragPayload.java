package com.zuxos.desktopplus.desktop;

import android.content.ClipData;
import android.content.ClipDescription;
import android.view.DragEvent;

import com.zuxos.desktopplus.logic.DragCodec;
import com.zuxos.desktopplus.model.Item;

/** Local state carried by a drag: what is moving and where it came from. */
public final class DragPayload {

    public static final int SRC_DESKTOP = 0;
    public static final int SRC_DRAWER = 1;
    public static final int SRC_FOLDER = 2;
    /** A pin being moved along the taskbar. Never leaves the taskbar's window. */
    public static final int SRC_TASKBAR = 3;

    /**
     * {@code View.DRAG_FLAG_GLOBAL_SAME_APPLICATION} (Android 15): the drag reaches every window
     * of the launcher and no other app's. Spelled out because the constant is not in every SDK.
     */
    public static final int SAME_APPLICATION = 1 << 12;

    /**
     * How every drag of ours starts: across the launcher's own windows, opaque, and seen by no
     * other app - ZUI's sidebar freezes the tablet's input when it is shown one.
     */
    public static final int FLAGS = android.view.View.DRAG_FLAG_GLOBAL | SAME_APPLICATION
            | android.view.View.DRAG_FLAG_OPAQUE;

    public final Item item;
    public final int source;
    /** Folder the item was dragged out of, for {@link #SRC_FOLDER}. */
    public final Item folder;
    /** Everything being dragged when several items are selected; null for a single item. */
    public final java.util.List<Item> batch;

    public DragPayload(Item item, int source, Item folder) {
        this(item, source, folder, null);
    }

    public DragPayload(Item item, int source, Item folder, java.util.List<Item> batch) {
        this.item = item;
        this.source = source;
        this.folder = folder;
        this.batch = batch;
    }

    /** The items this drag carries, always at least one. */
    public java.util.List<Item> items() {
        return batch != null && !batch.isEmpty()
                ? batch : java.util.Collections.singletonList(item);
    }

    public boolean isCopy() {
        return source == SRC_DRAWER;
    }

    // --- crossing a window --------------------------------------------------

    /**
     * The same payload, written onto the drag itself.
     *
     * <p>Needed by any drag that leaves the window it started in - the stock drawer and the taskbar
     * are windows of the taskbar's own, and the desktop is in the launcher's activity, so between
     * them the local state arrives as null. A global drag also has to carry real clip data; one
     * with none is refused outright.
     */
    public ClipData toClip() {
        String text = DragCodec.encode(item);
        ClipDescription description = new ClipDescription(DragCodec.labelFor(source),
                new String[]{DragCodec.MIME});
        return new ClipData(description, new ClipData.Item(text == null ? "" : text));
    }

    /**
     * Whether this drag is one of ours, asked at a moment when the data cannot be read yet.
     *
     * <p>A drop target has to answer that on {@code ACTION_DRAG_STARTED} - returning false there
     * means it hears nothing more about the drag - and Android hands out the clip's description
     * then but not its contents.
     */
    public static boolean isOurs(DragEvent event) {
        if (event.getLocalState() instanceof DragPayload) {
            return true;
        }
        ClipDescription description = event.getClipDescription();
        return description != null && description.hasMimeType(DragCodec.MIME);
    }

    /**
     * Where the drag came from, available from the moment it starts.
     *
     * @return one of the {@code SRC_} constants, or {@link DragCodec#UNKNOWN}
     */
    public static int sourceOf(DragEvent event) {
        Object local = event.getLocalState();
        if (local instanceof DragPayload) {
            return ((DragPayload) local).source;
        }
        ClipDescription description = event.getClipDescription();
        return description == null ? DragCodec.UNKNOWN
                : DragCodec.sourceFrom(description.getLabel());
    }

    /**
     * The payload behind a drag event, from this window or another one.
     *
     * <p>Local state first: a drag that never left its window keeps every reference it started
     * with, including the folder it came out of and the rest of a multiple selection, none of
     * which can be written down. The clip is the fallback, and it carries one item.
     *
     * <p>Only readable in full on {@code ACTION_DROP}; before that the item will be null and only
     * {@link #sourceOf} can be trusted.
     */
    public static DragPayload of(DragEvent event) {
        Object local = event.getLocalState();
        if (local instanceof DragPayload) {
            return (DragPayload) local;
        }
        int source = sourceOf(event);
        if (source == DragCodec.UNKNOWN) {
            return null;
        }
        ClipData clip = event.getClipData();
        if (clip == null || clip.getItemCount() == 0) {
            // Before the drop. The source is known, the item is not.
            return new DragPayload(null, source, null);
        }
        Item item = DragCodec.decode(clip.getItemAt(0).getText());
        return item == null ? null : new DragPayload(item, source, null);
    }
}
