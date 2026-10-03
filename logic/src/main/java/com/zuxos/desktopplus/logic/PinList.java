package com.zuxos.desktopplus.logic;

import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * The apps pinned to the taskbar, as a list.
 *
 * <p>Pinning is the sort of thing that looks trivial until the same app is dropped twice, or an
 * app is dropped onto the place it already occupies, or the last one is unpinned. None of that
 * needs a taskbar to get wrong, so none of it is written where only a taskbar can check it.
 *
 * <p>Identity is {@link Item#key()} throughout - the same app under a different label, or dragged
 * from a different place, is the same pin.
 */
public final class PinList {

    /** Dropped at no particular place. */
    public static final int AT_THE_END = -1;

    private PinList() {
    }

    /**
     * The list with an item pinned at a position.
     *
     * <p>An app that is already pinned moves rather than appears twice. Returns a new list; the
     * one passed in is not touched.
     */
    public static List<Item> add(List<Item> pins, Item item, int at) {
        List<Item> out = new ArrayList<>();
        if (item == null) {
            return pins == null ? out : new ArrayList<>(pins);
        }
        String key = item.key();
        if (pins != null) {
            for (Item pin : pins) {
                if (!key.equals(pin.key())) {
                    out.add(pin);
                }
            }
        }
        int index = at == AT_THE_END || at > out.size() ? out.size() : Math.max(0, at);
        out.add(index, item);
        return out;
    }

    public static List<Item> add(List<Item> pins, Item item) {
        return add(pins, item, AT_THE_END);
    }

    /** The list without whatever was pinned under this key. */
    public static List<Item> remove(List<Item> pins, String key) {
        List<Item> out = new ArrayList<>();
        if (pins == null) {
            return out;
        }
        for (Item pin : pins) {
            if (key == null || !key.equals(pin.key())) {
                out.add(pin);
            }
        }
        return out;
    }

    public static boolean holds(List<Item> pins, String key) {
        return indexOf(pins, key) >= 0;
    }

    public static int indexOf(List<Item> pins, String key) {
        if (pins == null || key == null) {
            return -1;
        }
        for (int i = 0; i < pins.size(); i++) {
            if (key.equals(pins.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The packages the pins stand for, folders included.
     *
     * <p>A pinned folder is open when anything inside it is, which is what stops an app being
     * shown twice - once as the folder holding it and once as an icon of its own.
     */
    public static List<String> packagesOf(List<Item> pins) {
        List<String> out = new ArrayList<>();
        if (pins == null) {
            return out;
        }
        for (Item pin : pins) {
            collect(pin, out);
        }
        return out;
    }

    private static void collect(Item item, List<String> out) {
        if (item == null) {
            return;
        }
        if (item.pkg != null && !out.contains(item.pkg)) {
            out.add(item.pkg);
        }
        for (Item child : item.children) {
            collect(child, out);
        }
    }
}
