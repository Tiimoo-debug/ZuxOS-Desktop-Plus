package com.zuxos.desktopplus.desktop;

import com.zuxos.desktopplus.model.Item;

/** Local state carried by a drag: what is moving and where it came from. */
public final class DragPayload {

    public static final int SRC_DESKTOP = 0;
    public static final int SRC_DRAWER = 1;
    public static final int SRC_FOLDER = 2;

    public final Item item;
    public final int source;
    /** Folder the item was dragged out of, for {@link #SRC_FOLDER}. */
    public final Item folder;

    public DragPayload(Item item, int source, Item folder) {
        this.item = item;
        this.source = source;
        this.folder = folder;
    }

    public boolean isCopy() {
        return source == SRC_DRAWER;
    }
}
